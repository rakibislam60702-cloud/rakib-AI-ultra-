package com.example

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

class FloatingAimService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var aimOverlayView: AimOverlayView? = null
    private var isPaused = false

    // =========================================================================
    // LIGHTWEIGHT MEMORY-ONLY MEDIA PROJECTION & IMAGE READER VISION ENGINE
    // =========================================================================
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageProcessingThread: HandlerThread? = null
    private var imageProcessingHandler: Handler? = null

    // Frame Throttling: 20 FPS Max (~50ms interval) for zero-lag vision processing
    private var lastFrameProcessedTimestamp = 0L
    private val MIN_FRAME_INTERVAL_MS = 50L // 20 checks/second
    private var isProcessingFrame = false
    private var reuseBitmap: Bitmap? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleFadeRunnable = Runnable {
        val menu = floatingView?.findViewById<LinearLayout>(R.id.floating_menu_container)
        if (menu == null || menu.visibility != View.VISIBLE) {
            floatingView?.animate()?.alpha(0.30f)?.setDuration(400)?.start()
        }
    }

    private fun resetIdleFade(menuVisible: Boolean = false) {
        idleHandler.removeCallbacks(idleFadeRunnable)
        floatingView?.animate()?.alpha(1.0f)?.setDuration(150)?.start()
        if (!menuVisible) {
            idleHandler.postDelayed(idleFadeRunnable, 3000L)
        }
    }

    companion object {
        private const val TAG = "FloatingAimService"
        private const val NOTIFICATION_CHANNEL_ID = "rakib_aim_hud_channel"
        private const val NOTIFICATION_ID = 1001
        val isServiceRunning = MutableStateFlow(false)

        // MediaProjection token holder
        var mediaProjectionResultCode: Int = Activity.RESULT_CANCELED
        var mediaProjectionResultData: Intent? = null

        fun setMediaProjectionPermission(resultCode: Int, data: Intent?) {
            mediaProjectionResultCode = resultCode
            mediaProjectionResultData = data
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("mp_result_code", mediaProjectionResultCode) ?: mediaProjectionResultCode
        val resultData: Intent? = intent?.getParcelableExtra("mp_result_data") ?: mediaProjectionResultData

        if (resultCode == Activity.RESULT_OK && resultData != null && mediaProjection == null) {
            initMediaProjectionCapture(resultCode, resultData)
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning.value = true
        startForegroundNotification()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        setupAimOverlayCanvas()
        setupFloatingHUDWidget()
        resetIdleFade(false)

        if (mediaProjectionResultCode == Activity.RESULT_OK && mediaProjectionResultData != null) {
            initMediaProjectionCapture(mediaProjectionResultCode, mediaProjectionResultData!!)
        }
    }

    /**
     * Initializes lightweight memory-only frame capture:
     * - Downscales capture resolution to 50% scale (e.g., 540x1200 instead of 1080x2400)
     * - Reduces memory bandwidth by >75% with zero disk writes, encoding, or recording
     * - Uses background HandlerThread for fast off-main-thread ImageReader buffer reads
     */
    private fun initMediaProjectionCapture(resultCode: Int, resultData: Intent) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            if (mediaProjection == null) {
                Log.w(TAG, "MediaProjection permission returned null")
                return
            }

            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)

            // 50% Scale Downscaling (540p equivalent)
            val captureWidth = (metrics.widthPixels * 0.5f).toInt().coerceAtLeast(360)
            val captureHeight = (metrics.heightPixels * 0.5f).toInt().coerceAtLeast(640)
            val densityDpi = (metrics.densityDpi * 0.5f).toInt().coerceAtLeast(120)

            imageProcessingThread = HandlerThread("CarromVisionThread").apply { start() }
            imageProcessingHandler = Handler(imageProcessingThread!!.looper)

            imageReader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2
            )

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "CarromAimVisionDisplay",
                captureWidth,
                captureHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                imageProcessingHandler
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                processImageReaderFrame(reader, captureWidth, captureHeight)
            }, imageProcessingHandler)

            Log.i(TAG, "Memory-only 50% scale ImageReader initialized ($captureWidth x $captureHeight @ 20 FPS max)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection: ${e.message}", e)
        }
    }

    /**
     * Processes frames with strict throttling (~50ms / 20 FPS max) and immediate closing:
     * - Discards excess frames instantly to maintain 0% CPU overhead when idle.
     * - Memory-only Direct Pixel buffer extraction without file I/O or disk caching.
     * - Always calls image.close() inside a try-finally block to prevent memory leaks or GC stalls.
     */
    private fun processImageReaderFrame(reader: ImageReader, targetW: Int, targetH: Int) {
        val now = SystemClock.elapsedRealtime()

        // Throttle check: Skip frame if within 50ms interval or already processing
        if (now - lastFrameProcessedTimestamp < MIN_FRAME_INTERVAL_MS || isProcessingFrame || isPaused) {
            // Drain and close immediately
            try {
                reader.acquireLatestImage()?.close()
            } catch (_: Exception) {}
            return
        }

        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            lastFrameProcessedTimestamp = now
            isProcessingFrame = true

            val planes = image.planes
            if (planes.isEmpty()) return

            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * targetW

            val bitmapWidth = targetW + rowPadding / pixelStride
            if (reuseBitmap == null || reuseBitmap?.width != bitmapWidth || reuseBitmap?.height != targetH) {
                reuseBitmap?.recycle()
                reuseBitmap = Bitmap.createBitmap(bitmapWidth, targetH, Bitmap.Config.ARGB_8888)
            }

            reuseBitmap?.copyPixelsFromBuffer(buffer)
            val currentFrameBmp = reuseBitmap

            if (currentFrameBmp != null) {
                // Pass lightweight downscaled frame to Vision Engine
                val metrics = DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(metrics)
                val fullBoardBounds = AimEngine.calculateBoardBounds(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())

                // Scale down board bounds to match 50% viewport
                val scaleFactor = targetW.toFloat() / metrics.widthPixels.toFloat()
                val scaledBounds = CarromBoardBounds(
                    boardSize = fullBoardBounds.boardSize * scaleFactor,
                    boardLeft = fullBoardBounds.boardLeft * scaleFactor,
                    boardTop = fullBoardBounds.boardTop * scaleFactor,
                    boardRight = fullBoardBounds.boardRight * scaleFactor,
                    boardBottom = fullBoardBounds.boardBottom * scaleFactor,
                    cushionLeft = fullBoardBounds.cushionLeft * scaleFactor,
                    cushionTop = fullBoardBounds.cushionTop * scaleFactor,
                    cushionRight = fullBoardBounds.cushionRight * scaleFactor,
                    cushionBottom = fullBoardBounds.cushionBottom * scaleFactor,
                    baselineY = fullBoardBounds.baselineY * scaleFactor,
                    baselineStartX = fullBoardBounds.baselineStartX * scaleFactor,
                    baselineEndX = fullBoardBounds.baselineEndX * scaleFactor,
                    pockets = fullBoardBounds.pockets.mapValues { (_, pt) ->
                        PointF(pt.x * scaleFactor, pt.y * scaleFactor)
                    },
                    pocketRadius = fullBoardBounds.pocketRadius * scaleFactor,
                    boardCenter = PointF(fullBoardBounds.boardCenter.x * scaleFactor, fullBoardBounds.boardCenter.y * scaleFactor)
                )

                val detection = AimEngine.detectBoardEntitiesFromViewport(currentFrameBmp, scaledBounds)

                // Dispatch to Overlay View on Main Thread smoothly
                Handler(Looper.getMainLooper()).post {
                    if (detection.isVisionCalibrated && aimOverlayView != null) {
                        val invScale = 1.0f / scaleFactor
                        val fullStrikerX = detection.strikerPosition.x * invScale
                        val fullStrikerY = detection.strikerPosition.y * invScale
                        aimOverlayView?.updateLiveStrikerPosition(fullStrikerX, fullStrikerY)

                        val firstTargetPuck = detection.detectedPucks.firstOrNull()
                        if (firstTargetPuck != null) {
                            val fullPuckX = firstTargetPuck.position.x * invScale
                            val fullPuckY = firstTargetPuck.position.y * invScale
                            aimOverlayView?.updateLiveCoinPosition(fullPuckX, fullPuckY)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame processing exception: ${e.message}")
        } finally {
            // Immediate closing prevents buffer exhaustion and GC pauses
            try {
                image?.close()
            } catch (_: Exception) {}
            isProcessingFrame = false
        }
    }

    private fun startForegroundNotification() {
        val channelName = "Rakib AI Ultra Aim HUD"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active Floating HUD Service for Real-time Aim Assistance"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Rakib AI Aim HUD Active")
            .setContentText("Tap floating icon to adjust aim trajectories & settings")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun setupAimOverlayCanvas() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // পুরো স্ক্রিন টাচ-ফ্রি এবং পাস-থ্রু করার ফ্ল্যাগ
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        aimOverlayView = AimOverlayView(this).apply {
            visibility = View.GONE // গেমের শুরুতে বন্ধ থাকবে
        }
        windowManager.addView(aimOverlayView, overlayParams)
    }

    private fun setupFloatingHUDWidget() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val hudParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)
        windowManager.addView(floatingView, hudParams)

        val bubbleIcon = floatingView!!.findViewById<ImageView>(R.id.floating_bubble_icon)
        val menuContainer = floatingView!!.findViewById<LinearLayout>(R.id.floating_menu_container)
        val switchMatch = floatingView!!.findViewById<Switch>(R.id.switch_match_active)
        val switchAutoPlay = floatingView!!.findViewById<Switch>(R.id.switch_auto_play)
        val switchFastMode = floatingView!!.findViewById<Switch>(R.id.switch_fast_mode)
        val btnTriggerStrike = floatingView!!.findViewById<Button>(R.id.btn_trigger_auto_strike)
        val switchCenter = floatingView!!.findViewById<Switch>(R.id.switch_center_target)
        val seekThickness = floatingView!!.findViewById<SeekBar>(R.id.seekbar_thickness)
        val seekStriker = floatingView!!.findViewById<SeekBar>(R.id.seekbar_striker_slider)
        val btnPause = floatingView!!.findViewById<Button>(R.id.btn_pause_tracking)

        // বাবল ড্র্যাগ, সিঙ্গেল ট্যাপ (টগল গাইডলাইন), ডাবল ট্যাপ (মেইন HUD ডায়লগ) এবং ৩-সেকেন্ড আইডল ফেড
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Single Tap: Toggle trajectory guidelines visibility instantly (Show / Hide lines)
                val currentlyVisible = (aimOverlayView?.visibility == View.VISIBLE)
                val nextVisible = !currentlyVisible
                aimOverlayView?.setMatchMode(nextVisible)
                aimOverlayView?.visibility = if (nextVisible && !isPaused) View.VISIBLE else View.GONE
                if (nextVisible) {
                    aimOverlayView?.wakeRenderingEngine()
                    Toast.makeText(this@FloatingAimService, "🎯 Aim Lines: VISIBLE", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FloatingAimService, "👁️ Aim Lines: HIDDEN", Toast.LENGTH_SHORT).show()
                }
                switchMatch.isChecked = nextVisible
                resetIdleFade(menuContainer.visibility == View.VISIBLE)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Double Tap: Open the main HUD settings dialog (switch between Auto-Play & pure Manual mode)
                val isMenuOpen = menuContainer.visibility == View.VISIBLE
                val willOpen = !isMenuOpen
                menuContainer.visibility = if (willOpen) View.VISIBLE else View.GONE
                resetIdleFade(willOpen)
                return true
            }
        })

        bubbleIcon.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                resetIdleFade(menuContainer.visibility == View.VISIBLE)
                gestureDetector.onTouchEvent(event)

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = hudParams.x
                        initialY = hudParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDragging = true
                        }
                        if (isDragging) {
                            hudParams.x = initialX + dx.toInt()
                            hudParams.y = initialY + dy.toInt()
                            windowManager.updateViewLayout(floatingView, hudParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        resetIdleFade(menuContainer.visibility == View.VISIBLE)
                        return true
                    }
                }
                return false
            }
        })

        // মেনু কন্টেইনারে যেকোনো ইন্টারঅ্যাকশনে আইডল টাইমার রিসেট হবে
        menuContainer.setOnTouchListener { _, _ ->
            resetIdleFade(true)
            false
        }

        // শুধু ম্যাচ শুরু হলেই দাগ স্ক্রিনে আসবে
        switchMatch.setOnCheckedChangeListener { _, isChecked ->
            resetIdleFade(menuContainer.visibility == View.VISIBLE)
            aimOverlayView?.setMatchMode(isChecked)
            aimOverlayView?.visibility = if (isChecked && !isPaused) View.VISIBLE else View.GONE
        }

        // স্ট্রাইকার বেসলাইন স্লাইডার লিসেনার (৬০ FPS লাইভ পজিশনিং)
        seekStriker?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                resetIdleFade(menuContainer.visibility == View.VISIBLE)
                aimOverlayView?.setStrikerBaselineSliderRatio(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                resetIdleFade(true)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                resetIdleFade(menuContainer.visibility == View.VISIBLE)
            }
        })

        // Auto-Play Strike Switch with Accessibility Verification
        switchAutoPlay.setOnCheckedChangeListener { buttonView, isChecked ->
            resetIdleFade(menuContainer.visibility == View.VISIBLE)
            if (isChecked) {
                if (!AutoStrikeAccessibilityService.isAccessibilitySettingsOn(this@FloatingAimService)) {
                    buttonView.isChecked = false
                    btnTriggerStrike?.visibility = View.GONE
                    Toast.makeText(
                        this@FloatingAimService,
                        "⚠️ Please enable 'AutoStrike Service' in Accessibility Settings",
                        Toast.LENGTH_LONG
                    ).show()
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    return@setOnCheckedChangeListener
                }

                AimEngine.isAutoPlayActive = true
                aimOverlayView?.isAutoPlayActive = true
                aimOverlayView?.wakeRenderingEngine()
                btnTriggerStrike?.visibility = View.VISIBLE
                Toast.makeText(this@FloatingAimService, "⚡ Auto-Play Strike Active", Toast.LENGTH_SHORT).show()
            } else {
                AimEngine.isAutoPlayActive = false
                aimOverlayView?.isAutoPlayActive = false
                btnTriggerStrike?.visibility = View.GONE
            }
        }

        switchFastMode?.setOnCheckedChangeListener { _, isChecked ->
            resetIdleFade(menuContainer.visibility == View.VISIBLE)
            AimEngine.isFastModeActive = isChecked
            aimOverlayView?.isFastMode = isChecked
            val modeMsg = if (isChecked) "⚡ Fast Mode (80ms Strike): ON" else "Standard Strike Mode: ON"
            Toast.makeText(this@FloatingAimService, modeMsg, Toast.LENGTH_SHORT).show()
        }

        // Instant Auto-Strike Manual Trigger Button
        btnTriggerStrike?.setOnClickListener {
            resetIdleFade(menuContainer.visibility == View.VISIBLE)
            if (!AutoStrikeAccessibilityService.isAccessibilitySettingsOn(this@FloatingAimService)) {
                Toast.makeText(
                    this@FloatingAimService,
                    "⚠️ Please enable Accessibility Service in Settings",
                    Toast.LENGTH_LONG
                ).show()
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return@setOnClickListener
            }

            aimOverlayView?.triggerAutoStrike { success ->
                if (success) {
                    Toast.makeText(this@FloatingAimService, "🎯 Strike Fired!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FloatingAimService, "Strike cancelled or service unavailable", Toast.LENGTH_SHORT).show()
                }
            }
        }

        switchCenter.setOnCheckedChangeListener { _, isChecked ->
            resetIdleFade(menuContainer.visibility == View.VISIBLE)
            AimEngine.isCenterBullseyeActive = isChecked
            aimOverlayView?.config = aimOverlayView?.config?.copy(isCenterTargetGuideEnabled = isChecked) ?: AimEngineConfig()
            aimOverlayView?.invalidate()
        }

        seekThickness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
                resetIdleFade(menuContainer.visibility == View.VISIBLE)
                aimOverlayView?.setLaserThickness((p1 + 2).toFloat())
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {
                resetIdleFade(true)
            }
            override fun onStopTrackingTouch(p0: SeekBar?) {
                resetIdleFade(menuContainer.visibility == View.VISIBLE)
            }
        })

        // কালার সিলেকশন - Cyan (#00E5FF), Yellow (#FFD600), Red (#FF1744), Neon Green (#00E676)
        floatingView!!.findViewById<Button>(R.id.btn_color_cyan).setOnClickListener {
            resetIdleFade(menuContainer.visibility == View.VISIBLE)
            aimOverlayView?.setLaserColor(Color.parseColor("#00E5FF"))
            Toast.makeText(this@FloatingAimService, "🎨 Laser Color: Cyan (#00E5FF)", Toast.LENGTH_SHORT).show()
        }
        floatingView!!.findViewById<Button>(R.id.btn_color_gold).setOnClickListener {
            resetIdleFade(menuContainer.visibility == View.VISIBLE)
            aimOverlayView?.setLaserColor(Color.parseColor("#FFD600"))
            Toast.makeText(this@FloatingAimService, "🎨 Laser Color: Yellow (#FFD600)", Toast.LENGTH_SHORT).show()
        }
        floatingView!!.findViewById<Button>(R.id.btn_color_red).setOnClickListener {
            resetIdleFade(menuContainer.visibility == View.VISIBLE)
            aimOverlayView?.setLaserColor(Color.parseColor("#FF1744"))
            Toast.makeText(this@FloatingAimService, "🎨 Laser Color: Red (#FF1744)", Toast.LENGTH_SHORT).show()
        }
        floatingView!!.findViewById<Button>(R.id.btn_color_green).setOnClickListener {
            resetIdleFade(menuContainer.visibility == View.VISIBLE)
            aimOverlayView?.setLaserColor(Color.parseColor("#00E676"))
            Toast.makeText(this@FloatingAimService, "🎨 Laser Color: Neon Green (#00E676)", Toast.LENGTH_SHORT).show()
        }

        // পজ বাটন - স্ক্রিন বন্ধ হবে না, শুধু ট্র্যাকিং পজ থাকবে
        btnPause.setOnClickListener {
            resetIdleFade(menuContainer.visibility == View.VISIBLE)
            isPaused = !isPaused
            if (isPaused) {
                aimOverlayView?.visibility = View.GONE
                btnPause.text = "RESUME TRACKING"
                btnPause.setBackgroundColor(Color.parseColor("#D50000"))
            } else {
                if (switchMatch.isChecked) aimOverlayView?.visibility = View.VISIBLE
                btnPause.text = "PAUSE TRACKING"
                btnPause.setBackgroundColor(Color.parseColor("#263238"))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning.value = false
        CloudPhysicsSyncClient.stopTurnSyncWindow()
        NetworkClient.shutdown()

        // Clean up MediaProjection & ImageReader memory resources
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
            imageProcessingThread?.quitSafely()
            imageProcessingThread = null
            reuseBitmap?.recycle()
            reuseBitmap = null
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup exception: ${e.message}")
        }

        aimOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
    }
}
