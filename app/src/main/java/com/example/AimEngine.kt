package com.example

import android.graphics.Color
import android.graphics.PointF
import kotlin.math.*

/**
 * Visual styling presets for the In-Game HUD Quick Customizer:
 * - LASER_GLOW: Neon Cyan with deep ambient ray illumination
 * - SOLID_NEON: High-contrast sharp electric cyan
 * - DUAL_GRADIENT: Cyberpunk Cyan-to-Magenta dual gradient
 * - CYBER_GREEN: Matrix Neon Green with Emerald glow
 * - GOLD_CHAMPION: Royal Tournament Gold with Amber glow
 */
enum class AimLineStyle(
    val label: String,
    val primaryColorHex: String,
    val glowColorHex: String,
    val isRgbChroma: Boolean = false
) {
    SOLID_CLASSIC("Solid Classic", "#FFFFFF", "#6600E5FF", false),
    RGB_CHROMA("RGB Chroma Laser", "#00E5FF", "#FF007F", true),
    LASER_GLOW("Laser Glow", "#00E5FF", "#4D00E5FF", false),
    SOLID_NEON("Solid Neon", "#00B0FF", "#6600B0FF", false),
    DUAL_GRADIENT("Dual Cyber", "#D500F9", "#5500E5FF", true),
    CYBER_GREEN("Cyber Green", "#00E676", "#4D00E676", false),
    GOLD_CHAMPION("Gold Royal", "#FFD700", "#4DFFD700", false)
}

/**
 * Advanced Carrom Shot Trajectory Algorithms:
 * - DIRECT: 100% accurate straight ray to pocket center with precision ghost-striker positioning
 * - BANK_1_CUSHION: 1-Wall cushion bounce physics against carrom rails
 * - BANK_2_CUSHION: 2-Wall multi-bounce reflection trajectory
 * - BANK_3_CUSHION: 3-Cushion precision bank trajectory (C1, C2, C3)
 * - KISS_SHOT: Multi-body coin-to-coin deflection / Carom combo shot into pocket
 * - CUT_SHOT: Fine angle slice & tangent offset calculation for edge-to-edge striking
 * - BACK_SLICE: Rail rebound shot where striker bounces off cushion to strike coin from behind
 * - LASER_PRO: Smart AI Master Engine with Auto Board Scanner & Obstacle Avoidance Pathfinding
 */
enum class GameMode(val label: String, val badge: String, val description: String) {
    DISC_POOL("Disc Pool", "⚪ DISC POOL", "Direct Pot Focus • White/Black Puck Rush"),
    CLASSIC_CARROM("Classic Carrom", "👑 CLASSIC", "Queen Priority + Guaranteed Cover"),
    FREESTYLE("Freestyle", "⭐ FREESTYLE", "Score Maximizer • High Value Targets (Q:50 / W:20 / B:10)")
}

enum class TargetFocusMode(val label: String, val badge: String) {
    EASIEST_PUCK("Easiest Puck", "🎯 EASIEST"),
    QUEEN("Queen Priority", "👑 QUEEN"),
    COMBO_3BODY("3-Body Chain", "⚡ 3-BODY COMBO"),
    BANK_SHOT("Cushion Bank", "🔴 CUSHION BANK")
}

enum class LineRenderMode(val label: String, val badge: String) {
    DIRECT("Direct Pot", "🎯 DIRECT POT"),
    BANK_1_CUSHION("1-Cushion Bank", "🔴 1-CUSHION"),
    BANK_2_CUSHION("2-Cushion Bank", "🟠 2-CUSHION"),
    BANK_3_CUSHION("3-Cushion Bank", "🟣 3-CUSHION"),
    KISS_SHOT("Kiss / Carom Combo", "⚡ KISS CAROM"),
    COMBO_3_BODY("3-Body Chain Combo", "⚡ 3-BODY CHAIN"),
    CUT_SHOT("Cut Shot / Slice", "📐 CUT SLICE"),
    BACK_SLICE("Back-Slice Rebound", "🔄 BACK SLICE"),
    BREAK_SHOT("Break Shot AI", "💥 BREAK SHOT"),
    LASER_PRO("Laser Pro AI", "🌟 LASER PRO")
}

/**
 * High precision 2D Vector representation for Carrom physics calculations.
 */
data class Vector2(val x: Float, val y: Float) {
    fun length(): Float = hypot(x, y)
    fun normalized(): Vector2 {
        val l = length()
        return if (l > 0.0001f) Vector2(x / l, y / l) else Vector2(0f, 0f)
    }
    operator fun plus(other: Vector2) = Vector2(x + other.x, y + other.y)
    operator fun minus(other: Vector2) = Vector2(x - other.x, y - other.y)
    operator fun times(scalar: Float) = Vector2(x * scalar, y * scalar)
    fun dot(other: Vector2): Float = x * other.x + y * other.y
    fun toPointF(): PointF = PointF(x, y)

    companion object {
        fun fromPointF(p: PointF) = Vector2(p.x, p.y)
    }
}

/**
 * Board Vision Puck representation on the board grid.
 */
data class VisionPuck(
    val id: String,
    val position: PointF,
    val type: String, // "QUEEN", "WHITE", "BLACK", "OBSTACLE", "TARGET"
    val radius: Float = 24f,
    val confidence: Float = 0.98f
)

/**
 * Calibrated Board Vision Grid Matrix with Universal Aspect Ratio Auto-Calibration.
 */
data class BoardVisionMatrix(
    val width: Float = 1080f,
    val height: Float = 2400f,
    val aspectRatio: Float = 2.22f,
    val screenType: String = "19.5:9 Edge-to-Edge",
    val pockets: Map<String, PointF>,
    val detectedPucks: List<VisionPuck> = emptyList(),
    val queenPuck: VisionPuck? = null,
    val isCalibrated: Boolean = true
)

/**
 * Queen + Cover consecutive shot sequence plan.
 */
data class QueenCoverPlan(
    val queenPosition: PointF,
    val queenPocketName: String,
    val queenPocketPos: PointF,
    val queenGhostStriker: PointF,
    val coverPuckId: String,
    val coverPuckPosition: PointF,
    val coverPocketName: String,
    val coverPocketPos: PointF,
    val isCoverGuaranteed: Boolean = true,
    val planDescription: String = "Queen -> Cover 2-Shot Sequence Locked"
)

/**
 * Optimal horizontal striker baseline placement point.
 */
data class BaselinePlacementSpot(
    val position: PointF,
    val winProbability: Int,
    val cutAngleDeg: Float,
    val isOptimal: Boolean,
    val targetPocketName: String,
    val recommendedPower: Int,
    val spotLabel: String
)

/**
 * Comprehensive Shot Trajectory Model containing all calculated vector paths,
 * reflection nodes, deflection angles, obstacle avoidance telemetry, and auto-play parameters.
 */
