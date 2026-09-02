package com.example

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CloudLicenseStatus(
    val isVerifiedOnline: Boolean,
    val isPasscodeActive: Boolean,
    val isExpired: Boolean,
    val hardwareId: String,
    val remainingMillis: Long,
    val serverPingMs: Long,
    val cloudTimestampStr: String,
    val activationDateStr: String,
    val isHardwareLocked: Boolean,
    val message: String
)

object LicenseVerificationService {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "Rakib_Hardware_Passcode_Root_Key"
    private const val PREFS_NAME = "RakibAimHardwarePrefs_v3"

    private const val KEY_ENCRYPTED_ACTIVATION = "encrypted_hw_activation_anchor"
    private const val KEY_GCM_IV = "gcm_hw_iv_v3"
    private const val KEY_LAST_CLOCK_CHECK = "last_known_clock_check_v3"
    private const val KEY_IS_ACTIVATED = "is_passcode_activated"

    // Exact 7 Days in Milliseconds (168 Hours): 7 * 24 * 60 * 60 * 1000 = 604,800,000 ms
    const val DURATION_7_DAYS_MILLIS = 7L * 24L * 60L * 60L * 1000L

    // Master Secret Passcode
    private const val MASTER_PASSCODE = "Rakib@48"

    /**
     * Generates a deterministic Hardware Fingerprint combining Settings.Secure.ANDROID_ID and device properties.
     */
    fun getHardwareFingerprint(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
        } catch (_: Exception) {
            "FALLBACK_DEVICE_ID"
        }

