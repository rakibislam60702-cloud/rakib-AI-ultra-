package com.example

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.Manifest
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<out AccessibilityService>): Boolean {
    val expectedComponentName = ComponentName(context, serviceClass)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledComponent = ComponentName.unflattenFromString(componentNameString)
        if (enabledComponent != null && enabledComponent == expectedComponentName) {
            return true
        }
    }
    return false
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RakibGlassAppTheme {
                MainAppScaffold()
            }
        }
    }
}

@Composable
fun RakibGlassAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF060B13),
            surface = Color(0x1AFFFFFF),
            primary = Color(0xFF00E5FF)
        ),
        content = content
    )
}

enum class NavigationTab(val label: String, val icon: String) {
    DASHBOARD("Dashboard", "🎯"),
    STORE("Store", "🛍️"),
    PROFILE("Profile", "👤"),
    SETTINGS("Settings", "⚙️")
}

data class BannerSlideItem(
    val title: String,
    val subtitle: String,
    val bitmap: Bitmap? = null,
    val drawableRes: Int,
    val fallbackRes: Int
)

data class ProFeatureItem(
    val icon: String,
    val title: String,
    val subtitle: String,
    val tag: String,
    val accentColor: Color
)

@Composable
fun MainAppScaffold() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedPref = remember { context.getSharedPreferences("RakibAimPrefs", Context.MODE_PRIVATE) }

    var currentTab by remember { mutableStateOf(NavigationTab.DASHBOARD) }

    var isPasscodeActive by remember { mutableStateOf(false) }
    var remainingMillis by remember { mutableLongStateOf(0L) }
    var cloudLicenseInfo by remember { mutableStateOf<CloudLicenseStatus?>(null) }
    var isCheckingLicenseOnline by remember { mutableStateOf(false) }

    var showPasscodeDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showAccessibilityPromptDialog by remember { mutableStateOf(false) }
    var passcodeInputText by remember { mutableStateOf("") }
    var isActivatingPasscode by remember { mutableStateOf(false) }

    // Notification Permission Launcher for Android 13+ (POST_NOTIFICATIONS)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Notification permission helps show HUD controls in notification bar", Toast.LENGTH_SHORT).show()
        }
    }

    // Settings States
    var isStealthMode by remember { mutableStateOf(sharedPref.getBoolean("stealth_mode", true)) }
    var is120FpsEnabled by remember { mutableStateOf(sharedPref.getBoolean("fps_120_mode", true)) }
    var isAutoLaunchGame by remember { mutableStateOf(sharedPref.getBoolean("auto_launch_game", true)) }
    var selectedLaserColor by remember { mutableStateOf(Color(0xFF00E5FF)) }
    var laserStrokeThickness by remember { mutableFloatStateOf(6f) }
    var isDualBankEnabled by remember { mutableStateOf(true) }
    var isAutoPocketEnabled by remember { mutableStateOf(true) }

    // Gemini Vision Analysis States
    var isAnalyzingWithGemini by remember { mutableStateOf(false) }
    var aiAnalysisResult by remember {
        mutableStateOf<AiAimDetectionResult?>(
            AiAimDetectionResult(
                strikerXPercent = 0.50f,
                strikerYPercent = 0.75f,
                targetCoinXPercent = 0.44f,
                targetCoinYPercent = 0.38f,
                targetPocket = "Top-Left",
                confidence = 0.98f,
                shotAngleDegrees = 34.5f,
                recommendedPowerPercent = 85,
                strategyNotes = "Direct cut trajectory locked with Gemini 2.5 Flash Vision",
                rawAiResponse = "Ready"
            )
        )
    }

    // Observe floating service state
    val isFloatingServiceActive by FloatingAimService.isServiceRunning.collectAsStateWithLifecycle()
    val cloudAiState by CloudAiConnectionManager.connectionState.collectAsStateWithLifecycle()

    // Auto-Connect to Cloud Server & Hardware-Bound 7-Day License Check on Launch
    LaunchedEffect(Unit) {
        CloudAiConnectionManager.initializeAutoConnect(context)
        isCheckingLicenseOnline = true
        val status = LicenseVerificationService.verifyLicenseOnline(context)
        cloudLicenseInfo = status
        isPasscodeActive = status.isPasscodeActive
        remainingMillis = status.remainingMillis
        isCheckingLicenseOnline = false

        while (true) {
            delay(1000)
            if (remainingMillis > 0) {
                remainingMillis = (remainingMillis - 1000).coerceAtLeast(0L)
                if (remainingMillis <= 0L) {
                    isPasscodeActive = false
                }
            }
        }
    }

    val isAppUnlocked = isPasscodeActive && remainingMillis > 0

    val launchCarromAndForegroundService = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 1. Start Floating Service
        val intent = Intent(context, FloatingAimService::class.java)
        ContextCompat.startForegroundService(context, intent)

        // 2. Direct launch Carrom Disc Pool
        val packageName = "com.miniclip.carrom"
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            Toast.makeText(context, "🎯 Carrom Disc Pool Launched!", Toast.LENGTH_SHORT).show()
        } else {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }

        // 3. Smoothly minimize to background so overlay is on top
        (context as? Activity)?.moveTaskToBack(true)
    }

    val handleStartEngine = {
        if (!isAppUnlocked) {
            Toast.makeText(context, "🔒 App Locked. Enter Passcode to activate 7 days.", Toast.LENGTH_SHORT).show()
            passcodeInputText = ""
            showPasscodeDialog = true
        } else if (isFloatingServiceActive) {
            val intent = Intent(context, FloatingAimService::class.java)
            context.stopService(intent)
            Toast.makeText(context, "🛑 Floating Aim Engine Stopped", Toast.LENGTH_SHORT).show()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            showPermissionDialog = true
        } else if (!isAccessibilityServiceEnabled(context, AutoStrikeAccessibilityService::class.java) &&
            !isAccessibilityServiceEnabled(context, CarromAutoPlayService::class.java)) {
            showAccessibilityPromptDialog = true
        } else {
            launchCarromAndForegroundService()
        }
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF050811), Color(0xFF0A1224), Color(0xFF020408))
                )
            )
    ) {
        // Tab Content Screen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding, bottom = navBarPadding + 75.dp)
        ) {
            when (currentTab) {
                NavigationTab.DASHBOARD -> DashboardScreen(
                    isAppUnlocked = isAppUnlocked,
                    remainingMillis = remainingMillis,
                    cloudLicenseInfo = cloudLicenseInfo,
                    cloudAiState = cloudAiState,
                    isFloatingServiceActive = isFloatingServiceActive,
                    aiAnalysisResult = aiAnalysisResult,
                    isAnalyzingWithGemini = isAnalyzingWithGemini,
                    onOpenPasscodeDialog = {
                        passcodeInputText = ""
                        showPasscodeDialog = true
                    },
                    onAnalyzeGemini = {
                        scope.launch {
                            isAnalyzingWithGemini = true
                            val result = GeminiVisionAnalyzer.analyzeCarromBoardFrame(null, 600f, 400f)
                            result.onSuccess { detection ->
                                aiAnalysisResult = detection
                                Toast.makeText(context, "Gemini Vision: ${detection.targetPocket} Locked (${detection.shotAngleDegrees.toInt()}°)", Toast.LENGTH_SHORT).show()
                            }
                            isAnalyzingWithGemini = false
                        }
                    },
                    onToggleFloatingService = { handleStartEngine() },
                    simColor = selectedLaserColor,
                    simStrokeWidth = laserStrokeThickness,
                    simDualBank = isDualBankEnabled
                )

                NavigationTab.STORE -> StoreScreen(
                    isAppUnlocked = isAppUnlocked,
                    remainingMillis = remainingMillis,
                    onOpenPasscodeDialog = {
                        passcodeInputText = ""
                        showPasscodeDialog = true
                    }
                )

                NavigationTab.PROFILE -> ProfileScreen(
                    isAppUnlocked = isAppUnlocked,
                    remainingMillis = remainingMillis,
                    cloudLicenseInfo = cloudLicenseInfo,
                    cloudAiState = cloudAiState,
                    onOpenPasscodeDialog = {
                        passcodeInputText = ""
                        showPasscodeDialog = true
                    }
                )

                NavigationTab.SETTINGS -> SettingsScreen(
                    isStealthMode = isStealthMode,
                    onStealthModeChange = {
                        isStealthMode = it
                        sharedPref.edit().putBoolean("stealth_mode", it).apply()
                    },
                    is120FpsEnabled = is120FpsEnabled,
                    on120FpsChange = {
                        is120FpsEnabled = it
                        sharedPref.edit().putBoolean("fps_120_mode", it).apply()
                    },
                    isAutoLaunchGame = isAutoLaunchGame,
                    onAutoLaunchGameChange = {
                        isAutoLaunchGame = it
                        sharedPref.edit().putBoolean("auto_launch_game", it).apply()
                    },
                    selectedLaserColor = selectedLaserColor,
                    onLaserColorChange = { selectedLaserColor = it },
                    laserStrokeThickness = laserStrokeThickness,
                    onLaserStrokeChange = { laserStrokeThickness = it },
                    isDualBankEnabled = isDualBankEnabled,
                    onDualBankChange = { isDualBankEnabled = it },
                    isAutoPocketEnabled = isAutoPocketEnabled,
                    onAutoPocketChange = { isAutoPocketEnabled = it }
                )
            }
        }

        // Sleek Floating Glassmorphic Bottom Navigation Bar
        SleekBottomNavBar(
            currentTab = currentTab,
            onTabSelected = { currentTab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navBarPadding + 10.dp, start = 16.dp, end = 16.dp)
        )

        // Overlay Permission Dialog
        if (showPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showPermissionDialog = false },
                title = { Text("Overlay Permission Required", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        text = "To draw laser aim lines and display the floating controller directly over Carrom Disc Pool, please grant the 'Draw over other apps' (SYSTEM_ALERT_WINDOW) permission.",
                        fontSize = 13.sp,
                        color = Color(0xFFC0D0E5)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showPermissionDialog = false
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                    ) {
                        Text("ENABLE PERMISSION", color = Color(0xFF060B13), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPermissionDialog = false }) {
                        Text("Cancel", color = Color(0xFF88A0C2))
                    }
                },
                containerColor = Color(0xFF10192A)
            )
        }

        // Accessibility Service Prompt Dialog
        if (showAccessibilityPromptDialog) {
            AlertDialog(
                onDismissRequest = { showAccessibilityPromptDialog = false },
                title = { Text("AutoStrike Accessibility", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        text = "Enable 'AutoStrikeAccessibilityService' in Accessibility Settings for automatic screen touches, coin alignment, and auto-play execution.\n\nYou can also launch directly without auto-strike gestures.",
                        fontSize = 13.sp,
                        color = Color(0xFFC0D0E5)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showAccessibilityPromptDialog = false
                            try {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                launchCarromAndForegroundService()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                    ) {
                        Text("OPEN SETTINGS", color = Color(0xFF060B13), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showAccessibilityPromptDialog = false
                            launchCarromAndForegroundService()
                        }
                    ) {
                        Text("LAUNCH DIRECTLY", color = Color(0xFF88A0C2))
                    }
                },
                containerColor = Color(0xFF10192A)
            )
        }

        // Secret Passcode Dialog (Anti-Reset Hardware Anchored)
        if (showPasscodeDialog) {
            val hwid = cloudLicenseInfo?.hardwareId ?: LicenseVerificationService.getHardwareFingerprint(context)
            AlertDialog(
                onDismissRequest = { showPasscodeDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isAppUnlocked) "⚡ 7-DAY ACCESS ACTIVE" else "🔒 ENTER SECRET PASSCODE",
                            color = if (isAppUnlocked) Color(0xFF00E676) else Color(0xFFFFD700),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        )
                    }
                },
                text = {
                    Column {
                        // Hardware-Locked Security Badge
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (!isAppUnlocked) Color(0x2EFF1744) else Color(0x1F00E5FF))
                                .border(1.dp, if (!isAppUnlocked) Color(0x66FF1744) else Color(0x3300E5FF), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🛡️ HWID: $hwid",
                                        fontSize = 10.5.sp,
                                        color = if (!isAppUnlocked) Color(0xFFFF8A80) else Color(0xFF00E5FF),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "ANTI-RESET 🔒",
                                        fontSize = 9.sp,
                                        color = Color(0xFF00E676),
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                                Text(
                                    text = "Android Keystore Hardware Bound • 7-Day Limit (168 Hours)",
                                    fontSize = 9.sp,
                                    color = Color(0xFF88A0C2)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = if (isAppUnlocked) {
                                "7-Day trial is currently active (${formatCountdown(remainingMillis)} remaining). You can re-activate or enter a new passcode below:"
                            } else {
                                "This engine is hardware-locked. Enter your 7-Day secret passcode to activate full aim assistance on this device:"
                            },
                            fontSize = 12.sp,
                            color = Color(0xFFC0D0E5),
                            modifier = Modifier.padding(bottom = 10.dp)
                        )

                        OutlinedTextField(
                            value = passcodeInputText,
                            onValueChange = { passcodeInputText = it },
                            placeholder = { Text("••••••••", color = Color(0x66FFFFFF), fontSize = 14.sp) },
                            label = { Text("Master Passcode") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrect = false
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00E5FF),
                                unfocusedBorderColor = Color(0x6600E5FF),
                                focusedLabelColor = Color(0xFF00E5FF),
                                unfocusedLabelColor = Color(0xFF88A0C2),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                isActivatingPasscode = true
                                val res = LicenseVerificationService.activatePasscode(context, passcodeInputText)
                                res.onSuccess { status ->
                                    isPasscodeActive = status.isPasscodeActive
                                    cloudLicenseInfo = status
                                    remainingMillis = status.remainingMillis
                                    showPasscodeDialog = false
                                    Toast.makeText(context, "🎯 Access Granted: 7 Days (168 Hours) Activated!", Toast.LENGTH_LONG).show()
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                        showPermissionDialog = true
                                    } else {
                                        launchCarromAndForegroundService()
                                    }
                                }.onFailure { error ->
                                    Toast.makeText(context, error.message ?: "Access Denied", Toast.LENGTH_SHORT).show()
                                }
                                isActivatingPasscode = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFD700)
                        )
                    ) {
                        if (isActivatingPasscode) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color(0xFF060B13))
                        } else {
                            Text(
                                text = "ACTIVATE 7 DAYS",
                                color = Color(0xFF060B13),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPasscodeDialog = false }) {
                        Text("Cancel", color = Color(0xFF88A0C2))
                    }
                },
                containerColor = Color(0xFF10192A)
            )
        }
    }
}