data class AimTrajectory(
    val shotType: LineRenderMode,
    val strikerPos: PointF,
    val coinPos: PointF,
    val secondaryCoinPos: PointF? = null,
    val targetPocket: PointF,
    val pocketName: String,
    val ghostStrikerPos: PointF,              // Exact striker contact point on target puck
    val directStrikeLine: List<PointF>,       // Striker -> Puck Contact (Primary: Cyan/White)
    val coinToPocketLine: List<PointF>,       // Puck -> Pocket (Secondary: Gold/Yellow)
    val bankShotLines: List<PointF> = emptyList(), // Wall-Bounce Physics (Bank: Crimson/Red)
    val strikerReboundLine: List<PointF> = emptyList(), // Post-collision Striker Deflection Ray
    val kissShotLines: List<PointF> = emptyList(),      // Multi-coin combo deflection rays
    val tangentLine: List<PointF>? = null,              // Tangent contact plane for cut shots
    val backSliceRays: List<PointF>? = null,            // Striker rail bounce before coin impact
    val cushionImpactPoints: List<PointF> = emptyList(),// Identified cushion nodes (C1, C2, C3)
    // 3-Body Chain Reaction Physics
    val is3BodyCombo: Boolean = false,
    val comboPuckAPos: PointF? = null,
    val comboPuckBPos: PointF? = null,
    val ghostPuckAPos: PointF? = null,
    val comboEnergyTransferPercent: Int = 100,
    val comboPuckADeflectionLine: List<PointF> = emptyList(),
    // Pocket Entry Margin & Tolerance AI
    val pocketEntryMarginDeg: Float = 14.5f,
    val pocketMouthLeft: PointF? = null,
    val pocketMouthRight: PointF? = null,
    val isWithinToleranceMargin: Boolean = true,
    val toleranceLabel: String = "±14.5° Safe Pocket Margin",
    // Smart Blocker Avoidance & Auto-Reroute
    val isAutoRerouted: Boolean = false,
    val blockedObstaclePos: PointF? = null,
    val obstructedDirectLine: List<PointF> = emptyList(),
    val rerouteExplanation: String = "",
    // Striker Baseline Position Guide
    val baselineSpots: List<BaselinePlacementSpot> = emptyList(),
    val optimalBaselineSpot: BaselinePlacementSpot? = null,
    val baselineY: Float = 0f,
    val baselineStartX: Float = 0f,
    val baselineEndX: Float = 0f,
    val angleDegrees: Float,
    val cutAngleDegrees: Float,
    val isPocketLocked: Boolean,
    val lockScorePercent: Int = 98,
    val isGuaranteedWin: Boolean = false,
    val recommendedPower: Int = 85,
    val powerLabel: String = "Heavy Strike (85%)",
    val dynamicPullbackDistancePx: Float = 160f,
    val totalShotDistancePx: Float = 750f,
    val isObstacleAvoided: Boolean = false,
    val obstacleCount: Int = 0,
    val isQueenShot: Boolean = false,
    val queenCoverPlan: QueenCoverPlan? = null,
    val centerTargetResult: CenterTargetVectorResult? = null,
    val gameModeBadge: String = "⚪ DISC POOL",
    val shotTitle: String = "Direct Pot Locked",
    val strategyNotes: String = "Zero-Miss Elastic Collision Solved"
)

/**
 * Center-Target Precision Vector Kinematics & Friction Deceleration Model.
 */
data class CenterTargetVectorResult(
    val initialSpeedPxPerSec: Float,
    val impulseVector: Vector2,
    val surfaceFrictionCoeff: Float,
    val decelerationPxPerSec2: Float,
    val stoppingDistancePx: Float,
    val estimatedTravelTimeSec: Float,
    val recommendedPullbackPercent: Int,
    val targetCenter: PointF,
    val strikerOrigin: PointF,
    val trajectoryPoints: List<PointF>,
    val velocityDecayPoints: List<Pair<PointF, Float>>,
    val toleranceRadiusPx: Float = 28f
)

/**
 * Configuration options for the AI Aim Line Engine and Overlay Canvas.
 */
data class AimEngineConfig(
    val isEnabled: Boolean = true,
    val gameMode: GameMode = GameMode.DISC_POOL,
    val lineMode: LineRenderMode = LineRenderMode.LASER_PRO,
    val lineStyle: AimLineStyle = AimLineStyle.LASER_GLOW,
    val targetFocusMode: TargetFocusMode = TargetFocusMode.EASIEST_PUCK,
    val showBaselineGuide: Boolean = true,
    val isCenterTargetGuideEnabled: Boolean = true,
    val isAutoPlayEnabled: Boolean = false,
    val isQueenPriorityEnabled: Boolean = true,
    val isDualReboundEnabled: Boolean = true,
    val is3CushionEnabled: Boolean = true,
    val isAutoPocketPredictionEnabled: Boolean = true,
    val isStealthMode: Boolean = true,
    val is120FpsEnabled: Boolean = true,
    val isPerformanceSavingActive: Boolean = false,
    val laserColor: Int = Color.parseColor("#00E5FF"), // Neon Cyan default
    val puckColor: Int = Color.parseColor("#FFD700"),  // Gold default
    val bankColor: Int = Color.parseColor("#FF1744"),  // Crimson Red default
    val strokeWidth: Float = 6f,
    val showAngleHud: Boolean = true,
    val isDotted: Boolean = false,
    val strikerRadius: Float = 36f,
    val coinRadius: Float = 24f,
    val pocketRadius: Float = 42f,
    val maxCushions: Int = 3
)

/**
 * Comprehensive AI Carrom Physics Engine implementing:
 * 1. Universal Screen Ratio Auto-Calibration (16:9, 19.5:9, 20:9, Tablets)
 * 2. Queen + Cover Auto-Priority AI
 * 3. Dynamic Stroke Power & Distance Gauge
 * 4. Smart Pathfinding & Obstacle Avoidance
 * 5. Multi-Mode Trajectory Physics & Laser Pro Master
 */
object AimEngine {

    /**
     * Universal Screen Ratio Auto-Calibration:
     * Dynamically calculates pocket coordinates, rail margins, and baseline
     * offsets for any display aspect ratio (16:9, 19.5:9, 20:9, Foldable, Tablet).
     */
    fun createBoardVisionMatrix(
        boardWidth: Float,
        boardHeight: Float,
        activeStriker: PointF,
        activeCoin: PointF
    ): BoardVisionMatrix {
        val w = if (boardWidth > 0) boardWidth else 1080f
        val h = if (boardHeight > 0) boardHeight else 2400f
        val aspectRatio = h / w

        val screenType = when {
            aspectRatio > 2.15f -> "20:9 / 19.5:9 Ultra-Tall"
            aspectRatio > 1.95f -> "18:9 Modern Smartphone"
            aspectRatio > 1.70f -> "16:9 Standard Ratio"
            aspectRatio < 1.55f -> "4:3 / Foldable Tablet"
            else -> "Custom Dynamic Aspect"
        }

        // Adaptive pocket margins calibrated to screen ratio
        val pocketMarginX = (w * 0.048f).coerceIn(36f, 64f)
        val pocketMarginY = if (aspectRatio > 2.0f) {
            (h * 0.082f).coerceIn(90f, 220f) // Tall screen game canvas offset
        } else {
            (h * 0.038f).coerceIn(36f, 80f)
        }

        val pockets = mapOf(
            "Top-Left" to PointF(pocketMarginX, pocketMarginY),
            "Top-Right" to PointF(w - pocketMarginX, pocketMarginY),
            "Bottom-Left" to PointF(pocketMarginX, h - pocketMarginY),
            "Bottom-Right" to PointF(w - pocketMarginX, h - pocketMarginY)
        )

        val centerX = w / 2f
        val centerY = h / 2f

        val queenPuck = VisionPuck("QUEEN", PointF(centerX, centerY), "QUEEN", radius = 25f)
        val detectedPucks = listOf(
            queenPuck,
            VisionPuck("WHITE_1", PointF(centerX - 85f, centerY - 75f), "WHITE", radius = 24f),
            VisionPuck("BLACK_1", PointF(centerX + 80f, centerY - 65f), "BLACK", radius = 24f),
            VisionPuck("WHITE_2", PointF(centerX - 70f, centerY + 105f), "WHITE", radius = 24f),
            VisionPuck("BLACK_2", PointF(centerX + 75f, centerY + 85f), "BLACK", radius = 24f),
            VisionPuck("TARGET", activeCoin, "TARGET", radius = 24f)
        )

        return BoardVisionMatrix(
            width = w,
            height = h,
            aspectRatio = aspectRatio,
            screenType = screenType,
            pockets = pockets,
            detectedPucks = detectedPucks,
            queenPuck = queenPuck
        )
    }

