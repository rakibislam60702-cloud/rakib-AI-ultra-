package com.example

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * Pure, deterministic 2D Ghost-Ball Aim Overlay View with Autonomous Slingshot Auto-Strike Injection.
 *
 * Clean Dynamic Rendering:
 * - When idle (no touch or turn active), draws NOTHING on screen (zero clutter, 100% transparent).
 * - When an aim interaction occurs, draws two ultra-thin glowing laser lines:
 *     Line 1 (Striker Line): From S to G (Cyan Laser with small white impact circle at G).
 *     Line 2 (Puck Line): From P directly into pocket K (Yellow/Green Laser).
 * - Lines stop drawing immediately at pocket center K and never spill over screen edges.
 *
 * Autonomous Auto-Strike System:
 * 1. Autonomous Turn & Stability Trigger:
 *    - When AutoPlay switch is ON, monitors detected striker position (Xs, Ys).
 *    - Once striker position remains stable for >= 250ms (confirming board is completely static
 *      and it is player's turn), triggers the auto-strike sequence.
 * 2. Inverted Slingshot Math:
 *    - Read locked Ghost-Ball trajectory angle (Theta).
 *    - Slingshot pull angle is strictly inverted: PullAngle = Theta + 180 degrees.
 *    - Calculate pull distance based on target puck distance:
 *        Short pot (< 250px): Pull back 45-60px (Soft touch).
 *        Long bank/rebound (> 500px): Pull back 110-135px (Full power).
 *    - Compute Drag End Point:
 *        EndX = Xs + (pullDistance * cos(PullAngle))
 *        EndY = Ys + (pullDistance * sin(PullAngle))
 * 3. Humanized Gesture Dispatch:
 *    - Dispatches using Path() with a subtle quadratic Bezier curve over a duration of 120-160ms.
 *    - Releases touch cleanly at (EndX, EndY).
 * 4. Cooldown Lock:
 *    - Locks Auto-Strike for 2.5 seconds to allow pucks to finish rolling before looking for next turn.
 */
class AimOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "AimOverlayView"
        private const val STABILITY_THRESHOLD_MS = 250L // Striker must be stable for >= 250ms
        private const val STABILITY_TOLERANCE_PX = 4.0f // Position movement tolerance to count as static
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

    // Idle & Interaction State Management
    var isMatchModeActive: Boolean = true
    var isPlayerTurn: Boolean = false
    var isAimActive: Boolean = false
        private set

    var isAutoPlayActive: Boolean = false
    var isFastMode: Boolean = true

    private var overridePocketPos: PointF? = null
    private var overridePocketName: String? = null

    // Autonomous Turn & Stability Tracker
    private var lastObservedStrikerX: Float = -1f
    private var lastObservedStrikerY: Float = -1f
    private var strikerStableStartTime: Long = 0L
    private var hasFiredForCurrentTurn: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleTimeoutRunnable = Runnable {
        isAimActive = false
        invalidate() // Screen becomes completely blank/empty when idle
    }

    // Touch dragging: 0 = none, 1 = striker, 2 = puck, 3 = general aim
    private var activeTouchMode = 0

    // Multi-Ray Trajectory & Resting Point Visualizer Paints:
    // Line 1: Striker Vector (Crisp White glowing solid ray)
    private val strikerLaserCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE // Crisp White
        strokeWidth = 2.8f
    }

    private val strikerLaserGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#55FFFFFF") // Translucent Crisp White Glow
        strokeWidth = 7.5f
    }

    // Line 2: Target Puck Trajectory (Vivid Neon Yellow ray)
    private val puckLaserCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FFEA00") // Vivid Neon Yellow
        strokeWidth = 2.8f
    }

    private val puckLaserGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#55FFEA00") // Translucent Neon Yellow Glow
        strokeWidth = 7.5f
    }

    // Line 3: Secondary / Kiss Shot (Cyan ray toward pocket)
    private val kissLaserCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00E5FF") // Vivid Cyan
        strokeWidth = 2.8f
    }

    private val kissLaserGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#5500E5FF") // Translucent Cyan Glow
        strokeWidth = 7.5f
    }

    // Impact Ghost-Point (G) with white impact ring & core dot
    private val ghostImpactCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.0f
    }

    private val ghostImpactGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#55FFFFFF")
        strokeWidth = 5.5f
    }

    private val ghostImpactDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    // Stop/Resting Point Markers (Distinct colored dots where kinetic energy drops to 0):
    // Striker Stop Marker: Crisp White
    private val strikerRestDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val strikerRestHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#66FFFFFF")
        strokeWidth = 2.5f
    }

    // Target Puck Stop Marker: Vivid Neon Yellow
    private val puckRestDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFEA00")
    }
    private val puckRestHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#66FFEA00")
        strokeWidth = 2.5f
    }

    // Secondary / Kiss Puck Stop Marker: Vivid Cyan
    private val kissRestDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }
    private val kissRestHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#6600E5FF")
        strokeWidth = 2.5f
    }

    // Active vision detected pucks & secondary puck representation
    var secondaryCoinPos: PointF? = null
    private var detectedPucksList: List<PointF> = emptyList()

    init {
        updatePaints()
    }

    private fun updatePaints() {
        val width = config.strokeWidth.coerceIn(1.5f, 6.0f)
        strikerLaserCorePaint.strokeWidth = width
        strikerLaserGlowPaint.strokeWidth = width * 2.8f
        puckLaserCorePaint.strokeWidth = width
        puckLaserGlowPaint.strokeWidth = width * 2.8f
        kissLaserCorePaint.strokeWidth = width
        kissLaserGlowPaint.strokeWidth = width * 2.8f

        strikerLaserCorePaint.color = Color.WHITE
        puckLaserCorePaint.color = Color.parseColor("#FFEA00")
        kissLaserCorePaint.color = Color.parseColor("#00E5FF")
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

    /**
     * Called whenever new vision detection results arrive from the frame scanner:
     * - Evaluates stability of striker position for autonomous auto-strike triggering.
     * - Once stable for >= 250ms, executes the shot.
     */
    fun updateVisionDetection(
        isTurn: Boolean,
        striker: PointF?,
        puck: PointF?,
        pocket: PointF? = null,
        pocketName: String? = null,
        allPucks: List<PointF> = emptyList()
    ) {
        isPlayerTurn = isTurn
        if (!isTurn || striker == null || puck == null) {
            // Immediately hide overlay lines when not player turn or balls rolling
            isAimActive = false
            mainHandler.removeCallbacks(idleTimeoutRunnable)
            strikerStableStartTime = 0L
            hasFiredForCurrentTurn = false
            invalidate()
            return
        }

        detectedPucksList = allPucks

        // Live player turn detected!
        val newStrikerX = striker.x.coerceIn(boardBounds.cushionLeft, boardBounds.cushionRight)
        val newStrikerY = striker.y.coerceIn(boardBounds.cushionTop, boardBounds.cushionBottom)
        val newCoinX = puck.x.coerceIn(boardBounds.cushionLeft, boardBounds.cushionRight)
        val newCoinY = puck.y.coerceIn(boardBounds.cushionTop, boardBounds.cushionBottom)

        // 1. Autonomous Turn & Stability Tracking:
        // Monitor detected striker position (Xs, Ys).
        val now = System.currentTimeMillis()
        val dX = abs(newStrikerX - lastObservedStrikerX)
        val dY = abs(newStrikerY - lastObservedStrikerY)

        if (dX <= STABILITY_TOLERANCE_PX && dY <= STABILITY_TOLERANCE_PX) {
            // Striker is resting in the same position
            if (strikerStableStartTime == 0L) {
                strikerStableStartTime = now
            }
        } else {
            // Striker moved (e.g. user repositioned baseline or board settling)
            lastObservedStrikerX = newStrikerX
            lastObservedStrikerY = newStrikerY
            strikerStableStartTime = now
            hasFiredForCurrentTurn = false
        }

        strikerPos.x = newStrikerX
        strikerPos.y = newStrikerY
        coinPos.x = newCoinX
        coinPos.y = newCoinY

        overridePocketPos = pocket
        overridePocketName = pocketName

        recalculateTrajectory()
        activateAim(3000L)

        // Check if AutoPlay switch is ON, striker stable for >= 250ms, and cooldown expired
        if (isAutoPlayActive &&
            !hasFiredForCurrentTurn &&
            strikerStableStartTime > 0L &&
            (now - strikerStableStartTime) >= STABILITY_THRESHOLD_MS &&
            !AutoStrikeAccessibilityService.isShotCooldownActive()
        ) {
            hasFiredForCurrentTurn = true
            Log.d(TAG, "Autonomous Auto-Strike stability triggered (Stable: ${now - strikerStableStartTime}ms) -> Dispatching shot!")
            triggerAutoStrike()
        }
    }

    private fun activateAim(durationMs: Long = 3000L) {
        isAimActive = true
        mainHandler.removeCallbacks(idleTimeoutRunnable)
        mainHandler.postDelayed(idleTimeoutRunnable, durationMs)
        invalidate()
    }

    fun wakeRenderingEngine() {
        activateAim(3500L)
    }

    fun setMatchMode(active: Boolean) {
        isMatchModeActive = active
        if (!active) {
            isAimActive = false
            mainHandler.removeCallbacks(idleTimeoutRunnable)
            invalidate()
        } else {
            activateAim(3000L)
        }
    }

    fun setStrikerBaselineSliderRatio(ratio: Float) {
        val clampedRatio = ratio.coerceIn(0f, 1f)
        strikerPos.x = boardBounds.baselineStartX + clampedRatio * (boardBounds.baselineEndX - boardBounds.baselineStartX)
        strikerPos.y = boardBounds.baselineY
        recalculateTrajectory()
        activateAim(3500L)
    }

    fun updateLiveStrikerPosition(x: Float, y: Float) {
        strikerPos.x = x.coerceIn(boardBounds.cushionLeft, boardBounds.cushionRight)
        strikerPos.y = y.coerceIn(boardBounds.cushionTop, boardBounds.cushionBottom)
        recalculateTrajectory()
        activateAim(2500L)
    }

    fun updateLiveCoinPosition(x: Float, y: Float) {
        coinPos.x = x.coerceIn(boardBounds.cushionLeft, boardBounds.cushionRight)
        coinPos.y = y.coerceIn(boardBounds.cushionTop, boardBounds.cushionBottom)
        recalculateTrajectory()
        activateAim(2500L)
    }

    fun setLaserThickness(thickness: Float) {
        config = config.copy(strokeWidth = thickness)
    }

    fun setLaserColor(color: Int) {
        config = config.copy(laserColor = color)
    }

    fun resetIdleFade(isMenuOpen: Boolean) {
        if (isMenuOpen || isMatchModeActive) {
            activateAim(4000L)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isMatchModeActive) return false

        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activateAim(4000L)
                val distToStriker = hypot(x - strikerPos.x, y - strikerPos.y)
                val distToCoin = hypot(x - coinPos.x, y - coinPos.y)

                activeTouchMode = when {
                    distToStriker < 75f -> 1
                    distToCoin < 75f -> 2
                    else -> 3
                }

                if (activeTouchMode == 3) {
                    coinPos.x = x.coerceIn(boardBounds.cushionLeft, boardBounds.cushionRight)
                    coinPos.y = y.coerceIn(boardBounds.cushionTop, boardBounds.cushionBottom)
                    recalculateTrajectory()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                activateAim(4000L)
                when (activeTouchMode) {
                    1 -> {
                        strikerPos.x = x.coerceIn(boardBounds.cushionLeft, boardBounds.cushionRight)
                        strikerPos.y = y.coerceIn(boardBounds.cushionTop, boardBounds.cushionBottom)
                    }
                    2, 3 -> {
                        coinPos.x = x.coerceIn(boardBounds.cushionLeft, boardBounds.cushionRight)
                        coinPos.y = y.coerceIn(boardBounds.cushionTop, boardBounds.cushionBottom)
                    }
                }
                recalculateTrajectory()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeTouchMode = 0
                activateAim(2500L)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Multi-Color Trajectory & Resting Point Visualization:
     * - Striker Vector (Line 1): Crisp White glowing solid ray connecting Striker (Xs, Ys) to impact Ghost-Point (G).
     * - Target Puck Trajectory (Line 2): Vivid Neon Yellow ray directing from active puck into target corner pocket.
     * - Secondary / Kiss Shot (Line 3): If another puck obstructs line, render Cyan ray toward pocket.
     * - Stop/Resting Point Markers: Render distinct colored dots at the precise points where striker and pucks lose kinetic energy.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Idle state: draw NOTHING
        if (!isAimActive || !isMatchModeActive) {
            return
        }

        val traj = currentTrajectory ?: return
        val s = traj.strikerPos
        val g = traj.ghostStrikerPos
        val p = traj.coinPos
        val k = traj.targetPocket

        // 2. Line 1 (Striker Vector): Crisp White glowing solid ray connecting Striker (Xs, Ys) to Ghost-Point (G)
        canvas.drawLine(s.x, s.y, g.x, g.y, strikerLaserGlowPaint)
        canvas.drawLine(s.x, s.y, g.x, g.y, strikerLaserCorePaint)

        // Small white impact circle at Ghost-Ball impact point G
        val impactRadius = (config.coinRadius * 0.7f).coerceIn(12f, 18f)
        canvas.drawCircle(g.x, g.y, impactRadius, ghostImpactGlowPaint)
        canvas.drawCircle(g.x, g.y, impactRadius, ghostImpactCirclePaint)
        canvas.drawCircle(g.x, g.y, 2.5f, ghostImpactDotPaint)

        // Subtle Striker Deceleration / Tangent Ray rollout towards resting point
        traj.strikerRestPoint?.let { restPt ->
            if (hypot(restPt.x - g.x, restPt.y - g.y) > 8f) {
                canvas.drawLine(g.x, g.y, restPt.x, restPt.y, strikerLaserGlowPaint)
                canvas.drawLine(g.x, g.y, restPt.x, restPt.y, strikerLaserCorePaint)
            }
        }

        // 3. Line 2 (Target Puck Trajectory): Vivid Neon Yellow ray directing from active puck into target corner pocket
        val pLine = traj.coinToPocketLine
        if (pLine.size >= 2) {
            canvas.drawLine(pLine[0].x, pLine[0].y, pLine[1].x, pLine[1].y, puckLaserGlowPaint)
            canvas.drawLine(pLine[0].x, pLine[0].y, pLine[1].x, pLine[1].y, puckLaserCorePaint)
        }

        // 4. Line 3 (Secondary / Kiss Shot): Cyan ray directing secondary puck toward pocket
        if (traj.isKissShotActive && traj.kissShotLines.size >= 2) {
            val kLine = traj.kissShotLines
            canvas.drawLine(kLine[0].x, kLine[0].y, kLine[1].x, kLine[1].y, kissLaserGlowPaint)
            canvas.drawLine(kLine[0].x, kLine[0].y, kLine[1].x, kLine[1].y, kissLaserCorePaint)
        }

        // 5. Stop/Resting Point Markers: Distinct colored dots at the precise points where kinetic energy drops to 0
        // a) Striker Stop Marker: Crisp White dot
        traj.strikerRestPoint?.let { restPt ->
            canvas.drawCircle(restPt.x, restPt.y, 9.5f, strikerRestHaloPaint)
            canvas.drawCircle(restPt.x, restPt.y, 4.5f, strikerRestDotPaint)
        }

        // b) Target Puck Stop Marker: Vivid Neon Yellow dot
        traj.targetPuckRestPoint?.let { restPt ->
            canvas.drawCircle(restPt.x, restPt.y, 10.5f, puckRestHaloPaint)
            canvas.drawCircle(restPt.x, restPt.y, 5.0f, puckRestDotPaint)
        }

        // c) Secondary / Kiss Puck Stop Marker: Vivid Cyan dot
        if (traj.isKissShotActive) {
            traj.secondaryPuckRestPoint?.let { restPt ->
                canvas.drawCircle(restPt.x, restPt.y, 10.5f, kissRestHaloPaint)
                canvas.drawCircle(restPt.x, restPt.y, 5.0f, kissRestDotPaint)
            }
        }
    }

    /**
     * Executes the calculated strike via Accessibility Service touch injection:
     * 1. Inverted Slingshot Math:
     *      Reads locked Ghost-Ball trajectory angle Theta.
     *      Slingshot PullAngle = Theta + 180 degrees.
     * 2. Humanized Gesture Dispatch:
     *      Subtle quadratic Bezier curve over 120-160ms (or 80ms Fast Mode).
     * 3. Cooldown Lock:
     *      2.5 seconds lock ensures pucks finish rolling.
     */
    fun triggerAutoStrike(onComplete: ((Boolean) -> Unit)? = null) {
        val traj = currentTrajectory
        if (traj == null) {
            onComplete?.invoke(false)
            return
        }

        if (!AutoStrikeAccessibilityService.isAccessibilitySettingsOn(context)) {
            onComplete?.invoke(false)
            return
        }

        if (AutoStrikeAccessibilityService.isShotCooldownActive()) {
            Log.d(TAG, "Auto-Strike in cooldown lock. Skipping trigger.")
            onComplete?.invoke(false)
            return
        }

        val s = traj.strikerPos
        val g = traj.ghostStrikerPos
        val p = traj.coinPos

        val params = AimEngine.calculateSlingshotShotParameters(
            strikerPos = s,
            ghostPoint = g,
            targetPuckPos = p
        )

        val duration = if (isFastMode) 80L else 140L

        AutoStrikeAccessibilityService.performSlingshotAutoStrike(
            strikerPos = s,
            shotAngleDeg = params.forwardThetaDeg,
            targetPuckDist = params.targetPuckDistPx,
            durationMs = duration,
            isFastMode = isFastMode,
            onComplete = { success ->
                if (success) {
                    activateAim(1500L)
                }
                onComplete?.invoke(success)
            }
        )
    }
}
