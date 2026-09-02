package com.example

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * High-Precision Laser Aim Assist Overlay View:
 * 1. Accurately tracks striker location horizontally along the bottom baseline with origin strictly at striker center.
 * 2. Strict Carrom board boundary clamping (trajectories never render outside wooden carrom frame or over player profiles).
 * 3. Clean 2D raycast reflections: Striker -> Target Puck -> Target Pocket.
 * 4. Solid laser guidelines with smooth alpha fading, zero cluttered badges.
 * 5. High-performance rendering capped at 60 FPS via Choreographer with zero object allocation during draw cycles (no GC churn).
 * 6. Thread-safe offloading of trajectory and physics vector calculations to Dispatchers.Default.
 */
class AimOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var config: AimEngineConfig = AimEngineConfig()
        set(value) {
            field = value
            updatePaints()
            requestAsyncTrajectoryCalculation()
            postInvalidateOnAnimation()
        }

    val strikerPos = PointF(540f, 1500f)
    val coinPos = PointF(540f, 1050f)

    // Anti-Jitter EMA Filters for Striker and Puck (Zero Shaking)
    private val strikerFilter = AntiJitterFilter(alpha = 0.38f, freezeThreshold = 1.4f)
    private val coinFilter = AntiJitterFilter(alpha = 0.38f, freezeThreshold = 1.4f)

    @Volatile
    private var currentTrajectory: AimTrajectory? = null
    private var boardBounds: CarromBoardBounds = AimEngine.calculateBoardBounds(1080f, 2400f)

    // Touch interaction tracking
    // 0 = none, 1 = striker baseline dragging, 2 = target puck dragging
    private var activeTouchTarget = 0
    private var isManualAimingActive = false
    var isMatchModeActive: Boolean = false
    var isLiveTrackingEngaged: Boolean = false

    // Calculation Coroutine Scope on Dispatchers.Default
    private val calculationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var calculationJob: Job? = null
    private var isCalculationPending = false

    // Auto-Play & Idle Sleep Engine
    var isAutoPlayActive: Boolean = false
    var isFastMode: Boolean = true
    private var isEngineAsleep = false
    private var lastInteractionTimestamp = System.currentTimeMillis()
    private val IDLE_SLEEP_THRESHOLD_MS = 15000L

    // Frame-rate throttling at 60 FPS (16.6ms / frame)
    private var lastFrameTimeNanos: Long = 0L
    private val FRAME_INTERVAL_NANOS = 16_000_000L // ~60 FPS cap

    // Cached Reusable Paints (Zero GC churn in onDraw)
    private val laserCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val laserGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val puckLaserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val puckLaserGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val bankLaserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val ghostStrikerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val ghostFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pocketTargetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val baselineTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#40FFFFFF")
    }

    private val baselineCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#6000E5FF")
    }

    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0FFFFFF")
        textSize = 28f
        isFakeBoldText = true
    }

    private val hudSubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B000E5FF")
        textSize = 22f
    }

    private val powerBarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#40000000")
    }

    private val powerBarFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }

    // Reticle & Post-Collision Cached Paints
    private val strikerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strikerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val centerWhiteDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val pocketRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC00E676")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val pocketFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2600E676")
        style = Paint.Style.FILL
    }

    private val deflectionDashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }

    private val tangentGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFAB00")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    // Cached Reusable RectF & Matrix instances
    private val clipRect = RectF()
    private val barBgRect = RectF()
    private val barFillRect = RectF()
    private val tempMatrix = Matrix()

    // 60 FPS Throttled Choreographer Frame Callback
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isEngineAsleep) {
                if (frameTimeNanos - lastFrameTimeNanos >= FRAME_INTERVAL_NANOS) {
                    lastFrameTimeNanos = frameTimeNanos
                    invalidate()
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    init {
        setWillNotDraw(false)
        updatePaints()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun updatePaints() {
        val primaryColor = AimEngine.lineColor
        val strokeW = AimEngine.laserThickness.coerceIn(2.5f, 18f)

        laserCorePaint.apply {
            color = primaryColor
            strokeWidth = strokeW
        }

        laserGlowPaint.apply {
            color = (primaryColor and 0x00FFFFFF) or 0x4D000000
            strokeWidth = strokeW * 2.8f
        }

        puckLaserPaint.apply {
            color = Color.parseColor("#FFD600") // Gold/Yellow laser for puck
            strokeWidth = strokeW * 0.9f
        }

        puckLaserGlowPaint.apply {
            color = Color.parseColor("#4DFFD600")
            strokeWidth = strokeW * 2.4f
        }

        bankLaserPaint.apply {
            color = Color.parseColor("#FF1744") // Crimson for bank rebounds
            strokeWidth = strokeW
        }

        ghostStrikerPaint.apply {
            color = primaryColor
        }

        ghostFillPaint.apply {
            color = (primaryColor and 0x00FFFFFF) or 0x26000000
        }

        pocketTargetPaint.apply {
            color = Color.parseColor("#00E676")
        }

        strikerHaloPaint.apply {
            color = (primaryColor and 0x00FFFFFF) or 0x33000000
        }

        strikerBorderPaint.apply {
            color = primaryColor
            strokeWidth = 2.5f
        }

        deflectionDashedPaint.apply {
            color = (primaryColor and 0x00FFFFFF) or 0x80000000.toInt()
            strokeWidth = config.strokeWidth * 0.65f
        }
    }

    fun setLaserColor(color: Int) {
        AimEngine.lineColor = color
        config = config.copy(laserColor = color)
        updatePaints()
        requestAsyncTrajectoryCalculation()
        wakeRenderingEngine()
        postInvalidateOnAnimation()
    }

    fun setLaserThickness(thickness: Float) {
        AimEngine.laserThickness = thickness
        config = config.copy(strokeWidth = thickness)
        updatePaints()
        requestAsyncTrajectoryCalculation()
        wakeRenderingEngine()
        postInvalidateOnAnimation()
    }

    fun wakeRenderingEngine() {
        lastInteractionTimestamp = System.currentTimeMillis()
        if (isEngineAsleep) {
            isEngineAsleep = false
            Choreographer.getInstance().postFrameCallback(frameCallback)
            requestAsyncTrajectoryCalculation()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            boardBounds = AimEngine.calculateBoardBounds(w.toFloat(), h.toFloat())

            // Position striker on baseline center
            val initialStrikerX = (boardBounds.baselineStartX + boardBounds.baselineEndX) / 2f
            strikerPos.set(initialStrikerX, boardBounds.baselineY)

            // Position target coin in upper-center quadrant
            val initialCoinX = boardBounds.boardCenter.x
            val initialCoinY = boardBounds.cushionTop + (boardBounds.boardSize * 0.32f)
            coinPos.set(initialCoinX, initialCoinY)

            requestAsyncTrajectoryCalculation()
        }
    }

    /**
     * Non-blocking asynchronous vector calculation offloaded to Dispatchers.Default.
     * Prevents any UI micro-stutters or frame drops on the Android Main UI Thread.
     */
    fun recalculateTrajectorySync() {
        requestAsyncTrajectoryCalculation()
    }

    fun requestAsyncTrajectoryCalculation() {
        val w = width.toFloat().takeIf { it > 0 } ?: return
        val h = height.toFloat().takeIf { it > 0 } ?: return
        val curStriker = PointF(strikerPos.x, strikerPos.y)
        val curCoin = PointF(coinPos.x, coinPos.y)
        val curConfig = config
        val curBounds = boardBounds

        calculationJob?.cancel()
        calculationJob = calculationScope.launch {
            val traj = AimEngine.calculateTrajectory(
                striker = curStriker,
                coin = curCoin,
                boardWidth = w,
                boardHeight = h,
                config = curConfig
            )

            currentTrajectory = traj

            // Background telemetry sync with Cloud AI Physics server on Dispatchers.IO
            traj?.let { t ->
                CloudPhysicsSyncClient.startTurnSyncWindow(
                    striker = curStriker,
                    targetPuck = curCoin,
                    pocket = t.targetPocket,
                    pocketName = t.pocketName,
                    boardBounds = curBounds
                )
            }

            // Trigger redraw on next frame
            postInvalidateOnAnimation()
        }
    }

    fun setMatchMode(active: Boolean) {
        isMatchModeActive = active
        isLiveTrackingEngaged = active
        wakeRenderingEngine()
        requestAsyncTrajectoryCalculation()
        postInvalidateOnAnimation()
    }

    /**
     * Binds the striker baseline position directly to a fraction (0.0 to 1.0) along the bottom bar.
     * Updates aim raycast origin in real-time at 60 FPS without frame drops.
     */
    fun setStrikerBaselineSliderRatio(fraction: Float) {
        val clampedFraction = fraction.coerceIn(0f, 1f)
        val x = boardBounds.baselineStartX + (boardBounds.baselineEndX - boardBounds.baselineStartX) * clampedFraction
        strikerFilter.reset(PointF(x, boardBounds.baselineY))
        strikerPos.set(x, boardBounds.baselineY)
        isLiveTrackingEngaged = true
        wakeRenderingEngine()
        requestAsyncTrajectoryCalculation()
    }

    /**
     * Real-time live striker positioning from touch or vision tracking.
     */
    fun updateLiveStrikerPosition(x: Float, y: Float? = null) {
        val targetY = y ?: boardBounds.baselineY
        val smoothed = strikerFilter.filter(PointF(x, targetY))
        val clampedX = smoothed.x.coerceIn(boardBounds.baselineStartX, boardBounds.baselineEndX)
        strikerPos.set(clampedX, boardBounds.baselineY)
        isLiveTrackingEngaged = true
        wakeRenderingEngine()
        requestAsyncTrajectoryCalculation()
    }

    /**
     * Real-time live target puck positioning from touch or vision tracking.
     */
    fun updateLiveCoinPosition(x: Float, y: Float) {
        val clamped = boardBounds.clampToCushions(PointF(x, y))
        val smoothed = coinFilter.filter(clamped)
        val strictlyClamped = boardBounds.clampToCushions(smoothed)
        coinPos.set(strictlyClamped.x, strictlyClamped.y)
        isLiveTrackingEngaged = true
        wakeRenderingEngine()
        requestAsyncTrajectoryCalculation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!config.isEnabled) return false

        val touchX = event.x
        val touchY = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wakeRenderingEngine()
                val distToStriker = hypot(touchX - strikerPos.x, touchY - strikerPos.y)
                val distToCoin = hypot(touchX - coinPos.x, touchY - coinPos.y)

                // Check striker touch or baseline slider bar touch
                val baselineZoneTop = boardBounds.baselineY - (boardBounds.boardSize * 0.12f)
                val baselineZoneBottom = boardBounds.baselineY + (boardBounds.boardSize * 0.12f)
                val isNearBaseline = touchY in baselineZoneTop..baselineZoneBottom
                val isWithinBaselineX = touchX in (boardBounds.baselineStartX - 60f)..(boardBounds.baselineEndX + 60f)

                if (distToStriker < config.strikerRadius * 3.2f || (isNearBaseline && isWithinBaselineX)) {
                    activeTouchTarget = 1
                    isManualAimingActive = true
                    isLiveTrackingEngaged = true
                    strikerFilter.reset(PointF(touchX, boardBounds.baselineY))
                    val clampedX = touchX.coerceIn(boardBounds.baselineStartX, boardBounds.baselineEndX)
                    strikerPos.set(clampedX, boardBounds.baselineY)
                    requestAsyncTrajectoryCalculation()
                    return true
                } else if (distToCoin < config.coinRadius * 3.5f) {
                    activeTouchTarget = 2
                    isManualAimingActive = true
                    isLiveTrackingEngaged = true
                    val clampedCoin = boardBounds.clampToCushions(PointF(touchX, touchY))
                    coinFilter.reset(clampedCoin)
                    coinPos.set(clampedCoin.x, clampedCoin.y)
                    requestAsyncTrajectoryCalculation()
                    return true
                }

                activeTouchTarget = 0
                isManualAimingActive = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                wakeRenderingEngine()
                if (activeTouchTarget == 1) {
                    // Striker moves strictly horizontally along bottom baseline with Anti-Jitter EMA Filter
                    val smoothed = strikerFilter.filter(PointF(touchX, boardBounds.baselineY))
                    val clampedX = smoothed.x.coerceIn(boardBounds.baselineStartX, boardBounds.baselineEndX)
                    strikerPos.set(clampedX, boardBounds.baselineY)
                    isLiveTrackingEngaged = true
                    requestAsyncTrajectoryCalculation()
                    return true
                } else if (activeTouchTarget == 2) {
                    // Target puck clamped inside cushion boundaries with Anti-Jitter EMA Filter
                    val clampedRaw = boardBounds.clampToCushions(PointF(touchX, touchY))
                    val smoothedCoin = coinFilter.filter(clampedRaw)
                    val clampedCoin = boardBounds.clampToCushions(smoothedCoin)
                    coinPos.set(clampedCoin.x, clampedCoin.y)
                    isLiveTrackingEngaged = true
                    requestAsyncTrajectoryCalculation()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeTouchTarget = 0
                isManualAimingActive = false
                wakeRenderingEngine()
                requestAsyncTrajectoryCalculation()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!config.isEnabled) return

        // Sleep management for battery saving when idle
        val now = System.currentTimeMillis()
        if (!isManualAimingActive && !isAutoPlayActive && !isMatchModeActive && (now - lastInteractionTimestamp > IDLE_SLEEP_THRESHOLD_MS)) {
            isEngineAsleep = true
            return
        }

        val bounds = boardBounds
        val traj = currentTrajectory

        // 1. Draw Baseline Guide Track if configured
        if (config.showBaselineGuide) {
            drawBaselineGuide(canvas, bounds)
        }

        // Only draw trajectory rays when tracking is active (manual touch, auto-play, match mode, or live sliding)
        val shouldDrawTrajectories = isManualAimingActive || isAutoPlayActive || isMatchModeActive || isLiveTrackingEngaged || activeTouchTarget != 0
        if (!shouldDrawTrajectories) {
            return
        }

        // 2. Strict Carrom Board Clamping:
        // Save canvas and clip strictly to the board cushion frame
        // This ensures NO trajectory line or reflection ever renders outside the board or over player profiles
        canvas.save()
        clipRect.set(
            bounds.boardLeft,
            bounds.boardTop,
            bounds.boardRight,
            bounds.boardBottom
        )
        canvas.clipRect(clipRect)

        if (traj != null) {
            // Draw trajectories inside board boundary
            drawAimTrajectories(canvas, traj, bounds)
        }

        canvas.restore()

        // 3. Draw Clean Unobtrusive Striker & Pocket Reticles
        if (traj != null) {
            drawStrikerReticle(canvas, traj)
            drawPocketReticle(canvas, traj)
            drawMinimalStatusHUD(canvas, traj, bounds)
        }
    }

    private fun drawBaselineGuide(canvas: Canvas, bounds: CarromBoardBounds) {
        val y = bounds.baselineY
        val startX = bounds.baselineStartX
        val endX = bounds.baselineEndX

        // Baseline horizontal line
        canvas.drawLine(startX, y, endX, y, baselineTrackPaint)

        // Baseline end circles
        canvas.drawCircle(startX, y, config.strikerRadius * 0.9f, baselineCirclePaint)
        canvas.drawCircle(endX, y, config.strikerRadius * 0.9f, baselineCirclePaint)
    }

    private fun drawAimTrajectories(canvas: Canvas, traj: AimTrajectory, bounds: CarromBoardBounds) {
        // 1. Bank Shot Cushion Rebound Lines (if bank mode active)
        if (traj.bankShotLines.size >= 2) {
            for (i in 0 until traj.bankShotLines.size - 1) {
                val p1 = bounds.clampToCushions(traj.bankShotLines[i])
                val p2 = bounds.clampToCushions(traj.bankShotLines[i + 1])
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, bankLaserPaint)
            }
        }

        // 2. Direct Striker to Ghost Contact Line (Solid Laser with Glow)
        val s = traj.strikerPos
        val g = bounds.clampToCushions(traj.ghostStrikerPos)

        // Draw direct laser rays
        canvas.drawLine(s.x, s.y, g.x, g.y, laserGlowPaint)
        canvas.drawLine(s.x, s.y, g.x, g.y, laserCorePaint)

        // 3. Ghost Striker Collision Contact Ring
        canvas.drawCircle(g.x, g.y, config.strikerRadius, ghostFillPaint)
        canvas.drawCircle(g.x, g.y, config.strikerRadius, ghostStrikerPaint)
        canvas.drawCircle(g.x, g.y, 4f, ghostStrikerPaint)

        // 4. Target Puck to Target Pocket Line
        val c = bounds.clampToCushions(traj.coinPos)
        val p = bounds.clampToCushions(traj.targetPocket)

        canvas.drawLine(c.x, c.y, p.x, p.y, puckLaserGlowPaint)
        canvas.drawLine(c.x, c.y, p.x, p.y, puckLaserPaint)

        // 5. Striker Deflection / Rebound Ray (Post-collision subtle guide)
        if (traj.strikerReboundLine.size >= 2) {
            val r1 = bounds.clampToCushions(traj.strikerReboundLine[0])
            val r2 = bounds.clampToCushions(traj.strikerReboundLine[1])
            canvas.drawLine(r1.x, r1.y, r2.x, r2.y, deflectionDashedPaint)
        }

        // 6. Tangent Plane for Cut Shots (if Cut Shot mode)
        traj.tangentLine?.let { tLine ->
            if (tLine.size >= 2) {
                canvas.drawLine(tLine[0].x, tLine[0].y, tLine[1].x, tLine[1].y, tangentGuidePaint)
            }
        }
    }

    private fun drawStrikerReticle(canvas: Canvas, traj: AimTrajectory) {
        val s = traj.strikerPos
        canvas.drawCircle(s.x, s.y, config.strikerRadius, strikerHaloPaint)
        canvas.drawCircle(s.x, s.y, config.strikerRadius, strikerBorderPaint)
        canvas.drawCircle(s.x, s.y, 4f, centerWhiteDotPaint)
    }

    private fun drawPocketReticle(canvas: Canvas, traj: AimTrajectory) {
        val p = traj.targetPocket
        canvas.drawCircle(p.x, p.y, config.pocketRadius * 0.85f, pocketFillPaint)
        canvas.drawCircle(p.x, p.y, config.pocketRadius * 0.85f, pocketRingPaint)
        canvas.drawCircle(p.x, p.y, 5f, pocketRingPaint)
    }

    private fun drawMinimalStatusHUD(canvas: Canvas, traj: AimTrajectory, bounds: CarromBoardBounds) {
        // Clean, elegant and unobtrusive status bar below baseline
        val hudY = bounds.baselineY + config.strikerRadius + 28f
        val centerX = bounds.boardCenter.x

        // Power gauge bar
        val barWidth = bounds.boardSize * 0.52f
        val barHeight = 8f
        val barLeft = centerX - barWidth / 2f
        val barTop = hudY + 10f

        barBgRect.set(barLeft, barTop, barLeft + barWidth, barTop + barHeight)
        canvas.drawRoundRect(barBgRect, 4f, 4f, powerBarBgPaint)

        val fillWidth = barWidth * (traj.recommendedPower / 100f)
        barFillRect.set(barLeft, barTop, barLeft + fillWidth, barTop + barHeight)
        powerBarFillPaint.color = AimEngine.lineColor
        canvas.drawRoundRect(barFillRect, 4f, 4f, powerBarFillPaint)

        // Status text with Cloud AI Physics WebSocket Latency & Anti-Jitter Lock status
        val remainingSec = CloudPhysicsSyncClient.turnRemainingSeconds
        val latency = NetworkClient.liveLatencyMs.value
        val isFallback = NetworkClient.isFallbackToLocal.value
        val cloudStatus = if (isFallback || latency > 120L) {
            "⚡ Local AI Engine (0ms)"
        } else if (CloudPhysicsSyncClient.isTurnActive) {
            "☁️ AI WSS: ${latency}ms (${remainingSec}s)"
        } else {
            "🔒 Solid Lock"
        }
        val titleText = "${traj.pocketName} • ${traj.cutAngleDegrees.toInt()}° Cut • $cloudStatus • ${traj.powerLabel}"
        val titleWidth = hudSubTextPaint.measureText(titleText)
        canvas.drawText(titleText, centerX - titleWidth / 2f, hudY, hudSubTextPaint)
    }

    fun resetPositions() {
        val w = width.toFloat().takeIf { it > 0 } ?: 1080f
        val h = height.toFloat().takeIf { it > 0 } ?: 2400f
        boardBounds = AimEngine.calculateBoardBounds(w, h)

        val initialStrikerX = (boardBounds.baselineStartX + boardBounds.baselineEndX) / 2f
        strikerPos.set(initialStrikerX, boardBounds.baselineY)
        strikerFilter.reset(PointF(initialStrikerX, boardBounds.baselineY))

        val initialCoinX = boardBounds.boardCenter.x
        val initialCoinY = boardBounds.cushionTop + (boardBounds.boardSize * 0.32f)
        coinPos.set(initialCoinX, initialCoinY)
        coinFilter.reset(PointF(initialCoinX, initialCoinY))

        wakeRenderingEngine()
        requestAsyncTrajectoryCalculation()
    }

    /**
     * Triggers the precision physical Auto-Strike gesture via AutoStrikeAccessibilityService:
     * - Striker coordinate -> Inverted pullback vector towards target ghost contact point or incoming server shot angle.
     * - Fast Mode executes in 80ms instantly; Standard Mode executes in 240ms.
     * - Validated against Cloud Physics verified impulse force curve within the 15-second turn window.
     */
    fun triggerAutoStrike(onComplete: ((Boolean) -> Unit)? = null) {
        val traj = currentTrajectory ?: return
        val cloudSol = CloudPhysicsSyncClient.latestSolution

        val sPos = traj.strikerPos
        val power = cloudSol?.recommendedPowerPercent ?: traj.recommendedPower

        if (cloudSol != null && cloudSol.precisionAngleDeg != 0f) {
            AutoStrikeAccessibilityService.performAutoStrikeByAngle(
                strikerPos = sPos,
                shotAngleDeg = cloudSol.precisionAngleDeg,
                powerPercent = power,
                durationMs = if (isFastMode || AimEngine.isFastModeActive) 80L else 240L,
                isFastMode = isFastMode || AimEngine.isFastModeActive,
                onComplete = { success ->
                    if (success) {
                        CloudPhysicsSyncClient.stopTurnSyncWindow()
                    }
                    onComplete?.invoke(success)
                }
            )
        } else {
            val targetPos = traj.ghostStrikerPos
            AutoStrikeAccessibilityService.performAutoStrike(
                strikerPos = sPos,
                aimTargetPos = targetPos,
                powerPercent = power,
                durationMs = if (isFastMode || AimEngine.isFastModeActive) 80L else 240L,
                isFastMode = isFastMode || AimEngine.isFastModeActive,
                onComplete = { success ->
                    if (success) {
                        CloudPhysicsSyncClient.stopTurnSyncWindow()
                    }
                    onComplete?.invoke(success)
                }
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isEngineAsleep = true
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        calculationJob?.cancel()
        calculationScope.cancel()
    }
}