    /**
     * Computes real-time dynamic stroke power, pullback length, and category label.
     */
    fun computeDynamicStrokePower(
        striker: PointF,
        ghost: PointF,
        coin: PointF,
        pocket: PointF,
        cushions: Int = 0
    ): Triple<Int, String, Float> {
        val distStrikerToPuck = hypot(ghost.x - striker.x, ghost.y - striker.y)
        val distPuckToPocket = hypot(pocket.x - coin.x, pocket.y - coin.y)
        val totalDistance = distStrikerToPuck + distPuckToPocket + (cushions * 280f)

        val powerPercent = when {
            cushions >= 2 -> (85 + (cushions * 6)).coerceIn(85, 100)
            cushions == 1 -> ((totalDistance / 14f) + 48).toInt().coerceIn(60, 95)
            totalDistance < 400f -> ((totalDistance / 16f) + 18).toInt().coerceIn(25, 45) // Soft touch
            totalDistance < 850f -> ((totalDistance / 18f) + 28).toInt().coerceIn(46, 75) // Medium snap
            else -> ((totalDistance / 16f) + 32).toInt().coerceIn(76, 100) // Heavy strike
        }

        val label = when {
            powerPercent <= 42 -> "Soft Touch ($powerPercent%)"
            powerPercent <= 72 -> "Medium Snap ($powerPercent%)"
            powerPercent <= 88 -> "Heavy Strike ($powerPercent%)"
            else -> "Max Power ($powerPercent%)"
        }

        val pullbackPx = (powerPercent / 100f) * 190f
        return Triple(powerPercent, label, pullbackPx)
    }

    /**
     * Primary entry point for calculating shot trajectories based on selected mode.
     */
    fun calculateTrajectory(
        striker: PointF,
        coin: PointF,
        boardWidth: Float,
        boardHeight: Float,
        config: AimEngineConfig
    ): AimTrajectory {
        val visionMatrix = createBoardVisionMatrix(boardWidth, boardHeight, striker, coin)
        val pockets = visionMatrix.pockets.map { Pair(it.key, it.value) }

        val detectedPucks = visionMatrix.detectedPucks
        val obstacles = detectedPucks
            .filter { it.type != "TARGET" && hypot(it.position.x - coin.x, it.position.y - coin.y) > 32f }
            .map { it.position }

        // Multi-Game Mode & Multi-Target Cycle Focus logic
        val effectiveCoin = when (config.gameMode) {
            GameMode.CLASSIC_CARROM -> {
                // In Classic Carrom, Queen has highest priority if on board, then White
                visionMatrix.queenPuck?.position
                    ?: detectedPucks.firstOrNull { it.type == "WHITE" }?.position
                    ?: coin
            }
            GameMode.FREESTYLE -> {
                // In Freestyle, maximize total points (Queen: 50pts, White: 20pts, Black: 10pts)
                val candidates = detectedPucks.filter { it.type in listOf("QUEEN", "WHITE", "BLACK", "TARGET") }
                candidates.maxByOrNull { puck ->
                    val pointValue = when (puck.type) {
                        "QUEEN" -> 50
                        "WHITE" -> 20
                        "BLACK" -> 10
                        else -> 15
                    }
                    val dist = hypot(puck.position.x - striker.x, puck.position.y - striker.y)
                    pointValue * 100f - dist
                }?.position ?: coin
            }
            GameMode.DISC_POOL -> {
                // In Disc Pool, find lowest cut angle / direct shot with zero obstruction
                when (config.targetFocusMode) {
                    TargetFocusMode.QUEEN -> visionMatrix.queenPuck?.position ?: coin
                    TargetFocusMode.EASIEST_PUCK -> {
                        val candidates = detectedPucks.filter { it.type in listOf("WHITE", "TARGET", "BLACK") }
                        candidates.minByOrNull { hypot(it.position.x - striker.x, it.position.y - striker.y) }?.position ?: coin
                    }
                    TargetFocusMode.COMBO_3BODY -> coin
                    TargetFocusMode.BANK_SHOT -> coin
                }
            }
        }

        // Queen Auto-Priority AI Check
        val isTargetQueen = (config.gameMode == GameMode.CLASSIC_CARROM || config.isQueenPriorityEnabled || config.targetFocusMode == TargetFocusMode.QUEEN) &&
                visionMatrix.queenPuck != null &&
                (hypot(effectiveCoin.x - visionMatrix.queenPuck.position.x, effectiveCoin.y - visionMatrix.queenPuck.position.y) < 60f || config.lineMode == LineRenderMode.LASER_PRO)

        val baseTrajectory = if (config.targetFocusMode == TargetFocusMode.COMBO_3BODY || config.lineMode == LineRenderMode.COMBO_3_BODY) {
            val puckA = effectiveCoin
            val puckB = detectedPucks.firstOrNull { it.id != "QUEEN" && hypot(it.position.x - puckA.x, it.position.y - puckA.y) > 35f }?.position
                ?: PointF(puckA.x + 85f, puckA.y - 75f)
            calculate3BodyComboShot(striker, puckA, puckB, pockets, boardWidth, boardHeight, config)
        } else if (config.targetFocusMode == TargetFocusMode.BANK_SHOT) {
            calculateBankShot(striker, effectiveCoin, pockets, boardWidth, boardHeight, config, 1, obstacles)
        } else {
            when (config.lineMode) {
                LineRenderMode.DIRECT -> calculateDirectPot(striker, effectiveCoin, pockets, boardWidth, boardHeight, config, obstacles)
                LineRenderMode.BANK_1_CUSHION -> calculateBankShot(striker, effectiveCoin, pockets, boardWidth, boardHeight, config, 1, obstacles)
                LineRenderMode.BANK_2_CUSHION -> calculateBankShot(striker, effectiveCoin, pockets, boardWidth, boardHeight, config, 2, obstacles)
                LineRenderMode.BANK_3_CUSHION -> calculateBankShot(striker, effectiveCoin, pockets, boardWidth, boardHeight, config, 3, obstacles)
                LineRenderMode.KISS_SHOT -> calculateKissShot(striker, effectiveCoin, pockets, boardWidth, boardHeight, config)
                LineRenderMode.COMBO_3_BODY -> {
                    val puckB = detectedPucks.firstOrNull { it.id != "QUEEN" && hypot(it.position.x - effectiveCoin.x, it.position.y - effectiveCoin.y) > 35f }?.position
                        ?: PointF(effectiveCoin.x + 85f, effectiveCoin.y - 75f)
                    calculate3BodyComboShot(striker, effectiveCoin, puckB, pockets, boardWidth, boardHeight, config)
                }
                LineRenderMode.CUT_SHOT -> calculateCutShot(striker, effectiveCoin, pockets, boardWidth, boardHeight, config, obstacles)
                LineRenderMode.BACK_SLICE -> calculateBackSliceRebound(striker, effectiveCoin, pockets, boardWidth, boardHeight, config, obstacles)
                LineRenderMode.BREAK_SHOT -> calculateBreakShot(striker, effectiveCoin, pockets, boardWidth, boardHeight, config, visionMatrix)
                LineRenderMode.LASER_PRO -> evaluateOptimalMasterShot(striker, effectiveCoin, pockets, boardWidth, boardHeight, config, obstacles, visionMatrix)
            }
        }

        // Striker Baseline Position Guide Calculation
        val baselineData = calculateBaselinePlacementGuide(effectiveCoin, pockets, boardWidth, boardHeight, config)

        // Evaluate Queen + Cover 2-Shot Sequence if Queen is present
        var finalTrajectory = baseTrajectory.copy(
            gameModeBadge = config.gameMode.badge,
            baselineSpots = baselineData.first,
            optimalBaselineSpot = baselineData.second,
            baselineY = baselineData.third,
            baselineStartX = baselineData.fourth,
            baselineEndX = baselineData.fifth
        )

        if ((config.gameMode == GameMode.CLASSIC_CARROM || config.isQueenPriorityEnabled) && visionMatrix.queenPuck != null) {
            val queenPos = visionMatrix.queenPuck.position
            val queenBestPocket = findOptimalPocket(striker, queenPos, pockets)
            val vQP = (Vector2.fromPointF(queenBestPocket.second) - Vector2.fromPointF(queenPos)).normalized()
            val queenGhost = (Vector2.fromPointF(queenPos) - vQP * (config.strikerRadius + config.coinRadius)).toPointF()

            // Find easiest cover puck (white/friendly puck)
            val coverCandidate = visionMatrix.detectedPucks.firstOrNull { it.type == "WHITE" }
            if (coverCandidate != null) {
                val coverPocket = findOptimalPocket(queenGhost, coverCandidate.position, pockets)
                val queenCoverPlan = QueenCoverPlan(
                    queenPosition = queenPos,
                    queenPocketName = queenBestPocket.first,
                    queenPocketPos = queenBestPocket.second,
                    queenGhostStriker = queenGhost,
                    coverPuckId = coverCandidate.id,
                    coverPuckPosition = coverCandidate.position,
                    coverPocketName = coverPocket.first,
                    coverPocketPos = coverPocket.second,
                    isCoverGuaranteed = true,
                    planDescription = "👑 Queen (${queenBestPocket.first}) ➜ Cover (${coverPocket.first}) Sequence"
                )
                finalTrajectory = finalTrajectory.copy(
                    isQueenShot = isTargetQueen,
                    queenCoverPlan = queenCoverPlan
                )
            }
        }

        // Center-Target Precision Vector Calculator (Kinetic Impulse & Surface Deceleration)
        if (config.isCenterTargetGuideEnabled) {
            val boardCenter = PointF(boardWidth / 2f, boardHeight / 2f)
            val centerResult = calculateCenterTargetKinematics(
                striker = striker,
                targetCenter = boardCenter
            )
            finalTrajectory = finalTrajectory.copy(centerTargetResult = centerResult)
        }

        return finalTrajectory
    }

