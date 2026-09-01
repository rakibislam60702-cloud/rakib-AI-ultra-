package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

class FloatingAimService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var aimOverlayView: AimOverlayView? = null
    private var isPaused = false

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "rakib_aim_hud_channel"
        private const val NOTIFICATION_ID = 1001
        val isServiceRunning = MutableStateFlow(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning.value = true
        startForegroundNotification()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        setupAimOverlayCanvas()
        setupFloatingHUDWidget()
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
        val switchCenter = floatingView!!.findViewById<Switch>(R.id.switch_center_target)
        val seekThickness = floatingView!!.findViewById<SeekBar>(R.id.seekbar_thickness)
        val btnPause = floatingView!!.findViewById<Button>(R.id.btn_pause_tracking)

        // বাবল ড্র্যাগ এবং ট্যাপ হ্যান্ডলার
        bubbleIcon.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
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
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            // বাবল ট্যাপে মেনু খোলা/বন্ধ
                            menuContainer.visibility = if (menuContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        }
                        return true
                    }
                }
                return false
            }
        })

        // শুধু ম্যাচ শুরু হলেই দাগ স্ক্রিনে আসবে
        switchMatch.setOnCheckedChangeListener { _, isChecked ->
            aimOverlayView?.visibility = if (isChecked && !isPaused) View.VISIBLE else View.GONE
        }

        switchAutoPlay.setOnCheckedChangeListener { _, isChecked ->
            AimEngine.isAutoPlayActive = isChecked
            aimOverlayView?.isAutoPlayActive = isChecked
            aimOverlayView?.wakeRenderingEngine()
        }

        switchCenter.setOnCheckedChangeListener { _, isChecked ->
            AimEngine.isCenterBullseyeActive = isChecked
            aimOverlayView?.config = aimOverlayView?.config?.copy(isCenterTargetGuideEnabled = isChecked) ?: AimEngineConfig()
            aimOverlayView?.invalidate()
        }

        seekThickness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
                AimEngine.laserThickness = (p1 + 2).toFloat()
                aimOverlayView?.config = aimOverlayView?.config?.copy(strokeWidth = (p1 + 2).toFloat()) ?: AimEngineConfig()
                aimOverlayView?.invalidate()
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        // কালার সিলেকশন
        floatingView!!.findViewById<Button>(R.id.btn_color_cyan).setOnClickListener {
            AimEngine.lineColor = Color.parseColor("#00E5FF")
            aimOverlayView?.invalidate()
        }
        floatingView!!.findViewById<Button>(R.id.btn_color_gold).setOnClickListener {
            AimEngine.lineColor = Color.parseColor("#FFD700")
            aimOverlayView?.invalidate()
        }
        floatingView!!.findViewById<Button>(R.id.btn_color_red).setOnClickListener {
            AimEngine.lineColor = Color.parseColor("#FF1744")
            aimOverlayView?.invalidate()
        }
        floatingView!!.findViewById<Button>(R.id.btn_color_green).setOnClickListener {
            AimEngine.lineColor = Color.parseColor("#00E676")
            aimOverlayView?.invalidate()
        }

        // পজ বাটন - স্ক্রিন বন্ধ হবে না, শুধু ট্র্যাকিং পজ থাকবে
        btnPause.setOnClickListener {
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
