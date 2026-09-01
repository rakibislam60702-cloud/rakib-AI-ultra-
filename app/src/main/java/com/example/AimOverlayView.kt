package com.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Transparent interactive overlay canvas with:
 * 1. 3-Body Chain Reaction Physics (Combo/Carom Shots with multi-puck impact kinematics)
 * 2. Pocket Entry Margin & Tolerance AI (Pocket mouth opening geometry and entry margin cone)
 * 3. Smart Blocker Avoidance & Auto-Reroute (Obstacle detection & cushion bank rerouting)
 * 4. Queen + Cover Auto-Priority Visualization (Crown marker + 2-shot cover trajectory)
 * 5. Dynamic Stroke Power & Distance Gauge (Real-time power meter & pullback indicator)
 * 6. Zero-Lag Thread Optimization: Geometric vector math offloaded to Dispatchers.Default for solid 120 FPS!
 */
class AimOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var isRenderingLoopActive = false
    private var lastStrikerPos = PointF(300f, 1200f)
    private var lastCoinPos = PointF(540f, 800f)
    private var lastMotionTimestamp: Long = SystemClock.uptimeMillis()
    private var isIdleStationary: Boolean = false

    // Thread Optimization for Zero Lag (120 FPS Guarantee)
    private val calculationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var asyncMathJob: Job? = null
    private var cachedTrajectory: AimTrajectory? = null
    private var isCalculationPending = false

    var isOpponentTurn: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isAttachedToWindow && config.isEnabled && isRenderingLoopActive) {
                val now = SystemClock.uptimeMillis()
                val isMoving = hypot(strikerPos.x - lastStrikerPos.x, strikerPos.y - lastStrikerPos.y) > 1.5f ||
                        hypot(coinPos.x - lastCoinPos.x, coinPos.y - lastCoinPos.y) > 1.5f ||
                        activeTouchTarget != 0 || isAiScanning

                if (isMoving) {
                    lastMotionTimestamp = now
                    isIdleStationary = false
                    lastStrikerPos.set(strikerPos.x, strikerPos.y)
                    lastCoinPos.set(coinPos.x, coinPos.y)
                    requestAsyncTrajectoryCalculation()
                } else if (now - lastMotionTimestamp > 800L) {
                    isIdleStationary = true
                }

                if (!isIdleStationary || !config.isPerformanceSavingActive) {
                    invalidate()
                }

                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    var config = AimEngineConfig()
        set(value) {
            val prevFps = field.is120FpsEnabled
            field = value
            updatePaints()
            if (value.is120FpsEnabled != prevFps) {
                if (value.is120FpsEnabled) start120FpsLoop() else stop120FpsLoop()
            }
            wakeRenderingEngine()
            requestAsyncTrajectoryCalculation()
            invalidate()
        }

    var isAutoPlayActive: Boolean = false
        set(value) {
            field = value
            wakeRenderingEngine()
            invalidate()
        }

    var isManualAimingActive: Boolean = false
        private set

    // Dynamic Striker and Puck positions
    var strikerPos = PointF(300f, 1200f)
    var coinPos = PointF(540f, 800f)

    var isInteractiveHandlesVisible: Boolean = true
    var aiStatusText: String = "⚡ ZERO-MISS CARROM RAY ENGINE: READY"
    var isAiScanning: Boolean = false

    // Active touch target: 0 = none, 1 = striker, 2 = coin
    private var activeTouchTarget: Int = 0

    // Paints for dynamic rendering
    private val primaryLaserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val primaryGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val secondaryPuckPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val secondaryPuckGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val bankShotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val bankGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val tangentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val ghostStrikerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private val deflectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        isFakeBoldText = true
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F0060D18")
        style = Paint.Style.FILL
    }

    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // Queen + Cover Paint Styles
    private val coverLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    // 3-Body Combo Shot Paints
    private val comboRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val comboGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFD600")
        strokeWidth = 10f
        style = Paint.Style.STROKE
    }

    // Pocket Entry Margin & Tolerance Paints
    private val pocketTolerancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4000E676")
        style = Paint.Style.FILL
    }

    private val pocketToleranceBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }

    // Blocker Detection Paint
    private val blockerAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FF1744")
        style = Paint.Style.FILL
    }

    private val blockerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val obstructedLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FF1744")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    // Dynamic Power Gauge Paints
    private val powerGaugeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC102027")
        style = Paint.Style.FILL
    }

    private val powerGaugeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        updatePaints()
    }

    fun wakeRenderingEngine() {
        lastMotionTimestamp = SystemClock.uptimeMillis()
        isIdleStationary = false
        requestAsyncTrajectoryCalculation()
        invalidate()
    }

    fun start120FpsLoop() {
        if (!isRenderingLoopActive && config.is120FpsEnabled) {
            isRenderingLoopActive = true
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    fun stop120FpsLoop() {
        isRenderingLoopActive = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (config.is120FpsEnabled) {
            start120FpsLoop()
        }
        requestAsyncTrajectoryCalculation()
    }

    override fun onDetachedFromWindow() {
        stop120FpsLoop()
        asyncMathJob?.cancel()
        calculationScope.cancel()
        super.onDetachedFromWindow()
    }

    private fun requestAsyncTrajectoryCalculation() {
        val w = width.toFloat().takeIf { it > 0 } ?: 1080f
        val h = height.toFloat().takeIf { it > 0 } ?: 1920f
        val s = PointF(strikerPos.x, strikerPos.y)
        val c = PointF(coinPos.x, coinPos.y)
        val curCfg = config

        asyncMathJob?.cancel()
        asyncMathJob = calculationScope.launch {
            val trajectory = AimEngine.calculateTrajectory(
                striker = s,
                coin = c,
                boardWidth = w,
                boardHeight = h,
                config = curCfg
            )
            withContext(Dispatchers.Main) {
                cachedTrajectory = trajectory
                invalidate()
            }
        }
    }

    private fun updatePaints() {
        val thickness = AimEngine.laserThickness.takeIf { it > 0f } ?: config.strokeWidth
        val primaryColor = AimEngine.lineColor
        val glowColor = Color.argb(90, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))

        // 1. Primary Striker Aim Line: Solid White / Bright Core Ray with Neon Aura
        primaryLaserPaint.color = Color.WHITE
        primaryLaserPaint.strokeWidth = thickness
        primaryLaserPaint.pathEffect = if (config.isDotted) DashPathEffect(floatArrayOf(16f, 10f), 0f) else null

        primaryGlowPaint.color = glowColor
        primaryGlowPaint.strokeWidth = thickness * 2.8f
        primaryGlowPaint.pathEffect = null

        // 2. Target Trajectory: Solid Laser Ray
        secondaryPuckPaint.color = primaryColor
        secondaryPuckPaint.strokeWidth = thickness * 0.95f
        secondaryPuckPaint.pathEffect = if (config.isDotted) DashPathEffect(floatArrayOf(16f, 10f), 0f) else null

        secondaryPuckGlowPaint.color = glowColor
        secondaryPuckGlowPaint.strokeWidth = thickness * 2.6f
        secondaryPuckGlowPaint.pathEffect = null

        // 3. Cushion Reflection: Solid Crimson Red Wall Bounce Line
        bankShotPaint.color = Color.parseColor("#FFFF1744")
        bankShotPaint.strokeWidth = thickness * 0.90f
        bankShotPaint.pathEffect = if (config.isDotted) DashPathEffect(floatArrayOf(14f, 8f), 0f) else null

        bankGlowPaint.color = Color.parseColor("#66FF1744")
        bankGlowPaint.strokeWidth = thickness * 2.4f
        bankGlowPaint.pathEffect = null

        ghostStrikerPaint.color = primaryColor
        deflectionPaint.color = Color.parseColor("#80FFFFFF")
        deflectionPaint.strokeWidth = thickness * 0.6f
        badgeBorderPaint.color = primaryColor
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            strikerPos.set(w * 0.5f, h * 0.72f)
            coinPos.set(w * 0.5f, h * 0.45f)
            wakeRenderingEngine()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updatePaints()

        if (!config.isEnabled && visibility != View.VISIBLE) return

        // Opponent turn indicator / pause
        if (isOpponentTurn) {
            drawOpponentTurnBanner(canvas)
            return
        }

        val w = width.toFloat().takeIf { it > 0 } ?: 1080f
        val h = height.toFloat().takeIf { it > 0 } ?: 1920f

        val shouldRenderTrajectory = true

        if (!shouldRenderTrajectory) {
            // When Play/Auto switch is OFF and not manual aiming, keep screen clear of trajectory lines
            if (isInteractiveHandlesVisible) {
                // Draw subtle minimal guide ring for striker touch anchor
                val subtleGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#4D00E5FF")
                    style = Paint.Style.STROKE
                    strokeWidth = 2.0f
                    pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
                }
                canvas.drawCircle(strikerPos.x, strikerPos.y, config.strikerRadius * 1.1f, subtleGuidePaint)
                canvas.drawCircle(strikerPos.x, strikerPos.y, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#8000E5FF")
                    style = Paint.Style.FILL
                })
            }
            return
        }

        // Retrieve pre-computed high-speed async trajectory or fallback synchronously if null
        val trajectory = cachedTrajectory ?: AimEngine.calculateTrajectory(
            striker = strikerPos,
            coin = coinPos,
            boardWidth = w,
            boardHeight = h,
            config = config
        )

        val currentTime = SystemClock.uptimeMillis()
        val pulseFraction = ((currentTime % 1200L) / 1200f)
        val isRgb = config.lineStyle.isRgbChroma
        val rgbCycleOffset = ((currentTime % 2400L) / 2400f)

        // Dynamic 7-Stage RGB Chroma Color Spectrum
        val chromaColors = intArrayOf(
            Color.parseColor("#FFFF0055"),
            Color.parseColor("#FFFF9100"),
            Color.parseColor("#FFFFEE00"),
            Color.parseColor("#FF00E676"),
            Color.parseColor("#FF00E5FF"),
            Color.parseColor("#FFD500F9"),
            Color.parseColor("#FFFF0055")
        )
        val chromaGlowColors = intArrayOf(
            Color.parseColor("#66FF0055"),
            Color.parseColor("#66FF9100"),
            Color.parseColor("#66FFEE00"),
            Color.parseColor("#6600E676"),
            Color.parseColor("#6600E5FF"),
            Color.parseColor("#66D500F9"),
            Color.parseColor("#66FF0055")
        )

        val s = trajectory.strikerPos
        val g = trajectory.ghostStrikerPos
        val c = trajectory.coinPos
        val p = trajectory.targetPocket

        // =========================================================================
        // 0. SMART BLOCKER AVOIDANCE INDICATOR (Clean minimal indicator)
        // =========================================================================
        trajectory.blockedObstaclePos?.let { blocker ->
            canvas.drawCircle(blocker.x, blocker.y, config.coinRadius + 6f, blockerAuraPaint)
            canvas.drawCircle(blocker.x, blocker.y, config.coinRadius + 6f, blockerBorderPaint)
        }

        // Draw Obstructed direct line if rerouted
        trajectory.obstructedDirectLine?.let { obsLine ->
            if (obsLine.size >= 2) {
                canvas.drawLine(obsLine[0].x, obsLine[0].y, obsLine[1].x, obsLine[1].y, obstructedLinePaint)
            }
        }

        // =========================================================================
        // 1. BANK SHOT LINES (Cushion Reflection Wall-Bounce Physics)
        // =========================================================================
        val bankPoints = trajectory.bankShotLines
        if (bankPoints.size >= 2) {
            val crimsonRed = Color.parseColor("#FFFF1744")
            val crimsonGlow = Color.parseColor("#66FF1744")

            val bankGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = crimsonGlow
                strokeWidth = config.strokeWidth * 2.6f
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val bankSolid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = crimsonRed
                strokeWidth = config.strokeWidth * 0.95f
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                pathEffect = if (config.isDotted) DashPathEffect(floatArrayOf(14f, 8f), 0f) else null
            }
            val bankCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFFF8A80")
                strokeWidth = (config.strokeWidth * 0.35f).coerceAtLeast(1.8f)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }

            for (i in 0 until bankPoints.size - 1) {
                val p1 = bankPoints[i]
                val p2 = bankPoints[i + 1]

                if (isRgb) {
                    val chromaBankShader = LinearGradient(
                        p1.x, p1.y, p2.x, p2.y,
                        chromaColors,
                        null,
                        Shader.TileMode.CLAMP
                    )
                    val chromaBankGlowShader = LinearGradient(
                        p1.x, p1.y, p2.x, p2.y,
                        chromaGlowColors,
                        null,
                        Shader.TileMode.CLAMP
                    )
                    bankGlow.shader = chromaBankGlowShader
                    bankSolid.shader = chromaBankShader
                } else {
                    bankGlow.shader = null
                    bankSolid.shader = null
                }

                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, bankGlow)
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, bankSolid)
                if (!isRgb) {
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, bankCore)
                }

                // Clean Cushion Impact Node
                if (i > 0 && i < bankPoints.size) {
                    val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (isRgb) Color.parseColor("#00E5FF") else crimsonRed
                        style = Paint.Style.FILL
                    }
                    canvas.drawCircle(p1.x, p1.y, 7f, nodePaint)
                    canvas.drawCircle(p1.x, p1.y, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        style = Paint.Style.STROKE
                        strokeWidth = 2.0f
                    })
                }
            }
        }

        // =========================================================================
        // 2. 3-BODY CHAIN REACTION PHYSICS (COMBO/CAROM SHOT RENDERING)
        // =========================================================================
        if (trajectory.is3BodyCombo && trajectory.comboPuckAPos != null && trajectory.comboPuckBPos != null && trajectory.ghostPuckAPos != null) {
            val puckA = trajectory.comboPuckAPos
            val puckB = trajectory.comboPuckBPos
            val ghostA = trajectory.ghostPuckAPos
            val targetP = trajectory.targetPocket

            canvas.drawLine(puckA.x, puckA.y, ghostA.x, ghostA.y, comboGlowPaint)
            canvas.drawLine(puckA.x, puckA.y, ghostA.x, ghostA.y, comboRayPaint)

            canvas.drawCircle(ghostA.x, ghostA.y, config.coinRadius, ghostStrikerPaint)

            canvas.drawLine(puckB.x, puckB.y, targetP.x, targetP.y, secondaryPuckGlowPaint)
            canvas.drawLine(puckB.x, puckB.y, targetP.x, targetP.y, secondaryPuckPaint)

            val puckBPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFD600")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(puckB.x, puckB.y, config.coinRadius, puckBPaint)
            canvas.drawCircle(puckB.x, puckB.y, config.coinRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            })
        }

        // =========================================================================
        // 3. BACK-SLICE REBOUND SHOT RAYS
        // =========================================================================
        trajectory.backSliceRays?.let { backRays ->
            if (backRays.size >= 2) {
                val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#00E5FF")
                    strokeWidth = config.strokeWidth
                    style = Paint.Style.STROKE
                    pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
                }
                for (i in 0 until backRays.size - 1) {
                    canvas.drawLine(backRays[i].x, backRays[i].y, backRays[i + 1].x, backRays[i + 1].y, backPaint)
                }
            }
        }

        // =========================================================================
        // 4. QUEEN + COVER 2-SHOT SEQUENCE VISUALIZATION
        // =========================================================================
        trajectory.queenCoverPlan?.let { plan ->
            canvas.drawLine(plan.coverPuckPosition.x, plan.coverPuckPosition.y, plan.coverPocketPos.x, plan.coverPocketPos.y, coverLinePaint)

            val coverRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00E5FF")
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            }
            canvas.drawCircle(plan.coverPuckPosition.x, plan.coverPuckPosition.y, config.coinRadius + 6f, coverRingPaint)
        }

        // =========================================================================
        // 5. PRIMARY STRIKER TO GHOST CONTACT LINE (RGB Chroma or Solid White Core)
        // =========================================================================
        val primaryColor = Color.parseColor(config.lineStyle.primaryColorHex)
        val glowColor = Color.parseColor(config.lineStyle.glowColorHex)

        if (isRgb) {
            val chromaShader = LinearGradient(
                s.x, s.y, g.x, g.y,
                chromaColors,
                null,
                Shader.TileMode.CLAMP
            )
            val chromaGlowShader = LinearGradient(
                s.x, s.y, g.x, g.y,
                chromaGlowColors,
                null,
                Shader.TileMode.CLAMP
            )

            primaryGlowPaint.shader = chromaGlowShader
            canvas.drawLine(s.x, s.y, g.x, g.y, primaryGlowPaint)

            primaryLaserPaint.shader = chromaShader
            canvas.drawLine(s.x, s.y, g.x, g.y, primaryLaserPaint)
            primaryLaserPaint.shader = null
            primaryGlowPaint.shader = null
        } else {
            val laserShader = LinearGradient(
                s.x, s.y, g.x, g.y,
                intArrayOf(Color.WHITE, primaryColor, Color.WHITE),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            val glowShader = LinearGradient(
                s.x, s.y, g.x, g.y,
                intArrayOf(glowColor, (glowColor and 0x00FFFFFF) or 0x88000000.toInt(), glowColor),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )

            primaryGlowPaint.shader = glowShader
            canvas.drawLine(s.x, s.y, g.x, g.y, primaryGlowPaint)

            primaryLaserPaint.shader = laserShader
            canvas.drawLine(s.x, s.y, g.x, g.y, primaryLaserPaint)
            primaryLaserPaint.shader = null
            primaryGlowPaint.shader = null

            val whiteCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = (config.strokeWidth * 0.40f).coerceAtLeast(2.0f)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(s.x, s.y, g.x, g.y, whiteCorePaint)
        }

        // =========================================================================
        // 6. GHOST STRIKER TARGET OUTLINE (Radial Glow Shader)
        // =========================================================================
        val ghostRadialShader = RadialGradient(
            g.x, g.y, config.strikerRadius * 1.35f,
            intArrayOf((primaryColor and 0x00FFFFFF) or 0x66000000.toInt(), Color.TRANSPARENT),
            floatArrayOf(0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        val ghostHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = ghostRadialShader
            style = Paint.Style.FILL
        }
        canvas.drawCircle(g.x, g.y, config.strikerRadius * 1.35f, ghostHaloPaint)

        canvas.drawCircle(g.x, g.y, config.strikerRadius, ghostStrikerPaint)
        canvas.drawCircle(g.x, g.y, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isRgb) Color.WHITE else primaryColor
            style = Paint.Style.FILL
        })

        // =========================================================================
        // 7. CUT SHOT TANGENT PLANE (Clean line)
        // =========================================================================
        trajectory.tangentLine?.let { tLine ->
            if (tLine.size >= 2) {
                canvas.drawLine(tLine[0].x, tLine[0].y, tLine[1].x, tLine[1].y, tangentPaint)
            }
        }

        // =========================================================================
        // 8. KISS / CAROM SECONDARY COIN
        // =========================================================================
        trajectory.secondaryCoinPos?.let { secCoin ->
            val secCoinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#D9FF9100")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(secCoin.x, secCoin.y, config.coinRadius, secCoinPaint)
            canvas.drawCircle(secCoin.x, secCoin.y, config.coinRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            })
        }

        val kissLines = trajectory.kissShotLines
        if (kissLines.size >= 2) {
            val kissPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (trajectory.shotType == LineRenderMode.BREAK_SHOT) Color.parseColor("#FF9100") else Color.parseColor("#FFD700")
                strokeWidth = config.strokeWidth * 0.9f
                style = Paint.Style.STROKE
                if (trajectory.shotType == LineRenderMode.BREAK_SHOT) {
                    pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
                }
            }
            for (i in 0 until kissLines.size - 1) {
                canvas.drawLine(kissLines[i].x, kissLines[i].y, kissLines[i + 1].x, kissLines[i + 1].y, kissPaint)
            }
        }

        // =========================================================================
        // 9. SECONDARY PUCK TO POCKET LINE (Solid Gold / RGB Chroma)
        // =========================================================================
        if (config.isAutoPocketPredictionEnabled) {
            if (isRgb) {
                val chromaPuckShader = LinearGradient(
                    c.x, c.y, p.x, p.y,
                    chromaColors,
                    null,
                    Shader.TileMode.CLAMP
                )
                val chromaPuckGlow = LinearGradient(
                    c.x, c.y, p.x, p.y,
                    chromaGlowColors,
                    null,
                    Shader.TileMode.CLAMP
                )
                secondaryPuckGlowPaint.shader = chromaPuckGlow
                secondaryPuckPaint.shader = chromaPuckShader
            } else {
                secondaryPuckGlowPaint.shader = null
                secondaryPuckPaint.shader = null
            }

            canvas.drawLine(c.x, c.y, p.x, p.y, secondaryPuckGlowPaint)
            canvas.drawLine(c.x, c.y, p.x, p.y, secondaryPuckPaint)

            if (!isRgb) {
                val goldCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FFFFFFAA")
                    strokeWidth = (config.strokeWidth * 0.35f).coerceAtLeast(1.5f)
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(c.x, c.y, p.x, p.y, goldCorePaint)
            }

            // Pocket Entry Margin Cone (Subtle)
            if (trajectory.pocketMouthLeft != null && trajectory.pocketMouthRight != null) {
                val mouthL = trajectory.pocketMouthLeft
                val mouthR = trajectory.pocketMouthRight
                val conePath = Path().apply {
                    moveTo(c.x, c.y)
                    lineTo(mouthL.x, mouthL.y)
                    lineTo(mouthR.x, mouthR.y)
                    close()
                }
                canvas.drawPath(conePath, pocketTolerancePaint)
                canvas.drawPath(conePath, pocketToleranceBorderPaint)
            }

            // Clean glowing target lock reticle on pocket (No obstructive text box)
            val lockColor = if (isRgb) Color.parseColor("#00E5FF")
            else if (trajectory.isGuaranteedWin) Color.parseColor("#00E676")
            else Color.parseColor("#FFD700")

            val pulseRadius1 = config.pocketRadius + (pulseFraction * 24f)
            val pulseAlpha1 = ((1f - pulseFraction) * 200).toInt().coerceIn(0, 255)
            val pulsePaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (lockColor and 0x00FFFFFF) or (pulseAlpha1 shl 24)
                style = Paint.Style.STROKE
                strokeWidth = 3.0f
            }
            canvas.drawCircle(p.x, p.y, pulseRadius1, pulsePaint1)

            val mainPocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = lockColor
                style = Paint.Style.STROKE
                strokeWidth = 4.0f
            }
            canvas.drawCircle(p.x, p.y, config.pocketRadius, mainPocketPaint)

            val tickRadius = config.pocketRadius + 10f
            val angleOffset = (currentTime % 3200L) / 3200f * 360f
            for (angleStep in 0 until 4) {
                val rad = Math.toRadians((angleStep * 90.0 + angleOffset))
                val cosA = Math.cos(rad).toFloat()
                val sinA = Math.sin(rad).toFloat()
                val x1 = p.x + cosA * (config.pocketRadius - 4f)
                val y1 = p.y + sinA * (config.pocketRadius - 4f)
                val x2 = p.x + cosA * (tickRadius + 6f)
                val y2 = p.y + sinA * (tickRadius + 6f)
                canvas.drawLine(x1, y1, x2, y2, mainPocketPaint)
            }
        }

        // =========================================================================
        // 10. STRIKER POST-COLLISION DEFLECTION RAY
        // =========================================================================
        val deflectPoints = trajectory.strikerReboundLine
        if (deflectPoints.size >= 2) {
            canvas.drawLine(deflectPoints[0].x, deflectPoints[0].y, deflectPoints[1].x, deflectPoints[1].y, deflectionPaint)
        }

        // =========================================================================
        // 11. DYNAMIC STROKE POWER & DISTANCE GAUGE
        // =========================================================================
        drawDynamicPowerGauge(canvas, s, trajectory)

        // =========================================================================
        // 11.5. STRIKER BASELINE POSITION GUIDE (Horizontal Sweet-Spot Markers)
        // =========================================================================
        if (config.showBaselineGuide && trajectory.baselineSpots.isNotEmpty()) {
            val baselineTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4D00E5FF")
                strokeWidth = 3f
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
            }
            val baselineGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1A00E5FF")
                strokeWidth = 14f
                style = Paint.Style.STROKE
            }
            val bY = trajectory.baselineY
            val bStartX = trajectory.baselineStartX
            val bEndX = trajectory.baselineEndX

            // Draw horizontal baseline track
            canvas.drawLine(bStartX, bY, bEndX, bY, baselineGlowPaint)
            canvas.drawLine(bStartX, bY, bEndX, bY, baselineTrackPaint)

            // Baseline end limits
            val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00E5FF")
                strokeWidth = 4f
            }
            canvas.drawLine(bStartX, bY - 12f, bStartX, bY + 12f, limitPaint)
            canvas.drawLine(bEndX, bY - 12f, bEndX, bY + 12f, limitPaint)

            // Draw baseline probe spots
            for (spot in trajectory.baselineSpots) {
                if (spot.isOptimal) {
                    // Pulsating golden sweet spot marker
                    val optPulseRadius = 14f + (pulseFraction * 16f)
                    val optPulseAlpha = ((1f - pulseFraction) * 200).toInt().coerceIn(0, 255)
                    val optPulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = (Color.parseColor("#FFD700") and 0x00FFFFFF) or (optPulseAlpha shl 24)
                        style = Paint.Style.STROKE
                        strokeWidth = 3f
                    }
                    canvas.drawCircle(spot.position.x, bY, optPulseRadius, optPulsePaint)

                    val optSpotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#FFD700")
                        style = Paint.Style.FILL
                    }
                    canvas.drawCircle(spot.position.x, bY, 8f, optSpotPaint)

                    // Dotted projection line from optimal baseline spot to ghost
                    val projLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#80FFD700")
                        strokeWidth = 2.5f
                        style = Paint.Style.STROKE
                        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
                    }
                    canvas.drawLine(spot.position.x, bY, g.x, g.y, projLinePaint)
                } else {
                    // Regular baseline marker
                    val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#8000E5FF")
                        style = Paint.Style.FILL
                    }
                    canvas.drawCircle(spot.position.x, bY, 4f, nodePaint)
                }
            }
        }

        // =========================================================================
        // 11.8. CENTER-TARGET PRECISION VECTOR GUIDE & KINEMATICS HUD
        // =========================================================================
        if (config.isCenterTargetGuideEnabled && trajectory.centerTargetResult != null) {
            drawCenterTargetGuide(canvas, trajectory.centerTargetResult, pulseFraction)
        }

        // =========================================================================
        // 12. INTERACTIVE STRIKER & COIN HANDLES
        // =========================================================================
        if (isInteractiveHandlesVisible) {
            val strikerHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#B3FFD700")
                style = Paint.Style.FILL
            }
            val strikerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3.5f
            }
            canvas.drawCircle(s.x, s.y, config.strikerRadius, strikerHandlePaint)
            canvas.drawCircle(s.x, s.y, config.strikerRadius, strikerBorderPaint)

            canvas.drawCircle(s.x, s.y, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#060B13")
                style = Paint.Style.FILL
            })

            val coinHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (trajectory.isQueenShot) Color.parseColor("#FFFF1744") else Color.parseColor("#D9FF1744")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(c.x, c.y, config.coinRadius, coinHandlePaint)
            canvas.drawCircle(c.x, c.y, config.coinRadius, strikerBorderPaint)

            if (trajectory.isQueenShot) {
                val crownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FFD700")
                    textSize = 22f
                    isFakeBoldText = true
                }
                canvas.drawText("👑", c.x - 14f, c.y + 8f, crownPaint)
            } else {
                val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    strokeWidth = 2f
                }
                canvas.drawLine(c.x - 10f, c.y, c.x + 10f, c.y, crossPaint)
                canvas.drawLine(c.x, c.y - 10f, c.x, c.y + 10f, crossPaint)
            }
        }

        if (isRgb || !isIdleStationary) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawDynamicPowerGauge(canvas: Canvas, striker: PointF, trajectory: AimTrajectory) {
        val gaugeWidth = 140f
        val gaugeHeight = 10f
        val gaugeX = striker.x - gaugeWidth / 2f
        val gaugeY = striker.y + config.strikerRadius + 14f

        val bgRect = RectF(gaugeX, gaugeY, gaugeX + gaugeWidth, gaugeY + gaugeHeight)
        canvas.drawRoundRect(bgRect, 5f, 5f, powerGaugeBgPaint)

        val fillWidth = (gaugeWidth * (trajectory.recommendedPower / 100f)).coerceIn(4f, gaugeWidth)
        val fillRect = RectF(gaugeX, gaugeY, gaugeX + fillWidth, gaugeY + gaugeHeight)

        val powerColor = when {
            trajectory.recommendedPower >= 85 -> Color.parseColor("#FF1744")
            trajectory.recommendedPower >= 60 -> Color.parseColor("#FF9100")
            else -> Color.parseColor("#00E676")
        }
        powerGaugeFillPaint.color = powerColor
        canvas.drawRoundRect(fillRect, 5f, 5f, powerGaugeFillPaint)

        // Mini Power text
        val miniTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#ECEFF1")
            textSize = 15f
            isFakeBoldText = true
        }
        val pwrLabel = "Power: ${trajectory.recommendedPower}%"
        val pwrWidth = miniTextPaint.measureText(pwrLabel)
        canvas.drawText(pwrLabel, striker.x - pwrWidth / 2f, gaugeY + gaugeHeight + 16f, miniTextPaint)
    }

    private fun drawCenterTargetGuide(canvas: Canvas, res: CenterTargetVectorResult, pulseFraction: Float) {
        val center = res.targetCenter
        val origin = res.strikerOrigin

        // 1. Concentric Golden Precision Center Rings (Bullseye Target)
        val outerRadius = 55f
        val innerRadius = 28f
        val pulseRingRadius = innerRadius + (pulseFraction * 20f)
        val pulseAlpha = ((1f - pulseFraction) * 200).toInt().coerceIn(0, 255)

        val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (Color.parseColor("#FFD700") and 0x00FFFFFF) or (pulseAlpha shl 24)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(center.x, center.y, pulseRingRadius, pulsePaint)

        val targetOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80FFD700")
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
        }
        canvas.drawCircle(center.x, center.y, outerRadius, targetOuterPaint)

        val targetInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD700")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(center.x, center.y, innerRadius, targetInnerPaint)

        val targetDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD700")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center.x, center.y, 6f, targetDotPaint)

        // Precision Crosshairs on Center Bullseye
        val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCFFD700")
            strokeWidth = 2f
        }
        canvas.drawLine(center.x - outerRadius - 8f, center.y, center.x + outerRadius + 8f, center.y, crossPaint)
        canvas.drawLine(center.x, center.y - outerRadius - 8f, center.x, center.y + outerRadius + 8f, crossPaint)

        // 2. Trajectory Deceleration Vector Ray (Gradient from Striker to Target Center)
        val lineGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 10f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            shader = LinearGradient(
                origin.x, origin.y, center.x, center.y,
                Color.parseColor("#66FFD700"), Color.parseColor("#1AFFD700"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawLine(origin.x, origin.y, center.x, center.y, lineGlowPaint)

        val lineMainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 4f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            shader = LinearGradient(
                origin.x, origin.y, center.x, center.y,
                Color.parseColor("#FFFFD700"), Color.parseColor("#80FFD700"),
                Shader.TileMode.CLAMP
            )
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }
        canvas.drawLine(origin.x, origin.y, center.x, center.y, lineMainPaint)

        // 3. Velocity Decay Nodes along kinematic path
        if (res.trajectoryPoints.size > 2) {
            val stepSize = (res.trajectoryPoints.size / 5).coerceAtLeast(1)
            for (i in stepSize until res.trajectoryPoints.size - 1 step stepSize) {
                val pt = res.trajectoryPoints[i]
                val decayFraction = 1f - (i.toFloat() / res.trajectoryPoints.size)
                val nodeAlpha = (decayFraction * 200).toInt().coerceIn(40, 220)
                val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = (Color.parseColor("#FFD700") and 0x00FFFFFF) or (nodeAlpha shl 24)
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(pt.x, pt.y, 4f + decayFraction * 3f, nodePaint)
            }
        }

        // 4. Center-Target Physics HUD Badge
        val hudText = "🎯 CENTER TARGET • Force: ${res.recommendedPullbackPercent}% • μ: 0.082 • v₀: ${res.initialSpeedPxPerSec.toInt()}px/s"
        val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#060B13")
            textSize = 15f
            isFakeBoldText = true
        }
        val textWidth = hudPaint.measureText(hudText)
        val badgeX = center.x - textWidth / 2f - 14f
        val badgeY = center.y + outerRadius + 16f
        val badgeRect = RectF(badgeX, badgeY, badgeX + textWidth + 28f, badgeY + 28f)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6FFD700")
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(badgeRect, 8f, 8f, bgPaint)
        canvas.drawRoundRect(badgeRect, 8f, 8f, borderPaint)
        canvas.drawText(hudText, badgeX + 14f, badgeY + 19f, hudPaint)
    }

    private fun drawOpponentTurnBanner(canvas: Canvas) {
        val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC102027")
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFB300")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val bannerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD54F")
            textSize = 22f
            isFakeBoldText = true
        }
        val text = "⏸️ Opponent Turn Active • Standby / Battery Saver (0% CPU)"
        val textW = bannerTextPaint.measureText(text)
        val rect = RectF(width / 2f - textW / 2f - 24f, 160f, width / 2f + textW / 2f + 24f, 220f)
        canvas.drawRoundRect(rect, 16f, 16f, bannerPaint)
        canvas.drawRoundRect(rect, 16f, 16f, borderPaint)
        canvas.drawText(text, width / 2f - textW / 2f, 200f, bannerTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!config.isEnabled) {
            return false
        }

        val touchX = event.x
        val touchY = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wakeRenderingEngine()
                val distToStriker = hypot(touchX - strikerPos.x, touchY - strikerPos.y)
                val distToCoin = hypot(touchX - coinPos.x, touchY - coinPos.y)

                if (distToStriker < config.strikerRadius * 2.8f) {
                    activeTouchTarget = 1
                    isManualAimingActive = true
                    requestAsyncTrajectoryCalculation()
                    invalidate()
                    return true
                } else if (distToCoin < config.coinRadius * 3.2f) {
                    activeTouchTarget = 2
                    isManualAimingActive = true
                    requestAsyncTrajectoryCalculation()
                    invalidate()
                    return true
                }
                
                // Striker baseline area grab
                val baselineY = height * 0.72f
                if (Math.abs(touchY - baselineY) < 160f) {
                    activeTouchTarget = 1
                    strikerPos.set(touchX, baselineY)
                    isManualAimingActive = true
                    requestAsyncTrajectoryCalculation()
                    invalidate()
                    return true
                }

                activeTouchTarget = 0
                isManualAimingActive = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                wakeRenderingEngine()
                if (activeTouchTarget == 1) {
                    isManualAimingActive = true
                    strikerPos.set(touchX, touchY)
                    requestAsyncTrajectoryCalculation()
                    invalidate()
                    return true
                } else if (activeTouchTarget == 2) {
                    isManualAimingActive = true
                    coinPos.set(touchX, touchY)
                    requestAsyncTrajectoryCalculation()
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeTouchTarget = 0
                isManualAimingActive = false
                wakeRenderingEngine()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun applyAiDetectionResult(result: AiAimDetectionResult) {
        val w = width.toFloat().takeIf { it > 0 } ?: 1080f
        val h = height.toFloat().takeIf { it > 0 } ?: 1920f

        strikerPos.set(result.strikerXPercent * w, result.strikerYPercent * h)
        coinPos.set(result.targetCoinXPercent * w, result.targetCoinYPercent * h)
        aiStatusText = "AI: ${result.targetPocket} (${result.shotAngleDegrees.toInt()}°) Pwr:${result.recommendedPowerPercent}%"
        wakeRenderingEngine()
    }

    fun resetPositions() {
        val w = width.toFloat().takeIf { it > 0 } ?: 1080f
        val h = height.toFloat().takeIf { it > 0 } ?: 1920f
        strikerPos.set(w * 0.5f, h * 0.72f)
        coinPos.set(w * 0.5f, h * 0.45f)
        aiStatusText = "⚡ ZERO-MISS RAY ENGINE: CALIBRATED"
        wakeRenderingEngine()
    }
}