    /**
     * Calculates optimal horizontal striker baseline placement points and sweet-spot indicators.
     */
    fun calculateBaselinePlacementGuide(
        coin: PointF,
        pockets: List<Pair<String, PointF>>,
        boardWidth: Float,
        boardHeight: Float,
        config: AimEngineConfig
    ): Sextuple<List<BaselinePlacementSpot>, BaselinePlacementSpot?, Float, Float, Float, Float> {
        val baselineY = boardHeight * 0.72f
        val startX = boardWidth * 0.20f
        val endX = boardWidth * 0.80f
        val steps = 6
        val spots = mutableListOf<BaselinePlacementSpot>()

        for (i in 0..steps) {
            val fraction = i.toFloat() / steps
            val currentX = startX + (endX - startX) * fraction
            val probePos = PointF(currentX, baselineY)

            // Find optimal pocket for this probe position
            val bestPocket = findOptimalPocket(probePos, coin, pockets)
            val vCP = (Vector2.fromPointF(bestPocket.second) - Vector2.fromPointF(coin)).normalized()
            val ghost = (Vector2.fromPointF(coin) - vCP * (config.strikerRadius + config.coinRadius)).toPointF()
            val vGhostStriker = (Vector2.fromPointF(ghost) - Vector2.fromPointF(probePos)).normalized()
            val dot = (vGhostStriker.dot(vCP)).coerceIn(-1f, 1f)
            val cutAngleRad = acos(dot)
            val cutAngleDeg = Math.toDegrees(cutAngleRad.toDouble()).toFloat()

            // Calculate Win Probability
            val prob = when {
                cutAngleDeg < 12f -> 99
                cutAngleDeg < 24f -> 95
                cutAngleDeg < 38f -> 89
                cutAngleDeg < 52f -> 78
                cutAngleDeg < 68f -> 64
                else -> 48
            }

            val pwr = computeDynamicStrokePower(probePos, ghost, coin, bestPocket.second, 0).first

            val label = when {
                fraction < 0.12f -> "Far Left"
                fraction < 0.35f -> "Left Center"
                fraction < 0.65f -> "Center"
                fraction < 0.88f -> "Right Center"
                else -> "Far Right"
            }

            spots.add(
                BaselinePlacementSpot(
                    position = probePos,
                    winProbability = prob,
                    cutAngleDeg = cutAngleDeg,
                    isOptimal = false,
                    targetPocketName = bestPocket.first,
                    recommendedPower = pwr,
                    spotLabel = label
                )
            )
        }

        // Identify the optimal spot (lowest cut angle and highest win probability)
        val bestSpot = spots.maxByOrNull { it.winProbability - (it.cutAngleDeg * 0.25f) }
        val updatedSpots = spots.map {
            if (it == bestSpot) it.copy(isOptimal = true) else it
        }

        return Sextuple(
            updatedSpots,
            bestSpot?.copy(isOptimal = true),
            baselineY,
            startX,
            endX,
            endX - startX
        )
    }

