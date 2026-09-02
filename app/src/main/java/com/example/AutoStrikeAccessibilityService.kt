package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.hypot

/**
 * AutoStrikeAccessibilityService allows the app to dispatch precision touch gestures
 * for aiming and automated strike execution in Carrom Pool:
 * 1. Constructs dynamic GestureDescription starting from detected striker coordinate.
 * 2. Drags backwards along the calculated inverted aim vector (simulating slingshot pullback).
 * 3. Releases within 200-300ms smoothly via dispatchGesture().
 */
open class AutoStrikeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoStrikeService"

        // State observable across HUD and Engine
        val isServiceConnected = MutableStateFlow(false)
        val isAutoPlayExecuting = MutableStateFlow(false)
        val lastExecutedShotInfo = MutableStateFlow("Ready for Auto-Play Strike")

        var instance: AutoStrikeAccessibilityService? = null

        /**
         * Verifies whether the Accessibility Service permission is granted in Android Settings.
         */
        fun isAccessibilitySettingsOn(context: Context): Boolean {
            var accessibilityEnabled = 0
            val autoStrikeService = "${context.packageName}/${AutoStrikeAccessibilityService::class.java.canonicalName}"
            val carromService = "${context.packageName}/${CarromAutoPlayService::class.java.canonicalName}"

            try {
                accessibilityEnabled = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED
                )
            } catch (e: Settings.SettingNotFoundException) {
                Log.e(TAG, "Error checking accessibility setting: ${e.message}")
            }

            val mStringColonSplitter = TextUtils.SimpleStringSplitter(':')

            if (accessibilityEnabled == 1) {
                val settingValue = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                if (settingValue != null) {
                    mStringColonSplitter.setString(settingValue)
                    while (mStringColonSplitter.hasNext()) {
                        val serviceName = mStringColonSplitter.next()
                        if (serviceName.equals(autoStrikeService, ignoreCase = true) ||
                            serviceName.equals(carromService, ignoreCase = true) ||
                            (serviceName.contains(context.packageName) &&
                                    (serviceName.contains("AutoStrike") || serviceName.contains("CarromAutoPlay")))
                        ) {
                            return true
                        }
                    }
                }
            }
            return false
        }

        /**
         * Dispatches a precision physical strike gesture:
         * - Starts at the striker center.
         * - Drags backwards along the inverted aim vector (opposite of target direction).
         * - In Fast Mode, executes within 80ms for instant auto-play strike; otherwise 220-260ms.
         * - Power is dynamically scaled based on distance between striker and target puck.
         */
        fun performAutoStrike(
            strikerPos: PointF,
            aimTargetPos: PointF,
            powerPercent: Int = 85,
            durationMs: Long = 240L,
            isFastMode: Boolean = false,
            onComplete: ((Boolean) -> Unit)? = null
        ) {
            val service = instance
            if (service == null) {
                Log.w(TAG, "AutoStrikeAccessibilityService is not connected or enabled.")
                onComplete?.invoke(false)
                return
            }

            service.executeSlingshotGesture(strikerPos, aimTargetPos, powerPercent, durationMs, isFastMode, onComplete)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceConnected.value = true
        Log.i(TAG, "AutoStrikeAccessibilityService connected and ready for dispatch.")
        Toast.makeText(this, "⚡ Auto-Strike Accessibility Service Connected!", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility event monitoring
    }

    override fun onInterrupt() {
        Log.w(TAG, "AutoStrikeAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        isServiceConnected.value = false
        isAutoPlayExecuting.value = false
        Log.i(TAG, "AutoStrikeAccessibilityService destroyed.")
    }

    /**
     * Executes the slingshot touch gesture:
     * 1. Touch-down at striker origin.
     * 2. Pullback opposite to aim target vector with dynamic power based on distance.
     * 3. Fast Mode executes instantly in 80ms; Standard Mode executes in 220-260ms.
     */
    private fun executeSlingshotGesture(
        striker: PointF,
        target: PointF,
        powerPercent: Int,
        durationMs: Long,
        isFastMode: Boolean,
        onComplete: ((Boolean) -> Unit)?
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "GestureDescription requires Android 7.0 (API 24)+")
            onComplete?.invoke(false)
            return
        }

        val dx = target.x - striker.x
        val dy = target.y - striker.y
        val dist = hypot(dx, dy)

        if (dist < 1f) {
            Log.w(TAG, "Invalid aim vector distance: $dist")
            onComplete?.invoke(false)
            return
        }

        // Normalized aim vector
        val normX = dx / dist
        val normY = dy / dist

        // Calculate dynamic shot power based on distance if default power was requested
        val computedPower = if (powerPercent in 20..100) {
            powerPercent
        } else {
            // Scale dynamically based on distance: short distance = gentle touch, long distance = high power
            ((dist / 14f) + 30f).toInt().coerceIn(35, 100)
        }
        val clampedPower = computedPower.coerceIn(20, 100)
        val maxPullDistance = 180f
        val pullDistance = (clampedPower / 100f) * maxPullDistance

        // Inverted aim vector endpoint (pulling the striker backwards charges the shot)
        val pullBackEndX = striker.x - normX * pullDistance
        val pullBackEndY = striker.y - normY * pullDistance

        // Construct dynamic force curve gesture path:
        // Striker Center -> Smooth Interpolated Pullback Path with non-linear force acceleration
        val path = Path().apply {
            moveTo(striker.x, striker.y)
            // Quadratic Bezier or direct line along the confirmed server vector trajectory
            val midX = striker.x - normX * (pullDistance * 0.5f)
            val midY = striker.y - normY * (pullDistance * 0.5f)
            lineTo(midX, midY)
            lineTo(pullBackEndX, pullBackEndY)
        }

        val gestureDuration = if (isFastMode) 80L else durationMs.coerceIn(120L, 300L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, gestureDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        isAutoPlayExecuting.value = true
        val modeLabel = if (isFastMode) "⚡ Fast (80ms)" else "Standard (${gestureDuration}ms)"
        lastExecutedShotInfo.value = "Executing Strike: Force $clampedPower% [$modeLabel]"

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                isAutoPlayExecuting.value = false
                Log.d(TAG, "Auto-Strike gesture completed successfully in ${gestureDuration}ms.")
                mainHandler.post {
                    val toastMsg = if (isFastMode) "⚡ Fast Auto-Strike Fired! (80ms)" else "🎯 Auto-Strike Executed ($clampedPower% Power)"
                    Toast.makeText(
                        this@AutoStrikeAccessibilityService,
                        toastMsg,
                        Toast.LENGTH_SHORT
                    ).show()
                    onComplete?.invoke(true)
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                isAutoPlayExecuting.value = false
                Log.w(TAG, "Auto-Strike gesture was cancelled.")
                mainHandler.post {
                    onComplete?.invoke(false)
                }
            }
        }, null)

        if (!dispatched) {
            isAutoPlayExecuting.value = false
            Log.e(TAG, "Failed to dispatch auto-strike gesture.")
            mainHandler.post {
                onComplete?.invoke(false)
            }
        }
    }
}
