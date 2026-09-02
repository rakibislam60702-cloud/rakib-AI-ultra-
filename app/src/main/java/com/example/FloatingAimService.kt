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
import android.animation.ValueAnimator
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.*

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

    // Frame Throttling: 25 FPS (~40ms interval) on dedicated background HandlerThread
    private var lastFrameProcessedTimestamp = 0L
    private val MIN_FRAME_INTERVAL_MS = 40L // 25 FPS
    private var isProcessingFrame = false
    private var reuseBoardPixels: IntArray? = null
    private var reuseBitmap: Bitmap? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleFadeRunnable = Runnable {
        val drawer = floatingView?.findViewById<LinearLayout>(R.id.compact_settings_drawer)
        if (drawer == null || drawer.visibility != View.VISIBLE) {
            floatingView?.animate()?.alpha(0.65f)?.setDuration(350)?.start()
        }
    }

    private fun resetIdleFade(drawerVisible: Boolean = false) {
        idleHandler.removeCallbacks(idleFadeRunnable)
        floatingView?.animate()?.alpha(1.0f)?.setDuration(150)?.start()
        if (!drawerVisible) {
            idleHandler.postDelayed(idleFadeRunnable, 4000L)
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
     * Processes frames with strict throttling (~40ms / 25 FPS) and immediate closing:
     * - Discards excess frames instantly to maintain 0% CPU overhead when idle.
     * - Direct int[] pixel buffer extraction strictly mapped to 1:1 Carrom Board square.
     * - Dispatches deterministic striker, target puck, and isPlayerTurn status to AimOverlayView.
     * - Always calls image.close() inside a try-finally block to prevent memory leaks or GC stalls.
     */
    private fun processImageReaderFrame(reader: ImageReader, targetW: Int, targetH: Int) {
        val now = SystemClock.elapsedRealtime()

        // Throttle check: Skip frame if within 40ms interval or already processing
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
                val metrics = DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(metrics)
                val fullBoardBounds = AimEngine.calculateBoardBounds(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())

                // Crop & extract raw pixel buffer strictly mapped to the 1:1 board square
                val scaleFactor = targetW.toFloat() / metrics.widthPixels.toFloat()
                val scaledBoardSize = (fullBoardBounds.boardSize * scaleFactor).toInt()
                val scaledBoardLeft = (fullBoardBounds.boardLeft * scaleFactor).toInt().coerceAtLeast(0)
                val scaledBoardTop = (fullBoardBounds.boardTop * scaleFactor).toInt().coerceAtLeast(0)

                val validBoardSide = min(scaledBoardSize, min(targetW - scaledBoardLeft, targetH - scaledBoardTop))

                if (validBoardSide > 100) {
                    val pixelCount = validBoardSide * validBoardSide
                    if (reuseBoardPixels == null || reuseBoardPixels?.size != pixelCount) {
                        reuseBoardPixels = IntArray(pixelCount)
                    }
                    val boardPixels = reuseBoardPixels!!

                    currentFrameBmp.getPixels(
                        boardPixels,
                        0,
                        validBoardSide,
                        scaledBoardLeft,
                        scaledBoardTop,
                        validBoardSide,
                        validBoardSide
                    )

                    // Execute deterministic 2D pixel scan on background thread
                    val detection = AimEngine.scanBoardPixelsDirect(boardPixels, validBoardSide, fullBoardBounds)

                    // Dispatch to Overlay View on Main Thread smoothly
                    Handler(Looper.getMainLooper()).post {
                        if (aimOverlayView != null) {
                            aimOverlayView?.updateVisionDetection(
                                isTurn = detection.isPlayerTurn,
                                striker = detection.strikerPosition,
                                puck = detection.targetPuckPosition,
                                pocket = detection.targetPocket,
                                pocketName = detection.targetPocketName
                            )
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

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // Compact side dock layout params
        val hudParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0 // Cleanly docked to the left edge by default
            y = (screenHeight * 0.35f).toInt()
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)
        windowManager.addView(floatingView, hudParams)

        val pillBarContainer = floatingView!!.findViewById<LinearLayout>(R.id.pill_bar_container)
        val btnAutoPlay = floatingView!!.findViewById<LinearLayout>(R.id.btn_auto_play_pill)
        val ledStatus = floatingView!!.findViewById<View>(R.id.led_status_indicator)
        val iconPlayPause = floatingView!!.findViewById<ImageView>(R.id.icon_play_pause)
        val tvAutoPlayLabel = floatingView!!.findViewById<TextView>(R.id.tv_auto_play_label)

        val btnRakibUltra = floatingView!!.findViewById<LinearLayout>(R.id.btn_rakib_ultra_pill)
        val tvDrawerChevron = floatingView!!.findViewById<TextView>(R.id.tv_drawer_chevron)
        val compactDrawer = floatingView!!.findViewById<LinearLayout>(R.id.compact_settings_drawer)
        val btnCloseDrawer = floatingView!!.findViewById<TextView>(R.id.btn_close_drawer)

        val seekThickness = floatingView!!.findViewById<SeekBar>(R.id.seekbar_thickness)
        val btnCyan = floatingView!!.findViewById<Button>(R.id.btn_color_cyan)
        val btnGold = floatingView!!.findViewById<Button>(R.id.btn_color_gold)
        val btnRed = floatingView!!.findViewById<Button>(R.id.btn_color_red)
        val btnGreen = floatingView!!.findViewById<Button>(R.id.btn_color_green)
        val btnToggleLines = floatingView!!.findViewById<Button>(R.id.btn_toggle_aim_lines)

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        // Edge-Snapping Animator to cleanly dock widget to Left or Right screen edge
        fun snapToNearestEdge() {
            val pillWidth = pillBarContainer.width.takeIf { it > 0 } ?: (122 * resources.displayMetrics.density).toInt()
            val totalWidth = if (compactDrawer.visibility == View.VISIBLE) {
                floatingView?.width ?: pillWidth
            } else {
                pillWidth
            }

            val currentX = hudParams.x
            val midPoint = currentX + (totalWidth / 2)
            val targetX = if (midPoint < screenWidth / 2) {
                0 // Snap flush to the Left edge
            } else {
                (screenWidth - totalWidth).coerceAtLeast(0) // Snap flush to the Right edge
            }

            val animator = ValueAnimator.ofInt(currentX, targetX).apply {
                duration = 200L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    hudParams.x = anim.animatedValue as Int
                    try {
                        windowManager.updateViewLayout(floatingView, hudParams)
                    } catch (_: Exception) {}
                }
            }
            animator.start()
        }

        // Draggable touch handler cleanly separating click events from dragging
        fun createDraggableTouchListener(onSingleTap: () -> Unit): View.OnTouchListener {
            return object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isDragging = false
                private var downTime = 0L

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    resetIdleFade(compactDrawer.visibility == View.VISIBLE)
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = hudParams.x
                            initialY = hudParams.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            downTime = System.currentTimeMillis()
                            v.isPressed = true
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            if (hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                                isDragging = true
                                v.isPressed = false
                            }
                            if (isDragging) {
                                hudParams.x = (initialX + dx.toInt()).coerceIn(-50, screenWidth + 50)
                                val widgetH = floatingView?.height ?: 200
                                val minY = 40
                                val maxY = (screenHeight - widgetH).coerceAtLeast(minY)
                                hudParams.y = (initialY + dy.toInt()).coerceIn(minY, maxY)
                                try {
                                    windowManager.updateViewLayout(floatingView, hudParams)
                                } catch (_: Exception) {}
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            v.isPressed = false
                            val elapsed = System.currentTimeMillis() - downTime
                            if (!isDragging && elapsed < 400L) {
                                v.performClick()
                                onSingleTap.invoke()
                            } else if (isDragging) {
                                snapToNearestEdge()
                            }
                            return true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            if (isDragging) {
                                snapToNearestEdge()
                            }
                            return true
                        }
                    }
                    return false
                }
            }
        }

        // Helper to update the Auto-Play pill visual states (LED, Icon, Label)
        fun updateAutoPlayUI(isActive: Boolean) {
            if (isActive) {
                ledStatus.setBackgroundResource(R.drawable.bg_led_green)
                iconPlayPause.setImageResource(R.drawable.ic_pause)
                tvAutoPlayLabel.text = "AUTO"
                tvAutoPlayLabel.setTextColor(Color.parseColor("#00E676"))
            } else {
                ledStatus.setBackgroundResource(R.drawable.bg_led_red)
                iconPlayPause.setImageResource(R.drawable.ic_play_arrow)
                tvAutoPlayLabel.text = "MANUAL"
                tvAutoPlayLabel.setTextColor(Color.WHITE)
            }
        }

        // Initialize state
        updateAutoPlayUI(AimEngine.isAutoPlayActive)

        // 1. TOP BUTTON: Auto-Play Toggle Pill
        btnAutoPlay.setOnTouchListener(createDraggableTouchListener {
            resetIdleFade(compactDrawer.visibility == View.VISIBLE)
            val nextState = !AimEngine.isAutoPlayActive

            if (nextState) {
                // Verify Accessibility permission before activating hands-free Auto-Strike
                if (!AutoStrikeAccessibilityService.isAccessibilitySettingsOn(this@FloatingAimService)) {
                    updateAutoPlayUI(false)
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
                    return@createDraggableTouchListener
                }

                AimEngine.isAutoPlayActive = true
                aimOverlayView?.isAutoPlayActive = true
                aimOverlayView?.wakeRenderingEngine()
                updateAutoPlayUI(true)
                Toast.makeText(this@FloatingAimService, "⚡ Auto-Play Active: Hands-Free Slingshot ON", Toast.LENGTH_SHORT).show()
            } else {
                AimEngine.isAutoPlayActive = false
                aimOverlayView?.isAutoPlayActive = false
                updateAutoPlayUI(false)
                Toast.makeText(this@FloatingAimService, "✋ Manual Mode: Play with your fingers", Toast.LENGTH_SHORT).show()
            }
        })

        // 2. BOTTOM BUTTON: "Rakib Ultra" Pill (Expands/Collapses Compact Drawer)
        fun toggleDrawer() {
            resetIdleFade(true)
            val willOpen = (compactDrawer.visibility != View.VISIBLE)
            compactDrawer.visibility = if (willOpen) View.VISIBLE else View.GONE
            tvDrawerChevron.text = if (willOpen) "◀" else "⚙"

            // Adjust position if expanding near the right screen edge
            if (willOpen) {
                floatingView?.post {
                    val currentRight = hudParams.x + (floatingView?.width ?: 0)
                    if (currentRight > screenWidth) {
                        hudParams.x = (screenWidth - (floatingView?.width ?: 0)).coerceAtLeast(0)
                        try {
                            windowManager.updateViewLayout(floatingView, hudParams)
                        } catch (_: Exception) {}
                    }
                }
            } else {
                snapToNearestEdge()
            }
        }

        btnRakibUltra.setOnTouchListener(createDraggableTouchListener {
            toggleDrawer()
        })

        pillBarContainer.setOnTouchListener(createDraggableTouchListener {
            toggleDrawer()
        })

        btnCloseDrawer.setOnClickListener {
            compactDrawer.visibility = View.GONE
            tvDrawerChevron.text = "⚙"
            resetIdleFade(false)
            snapToNearestEdge()
        }

        // 3. COMPACT SLIDING DRAWER SETTINGS
        // Line Thickness Slider
        seekThickness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, p1: Int, fromUser: Boolean) {
                resetIdleFade(true)
                aimOverlayView?.setLaserThickness((p1 + 2).toFloat())
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {
                resetIdleFade(true)
            }
            override fun onStopTrackingTouch(p0: SeekBar?) {
                resetIdleFade(true)
            }
        })

        // Laser Color Palette: Cyan, Yellow, Red, Green
        btnCyan.setOnClickListener {
            resetIdleFade(true)
            aimOverlayView?.setLaserColor(Color.parseColor("#00E5FF"))
            Toast.makeText(this@FloatingAimService, "🎨 Cyan Laser", Toast.LENGTH_SHORT).show()
        }
        btnGold.setOnClickListener {
            resetIdleFade(true)
            aimOverlayView?.setLaserColor(Color.parseColor("#FFD600"))
            Toast.makeText(this@FloatingAimService, "🎨 Yellow Laser", Toast.LENGTH_SHORT).show()
        }
        btnRed.setOnClickListener {
            resetIdleFade(true)
            aimOverlayView?.setLaserColor(Color.parseColor("#FF1744"))
            Toast.makeText(this@FloatingAimService, "🎨 Red Laser", Toast.LENGTH_SHORT).show()
        }
        btnGreen.setOnClickListener {
            resetIdleFade(true)
            aimOverlayView?.setLaserColor(Color.parseColor("#00E676"))
            Toast.makeText(this@FloatingAimService, "🎨 Green Laser", Toast.LENGTH_SHORT).show()
        }

        // Toggle Aim Lines (Visible / Hidden)
        var areLinesVisible = true
        btnToggleLines.setOnClickListener {
            resetIdleFade(true)
            areLinesVisible = !areLinesVisible
            aimOverlayView?.setMatchMode(areLinesVisible)
            aimOverlayView?.visibility = if (areLinesVisible && !isPaused) View.VISIBLE else View.GONE
            if (areLinesVisible) {
                aimOverlayView?.wakeRenderingEngine()
                btnToggleLines.text = "HIDE AIM LINES"
                btnToggleLines.setBackgroundColor(Color.parseColor("#263238"))
                Toast.makeText(this@FloatingAimService, "🎯 Aim Lines: ON", Toast.LENGTH_SHORT).show()
            } else {
                btnToggleLines.text = "SHOW AIM LINES"
                btnToggleLines.setBackgroundColor(Color.parseColor("#00838F"))
                Toast.makeText(this@FloatingAimService, "👁️ Aim Lines: OFF", Toast.LENGTH_SHORT).show()
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