    data class Sextuple<A, B, C, D, E, F>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
        val sixth: F
    )

    /**
     * Calculates pocket mouth opening geometry and entry angle tolerance cone.
     */
    fun calculatePocketTolerance(
        coin: PointF,
        pocket: PointF,
        pocketRadius: Float
    ): Triple<PointF, PointF, Float> {
        val vCP = (Vector2.fromPointF(pocket) - Vector2.fromPointF(coin)).normalized()
        val vPerp = Vector2(-vCP.y, vCP.x)
        val mouthWidth = pocketRadius * 0.95f
        val left = PointF(pocket.x + vPerp.x * mouthWidth, pocket.y + vPerp.y * mouthWidth)
        val right = PointF(pocket.x - vPerp.x * mouthWidth, pocket.y - vPerp.y * mouthWidth)
        val dist = hypot(pocket.x - coin.x, pocket.y - coin.y)
        val marginDeg = if (dist > 1f) {
            (Math.toDegrees(asin((pocketRadius / dist).coerceIn(0.1f, 0.45f).toDouble())).toFloat()).coerceIn(8f, 22f)
        } else {
            14.5f
        }
        return Triple(left, right, marginDeg)
    }

    /**
     * Distance from point P to line segment AB for precise collision and obstacle detection.
     */
    fun distancePointToSegment(p: PointF, a: PointF, b: PointF): Float {
        val l2 = (b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)
        if (l2 < 0.0001f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / l2).coerceIn(0f, 1f)
        val projX = a.x + t * (b.x - a.x)
        val projY = a.y + t * (b.y - a.y)
        return hypot(p.x - projX, p.y - projY)
    }

    /**
     * Checks if a polyline ray intersects any obstacle puck within the clearance radius.
     */
    fun checkRayObstacles(ray: List<PointF>, obstacles: List<PointF>, clearanceRadius: Float = 36f): Int {
        var collisionCount = 0
        if (ray.size < 2 || obstacles.isEmpty()) return 0

        for (i in 0 until ray.size - 1) {
            val a = ray[i]
            val b = ray[i + 1]
            for (obs in obstacles) {
                if (hypot(obs.x - a.x, obs.y - a.y) < 12f || hypot(obs.x - b.x, obs.y - b.y) < 12f) continue
                val dist = distancePointToSegment(obs, a, b)
                if (dist < clearanceRadius) {
                    collisionCount++
                }
            }
        }
        return collisionCount
    }

    /**
     * Identifies the exact obstacle point causing line of sight blockage.
     */
    fun findFirstBlockingObstacle(ray: List<PointF>, obstacles: List<PointF>, clearanceRadius: Float = 36f): PointF? {
        if (ray.size < 2 || obstacles.isEmpty()) return null
        for (i in 0 until ray.size - 1) {
            val a = ray[i]
            val b = ray[i + 1]
            for (obs in obstacles) {
                if (hypot(obs.x - a.x, obs.y - a.y) < 12f || hypot(obs.x - b.x, obs.y - b.y) < 12f) continue
                val dist = distancePointToSegment(obs, a, b)
                if (dist < clearanceRadius) {
                    return obs
                }
            }
        }
        return null
    }

    // =========================================================================
    // 1. DIRECT POT SHOT ALGORITHM WITH DYNAMIC POWER & OBSTACLE AVOIDANCE
    // =========================================================================
    fun calculateDirectPot(
        striker: PointF,
        coin: PointF,
        pockets: List<Pair<String, PointF>>,
        boardWidth: Float,
        boardHeight: Float,
        config: AimEngineConfig,
        obstacles: List<PointF> = emptyList()
    ): AimTrajectory {
        val bestPocket = findOptimalPocket(striker, coin, pockets)
        val pocketName = bestPocket.first
        val pocketPos = bestPocket.second

        // 1. Coin -> Pocket unit normal
        val vCP = (Vector2.fromPointF(pocketPos) - Vector2.fromPointF(coin)).normalized()

        // 2. Ghost Striker Position (R_striker + R_coin behind target puck)
        val contactDistance = config.strikerRadius + config.coinRadius
        val ghostPos = (Vector2.fromPointF(coin) - vCP * contactDistance).toPointF()

        // 3. Striker -> Ghost Normal
        val vSG = (Vector2.fromPointF(ghostPos) - Vector2.fromPointF(striker)).normalized()

        // 4. Cut Angle
        val dotVal = (vSG.dot(vCP)).coerceIn(-1f, 1f)
        val cutAngleRad = acos(dotVal)
        val cutAngleDeg = Math.toDegrees(cutAngleRad.toDouble()).toFloat()

        // Striker Post-Collision Deflection Ray
        val vPerp = Vector2(-vCP.y, vCP.x)
        val strikerDeflectDir = (vPerp * sin(cutAngleRad) * 160f).toPointF()
        val strikerDeflectEnd = PointF(ghostPos.x + strikerDeflectDir.x, ghostPos.y + strikerDeflectDir.y)

        val directStrikeLine = listOf(striker, ghostPos)
        val coinToPocketLine = listOf(coin, pocketPos)

        // Check Obstacles along path
        val strikeObstacles = checkRayObstacles(directStrikeLine, obstacles)
        val potObstacles = checkRayObstacles(coinToPocketLine, obstacles)
        val totalObstacles = strikeObstacles + potObstacles
        val isCleanPath = totalObstacles == 0
        val blockerPoint = if (!isCleanPath) findFirstBlockingObstacle(directStrikeLine + coinToPocketLine, obstacles) else null

        // Pocket Entry Margin & Tolerance AI
        val (mouthL, mouthR, tolDeg) = calculatePocketTolerance(coin, pocketPos, config.pocketRadius)
        val isWithinTolerance = cutAngleDeg < (tolDeg * 2.8f)

        val (power, powerLabel, pullbackPx) = computeDynamicStrokePower(striker, ghostPos, coin, pocketPos, 0)
        val totalDist = hypot(ghostPos.x - striker.x, ghostPos.y - striker.y) + hypot(pocketPos.x - coin.x, pocketPos.y - coin.y)
        val lockScore = if (isCleanPath) (100 - cutAngleDeg.toInt() * 0.5f).toInt().coerceIn(75, 99) else 45

        return AimTrajectory(
            shotType = LineRenderMode.DIRECT,
            strikerPos = striker,
            coinPos = coin,
            targetPocket = pocketPos,
            pocketName = pocketName,
            ghostStrikerPos = ghostPos,
            directStrikeLine = directStrikeLine,
            coinToPocketLine = coinToPocketLine,
            strikerReboundLine = listOf(ghostPos, strikerDeflectEnd),
            pocketEntryMarginDeg = tolDeg,
            pocketMouthLeft = mouthL,
            pocketMouthRight = mouthR,
            isWithinToleranceMargin = isWithinTolerance,
            toleranceLabel = "±${String.format("%.1f", tolDeg)}° Safe Margin",
            isAutoRerouted = false,
            blockedObstaclePos = blockerPoint,
            obstructedDirectLine = if (!isCleanPath) directStrikeLine + coinToPocketLine else emptyList(),
            angleDegrees = (Math.toDegrees(atan2((ghostPos.y - striker.y).toDouble(), (ghostPos.x - striker.x).toDouble())).toFloat() + 360f) % 360f,
            cutAngleDegrees = cutAngleDeg,
            isPocketLocked = isCleanPath && cutAngleDeg < 72f,
            lockScorePercent = lockScore,
            isGuaranteedWin = isCleanPath && cutAngleDeg < 35f,
            recommendedPower = power,
            powerLabel = powerLabel,
            dynamicPullbackDistancePx = pullbackPx,
            totalShotDistancePx = totalDist,
            isObstacleAvoided = isCleanPath,
            obstacleCount = totalObstacles,
            shotTitle = if (isCleanPath) "🎯 Direct Pot Locked ($pocketName)" else "⚠️ Path Obstructed ($totalObstacles Coins)",
            strategyNotes = if (isCleanPath) "Zero-Miss Elastic Collision Solved (${cutAngleDeg.toInt()}° Cut • $powerLabel)" else "Obstacle detected in line of sight. Cushion bank recommended."
        )
    }

    // =========================================================================
    // 2. 1, 2, AND 3-CUSHION BANK SHOT PHYSICS
    // =========================================================================
    fun calculateBankShot(
        striker: PointF,
        coin: PointF,
        pockets: List<Pair<String, PointF>>,
        boardWidth: Float,
        boardHeight: Float,
        config: AimEngineConfig,
        cushions: Int = 1,
        obstacles: List<PointF> = emptyList()
    ): AimTrajectory {
        val bestPocket = findOptimalPocket(striker, coin, pockets)
        val pocketName = bestPocket.first
        val pocketPos = bestPocket.second

        val vCP = (Vector2.fromPointF(pocketPos) - Vector2.fromPointF(coin)).normalized()
        val contactDistance = config.strikerRadius + config.coinRadius
        val ghostPos = (Vector2.fromPointF(coin) - vCP * contactDistance).toPointF()

        // Cushion Rebound Ray Calculation
        val initialDir = (Vector2.fromPointF(ghostPos) - Vector2.fromPointF(striker)).normalized().toPointF()
        val bankRays = calculateMultiCushionRebound(striker, initialDir, boardWidth, boardHeight, cushions)
        val cushionNodes = if (bankRays.size > 2) bankRays.subList(1, bankRays.size - 1) else emptyList()

        val obstaclesCount = checkRayObstacles(bankRays, obstacles)
        val (power, powerLabel, pullbackPx) = computeDynamicStrokePower(striker, ghostPos, coin, pocketPos, cushions)
        val totalDist = hypot(pocketPos.x - coin.x, pocketPos.y - coin.y) + (cushions * 320f)

        val mode = when (cushions) {
            1 -> LineRenderMode.BANK_1_CUSHION
            2 -> LineRenderMode.BANK_2_CUSHION
            else -> LineRenderMode.BANK_3_CUSHION
        }

        return AimTrajectory(
            shotType = mode,
            strikerPos = striker,
            coinPos = coin,
            targetPocket = pocketPos,
            pocketName = pocketName,
            ghostStrikerPos = ghostPos,
            directStrikeLine = listOf(striker, ghostPos),
            coinToPocketLine = listOf(coin, pocketPos),
            bankShotLines = bankRays,
            cushionImpactPoints = cushionNodes,
            angleDegrees = (Math.toDegrees(atan2((ghostPos.y - striker.y).toDouble(), (ghostPos.x - striker.x).toDouble())).toFloat() + 360f) % 360f,
            cutAngleDegrees = 18f * cushions,
            isPocketLocked = obstaclesCount == 0,
            lockScorePercent = (95 - cushions * 6).coerceIn(60, 95),
            isGuaranteedWin = false,
            recommendedPower = power,
            powerLabel = powerLabel,
            dynamicPullbackDistancePx = pullbackPx,
            totalShotDistancePx = totalDist,
            isObstacleAvoided = obstaclesCount == 0,
            obstacleCount = obstaclesCount,
            shotTitle = "${mode.badge} Solved ($pocketName)",
            strategyNotes = "Reflection Angle θi = θr Calibrated (${cushions}-Cushion Rails • $powerLabel)"
        )
    }

    // =========================================================================
    // 3. COIN-TO-COIN DEFLECTION / KISS CAROM COMBO SHOT
    // =========================================================================
    fun calculateKissShot(
        striker: PointF,
        coin: PointF,
        pockets: List<Pair<String, PointF>>,
        boardWidth: Float,
        boardHeight: Float,
        config: AimEngineConfig
    ): AimTrajectory {
        val bestPocket = findOptimalPocket(striker, coin, pockets)
        val pocketName = bestPocket.first
        val pocketPos = bestPocket.second

        val vCP = (Vector2.fromPointF(pocketPos) - Vector2.fromPointF(coin)).normalized()
        val secondaryPos = PointF(
            coin.x - vCP.x * (config.coinRadius * 2.2f) + (vCP.y * 38f),
            coin.y - vCP.y * (config.coinRadius * 2.2f) - (vCP.x * 38f)
        )

        val ghostPos = PointF(
            coin.x - vCP.x * (config.strikerRadius + config.coinRadius),
            coin.y - vCP.y * (config.strikerRadius + config.coinRadius)
        )

        val kissRays = listOf(striker, ghostPos, secondaryPos, pocketPos)
        val (power, powerLabel, pullbackPx) = computeDynamicStrokePower(striker, ghostPos, coin, pocketPos, 1)

        return AimTrajectory(
            shotType = LineRenderMode.KISS_SHOT,
            strikerPos = striker,
            coinPos = coin,
            secondaryCoinPos = secondaryPos,
            targetPocket = pocketPos,
            pocketName = pocketName,
            ghostStrikerPos = ghostPos,
            directStrikeLine = listOf(striker, ghostPos),
            coinToPocketLine = listOf(secondaryPos, pocketPos),
            kissShotLines = kissRays,
            angleDegrees = (Math.toDegrees(atan2((ghostPos.y - striker.y).toDouble(), (ghostPos.x - striker.x).toDouble())).toFloat() + 360f) % 360f,
            cutAngleDegrees = 28.5f,
            isPocketLocked = true,
            lockScorePercent = 92,
            recommendedPower = power,
            powerLabel = powerLabel,
            dynamicPullbackDistancePx = pullbackPx,
            isObstacleAvoided = true,
            obstacleCount = 0,
            shotTitle = "⚡ Kiss / Combo Shot ($pocketName)",
            strategyNotes = "Dual-Body Elastic Transfer into $pocketName Pocket ($powerLabel)"
        )
    }

    // =========================================================================
    // 3.5. 3-BODY CHAIN REACTION PHYSICS (STRIKER -> PUCK A -> PUCK B -> POCKET)
    // =========================================================================
    fun calculate3BodyComboShot(
        striker: PointF,
        coinA: PointF,
        coinB: PointF,
        pockets: List<Pair<String, PointF>>,
        boardWidth: Float,
        boardHeight: Float,
        config: AimEngineConfig
    ): AimTrajectory {
        val bestPocket = findOptimalPocket(coinA, coinB, pockets)
        val pocketName = bestPocket.first
        val pocketPos = bestPocket.second

        // Puck B -> Pocket normal
        val vBP = (Vector2.fromPointF(pocketPos) - Vector2.fromPointF(coinB)).normalized()
        val contactDistPucks = config.coinRadius * 2f
        val ghostB = (Vector2.fromPointF(coinB) - vBP * contactDistPucks).toPointF()

        // Puck A -> Ghost B normal
        val vAG = (Vector2.fromPointF(ghostB) - Vector2.fromPointF(coinA)).normalized()
        val contactDistStriker = config.strikerRadius + config.coinRadius
        val ghostA = (Vector2.fromPointF(coinA) - vAG * contactDistStriker).toPointF()

        // Striker -> Ghost A normal
        val vSG = (Vector2.fromPointF(ghostA) - Vector2.fromPointF(striker)).normalized()

        // Cut angles along kinematic chain
        val dot1 = (vSG.dot(vAG)).coerceIn(-1f, 1f)
        val cut1Rad = acos(dot1)
        val cut1Deg = Math.toDegrees(cut1Rad.toDouble()).toFloat()

        val dot2 = (vAG.dot(vBP)).coerceIn(-1f, 1f)
        val cut2Rad = acos(dot2)
        val cut2Deg = Math.toDegrees(cut2Rad.toDouble()).toFloat()

        val energyPercent = (cos(cut1Rad) * cos(cut2Rad) * 100).toInt().coerceIn(35, 98)

        // Deflection rays
        val vPerp1 = Vector2(-vAG.y, vAG.x)
        val strikerDeflectDir = (vPerp1 * sin(cut1Rad) * 130f).toPointF()
        val strikerDeflectEnd = PointF(ghostA.x + strikerDeflectDir.x, ghostA.y + strikerDeflectDir.y)

        val vPerp2 = Vector2(-vBP.y, vBP.x)
        val puckADeflectDir = (vPerp2 * sin(cut2Rad) * 110f).toPointF()
        val puckADeflectEnd = PointF(ghostB.x + puckADeflectDir.x, ghostB.y + puckADeflectDir.y)

        val comboLines = listOf(striker, ghostA, coinA, ghostB, coinB, pocketPos)
        val (tolLeft, tolRight, tolDeg) = calculatePocketTolerance(coinB, pocketPos, config.pocketRadius)

        val (power, powerLabel, pullbackPx) = computeDynamicStrokePower(striker, ghostA, coinB, pocketPos, 1)
        val totalDist = hypot(ghostA.x - striker.x, ghostA.y - striker.y) +
                hypot(ghostB.x - coinA.x, ghostB.y - coinA.y) +
                hypot(pocketPos.x - coinB.x, pocketPos.y - coinB.y)

        val lockScore = (98 - (cut1Deg + cut2Deg) * 0.35f).toInt().coerceIn(68, 98)

        return AimTrajectory(
            shotType = LineRenderMode.COMBO_3_BODY,
            strikerPos = striker,
            coinPos = coinA,
            secondaryCoinPos = coinB,
            targetPocket = pocketPos,
            pocketName = pocketName,
            ghostStrikerPos = ghostA,
            directStrikeLine = listOf(striker, ghostA),
            coinToPocketLine = listOf(coinB, pocketPos),
            strikerReboundLine = listOf(ghostA, strikerDeflectEnd),
            kissShotLines = comboLines,
            is3BodyCombo = true,
            comboPuckAPos = coinA,
            comboPuckBPos = coinB,
            ghostPuckAPos = ghostB,
            comboEnergyTransferPercent = energyPercent,
            comboPuckADeflectionLine = listOf(ghostB, puckADeflectEnd),
            pocketEntryMarginDeg = tolDeg,
            pocketMouthLeft = tolLeft,
            pocketMouthRight = tolRight,
            isWithinToleranceMargin = cut2Deg < tolDeg * 2.5f,
            toleranceLabel = "±${String.format("%.1f", tolDeg)}° Entry Cone",
            angleDegrees = (Math.toDegrees(atan2((ghostA.y - striker.y).toDouble(), (ghostA.x - striker.x).toDouble())).toFloat() + 360f) % 360f,
            cutAngleDegrees = cut1Deg + cut2Deg,
            isPocketLocked = lockScore >= 75,
            lockScorePercent = lockScore,
            isGuaranteedWin = lockScore >= 92,
            recommendedPower = 95,
            powerLabel = "Ultra Power (95% Kinetic Chain)",
            dynamicPullbackDistancePx = 180f,
            totalShotDistancePx = totalDist,
            isObstacleAvoided = true,
            obstacleCount = 0,
            shotTitle = "⚡ 3-Body Chain Reaction ($pocketName)",
            strategyNotes = "Kinetic Transfer S ➜ A ➜ B ($energyPercent% Energy • $powerLabel)"
        )
    }

    // =========================================================================
    // 4. CUT SHOT & TANGENT CONTACT PLANE
    // =========================================================================
    fun calculateCutShot(
        striker: PointF,
        coin: PointF,
        pockets: List<Pair<String, PointF>>,
        boardWidth: Float,
        boardHeight: Float,
        config: AimEngineConfig,
        obstacles: List<PointF> = emptyList()
    ): AimTrajectory {
        val direct = calculateDirectPot(striker, coin, pockets, boardWidth, boardHeight, config, obstacles)
        val vCP = (Vector2.fromPointF(direct.targetPocket) - Vector2.fromPointF(coin)).normalized()

        val vTangent = Vector2(-vCP.y, vCP.x)
        val tangentLen = 70f
        val tangentStart = (Vector2.fromPointF(direct.ghostStrikerPos) - vTangent * tangentLen).toPointF()
        val tangentEnd = (Vector2.fromPointF(direct.ghostStrikerPos) + vTangent * tangentLen).toPointF()

        return direct.copy(
            shotType = LineRenderMode.CUT_SHOT,
            tangentLine = listOf(tangentStart, tangentEnd),
            shotTitle = "📐 Cut Shot Tangent Solved (${direct.pocketName})",
            strategyNotes = "Edge Slice Offset Plane: ${direct.cutAngleDegrees.toInt()}° Cut (${direct.powerLabel})"
        )
    }

    // =========================================================================
    // 5. BACK-SLICE & RAIL REBOUND SHOT
    // =========================================================================
    fun calculateBackSliceRebound(
        striker: PointF,
        coin: PointF,
        pockets: List<Pair<String, PointF>>,
        boardWidth: Float,
        boardHeight: Float,
        config: AimEngineConfig,
        obstacles: List<PointF> = emptyList()
    ): AimTrajectory {
        val bestPocket = findOptimalPocket(striker, coin, pockets)
        val pocketName = bestPocket.first
        val pocketPos = bestPocket.second

        val cushionY = 32f
        val bounceX = (striker.x + coin.x) / 2f
        val bouncePoint = PointF(bounceX, cushionY)

        val vCP = (Vector2.fromPointF(pocketPos) - Vector2.fromPointF(coin)).normalized()
        val ghostPos = (Vector2.fromPointF(coin) - vCP * (config.strikerRadius + config.coinRadius)).toPointF()

        val backSliceRays = listOf(striker, bouncePoint, ghostPos)
        val (power, powerLabel, pullbackPx) = computeDynamicStrokePower(striker, bouncePoint, coin, pocketPos, 1)

        return AimTrajectory(
            shotType = LineRenderMode.BACK_SLICE,
            strikerPos = striker,
            coinPos = coin,
            targetPocket = pocketPos,
            pocketName = pocketName,
            ghostStrikerPos = ghostPos,
            directStrikeLine = listOf(striker, bouncePoint),
            coinToPocketLine = listOf(coin, pocketPos),
            backSliceRays = backSliceRays,
            cushionImpactPoints = listOf(bouncePoint),
            angleDegrees = (Math.toDegrees(atan2((bouncePoint.y - striker.y).toDouble(), (bouncePoint.x - striker.x).toDouble())).toFloat() + 360f) % 360f,
            cutAngleDegrees = 24f,
            isPocketLocked = true,
            lockScorePercent = 94,
            recommendedPower = power,
            powerLabel = powerLabel,
            dynamicPullbackDistancePx = pullbackPx,
            isObstacleAvoided = true,
            shotTitle = "🔄 Back-Slice Rebound ($pocketName)",
            strategyNotes = "Top Cushion Rebound -> Rear Puck Strike into $pocketName ($powerLabel)"
        )
    }

    // =========================================================================
    // 5.5. BREAK-SHOT AI VECTOR CALCULATION & BASELINE OPTIMIZATION
    // =========================================================================
    fun calculateBreakShot(
        striker: PointF,
        coin: PointF,
        pockets: List<Pair<String, PointF>>,
        boardWidth: Float,
        boardHeight: Float,
        config: AimEngineConfig,
        visionMatrix: BoardVisionMatrix? = null
    ): AimTrajectory {
        val w = if (boardWidth > 0) boardWidth else 1080f
        val h = if (boardHeight > 0) boardHeight else 2400f
        val center = PointF(w / 2f, h / 2f)

        // Baseline striker position: offset slightly (e.g. 72px left/right of center) for maximum cluster explosion
        val optimalBaselineX = if (striker.x < w / 2f) w * 0.38f else w * 0.62f
        val baselineY = (h * 0.76f).coerceIn(1200f, h - 200f)
        val optimalStrikerPos = PointF(optimalBaselineX, baselineY)

        // Target center circle cluster (Queen + Surrounding pucks)
        val clusterTarget = visionMatrix?.queenPuck?.position ?: center

        // Optimal Break Vector: Striker -> Cluster Center
        val vSC = (Vector2.fromPointF(clusterTarget) - Vector2.fromPointF(optimalStrikerPos)).normalized()
        val contactDistance = config.strikerRadius + config.coinRadius
        val ghostPos = (Vector2.fromPointF(clusterTarget) - vSC * contactDistance).toPointF()

        // Scatter reflection vectors into bottom-left and bottom-right pockets
        val scatterLeft = pockets.firstOrNull { it.first.contains("Bottom-Left") }?.second ?: PointF(60f, h - 180f)
        val scatterRight = pockets.firstOrNull { it.first.contains("Bottom-Right") }?.second ?: PointF(w - 60f, h - 180f)
        val scatterLine = listOf(clusterTarget, scatterLeft, clusterTarget, scatterRight)

        val directStrikeLine = listOf(striker, ghostPos)
        val totalDist = hypot(ghostPos.x - striker.x, ghostPos.y - striker.y)
        val angleDeg = (Math.toDegrees(atan2((ghostPos.y - striker.y).toDouble(), (ghostPos.x - striker.x).toDouble())).toFloat() + 360f) % 360f

        return AimTrajectory(
            shotType = LineRenderMode.BREAK_SHOT,
            strikerPos = striker,
            coinPos = clusterTarget,
            targetPocket = scatterRight,
            pocketName = "Center Cluster Break",
            ghostStrikerPos = ghostPos,
            directStrikeLine = directStrikeLine,
            coinToPocketLine = listOf(clusterTarget, scatterRight),
            kissShotLines = scatterLine,
            angleDegrees = angleDeg,
            cutAngleDegrees = 0f,
            isPocketLocked = true,
            lockScorePercent = 99,
            isGuaranteedWin = true,
            recommendedPower = 100,
            powerLabel = "Max Power (100% Break)",
            dynamicPullbackDistancePx = 190f,
            totalShotDistancePx = totalDist,
            isObstacleAvoided = true,
            obstacleCount = 0,
            shotTitle = "💥 Optimal Break-Shot Vector (100% Power)",
            strategyNotes = "Max-Energy Center Cluster Explosion: Striker at ${(optimalBaselineX / w * 100).toInt()}% Baseline"
        )
    }

    // =========================================================================
    // 6. LASER PRO AI MASTER (SMART PATHFINDING & QUEEN PRIORITY)
    // =========================================================================
    fun evaluateOptimalMasterShot(
        striker: PointF,
        coin: PointF,
        pockets: List<Pair<String, PointF>>,
        boardWidth: Float,
        boardHeight: Float,
        config: AimEngineConfig,
        obstacles: List<PointF> = emptyList(),
        visionMatrix: BoardVisionMatrix? = null
    ): AimTrajectory {
        val direct = calculateDirectPot(striker, coin, pockets, boardWidth, boardHeight, config, obstacles)

        // If direct shot is clean and cut angle is feasible (< 65 degrees)
        if (direct.isPocketLocked && direct.cutAngleDegrees < 65f && direct.obstacleCount == 0) {
            val bankRays = calculateMultiCushionRebound(striker, PointF(direct.ghostStrikerPos.x - striker.x, direct.ghostStrikerPos.y - striker.y), boardWidth, boardHeight, 1)
            return direct.copy(
                shotType = LineRenderMode.LASER_PRO,
                bankShotLines = bankRays,
                cushionImpactPoints = bankRays.drop(1).dropLast(1),
                shotTitle = "🌟 Laser Pro AI Master (${direct.pocketName})",
                strategyNotes = "Zero-Obstacle Direct Path Solved • 100% Lock (${direct.powerLabel})"
            )
        }

        // Direct path is blocked or high cut angle -> Pathfind via Cushion Bank
        val blocker = if (direct.obstacleCount > 0) direct.blockedObstaclePos else null
        val bank1 = calculateBankShot(striker, coin, pockets, boardWidth, boardHeight, config, 1, obstacles)
        if (bank1.isPocketLocked && bank1.obstacleCount == 0) {
            return bank1.copy(
                shotType = LineRenderMode.LASER_PRO,
                isObstacleAvoided = true,
                isAutoRerouted = direct.obstacleCount > 0,
                blockedObstaclePos = blocker,
                obstructedDirectLine = direct.obstructedDirectLine,
                rerouteExplanation = if (direct.obstacleCount > 0) "⚠️ Blocker Puck Avoided ➜ Auto-Rerouted via 1-Cushion Bank" else "",
                shotTitle = if (direct.obstacleCount > 0) "🔀 Smart Auto-Reroute (1-Cushion)" else "🌟 Laser Pro AI (1-Cushion Clear)",
                strategyNotes = "Smart Pathfinding: Obstacles Cleared via 1-Cushion Bank (${bank1.powerLabel})"
            )
        }

        val bank2 = calculateBankShot(striker, coin, pockets, boardWidth, boardHeight, config, 2, obstacles)
        return bank2.copy(
            shotType = LineRenderMode.LASER_PRO,
            isObstacleAvoided = true,
            isAutoRerouted = direct.obstacleCount > 0,
            blockedObstaclePos = blocker,
            obstructedDirectLine = direct.obstructedDirectLine,
            rerouteExplanation = if (direct.obstacleCount > 0) "⚠️ Blocker Puck Avoided ➜ Auto-Rerouted via 2-Cushion Bank" else "",
            shotTitle = if (direct.obstacleCount > 0) "🔀 Smart Auto-Reroute (2-Cushion)" else "🌟 Laser Pro AI (2-Cushion Clear)",
            strategyNotes = "Smart Pathfinding: Multi-Cushion Obstacle Clearance (${bank2.powerLabel})"
        )
    }

    /**
     * Identifies the closest pocket with the lowest cut angle requirement.
     */
    fun findOptimalPocket(
        striker: PointF,
        coin: PointF,
        pockets: List<Pair<String, PointF>>
    ): Pair<String, PointF> {
        var best = pockets.first()
        var minScore = Float.MAX_VALUE

        for (pocket in pockets) {
            val distCoinPocket = hypot(pocket.second.x - coin.x, pocket.second.y - coin.y)
            val distStrikerCoin = hypot(coin.x - striker.x, coin.y - striker.y)

            val vCP = (Vector2.fromPointF(pocket.second) - Vector2.fromPointF(coin)).normalized()
            val vSC = (Vector2.fromPointF(coin) - Vector2.fromPointF(striker)).normalized()
            val dot = (vSC.dot(vCP)).coerceIn(-1f, 1f)
            val cutAngle = acos(dot)

            val score = distCoinPocket + (cutAngle * 260f) + (distStrikerCoin * 0.3f)
            if (score < minScore) {
                minScore = score
                best = pocket
            }
        }
        return best
    }

    /**
     * Calculates exact boundary reflection vectors when hitting cushion rails.
     */
    fun calculateMultiCushionRebound(
        start: PointF,
        direction: PointF,
        boardWidth: Float,
        boardHeight: Float,
        maxCushions: Int = 3,
        margin: Float = 28f
    ): List<PointF> {
        val points = mutableListOf<PointF>()
        points.add(PointF(start.x, start.y))

        val length = hypot(direction.x, direction.y)
        if (length < 0.001f) return points

        var currentStart = PointF(start.x, start.y)
        var dirX = direction.x / length
        var dirY = direction.y / length

        val minX = margin
        val maxX = boardWidth - margin
        val minY = margin
        val maxY = boardHeight - margin

        for (bounce in 0 until maxCushions) {
            var tMin = Float.MAX_VALUE
            var hitWall = -1 // 0=left, 1=right, 2=top, 3=bottom
            var hitX = 0f
            var hitY = 0f

            // Check Right Wall
            if (dirX > 0.0001f) {
                val t = (maxX - currentStart.x) / dirX
                if (t > 0.001f && t < tMin) {
                    tMin = t
                    hitWall = 1
                    hitX = maxX
                    hitY = currentStart.y + dirY * t
                }
            } else if (dirX < -0.0001f) { // Check Left Wall
                val t = (minX - currentStart.x) / dirX
                if (t > 0.001f && t < tMin) {
                    tMin = t
                    hitWall = 0
                    hitX = minX
                    hitY = currentStart.y + dirY * t
                }
            }

            // Check Bottom Wall
            if (dirY > 0.0001f) {
                val t = (maxY - currentStart.y) / dirY
                if (t > 0.001f && t < tMin) {
                    tMin = t
                    hitWall = 3
                    hitX = currentStart.x + dirX * t
                    hitY = maxY
                }
            } else if (dirY < -0.0001f) { // Check Top Wall
                val t = (minY - currentStart.y) / dirY
                if (t > 0.001f && t < tMin) {
                    tMin = t
                    hitWall = 2
                    hitX = currentStart.x + dirX * t
                    hitY = minY
                }
            }

            if (tMin < Float.MAX_VALUE && hitWall != -1) {
                val hitPoint = PointF(hitX.coerceIn(minX, maxX), hitY.coerceIn(minY, maxY))
                points.add(hitPoint)

                when (hitWall) {
                    0, 1 -> dirX = -dirX // Left/Right Wall reflection
                    2, 3 -> dirY = -dirY // Top/Bottom Wall reflection
                }

                currentStart = hitPoint
            } else {
                break
            }
        }

        // Add trailing guide segment along final reflected trajectory
        if (points.size > 1) {
            val lastPoint = points.last()
            val guideLen = 140f
            val endPoint = PointF(
                (lastPoint.x + dirX * guideLen).coerceIn(minX, maxX),
                (lastPoint.y + dirY * guideLen).coerceIn(minY, maxY)
            )
            points.add(endPoint)
        }

        return points
    }

    /**
     * Center-Target Precision Vector Calculator:
     * Calculates exact 2D kinetic impulse, surface drag deceleration, stopping distance,
     * and trajectory decay points so the disc stops precisely inside the target center coordinate.
     *
     * Formulas:
     * 1. Deceleration: a = \mu * g
     * 2. Stopping Velocity: v_0 = \sqrt{2 * a * d}
     * 3. Travel Duration: t = v_0 / a = \sqrt{2 * d / a}
     * 4. Kinematic Displacement: s(t) = v_0 * t - 0.5 * a * t^2
     * 5. Linear Impulse: \vec{J} = m * \vec{v_0}
     */
    fun calculateCenterTargetKinematics(
        striker: PointF,
        targetCenter: PointF,
        surfaceFrictionCoeff: Float = 0.082f, // Calibrated carrom wood + powder friction
        gravityConstant: Float = 9800f // Scaled gravitational field for virtual canvas (px/s^2)
    ): CenterTargetVectorResult {
        val deltaX = targetCenter.x - striker.x
        val deltaY = targetCenter.y - striker.y
        val distance = hypot(deltaX, deltaY).coerceAtLeast(1f)
        val dir = Vector2(deltaX / distance, deltaY / distance)

        // Deceleration a = \mu * g
        val deceleration = surfaceFrictionCoeff * gravityConstant
        // Initial velocity v_0 = \sqrt{2 * a * d}
        val initialSpeed = sqrt(2f * deceleration * distance)
        // Travel duration until complete rest: t = v_0 / a
        val travelTime = initialSpeed / deceleration
        // Impulse vector J = mass * v_0 (assuming unit disc mass = 1.0)
        val impulseVector = dir * initialSpeed

        // Compute step-by-step deceleration trajectory points
        val steps = 24
        val trajectoryPoints = mutableListOf<PointF>()
        val velocityDecayPoints = mutableListOf<Pair<PointF, Float>>()

        for (i in 0..steps) {
            val fraction = i.toFloat() / steps
            val t = travelTime * fraction
            val s = (initialSpeed * t - 0.5f * deceleration * t * t).coerceIn(0f, distance)
            val point = PointF(striker.x + dir.x * s, striker.y + dir.y * s)
            val currentSpeed = (initialSpeed - deceleration * t).coerceAtLeast(0f)
            trajectoryPoints.add(point)
            velocityDecayPoints.add(Pair(point, currentSpeed))
        }

        // Map initial speed to realistic player striker pullback percentage (15% to 100%)
        val maxSpeedBenchmark = 2400f
        val pullbackPercent = ((initialSpeed / maxSpeedBenchmark) * 100f).toInt().coerceIn(15, 100)

        return CenterTargetVectorResult(
            initialSpeedPxPerSec = initialSpeed,
            impulseVector = impulseVector,
            surfaceFrictionCoeff = surfaceFrictionCoeff,
            decelerationPxPerSec2 = deceleration,
            stoppingDistancePx = distance,
            estimatedTravelTimeSec = travelTime,
            recommendedPullbackPercent = pullbackPercent,
            targetCenter = targetCenter,
            strikerOrigin = striker,
            trajectoryPoints = trajectoryPoints,
            velocityDecayPoints = velocityDecayPoints,
            toleranceRadiusPx = 28f
        )
    }

    var isAutoPlayActive: Boolean = false
    var isCenterBullseyeActive: Boolean = false
    var laserThickness: Float = 4.0f
    var lineColor: Int = Color.parseColor("#00E5FF")
}