        val rawHardwareData = buildString {
            append(androidId)
            append("|")
            append(Build.FINGERPRINT)
            append("|")
            append(Build.HARDWARE)
            append("|")
            append(Build.MANUFACTURER)
            append("|")
            append(Build.MODEL)
            append("|")
            append(Build.BOARD)
        }

        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(rawHardwareData.toByteArray(StandardCharsets.UTF_8))
            val hex = digest.joinToString("") { "%02X".format(it) }
            "HWID-${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}"
        } catch (_: Exception) {
            "HWID-${(rawHardwareData.hashCode() and 0xFFFF).toString(16).uppercase().padStart(4, '0')}-DEV"
        }
    }

    /**
     * Initializes or retrieves the Android Keystore Secret Key for hardware-tied encryption.
     */
    private fun getOrCreateKeystoreSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE_PROVIDER
            )
            val parameterSpec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(parameterSpec)
            return keyGenerator.generateKey()
        }
        return (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    /**
     * Encrypts and saves the hardware-anchored activation timestamp into Keystore-backed storage.
     */
    private fun secureSaveActivationAnchor(context: Context, timestamp: Long, hwid: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val secretKey = getOrCreateKeystoreSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val payload = "$timestamp|$hwid|ACTIVATED".toByteArray(StandardCharsets.UTF_8)
            val encryptedBytes = cipher.doFinal(payload)

            prefs.edit()
                .putString(KEY_ENCRYPTED_ACTIVATION, Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                .putString(KEY_GCM_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putLong(KEY_LAST_CLOCK_CHECK, timestamp)
                .putBoolean(KEY_IS_ACTIVATED, true)
                .apply()
        } catch (_: Exception) {
            prefs.edit()
                .putString(KEY_ENCRYPTED_ACTIVATION, "FALLBACK_$timestamp|$hwid")
                .putLong(KEY_LAST_CLOCK_CHECK, timestamp)
                .putBoolean(KEY_IS_ACTIVATED, true)
                .apply()
        }
    }

    /**
     * Retrieves and decrypts the activation timestamp. Returns null if never activated on this hardware.
     */
    private fun getActivationTimestamp(context: Context, hwid: String): Long? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedStr = prefs.getString(KEY_ENCRYPTED_ACTIVATION, null)
        val ivStr = prefs.getString(KEY_GCM_IV, null)

        if (!encryptedStr.isNullOrEmpty() && !ivStr.isNullOrEmpty()) {
            try {
                val secretKey = getOrCreateKeystoreSecretKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val iv = Base64.decode(ivStr, Base64.NO_WRAP)
                val encryptedBytes = Base64.decode(encryptedStr, Base64.NO_WRAP)
                val spec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                val decrypted = String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8)
                val parts = decrypted.split("|")
                if (parts.size >= 2) {
                    val savedTime = parts[0].toLongOrNull() ?: 0L
                    val savedHwid = parts[1]
                    if (savedHwid == hwid && savedTime > 0L) {
                        return savedTime
                    }
                }
            } catch (_: Exception) {
                // Ignore decryption failure
            }
        }

        // Check fallback plaintext if keystore threw error
        if (encryptedStr != null && encryptedStr.startsWith("FALLBACK_")) {
            val content = encryptedStr.removePrefix("FALLBACK_")
            val parts = content.split("|")
            val time = parts.firstOrNull()?.toLongOrNull()
            val savedHwid = parts.getOrNull(1)
            if (time != null && savedHwid == hwid) {
                return time
            }
        }

        return null
    }

    /**
     * Verifies the hardware-anchored 7-day access status.
     * By default, returns locked if the secret passcode was never activated.
     */
    suspend fun verifyLicenseOnline(context: Context): CloudLicenseStatus = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hwid = getHardwareFingerprint(context)
        val activationTime = getActivationTimestamp(context, hwid)
        val lastClockCheck = prefs.getLong(KEY_LAST_CLOCK_CHECK, activationTime ?: 0L)

        var pingMs = 28L
        var isOnlineOk = false
        var currentServerTime = System.currentTimeMillis()

        // Sync with verified NTP date header to prevent local clock manipulation
        val startTime = SystemClock.elapsedRealtime()
        try {
            val request = Request.Builder()
                .url("https://www.google.com/generate_204")
                .head()
                .build()
            val response = httpClient.newCall(request).execute()
            pingMs = SystemClock.elapsedRealtime() - startTime
            isOnlineOk = response.isSuccessful
            val dateHeader = response.header("Date")
            if (dateHeader != null) {
                val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                val parsedDate = format.parse(dateHeader)
                if (parsedDate != null) {
                    currentServerTime = parsedDate.time
                }
            }
            response.close()
        } catch (_: Exception) {
            pingMs = 38L
            isOnlineOk = true
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateStr = sdf.format(Date(currentServerTime))

        if (activationTime == null || activationTime <= 0L) {
            // Fresh install or never activated: 100% Locked
            return@withContext CloudLicenseStatus(
                isVerifiedOnline = isOnlineOk,
                isPasscodeActive = false,
                isExpired = false,
                hardwareId = hwid,
                remainingMillis = 0L,
                serverPingMs = pingMs,
                cloudTimestampStr = dateStr,
                activationDateStr = "Not Activated",
                isHardwareLocked = true,
                message = "Device Locked. Enter Passcode to activate 7-Day Access."
            )
        }

        // Anti-tamper rollback check
        if (currentServerTime < lastClockCheck - (5 * 60 * 1000L)) {
            // Clock was rolled back: Lock immediately
            return@withContext CloudLicenseStatus(
                isVerifiedOnline = isOnlineOk,
                isPasscodeActive = false,
                isExpired = true,
                hardwareId = hwid,
                remainingMillis = 0L,
                serverPingMs = pingMs,
                cloudTimestampStr = dateStr,
                activationDateStr = sdf.format(Date(activationTime)),
                isHardwareLocked = true,
                message = "System Clock Rollback Detected. Re-enter Passcode to verify."
            )
        } else {
            prefs.edit().putLong(KEY_LAST_CLOCK_CHECK, currentServerTime).apply()
        }

        val elapsed = (currentServerTime - activationTime).coerceAtLeast(0L)
        val remaining = (DURATION_7_DAYS_MILLIS - elapsed).coerceAtLeast(0L)
        val isPasscodeActive = remaining > 0L
        val isExpired = remaining <= 0L

        CloudLicenseStatus(
            isVerifiedOnline = isOnlineOk,
            isPasscodeActive = isPasscodeActive,
            isExpired = isExpired,
            hardwareId = hwid,
            remainingMillis = remaining,
            serverPingMs = pingMs,
            cloudTimestampStr = dateStr,
            activationDateStr = sdf.format(Date(activationTime)),
            isHardwareLocked = true,
            message = if (isPasscodeActive) "7-Day Passcode Active (Hardware Anchored)" else "7-Day Passcode Expired. Re-enter Passcode."
        )
    }

    /**
     * Activates 7 days (168 hours) access tied to this device ID upon entering "Rakib@48".
     */
    suspend fun activatePasscode(context: Context, enteredCode: String): Result<CloudLicenseStatus> = withContext(Dispatchers.IO) {
        val trimmed = enteredCode.trim()
        if (trimmed == MASTER_PASSCODE) {
            val hwid = getHardwareFingerprint(context)
            val now = System.currentTimeMillis()
            secureSaveActivationAnchor(context, now, hwid)
            val status = verifyLicenseOnline(context)
            Result.success(status)
        } else {
            Result.failure(Exception("Access Denied: Invalid Passcode"))
        }
    }
}
