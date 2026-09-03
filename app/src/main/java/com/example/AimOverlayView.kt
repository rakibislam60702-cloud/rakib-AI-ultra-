package com.example

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import kotlin.math.*

/**
 * Instant Zero-Lag Interactive Carrom Aim Overlay View.
 *
 * Performance Architecture:
 * - 0% CPU consumption during idle: Zero background loops, zero pixel scanners, zero coroutine threads.
 * - Hardware-accelerated Canvas rendering only triggered on direct user interaction.
 *
 * Interactive Touch Capabilities:
 * - Striker Guide circle anchored on the bottom baseline: Touching & dragging slides the striker dynamically.
 * - Multi-Color Trajectories rendered directly on screen in real time:
 *     * Striker Vector (Line 1): Crisp White glowing solid ray connecting Striker (Xs, Ys) to Ghost-Point (G).
 *     * White Ghost Puck Circle: Rendered at collision coordinate (G).
 *     * Target Puck Trajectory (Line 2): Vivid Neon Yellow ray directing active puck into target corner pocket (K).
 *     * Secondary / Kiss Shot (Line 3): Vivid Cyan ray directing secondary puck toward pocket if path obstructed.
 *     * Stop/Resting Point Markers: Color-coded dots (White for striker, Yellow for target, Cyan for secondary).
 *
 * Instant Slingshot Dispatch:
 * - When AUTO toggle is ON and player taps to shoot, immediately dispatches the AccessibilityService
 *   gesture from the current striker position to execute the slingshot pull.
 */
class AimOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "AimOverlayView"
        private const val TOUCH_MODE_NONE = 0
        private const val TOUCH_MODE_STRIKER = 1
        private const val TOUCH_MODE_PUCK = 2
        private const val TOUCH_MODE_BOARD = 3
    }

    var config: AimEngineConfig = AimEngineConfig()
        set(value) {
            field = value
            updatePaints()
            recalculateTrajectory()
            invalidate()
        }

    val strikerPos = PointF(540f, 1500f)
    val coinPos = PointF(540f, 1100f)

    private var boardBounds: CarromBoardBounds = AimEngine.calculateBoardBounds(1080f, 2400f)
    private var currentTrajectory: AimTrajectory? = null

    // Match & Overlay State
    var isMatchModeActive: Boolean = true
    var isAimActive: Boolean = true
        private set

    var isAutoPlayActive: Boolean = false
    var isFastMode: Boolean = true

    private var overridePocketPos: PointF? = null
    private var overridePocketName: String? = null

    // Touch interaction tracking
    private var activeTouchMode = TOUCH_MODE_NONE
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    // Active vision detected pucks & secondary puck representation
    var secondaryCoinPos: PointF? = null
    private var detectedPucksList: List<PointF> = emptyList()

    // -------------------------------------------------------------------------
    // PAINTS: Legendary Neon Trajectory Visualizer (Solid 4dp, ANTI_ALIAS)
    // -------------------------------------------------------------------------

    private val density get() = resources.displayMetrics.density
    private val stroke4dp get() = (4f * density).coerceAtLeast(4f)
    private val glow10dp get() = (10f * density).coerceAtLeast(10f)

    // Line 1: Striker Aim Line (Solid bright White laser ray, Paint strokeWidth 4dp, ANTI_ALIAS)
    private val strikerLaserCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
        strokeWidth = (4f * resources.displayMetrics.density).coerceAtLeast(4f)
    }
    private val strikerLaserGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#66FFFFFF")
        strokeWidth = (10f * resources.displayMetrics.density).coerceAtLeast(10f)
    }

    // Line 2: Pocket Trajectory (Vivid Neon Yellow ray from ghost puck straight into target pocket)
    private val puckLaserCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FFEA00")
        strokeWidth = (4f * resources.displayMetrics.density).coerceAtLeast(4f)
    }
    private val puckLaserGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#66FFEA00")
        strokeWidth = (10f * resources.displayMetrics.density).coerceAtLeast(10f)
    }

    // Line 3: Cushion/Kiss Vector (Vivid Neon Cyan deflection ray with distinct color-coded resting dots)
    private val kissLaserCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00E5FF")
        strokeWidth = (4f * resources.displayMetrics.density).coerceAtLeast(4f)
    }
    private val kissLaserGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#6600E5FF")
        strokeWidth = (10f * resources.displayMetrics.density).coerceAtLeast(10f)
    }

    // Ghost Puck: High-visibility glowing white ring at the impact point
    private val ghostPuckBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = (3.5f * resources.displayMetrics.density).coerceAtLeast(3.5f)
    }
    private val ghostPuckGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#80FFFFFF")
        strokeWidth = (9.0f * resources.displayMetrics.density).coerceAtLeast(9f)
    }
    private val ghostPuckFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#25FFFFFF")
    }
    private val ghostPuckDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    // Baseline Guide Visuals
    private val baselineLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#88FFFFFF")
        strokeWidth = 2.5f
    }
    private val baselineGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3300E5FF")
        strokeWidth = 7.0f
    }
    private val baselineEndPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#AA00E5FF")
        strokeWidth = 2.5f
    }

    // Interactive Striker Guide Circle
    private val strikerGuideAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#4400E5FF")
        strokeWidth = 8.0f
    }
    private val strikerGuideBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#00E5FF")
        strokeWidth = 3.2f
    }
    private val strikerGuideFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2500E5FF")
    }
    private val strikerGuideDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    // Target Puck Guide
    private val puckGuideAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#44FFEA00")
        strokeWidth = 7.0f
    }
    private val puckGuideBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFEA00")
        strokeWidth = 2.6f
    }
    private val puckGuideFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#25FFEA00")
    }
    private val puckGuideDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFEA00")
    }

    // Color-Coded Stop/Resting Point Markers
    private val strikerRestDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val strikerRestHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#66FFFFFF")
        strokeWidth = 2.5f
    }
    private val puckRestDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFEA00")
    }
    private val puckRestHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#66FFEA00")
        strokeWidth = 2.5f
    }
    private val kissRestDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }
    private val kissRestHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#6600E5FF")
        strokeWidth = 2.5f
    }

    // Interactive Text Labels
    private val guideTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        textSize = 28f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val autoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 30f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setShadowLayer(6f, 0f, 0f, Color.parseColor("#00E5FF"))
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        updatePaints()
        recalculateTrajectory()
    }

    private fun updatePaints() {
        val width = config.strokeWidth.coerceIn(1.5f, 6.0f)
        strikerLaserCorePaint.strokeWidth = width
        strikerLaserGlowPaint.strokeWidth = width * 2.8f
        puckLaserCorePaint.strokeWidth = width
        puckLaserGlowPaint.strokeWidth = width * 2.8f
        kissLaserCorePaint.strokeWidth = width
        kissLaserGlowPaint.strokeWidth = width * 2.8f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            boardBounds = AimEngine.calculateBoardBounds(w.toFloat(), h.toFloat())
            strikerPos.x = boardBounds.boardCenter.x
            strikerPos.y = boardBounds.baselineY
            coinPos.x = boardBounds.boardCenter.x
            coinPos.y = boardBounds.boardCenter.y - (boardBounds.boardSize * 0.15f)
            secondaryCoinPos = PointF(
                boardBounds.boardCenter.x - (boardBounds.boardSize * 0.12f),
                boardBounds.boardCenter.y - (boardBounds.boardSize * 0.26f)
            )
            recalculateTrajectory()
        }
    }

    private fun recalculateTrajectory() {
        val otherPucks = mutableListOf<PointF>()
        secondaryCoinPos?.let { otherPucks.add(it) }
        otherPucks.addAll(detectedPucksList.filter { hypot(it.x - coinPos.x, it.y - coinPos.y) > 25f })

        currentTrajectory = AimEngine.calculateGhostBallTrajectory(
            striker = strikerPos,
            puck = coinPos,
            bounds = boardBounds,
            config = config,
            overridePocket = overridePocketPos,
            overridePocketName = overridePocketName,
            otherPucks = otherPucks
        )
    }

    fun wakeRenderingEngine() {
        isMatchModeActive = true
        isAimActive = true
        recalculateTrajectory()
        invalidate()
    }

    fun setMatchMode(active: Boolean) {
        isMatchModeActive = active
        isAimActive = active
        invalidate()
    }

    fun setLaserThickness(thickness: Float) {
        config = config.copy(strokeWidth = thickness)
    }

    fun setLaserColor(color: Int) {
        config = config.copy(laserColor = color)
        strikerGuideBorderPaint.color = color
        strikerGuideAuraPaint.color = (color and 0x00FFFFFF) or 0x44000000
    }

    fun resetIdleFade(isMenuOpen: Boolean) {
        if (isMenuOpen || isMatchModeActive) {
            isAimActive = true
            invalidate()
        }
    }

    fun setStrikerBaselineSliderRatio(ratio: Float) {
        val clampedRatio = ratio.coerceIn(0f, 1f)
        strikerPos.x = boardBounds.baselineStartX + clampedRatio * (boardBounds.baselineEndX - boardBounds.baselineStartX)
        strikerPos.y = boardBounds.baselineY
        recalculateTrajectory()
        invalidate()
    }

    fun updateLiveStrikerPosition(x: Float, y: Float) {
        strikerPos.x = x.coerceIn(boardBounds.baselineStartX, boardBounds.baselineEndX)
        strikerPos.y = boardBounds.baselineY
        recalculateTrajectory()
        invalidate()
    }

    fun updateLiveCoinPosition(x: Float, y: Float) {
        coinPos.x = x.coerceIn(boardBounds.cushionLeft + 30f, boardBounds.cushionRight - 30f)
        coinPos.y = y.coerceIn(boardBounds.cushionTop + 30f, boardBounds.baselineY - 30f)
        recalculateTrajectory()
        invalidate()
    }

    // -------------------------------------------------------------------------
    // DIRECT TOUCH-BASED INTERACTIVE CONTROL (ZERO LAG)
    // -------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isMatchModeActive) return false

        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = x
                touchDownY = y
                touchDownTime = System.currentTimeMillis()

                val distToStriker = hypot(x - strikerPos.x, y - strikerPos.y)
                val distToPuck = hypot(x - coinPos.x, y - coinPos.y)
                val distToBaselineY = abs(y - boardBounds.baselineY)
                val isNearBaselineX = x in (boardBounds.baselineStartX - 100f)..(boardBounds.baselineEndX + 100f)

                activeTouchMode = when {
                    distToStriker < 130f || (distToBaselineY < 75f && isNearBaselineX) -> TOUCH_MODE_STRIKER
                    distToPuck < 110f -> TOUCH_MODE_PUCK
                    else -> TOUCH_MODE_NONE
                }

                // If touch is not on interactive handles, pass it cleanly to the game window
                if (activeTouchMode == TOUCH_MODE_NONE) {
                    return false
                }

                isAimActive = true
                when (activeTouchMode) {
                    TOUCH_MODE_STRIKER -> {
                        strikerPos.x = x.coerceIn(boardBounds.baselineStartX, boardBounds.baselineEndX)
                        strikerPos.y = boardBounds.baselineY
                    }
                    TOUCH_MODE_PUCK -> {
                        coinPos.x = x.coerceIn(boardBounds.cushionLeft + 35f, boardBounds.cushionRight - 35f)
                        coinPos.y = y.coerceIn(boardBounds.cushionTop + 35f, boardBounds.baselineY - 35f)
                    }
                }

                recalculateTrajectory()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeTouchMode == TOUCH_MODE_NONE) return false
                isAimActive = true
                when (activeTouchMode) {
                    TOUCH_MODE_STRIKER -> {
                        strikerPos.x = x.coerceIn(boardBounds.baselineStartX, boardBounds.baselineEndX)
                        strikerPos.y = boardBounds.baselineY
                    }
                    TOUCH_MODE_PUCK -> {
                        coinPos.x = x.coerceIn(boardBounds.cushionLeft + 35f, boardBounds.cushionRight - 35f)
                        coinPos.y = y.coerceIn(boardBounds.cushionTop + 35f, boardBounds.baselineY - 35f)
                    }
                }
                recalculateTrajectory()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (activeTouchMode == TOUCH_MODE_NONE) return false
                val distMoved = hypot(x - touchDownX, y - touchDownY)
                val duration = System.currentTimeMillis() - touchDownTime
                val isTap = distMoved < 45f && duration < 600L

                // Instant Slingshot Touch Dispatch:
                // When AUTO toggle is ON and the player taps to shoot, dispatch the
                // AccessibilityService gesture immediately from current striker position.
                if (isAutoPlayActive && isTap && activeTouchMode == TOUCH_MODE_STRIKER) {
                    Log.d(TAG, "Instant Slingshot Tap triggered -> Executing AutoStrike immediately!")
                    triggerAutoStrike()
                }

                activeTouchMode = TOUCH_MODE_NONE
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                activeTouchMode = TOUCH_MODE_NONE
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // -------------------------------------------------------------------------
    // CANVAS RENDERING: Zero Lag Multi-Color Trajectories & Guides
    // -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isMatchModeActive) {
            return
        }

        if (currentTrajectory == null) {
            recalculateTrajectory()
        }

        // 0. Bottom Baseline Guide Line
        val baselineStartX = boardBounds.baselineStartX
        val baselineEndX = boardBounds.baselineEndX
        val baselineY = boardBounds.baselineY

        canvas.drawLine(baselineStartX, baselineY, baselineEndX, baselineY, baselineGlowPaint)
        canvas.drawLine(baselineStartX, baselineY, baselineEndX, baselineY, baselineLinePaint)
        canvas.drawCircle(baselineStartX, baselineY, 12f, baselineEndPaint)
        canvas.drawCircle(baselineEndX, baselineY, 12f, baselineEndPaint)

        // 1. Active Glowing Striker Ring on bottom baseline
        val strikerRadius = (boardBounds.boardSize * 0.038f).coerceIn(32f, 48f)
        canvas.drawCircle(strikerPos.x, strikerPos.y, strikerRadius + 10f, strikerGuideAuraPaint)
        canvas.drawCircle(strikerPos.x, strikerPos.y, strikerRadius, strikerGuideFillPaint)
        canvas.drawCircle(strikerPos.x, strikerPos.y, strikerRadius, strikerGuideBorderPaint)
        canvas.drawCircle(strikerPos.x, strikerPos.y, strikerRadius * 0.45f, strikerGuideBorderPaint)
        canvas.drawCircle(strikerPos.x, strikerPos.y, 4.5f, strikerGuideDotPaint)

        // Subtle Reticle Crosshairs inside Striker Ring
        canvas.drawLine(strikerPos.x - strikerRadius * 0.55f, strikerPos.y, strikerPos.x + strikerRadius * 0.55f, strikerPos.y, strikerGuideBorderPaint)
        canvas.drawLine(strikerPos.x, strikerPos.y - strikerRadius * 0.55f, strikerPos.x, strikerPos.y + strikerRadius * 0.55f, strikerGuideBorderPaint)

        // Guide text below striker
        if (isAutoPlayActive) {
            val text = "⚡ TAP TO SHOOT"
            val textWidth = autoTextPaint.measureText(text)
            canvas.drawText(text, strikerPos.x - textWidth / 2f, strikerPos.y + strikerRadius + 30f, autoTextPaint)
        } else {
            val text = "◄ DRAG STRIKER ►"
            val textWidth = guideTextPaint.measureText(text)
            canvas.drawText(text, strikerPos.x - textWidth / 2f, strikerPos.y + strikerRadius + 28f, guideTextPaint)
        }

        // 1B. Target Puck Ghost-Circle by default
        val puckRadius = (boardBounds.boardSize * 0.028f).coerceIn(24f, 36f)
        canvas.drawCircle(coinPos.x, coinPos.y, puckRadius + 8f, puckGuideAuraPaint)
        canvas.drawCircle(coinPos.x, coinPos.y, puckRadius, puckGuideFillPaint)
        canvas.drawCircle(coinPos.x, coinPos.y, puckRadius, puckGuideBorderPaint)
        canvas.drawCircle(coinPos.x, coinPos.y, 4.0f, puckGuideDotPaint)

        val traj = currentTrajectory ?: return
        val s = traj.strikerPos
        val g = traj.ghostStrikerPos

        // 2. Line 1: Striker to Ghost-Ball (Solid White Ray)
        canvas.drawLine(s.x, s.y, g.x, g.y, strikerLaserGlowPaint)
        canvas.drawLine(s.x, s.y, g.x, g.y, strikerLaserCorePaint)

        // White Ghost Puck Circle at collision coordinate G
        canvas.drawCircle(g.x, g.y, puckRadius + 7f, ghostPuckGlowPaint)
        canvas.drawCircle(g.x, g.y, puckRadius, ghostPuckFillPaint)
        canvas.drawCircle(g.x, g.y, puckRadius, ghostPuckBorderPaint)
        canvas.drawCircle(g.x, g.y, 3.5f, ghostPuckDotPaint)

        // 3. Line 2: Ghost-Ball into Pocket (Neon Yellow Ray)
        val pLine = traj.coinToPocketLine
        if (pLine.size >= 2) {
            for (i in 0 until pLine.size - 1) {
                canvas.drawLine(pLine[i].x, pLine[i].y, pLine[i + 1].x, pLine[i + 1].y, puckLaserGlowPaint)
                canvas.drawLine(pLine[i].x, pLine[i].y, pLine[i + 1].x, pLine[i + 1].y, puckLaserCorePaint)
            }
        }

        // 4. Line 3: Cushions / Secondary Rebounds (Vivid Cyan Ray)
        // a) Secondary Kiss Shot lines toward pocket
        if (traj.isKissShotActive && traj.kissShotLines.size >= 2) {
            val kLine = traj.kissShotLines
            for (i in 0 until kLine.size - 1) {
                canvas.drawLine(kLine[i].x, kLine[i].y, kLine[i + 1].x, kLine[i + 1].y, kissLaserGlowPaint)
                canvas.drawLine(kLine[i].x, kLine[i].y, kLine[i + 1].x, kLine[i + 1].y, kissLaserCorePaint)
            }
        }

        // b) Cushion Bank Rebound Lines
        if (traj.bankShotLines.size >= 2) {
            val bLine = traj.bankShotLines
            for (i in 0 until bLine.size - 1) {
                canvas.drawLine(bLine[i].x, bLine[i].y, bLine[i + 1].x, bLine[i + 1].y, kissLaserGlowPaint)
                canvas.drawLine(bLine[i].x, bLine[i].y, bLine[i + 1].x, bLine[i + 1].y, kissLaserCorePaint)
            }
            // Cushion Bounce Impact Points
            for (bouncePt in traj.cushionImpactPoints) {
                canvas.drawCircle(bouncePt.x, bouncePt.y, 11f, kissRestHaloPaint)
                canvas.drawCircle(bouncePt.x, bouncePt.y, 5.0f, kissRestDotPaint)
            }
        }

        // c) Striker Rebound / Deflection Rollout
        if (traj.strikerReboundLine.size >= 2) {
            val rLine = traj.strikerReboundLine
            for (i in 0 until rLine.size - 1) {
                canvas.drawLine(rLine[i].x, rLine[i].y, rLine[i + 1].x, rLine[i + 1].y, kissLaserGlowPaint)
                canvas.drawLine(rLine[i].x, rLine[i].y, rLine[i + 1].x, rLine[i + 1].y, kissLaserCorePaint)
            }
        }

        // 5. Stopping Point Markers (Color-coded resting dots)
        // a) Striker Stop Marker: Crisp White dot
        traj.strikerRestPoint?.let { restPt ->
            canvas.drawCircle(restPt.x, restPt.y, 10f, strikerRestHaloPaint)
            canvas.drawCircle(restPt.x, restPt.y, 4.5f, strikerRestDotPaint)
        }

        // b) Target Puck Stop Marker: Vivid Neon Yellow dot
        traj.targetPuckRestPoint?.let { restPt ->
            canvas.drawCircle(restPt.x, restPt.y, 11f, puckRestHaloPaint)
            canvas.drawCircle(restPt.x, restPt.y, 5.0f, puckRestDotPaint)
        }

        // c) Secondary Puck Stop Marker: Vivid Cyan dot
        if (traj.isKissShotActive) {
            traj.secondaryPuckRestPoint?.let { restPt ->
                canvas.drawCircle(restPt.x, restPt.y, 11f, kissRestHaloPaint)
                canvas.drawCircle(restPt.x, restPt.y, 5.0f, kissRestDotPaint)
            }
        }
    }

    /**
     * Working One-Tap Slingshot Auto-Strike:
     * - Identifies the active target ghost point.
     * - Calculates reverse slingshot vector: angle = target_angle + 180 degrees, pull distance = 160px.
     * - Uses AccessibilityService to dispatch an inverted swipe gesture:
     *   Start at Striker (x, y) -> drag backward to (pull_x, pull_y) over 100ms -> release (ACTION_UP).
     * - Smooth execution with zero delay and zero CPU throttling.
     */
    fun triggerAutoStrike(onComplete: ((Boolean) -> Unit)? = null) {
        if (currentTrajectory == null) {
            recalculateTrajectory()
        }
        val traj = currentTrajectory
        val s = traj?.strikerPos ?: strikerPos
        val g = traj?.ghostStrikerPos ?: coinPos

        if (!AutoStrikeAccessibilityService.isAccessibilitySettingsOn(context)) {
            Log.w(TAG, "Accessibility service not enabled in settings.")
            Toast.makeText(context, "⚡ Please enable Auto Strike Accessibility Service", Toast.LENGTH_SHORT).show()
            onComplete?.invoke(false)
            return
        }

        // Direct inverted slingshot gesture: 160px pull, 100ms duration, zero throttling
        AutoStrikeAccessibilityService.performReverseSlingshotStrike(
            strikerPos = s,
            ghostPoint = g,
            pullDistancePx = 160f,
            durationMs = 100L,
            onComplete = onComplete
        )
    }
}