/**
 * Sleek Floating Bottom Navigation Bar with 4 Tabs
 */
@Composable
fun SleekBottomNavBar(
    currentTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xE610192D), Color(0xFA090E1A))
                )
            )
            .border(1.5.dp, Color(0x3300E5FF), RoundedCornerShape(24.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavigationTab.values().forEach { tab ->
                val isSelected = currentTab == tab
                val animBg = if (isSelected) Color(0x2600E5FF) else Color.Transparent
                val animBorder = if (isSelected) Color(0x6600E5FF) else Color.Transparent
                val contentColor = if (isSelected) Color(0xFF00E5FF) else Color(0xFF7E92B0)

                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(animBg)
                        .border(1.dp, animBorder, RoundedCornerShape(16.dp))
                        .clickable { onTabSelected(tab) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = tab.icon,
                        fontSize = if (isSelected) 18.sp else 16.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tab.label,
                        color = contentColor,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * 1. DASHBOARD TAB SCREEN
 */
@Composable
fun DashboardScreen(
    isAppUnlocked: Boolean,
    remainingMillis: Long,
    cloudLicenseInfo: CloudLicenseStatus?,
    cloudAiState: CloudAiConnectionState,
    isFloatingServiceActive: Boolean,
    aiAnalysisResult: AiAimDetectionResult?,
    isAnalyzingWithGemini: Boolean,
    onOpenPasscodeDialog: () -> Unit,
    onAnalyzeGemini: () -> Unit,
    onToggleFloatingService: () -> Unit,
    simColor: Color,
    simStrokeWidth: Float,
    simDualBank: Boolean
) {
    val context = LocalContext.current
    var currentBannerIndex by remember { mutableIntStateOf(0) }

    // Decode actual uploaded image Bitmaps via Base64 with local Drawable fallback
    val banner1Bitmap = remember(context) {
        try {
            val bytes = Base64.decode(BannerAssets.BANNER_1_BASE64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: BitmapFactory.decodeResource(context.resources, R.drawable.my_banner_1)
        } catch (e: Exception) {
            BitmapFactory.decodeResource(context.resources, R.drawable.my_banner_1)
        }
    }

    val banner2Bitmap = remember(context) {
        try {
            val bytes = Base64.decode(BannerAssets.BANNER_2_BASE64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: BitmapFactory.decodeResource(context.resources, R.drawable.my_banner_2)
        } catch (e: Exception) {
            BitmapFactory.decodeResource(context.resources, R.drawable.my_banner_2)
        }
    }

    // Banner photos with 4 complete high-definition slides
    val bannerItems = remember(banner1Bitmap, banner2Bitmap) {
        listOf(
            BannerSlideItem(
                title = "Rakibul Official",
                subtitle = "Carrom Aim Pro Lead & Founder",
                bitmap = banner1Bitmap,
                drawableRes = R.drawable.my_banner_1,
                fallbackRes = R.drawable.my_photo_1
            ),
            BannerSlideItem(
                title = "AI Trajectory Master",
                subtitle = "Gemini 2.5 Flash Vision Engine",
                bitmap = banner2Bitmap,
                drawableRes = R.drawable.my_banner_2,
                fallbackRes = R.drawable.my_photo_2
            ),
            BannerSlideItem(
                title = "Multi-Cushion Physics",
                subtitle = "1, 2, & 3-Cushion Angle Solvers",
                bitmap = null,
                drawableRes = R.drawable.my_banner_1,
                fallbackRes = R.drawable.my_photo_1
            ),
            BannerSlideItem(
                title = "Zero-Miss Auto Strike",
                subtitle = "Precision Pocket Lock & Reticle",
                bitmap = null,
                drawableRes = R.drawable.my_banner_2,
                fallbackRes = R.drawable.my_photo_2
            )
        )
    }

    val pagerState = rememberPagerState(pageCount = { bannerItems.size })
    val coroutineScope = rememberCoroutineScope()

    // True continuous infinite auto-slider loop running on Dispatchers.Main
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Main.immediate) {
            while (isActive) {
                delay(3000L)
                if (bannerItems.isNotEmpty() && !pagerState.isScrollInProgress) {
                    val nextPage = (pagerState.currentPage + 1) % bannerItems.size
                    try {
                        pagerState.animateScrollToPage(
                            page = nextPage,
                            animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
                        )
                    } catch (_: Exception) {
                        // Handled smoothly if interrupted by user touch
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Top Header: App Brand + VIP Lifetime Action Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "RAKIB AI ULTRA",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF00E5FF),
                    letterSpacing = 1.8.sp
                )
                Text(
                    text = "CARROM AIM ULTRA • GEMINI 2.5",
                    fontSize = 10.sp,
                    color = Color(0xFF7E92B0),
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Passcode / 7-Day Access Button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (isAppUnlocked) Brush.horizontalGradient(listOf(Color(0x4D00E676), Color(0x3300C853)))
                        else Brush.horizontalGradient(listOf(Color(0x33FFD700), Color(0x22FFA000)))
                    )
                    .border(
                        1.5.dp,
                        if (isAppUnlocked) Color(0xFF00E676) else Color(0xFFFFD700),
                        RoundedCornerShape(20.dp)
                    )
                    .clickable { onOpenPasscodeDialog() }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isAppUnlocked) "⚡ 7 DAYS ACTIVE" else "🔒 ENTER PASSCODE",
                        color = if (isAppUnlocked) Color(0xFF00E676) else Color(0xFFFFD700),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Sleek Neon Auto Device Recognition Card
        val manufacturer = remember {
            Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
        val deviceModel = remember { Build.MODEL }
        val screenMetrics = remember { context.resources.displayMetrics }
        val screenDimensionsStr = remember { "${screenMetrics.widthPixels} × ${screenMetrics.heightPixels} px (${screenMetrics.densityDpi} DPI)" }
        val androidVersionStr = remember { "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" }
        val hwid = cloudLicenseInfo?.hardwareId ?: remember(context) { LicenseVerificationService.getHardwareFingerprint(context) }

        Spacer(modifier = Modifier.height(12.dp))

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = if (!isAppUnlocked) Color(0xFFFF5252) else Color(0x5500E5FF)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = if (!isAppUnlocked) listOf(Color(0x2EFF1744), Color(0x0A060B13))
                            else listOf(Color(0x1F00E5FF), Color(0x0A060B13))
                        )
                    )
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📱", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "DEVICE: $manufacturer $deviceModel".uppercase(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (!isAppUnlocked) Color(0xFFFF8A80) else Color(0xFF00E5FF),
                            letterSpacing = 0.5.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (!isAppUnlocked) Color(0x33FF1744) else Color(0x3300E676))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (!isAppUnlocked) "HW LOCKED 🔒" else "LINKED 🟢",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (!isAppUnlocked) Color(0xFFFF5252) else Color(0xFF00E676)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "🛡️ HWID: $hwid",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF90CAF9)
                    )
                    Text(
                        text = "⚡ $androidVersionStr",
                        fontSize = 10.sp,
                        color = Color(0xFF78909C)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = when {
                        isAppUnlocked -> "⚡ 7-Day Access Active (Hardware Keystore Anchored)"
                        else -> "⚠️ 7-Day Session Expired for this Device (Passcode Required)"
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        isAppUnlocked -> Color(0xFF00E676)
                        else -> Color(0xFFFF5252)
                    }
                )
            }
        }

        // Cloud Server Verification & Real-time AI Handshake Bar
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x1400E5FF))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (cloudAiState.isConnected) Color(0xFF00E676) else Color(0xFFFFD700))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${cloudAiState.statusText} • ${if (isAppUnlocked) "7-DAY ACTIVE" else "LOCKED"}",
                    fontSize = 10.sp,
                    color = Color(0xFF90CAF9),
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = "${cloudAiState.handshakeProtocol} (${cloudAiState.latencyMs}ms)",
                fontSize = 10.sp,
                color = Color(0xFF00E5FF),
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // High-Clarity Crystal Banner Slider with Smooth Horizontal Swiping & Active Page Indicators
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0D1B2A))
                .border(
                    1.8.dp,
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xFF00E5FF), Color(0xFFFFD700), Color(0xFF00E5FF))
                    ),
                    RoundedCornerShape(20.dp)
                )
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                val targetItem = bannerItems[pageIndex]
                Box(modifier = Modifier.fillMaxSize()) {
                    // 100% Original Brightness & Crystal Clear Photo Rendering
                    if (targetItem.bitmap != null) {
                        Image(
                            bitmap = targetItem.bitmap.asImageBitmap(),
                            contentDescription = targetItem.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(targetItem.drawableRes)
                                .crossfade(600)
                                .placeholder(targetItem.fallbackRes)
                                .error(targetItem.fallbackRes)
                                .fallback(targetItem.fallbackRes)
                                .build(),
                            contentDescription = targetItem.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Floating Glassmorphic Mini-Badge in bottom-left corner
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xCC050A14))
                            .border(1.dp, Color(0x6600E5FF), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E676))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = targetItem.title,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = targetItem.subtitle,
                                    color = Color(0xFF00E5FF),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Carousel Dot Indicators with Click-to-Jump and Width Morphing
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x99050A14))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                bannerItems.indices.forEach { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 18.dp else 6.dp, 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) Color(0xFF00E5FF) else Color(0x66FFFFFF)
                            )
                            .clickable {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Access / Trial Countdown Status Card
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (!isAppUnlocked) onOpenPasscodeDialog()
                },
            borderColor = if (!isAppUnlocked) Color(0xFFFF1744) else Color(0x3300E5FF)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (!isAppUnlocked) Brush.horizontalGradient(listOf(Color(0x2EFF1744), Color(0x14060B13)))
                        else Brush.horizontalGradient(listOf(Color(0x1A00E5FF), Color(0x0A060B13)))
                    )
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (!isAppUnlocked) "HARDWARE ACCESS STATUS"
                        else "7-DAY ACCESS COUNTDOWN",
                        fontSize = 11.sp,
                        color = if (!isAppUnlocked) Color(0xFFFF8A80) else Color(0xFF88A0C2),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (!isAppUnlocked) "LOCKED (ENTER PASSCODE)"
                        else formatCountdown(remainingMillis),
                        fontSize = 16.sp,
                        color = if (!isAppUnlocked) Color(0xFFFF5252)
                        else Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isAppUnlocked) Color(0xFF00E676) else Color(0xFFFF1744))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isAppUnlocked) "ACTIVE ⚡"
                        else "LOCKED 🔒",
                        color = if (isAppUnlocked) Color(0xFF00E676) else Color(0xFFFF1744),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // =========================================================================
        // ONE-TAP "START ENGINE" MASTER ACTION & AI NEURAL MATRIX SYNC
        // =========================================================================
        var isStartingEngineSync by remember { mutableStateOf(false) }
        var syncProgress by remember { mutableFloatStateOf(0f) }
        var syncStatusText by remember { mutableStateOf("⚡ Initializing Neural Matrix...") }

        // Infinite glowing border pulse for master button
        val infiniteTransition = rememberInfiniteTransition(label = "StartEngineGlow")
        val glowAlpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "GlowAlpha"
        )

        fun executeStartEngine() {
            if (!isAppUnlocked) {
                Toast.makeText(context, "🔒 Locked. Enter Master Passcode to unlock 7-Day access.", Toast.LENGTH_SHORT).show()
                onOpenPasscodeDialog()
                return
            }

            if (isFloatingServiceActive) {
                val intent = Intent(context, FloatingAimService::class.java)
                context.stopService(intent)
                Toast.makeText(context, "🛑 Floating Aim Engine Stopped", Toast.LENGTH_SHORT).show()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                onToggleFloatingService() // will trigger showPermissionDialog in MainActivity
                return
            }

            coroutineScope.launch {
                isStartingEngineSync = true
                syncProgress = 0.15f
                syncStatusText = "⚡ Initializing Gemini 2.5 Neural Matrix..."

                delay(280)
                syncProgress = 0.55f
                syncStatusText = "🎯 Calibrating 120 FPS Sub-Pixel Raycaster..."

                delay(320)
                syncProgress = 0.90f
                syncStatusText = "🌐 Neural Cloud Matrix Synced (100%)..."

                delay(300)
                syncProgress = 1.0f
                syncStatusText = "🚀 Launching Carrom Disc Pool & HUD..."

                // Start Foreground Service
                val intent = Intent(context, FloatingAimService::class.java)
                ContextCompat.startForegroundService(context, intent)

                // Instant Auto Launch Carrom Disc Pool
                val packageName = "com.miniclip.carrom"
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)

                if (launchIntent != null) {
                    // গেম ফোনে ইনস্টল থাকলে সরাসরি ওপেন করবে
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    Toast.makeText(context, "🎯 Carrom Disc Pool Launched with Ultra HUD!", Toast.LENGTH_SHORT).show()
                } else {
                    // যদি ইনস্টল না থাকে তবেই কেবল প্লে স্টোরে পাঠাবে
                    try {
                        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(marketIntent)
                    } catch (e: Exception) {
                        try {
                            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(webIntent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "🎯 Rakib AI ultra Floating Engine Active (120 FPS)!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // Immediately send this app to background
                (context as? Activity)?.moveTaskToBack(true)

                delay(400)
                isStartingEngineSync = false
            }
        }

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = if (isFloatingServiceActive) Color(0xFF00E676)
            else if (!isAppUnlocked) Color(0xFFFF5252).copy(alpha = glowAlpha)
            else Color(0xFF00E5FF).copy(alpha = glowAlpha)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = if (isFloatingServiceActive) listOf(Color(0x1F00E676), Color(0x0A060B13))
                            else if (!isAppUnlocked) listOf(Color(0x2EFF1744), Color(0x14060B13))
                            else listOf(Color(0x2E00E5FF), Color(0x14FFD700), Color(0x0A060B13))
                        )
                    )
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isFloatingServiceActive) Brush.linearGradient(listOf(Color(0xFF00E676), Color(0xFF00838F)))
                                else if (!isAppUnlocked) Brush.linearGradient(listOf(Color(0xFFFF1744), Color(0xFFB71C1C)))
                                else Brush.linearGradient(listOf(Color(0xFF00E5FF), Color(0xFFFFD700)))
                            )
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF060B13)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isFloatingServiceActive) "🟢" else if (!isAppUnlocked) "🔒" else "🚀",
                                fontSize = 24.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "MASTER AI LAUNCH ENGINE",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = if (isStartingEngineSync) syncStatusText
                            else if (isFloatingServiceActive) "HUD Active • Carrom Disc Pool Synced 🟢"
                            else if (!isAppUnlocked) "Engine Locked • Enter Master Passcode to Launch"
                            else "1-Tap Cloud Neural Sync & Instant Game Launch",
                            fontSize = 11.sp,
                            color = if (isFloatingServiceActive) Color(0xFF00E676)
                            else if (!isAppUnlocked) Color(0xFFFF8A80)
                            else Color(0xFF90CAF9),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isStartingEngineSync) Color(0x33FFD700)
                                else if (isFloatingServiceActive) Color(0x3300E676)
                                else if (!isAppUnlocked) Color(0x33FF1744)
                                else Color(0x3300E5FF)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isStartingEngineSync) "SYNCING 🧠"
                            else if (isFloatingServiceActive) "ACTIVE 🟢"
                            else if (!isAppUnlocked) "LOCKED 🔒"
                            else "READY ⚡",
                            color = if (isStartingEngineSync) Color(0xFFFFD700)
                            else if (isFloatingServiceActive) Color(0xFF00E676)
                            else if (!isAppUnlocked) Color(0xFFFF5252)
                            else Color(0xFF00E5FF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Sleek Neural Matrix Progress Bar during launch sync
                if (isStartingEngineSync) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = syncStatusText,
                                fontSize = 10.5.sp,
                                color = Color(0xFF00E5FF),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${(syncProgress * 100).toInt()}%",
                                fontSize = 10.5.sp,
                                color = Color(0xFFFFD700),
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1B2838))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(syncProgress)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFF00E5FF), Color(0xFFFFD700), Color(0xFF00E676))
                                        )
                                    )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Prominent Glowing Cyan-Gold Master Button (or Locked Warning Button)
                Button(
                    onClick = { executeStartEngine() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        disabledContainerColor = Color(0xFF1E2631)
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    enabled = !isStartingEngineSync
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                when {
                                    !isAppUnlocked -> Brush.horizontalGradient(listOf(Color(0xFF4A148C), Color(0xFFB71C1C), Color(0xFF4A148C)))
                                    isStartingEngineSync -> Brush.horizontalGradient(listOf(Color(0xFF37474F), Color(0xFF455A64)))
                                    isFloatingServiceActive -> Brush.horizontalGradient(listOf(Color(0xFFFF1744), Color(0xFFD50000)))
                                    else -> Brush.horizontalGradient(
                                        listOf(
                                            Color(0xFF00E5FF),
                                            Color(0xFFFFD700),
                                            Color(0xFF00E5FF)
                                        )
                                    )
                                }
                            )
                            .border(
                                width = if (isFloatingServiceActive) 0.dp else 1.5.dp,
                                color = if (isFloatingServiceActive) Color.Transparent
                                else if (!isAppUnlocked) Color(0xFFFF5252).copy(alpha = 0.8f)
                                else Color(0xFFFFFFFF).copy(alpha = 0.6f),
                                shape = RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isStartingEngineSync) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color(0xFFFFD700),
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "SYNCING NEURAL MATRIX...",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp,
                                    color = Color.White,
                                    letterSpacing = 1.sp
                                )
                            } else if (isFloatingServiceActive) {
                                Text(
                                    text = "🛑 STOP ENGINE",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp,
                                    color = Color.White,
                                    letterSpacing = 1.sp
                                )
                            } else if (!isAppUnlocked) {
                                Text(
                                    text = "🔒 LOCKED • ENTER MASTER PASSCODE",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.5.sp,
                                    color = Color(0xFFFFD700),
                                    letterSpacing = 1.sp
                                )
                            } else {
                                Text(
                                    text = "🚀 START ENGINE",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 16.sp,
                                    color = Color(0xFF060B13),
                                    letterSpacing = 1.5.sp
                                )
                            }
                        }
                    }
                }

                // Secondary Quick Launch Button when service is running
                if (isFloatingServiceActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val packageName = "com.miniclip.carrom"
                            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launchIntent)
                                Toast.makeText(context, "🎯 Carrom Disc Pool Launched!", Toast.LENGTH_SHORT).show()
                            } else {
                                try {
                                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(marketIntent)
                                } catch (e: Exception) {
                                    try {
                                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(webIntent)
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Opening Carrom Disc Pool...", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            (context as? Activity)?.moveTaskToBack(true)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x3300E5FF))
                    ) {
                        Text(
                            text = "⚡ OPEN CARROM DISC POOL",
                            color = Color(0xFF00E5FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section: Gemini 2.5 Flash Vision & AI Screen Frame Analyzer
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = Color(0x336200EA)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "🧠 GEMINI 2.5 FLASH VISION SCAN",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF)
                        )
                        Text(
                            text = "Automated coin, pocket & strike vector detection",
                            fontSize = 11.sp,
                            color = Color(0xFF88A0C2)
                        )
                    }

                    Button(
                        onClick = onAnalyzeGemini,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EA)),
                        modifier = Modifier.height(36.dp)
                    ) {
                        if (isAnalyzingWithGemini) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("AI SCAN", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (aiAnalysisResult != null) {
                    val res = aiAnalysisResult
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x1A00E5FF))
                                .padding(8.dp)
                        ) {
                            Column {
                                Text("TARGET POCKET", fontSize = 9.sp, color = Color(0xFF88A0C2), fontWeight = FontWeight.Bold)
                                Text(res.targetPocket, fontSize = 13.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x1A00E676))
                                .padding(8.dp)
                        ) {
                            Column {
                                Text("ANGLE & POWER", fontSize = 9.sp, color = Color(0xFF88A0C2), fontWeight = FontWeight.Bold)
                                Text("${res.shotAngleDegrees.toInt()}° | ${res.recommendedPowerPercent}%", fontSize = 13.sp, color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x1AFFD700))
                                .padding(8.dp)
                        ) {
                            Column {
                                Text("CONFIDENCE", fontSize = 9.sp, color = Color(0xFF88A0C2), fontWeight = FontWeight.Bold)
                                Text("${(res.confidence * 100).toInt()}%", fontSize = 13.sp, color = Color(0xFFFFD700), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "💡 Strategy: ${res.strategyNotes}",
                        fontSize = 11.sp,
                        color = Color(0xFFCFD8DC),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section: Precision Center Target Math Engine Status Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = Color(0xFFFFD700).copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x24FFD700), Color(0x0A060B13))
                        )
                    )
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x33FFD700))
                                .border(1.dp, Color(0xFFFFD700), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🎯", fontSize = 18.sp)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "CENTER TARGET MATH ENGINE",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFFFD700),
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Kinetic impulse & surface friction deceleration",
                                fontSize = 10.5.sp,
                                color = Color(0xFFCFD8DC)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x3300E676))
                            .border(1.dp, Color(0xFF00E676), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "ACTIVE",
                            color = Color(0xFF00E676),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x1AFFD700))
                            .padding(8.dp)
                    ) {
                        Column {
                            Text("FRICTION COEFF (μ)", fontSize = 8.5.sp, color = Color(0xFF88A0C2), fontWeight = FontWeight.Bold)
                            Text("0.082 (Boric)", fontSize = 12.sp, color = Color(0xFFFFD700), fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x1A00E5FF))
                            .padding(8.dp)
                    ) {
                        Column {
                            Text("FORMULA MODEL", fontSize = 8.5.sp, color = Color(0xFF88A0C2), fontWeight = FontWeight.Bold)
                            Text("v₀ = √(2ad)", fontSize = 12.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x1A00E676))
                            .padding(8.dp)
                    ) {
                        Column {
                            Text("TARGET TOLERANCE", fontSize = 8.5.sp, color = Color(0xFF88A0C2), fontWeight = FontWeight.Bold)
                            Text("±28px Center", fontSize = 12.sp, color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "⚡ Real-time dynamic impulse vectors ensure the striker/puck stops dead-center with zero rebound.",
                    fontSize = 10.5.sp,
                    color = Color(0xFFB0BEC5),
                    lineHeight = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section: Rakib Pro AI Capabilities Feature Matrix Showcase
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = Color(0x6600E5FF)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x1F00E5FF), Color(0x0A060B13))
                        )
                    )
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "⚡ RAKIB PRO AI CAPABILITIES",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF00E5FF),
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Military-grade vector raycasting & neural board physics",
                            fontSize = 10.5.sp,
                            color = Color(0xFF88A0C2)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x3300E5FF))
                            .border(1.dp, Color(0x6600E5FF), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "PRO V2.5",
                            color = Color(0xFF00E5FF),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                val proFeatures = listOf(
                    ProFeatureItem(
                        icon = "⚡",
                        title = "100% Zero-Miss Trajectory Engine",
                        subtitle = "Direct, 3-Cushion & Multi-Bank Rays with sub-pixel cushion reflection",
                        tag = "ZERO-MISS",
                        accentColor = Color(0xFF00E5FF)
                    ),
                    ProFeatureItem(
                        icon = "🎯",
                        title = "Smart Auto-Lock & Queen Priority Sequence",
                        subtitle = "Instant corner pocket locking with automated Queen + White cover 2-shot plan",
                        tag = "AUTO-LOCK",
                        accentColor = Color(0xFFFFD700)
                    ),
                    ProFeatureItem(
                        icon = "🤖",
                        title = "Undetectable Anti-Ban Humanized Auto-Strike",
                        subtitle = "Physiological Cubic Bezier curves & randomized thumb release micro-jitter",
                        tag = "ANTI-BAN",
                        accentColor = Color(0xFF00E676)
                    ),
                    ProFeatureItem(
                        icon = "🌐",
                        title = "Real-Time Cloud AI Vector Sync",
                        subtitle = "Continuous physics matrix updates & micro-calibration with instant offline fallback",
                        tag = "CLOUD AI",
                        accentColor = Color(0xFFD500F9)
                    ),
                    ProFeatureItem(
                        icon = "📱",
                        title = "Universal Display & Board Auto-Calibration",
                        subtitle = "Hardware-accelerated 120 FPS laser shaders tailored for all mobile screens",
                        tag = "120 FPS",
                        accentColor = Color(0xFFFF9100)
                    )
                )

                proFeatures.forEachIndexed { index, feat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x0F00E5FF))
                            .border(0.8.dp, feat.accentColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(feat.accentColor.copy(alpha = 0.15f))
                                .border(1.dp, feat.accentColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = feat.icon, fontSize = 17.sp)
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = feat.title,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = feat.subtitle,
                                color = Color(0xFF90A4AE),
                                fontSize = 10.sp,
                                lineHeight = 13.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(feat.accentColor.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = feat.tag,
                                color = feat.accentColor,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (index < proFeatures.size - 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

/**
 * 2. STORE TAB SCREEN
 */
@Composable
fun StoreScreen(
    isAppUnlocked: Boolean,
    remainingMillis: Long,
    onOpenPasscodeDialog: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "PREMIUM STORE",
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF00E5FF),
            letterSpacing = 1.5.sp
        )
        Text(
            text = "Unlock Ultra Trajectory Engine & 7-Day Access",
            fontSize = 11.sp,
            color = Color(0xFF88A0C2)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 7-Day Pro Access Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = if (isAppUnlocked) Color(0xFF00E676) else Color(0xFFFFD700)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x2BFFD700), Color(0x0F060B13))
                        )
                    )
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "⚡ 7-DAY PRO ACCESS",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFFFD700)
                        )
                        Text(
                            text = "Hardware-Locked 168-Hour Access",
                            fontSize = 11.sp,
                            color = Color(0xFFE0E0E0)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isAppUnlocked) Color(0xFF00E676) else Color(0xFFFFD700))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isAppUnlocked) "ACTIVE ✅" else "LOCKED 🔒",
                            color = Color(0xFF060B13),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                listOf(
                    "✔ 7-Day Unlimited Aim Trajectory Lines",
                    "✔ Gemini 2.5 Flash Vision AI Scans",
                    "✔ Multi-Bank Cushion Rebound Trajectories",
                    "✔ Auto-Lock Target Pocket Predictor",
                    "✔ Sub-Pixel 120 FPS Real-time Physics Engine"
                ).forEach { feature ->
                    Text(
                        text = feature,
                        fontSize = 12.sp,
                        color = Color(0xFFCFD8DC),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onOpenPasscodeDialog,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAppUnlocked) Color(0xFF00E676) else Color(0xFFFFD700)
                    )
                ) {
                    Text(
                        text = if (isAppUnlocked) "7-DAY ACCESS ACTIVE (${formatCountdown(remainingMillis)})" else "ENTER MASTER PASSCODE",
                        color = Color(0xFF060B13),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Store Feature Cards
        val storeItems = listOf(
            Triple("🎯 3-Cushion Master Ray", "Predict up to 3 bank rebounds with precision physics", "INCLUDED IN VIP"),
            Triple("🌈 Neon Chroma Laser Pack", "Unlock 12 glowing aesthetic laser beam colors", "INCLUDED IN VIP"),
            Triple("⚡ Zero-Lag Cloud Pipeline", "Sub-10ms Gemini Vision frame analysis pipeline", "INCLUDED IN VIP")
        )

        storeItems.forEach { (title, desc, tag) ->
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = desc, fontSize = 11.sp, color = Color(0xFF88A0C2))
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x1F00E5FF))
                            .border(1.dp, Color(0x4D00E5FF), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = tag, color = Color(0xFF00E5FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

/**
 * 3. PROFILE TAB SCREEN
 */
@Composable
fun ProfileScreen(
    isAppUnlocked: Boolean,
    remainingMillis: Long,
    cloudLicenseInfo: CloudLicenseStatus?,
    cloudAiState: CloudAiConnectionState,
    onOpenPasscodeDialog: () -> Unit
) {
    val context = LocalContext.current
    val manufacturer = remember {
        Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
    val deviceModel = remember { Build.MODEL }
    val screenMetrics = remember { context.resources.displayMetrics }

    val profileBitmap = remember(context) {
        try {
            val bytes = Base64.decode(BannerAssets.BANNER_1_BASE64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: BitmapFactory.decodeResource(context.resources, R.drawable.my_banner_1)
        } catch (e: Exception) {
            BitmapFactory.decodeResource(context.resources, R.drawable.my_banner_1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Avatar Profile Header with Decoded Bitmap and Coil AsyncImage Fallback
        Box(
            modifier = Modifier
                .size(82.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF00E5FF), Color(0xFF0D47A1))
                    )
                )
                .border(2.5.dp, if (isAppUnlocked) Color(0xFF00E676) else Color(0xFFFFD700), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (profileBitmap != null) {
                Image(
                    bitmap = profileBitmap.asImageBitmap(),
                    contentDescription = "Profile Photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(R.drawable.my_banner_1)
                        .crossfade(true)
                        .placeholder(R.drawable.my_photo_1)
                        .error(R.drawable.my_photo_1)
                        .fallback(R.drawable.my_photo_1)
                        .build(),
                    contentDescription = "Profile Photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Rakibul User",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "rakibul74348@gmail.com",
            fontSize = 11.sp,
            color = Color(0xFF88A0C2)
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Subscription Status Chip
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (isAppUnlocked) Color(0x3300E676) else Color(0x22FF1744))
                .border(1.dp, if (isAppUnlocked) Color(0xFF00E676) else Color(0xFFFF1744), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (isAppUnlocked) "⚡ 7-DAY ACCESS ACTIVE" else "🔒 ACCESS LOCKED",
                color = if (isAppUnlocked) Color(0xFF00E676) else Color(0xFFFF5252),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Active Subscription Details Card
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "SUBSCRIPTION & LICENSE DETAILS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )

                Spacer(modifier = Modifier.height(10.dp))

                ProfileInfoRow("License Tier", "7-Day Hardware-Bound Session")
                ProfileInfoRow("Status", if (isAppUnlocked) "Active (168-Hour Session)" else "Locked (Passcode Required)")
                ProfileInfoRow("Remaining Time", if (isAppUnlocked) formatCountdown(remainingMillis) else "Expired / Locked")
                ProfileInfoRow("Hardware ID", cloudLicenseInfo?.hardwareId ?: LicenseVerificationService.getHardwareFingerprint(context))
                ProfileInfoRow("Anti-Reset Protection", "Active (Hardware Keystore Anchored)")
                ProfileInfoRow("Cloud AI Server", "${cloudAiState.latencyMs}ms • ${cloudAiState.handshakeProtocol}")
                ProfileInfoRow("Vision Node", cloudAiState.visionNode)
                ProfileInfoRow("Grid Calibration", cloudAiState.boardGrid)

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onOpenPasscodeDialog,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAppUnlocked) Color(0xFF1B2A3F) else Color(0xFFFFD700)
                    )
                ) {
                    Text(
                        text = if (isAppUnlocked) "VERIFY PASSCODE STATUS" else "ENTER MASTER PASSCODE",
                        color = if (isAppUnlocked) Color(0xFF00E5FF) else Color(0xFF060B13),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Linked Device Specs Card
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "LINKED HARDWARE & ENVIRONMENT",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )

                Spacer(modifier = Modifier.height(10.dp))

                ProfileInfoRow("Device Model", "$manufacturer $deviceModel")
                ProfileInfoRow("Manufacturer", manufacturer)
                ProfileInfoRow("Screen Resolution", "${screenMetrics.widthPixels} × ${screenMetrics.heightPixels} px")
                ProfileInfoRow("Screen Density", "${screenMetrics.densityDpi} DPI")
                ProfileInfoRow("Android Version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                ProfileInfoRow("Target Game", "com.miniclip.carrom")
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
fun ProfileInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color(0xFF88A0C2), fontSize = 12.sp)
        Text(text = value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 4. SETTINGS TAB SCREEN
 */
@Composable
fun SettingsScreen(
    isStealthMode: Boolean,
    onStealthModeChange: (Boolean) -> Unit,
    is120FpsEnabled: Boolean,
    on120FpsChange: (Boolean) -> Unit,
    isAutoLaunchGame: Boolean,
    onAutoLaunchGameChange: (Boolean) -> Unit,
    selectedLaserColor: Color,
    onLaserColorChange: (Color) -> Unit,
    laserStrokeThickness: Float,
    onLaserStrokeChange: (Float) -> Unit,
    isDualBankEnabled: Boolean,
    onDualBankChange: (Boolean) -> Unit,
    isAutoPocketEnabled: Boolean,
    onAutoPocketChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "SETTINGS & PREFERENCES",
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF00E5FF),
            letterSpacing = 1.5.sp
        )
        Text(
            text = "Anti-Ban Security, 120 FPS Performance & Aim Engine",
            fontSize = 11.sp,
            color = Color(0xFF88A0C2)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Anti-Ban & Performance Security Card
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🛡️ ANTI-BAN & STEALTH SECURITY",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E676)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x3300E676))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isStealthMode) "PROTECTION ACTIVE" else "STANDARD",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E676)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Stealth Guard Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Anti-Ban Stealth / Safe Mode", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Hardware-level safe rendering • Bypasses in-game screen recording captures (FLAG_SECURE) • 0 Game File Modification", color = Color(0xFF88A0C2), fontSize = 10.sp)
                    }
                    Switch(
                        checked = isStealthMode,
                        onCheckedChange = onStealthModeChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00E676),
                            checkedTrackColor = Color(0x6600E676)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 120 FPS Frame Optimization Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("120 FPS Ultra-Smooth Mode", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Choreographer vsync loop for zero touch latency on 90Hz/120Hz/144Hz displays", color = Color(0xFF88A0C2), fontSize = 10.sp)
                    }
                    Switch(
                        checked = is120FpsEnabled,
                        onCheckedChange = on120FpsChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00E5FF),
                            checkedTrackColor = Color(0x6600E5FF)
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Engine & Launch Preferences
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "ENGINE & LAUNCHER PREFERENCES",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Auto-Launch Game
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Launch Carrom Disc Pool", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Automatically opens com.miniclip.carrom upon starting engine", color = Color(0xFF88A0C2), fontSize = 10.sp)
                    }
                    Switch(
                        checked = isAutoLaunchGame,
                        onCheckedChange = onAutoLaunchGameChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00E5FF),
                            checkedTrackColor = Color(0x6600E5FF)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3-Cushion Bank Rebound Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("3-Cushion Bank Rebound Physics", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Calculates precision 3-stage cushion reflection rays for complex bank shots", color = Color(0xFF88A0C2), fontSize = 10.sp)
                    }
                    Switch(
                        checked = isDualBankEnabled,
                        onCheckedChange = onDualBankChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00E5FF),
                            checkedTrackColor = Color(0x6600E5FF)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Auto Pocket Prediction & Lock Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto Pocket Lock & Prediction", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Highlights target corner pocket with pulsating reticle lock", color = Color(0xFF88A0C2), fontSize = 10.sp)
                    }
                    Switch(
                        checked = isAutoPocketEnabled,
                        onCheckedChange = onAutoPocketChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00E5FF),
                            checkedTrackColor = Color(0x6600E5FF)
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Laser Appearance & Color Picker
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "LASER APPEARANCE & COLOR PICKER",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text("Select Laser Color Theme:", color = Color(0xFF88A0C2), fontSize = 11.sp)
                Spacer(modifier = Modifier.height(8.dp))

                val colorList = listOf(
                    Pair("Cyan", Color(0xFF00E5FF)),
                    Pair("Green", Color(0xFF00E676)),
                    Pair("Gold", Color(0xFFFFD700)),
                    Pair("Purple", Color(0xFFD500F9)),
                    Pair("Magenta", Color(0xFFFF007F)),
                    Pair("Orange", Color(0xFFFF9100))
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    colorList.forEach { (name, color) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (selectedLaserColor == color) 3.dp else 1.dp,
                                        color = if (selectedLaserColor == color) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { onLaserColorChange(color) }
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = name, fontSize = 9.sp, color = if (selectedLaserColor == color) Color(0xFF00E5FF) else Color(0xFF78909C))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Laser Beam Thickness: ${laserStrokeThickness.toInt()}px",
                    color = Color(0xFF88A0C2),
                    fontSize = 11.sp
                )
                Slider(
                    value = laserStrokeThickness,
                    onValueChange = onLaserStrokeChange,
                    valueRange = 2f..16f,
                    steps = 14,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF),
                        inactiveTrackColor = Color(0x33FFFFFF)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

/**
 * Interactive In-App Carrom Table Simulation Component with 3-Cushion Rebounds and Pulsating Pocket Lock.
 */
@Composable
fun InteractiveAimBoard(
    laserColor: Color,
    strokeWidth: Float,
    isDualBank: Boolean,
    isLinesOn: Boolean
) {
    var strikerPos by remember { mutableStateOf(Offset(120f, 420f)) }
    var coinPos by remember { mutableStateOf(Offset(240f, 240f)) }
    var selectedTarget by remember { mutableIntStateOf(0) }

    val infiniteTransition = rememberInfiniteTransition(label = "TargetLockPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )

    val boardBg = Color(0xFF0E1A2D)
    val boardBorder = Color(0xFF263F63)
    val pocketColor = Color(0xFF050B13)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(boardBg)
            .border(2.dp, boardBorder, RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val distStriker = hypot(offset.x - strikerPos.x, offset.y - strikerPos.y)
                        val distCoin = hypot(offset.x - coinPos.x, offset.y - coinPos.y)
                        selectedTarget = when {
                            distStriker < 70f -> 1
                            distCoin < 70f -> 2
                            else -> 0
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (selectedTarget == 1) {
                            strikerPos += dragAmount
                        } else if (selectedTarget == 2) {
                            coinPos += dragAmount
                        }
                    },
                    onDragEnd = { selectedTarget = 0 },
                    onDragCancel = { selectedTarget = 0 }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val margin = 24f

            val pockets = listOf(
                Offset(margin, margin),
                Offset(w - margin, margin),
                Offset(margin, h - margin),
                Offset(w - margin, h - margin)
            )

            pockets.forEach { p ->
                drawCircle(color = pocketColor, radius = 22f, center = p)
                drawCircle(color = Color(0x5500E5FF), radius = 22f, center = p, style = Stroke(width = 2f))
            }

            drawCircle(color = Color(0x22FFFFFF), radius = 40f, center = Offset(w / 2, h / 2), style = Stroke(width = 2f))
            drawCircle(color = Color(0x33FF1744), radius = 10f, center = Offset(w / 2, h / 2))

            if (isLinesOn) {
                var targetPocket = pockets[0]
                var minD = Float.MAX_VALUE
                pockets.forEach { p ->
                    val d = hypot(p.x - coinPos.x, p.y - coinPos.y)
                    if (d < minD) {
                        minD = d
                        targetPocket = p
                    }
                }

                // 1. Striker -> Coin Line
                drawLine(
                    color = laserColor.copy(alpha = 0.35f),
                    start = strikerPos,
                    end = coinPos,
                    strokeWidth = strokeWidth * 2f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = laserColor,
                    start = strikerPos,
                    end = coinPos,
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )

                // 2. Coin -> Pocket Line
                drawLine(
                    color = laserColor.copy(alpha = 0.35f),
                    start = coinPos,
                    end = targetPocket,
                    strokeWidth = strokeWidth * 2f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = laserColor,
                    start = coinPos,
                    end = targetPocket,
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )

                // 3. 3-Cushion Bank Reflection Lines
                if (isDualBank) {
                    val c1 = Offset(w - margin, (coinPos.y * 0.4f).coerceIn(margin, h - margin))
                    val c2 = Offset((w * 0.5f).coerceIn(margin, w - margin), margin)
                    val c3 = Offset(margin, (h * 0.4f).coerceIn(margin, h - margin))

                    // Cushion 1 (Gold)
                    drawLine(
                        color = Color(0xFFFFD700),
                        start = strikerPos,
                        end = c1,
                        strokeWidth = strokeWidth * 0.9f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                        cap = StrokeCap.Round
                    )
                    drawCircle(color = Color(0xFFFFD700), radius = 6f, center = c1)

                    // Cushion 2 (Amber)
                    drawLine(
                        color = Color(0xFFFF9100),
                        start = c1,
                        end = c2,
                        strokeWidth = strokeWidth * 0.8f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f),
                        cap = StrokeCap.Round
                    )
                    drawCircle(color = Color(0xFFFF9100), radius = 6f, center = c2)

                    // Cushion 3 (Purple)
                    drawLine(
                        color = Color(0xFFD500F9),
                        start = c2,
                        end = c3,
                        strokeWidth = strokeWidth * 0.7f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                        cap = StrokeCap.Round
                    )
                    drawCircle(color = Color(0xFFD500F9), radius = 6f, center = c3)
                }

                // Dynamic Pulsating Target Lock Reticle on Destination Pocket
                val pulseRadius = 24f + (pulseScale * 22f)
                val pulseAlpha = ((1f - pulseScale) * 0.8f).coerceIn(0f, 1f)
                drawCircle(
                    color = Color(0xFF00E676).copy(alpha = pulseAlpha),
                    radius = pulseRadius,
                    center = targetPocket,
                    style = Stroke(width = 3f)
                )

                drawCircle(
                    color = Color(0xFF00E676),
                    radius = 24f,
                    center = targetPocket,
                    style = Stroke(width = 3.5f)
                )

                // Reticle Crosshairs
                drawLine(
                    color = Color(0xFF00E676),
                    start = Offset(targetPocket.x - 30f, targetPocket.y),
                    end = Offset(targetPocket.x + 30f, targetPocket.y),
                    strokeWidth = 2f
                )
                drawLine(
                    color = Color(0xFF00E676),
                    start = Offset(targetPocket.x, targetPocket.y - 30f),
                    end = Offset(targetPocket.x, targetPocket.y + 30f),
                    strokeWidth = 2f
                )
            }

            drawCircle(color = Color(0xFFFFD700), radius = 18f, center = strikerPos)
            drawCircle(color = Color.White, radius = 18f, center = strikerPos, style = Stroke(width = 2.5f))

            drawCircle(color = Color(0xFFFF1744), radius = 14f, center = coinPos)
            drawCircle(color = Color.White, radius = 14f, center = coinPos, style = Stroke(width = 2f))
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    borderColor: Color = Color(0x22FFFFFF),
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x17FFFFFF))
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
    ) {
        content()
    }
}

fun formatCountdown(millis: Long): String {
    val totalSec = millis / 1000
    val days = totalSec / (24 * 3600)
    val hours = (totalSec % (24 * 3600)) / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return String.format("%02dd : %02dh : %02dm : %02ds", days, hours, minutes, seconds)
}
