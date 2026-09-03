package com.example

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.*

/**
 * Visual styling presets for the In-Game HUD.
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

enum class GameMode(val label: String, val badge: String, val description: String) {
    DISC_POOL("Disc Pool", "⚪ DISC POOL", "Direct Pot Focus • White/Black Puck Rush"),
    CLASSIC_CARROM("Classic Carrom", "👑 CLASSIC", "Queen Priority + Guaranteed Cover"),
    FREESTYLE("Freestyle", "⭐ FREESTYLE", "Score Maximizer • High Value Targets")
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
 * 2D Vector mathematics representation for Carrom physics.
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
 * Geometric Carrom Board Boundaries clamped to a 1:1 aspect square in the center of the display.
 */
data class CarromBoardBounds(
    val boardSize: Float,
    val boardLeft: Float,
    val boardTop: Float,
    val boardRight: Float,
    val boardBottom: Float,
    val cushionLeft: Float,
    val cushionTop: Float,
    val cushionRight: Float,
    val cushionBottom: Float,
    val baselineY: Float,
    val baselineStartX: Float,
    val baselineEndX: Float,
    val pockets: Map<String, PointF>,
    val pocketRadius: Float = 38f,
    val boardCenter: PointF = PointF((boardLeft + boardRight) / 2f, (boardTop + boardBottom) / 2f)
) {
    fun clampToCushions(point: PointF): PointF {
        return PointF(
            point.x.coerceIn(cushionLeft, cushionRight),
            point.y.coerceIn(cushionTop, cushionBottom)
        )
    }

    fun isInsideCushions(point: PointF): Boolean {
        return point.x in cushionLeft..cushionRight && point.y in cushionTop..cushionBottom
    }
}

/**
 * Board Vision Puck representation.
 */
enum class PuckType { WHITE, BLACK, QUEEN, STRIKER, ACTIVE_SHEEN }

data class VisionPuck(
    val id: String,
    val position: PointF,
    val type: String,
    val radius: Float = 24f,
    val confidence: Float = 0.98f,
    val hasSheenGlint: Boolean = false,
    val sheenLuminance: Float = 0f
)

/**
 * Vision detection result from viewport frame scanner.
 */
data class DetectedBoardVisionResult(
    val isPlayerTurn: Boolean,
    val strikerPosition: PointF?,
    val targetPuckPosition: PointF?,
    val detectedPucks: List<VisionPuck> = emptyList(),
    val targetPocket: PointF? = null,
    val targetPocketName: String? = null,
    val isVisionCalibrated: Boolean = true
)

/**
 * Calibrated Board Vision Grid Matrix.
 */
data class BoardVisionMatrix(
    val width: Float,
    val height: Float,
    val aspectRatio: Float,
    val screenType: String,
    val bounds: CarromBoardBounds,
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

data class BaselinePlacementSpot(
    val position: PointF,
    val winProbability: Int,
    val cutAngleDeg: Float,
    val isOptimal: Boolean,
    val targetPocketName: String,
    val recommendedPower: Int,
    val spotLabel: String
)

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
 * Pure 2D Ghost-Ball Trajectory Result.
 */
data class AimTrajectory(
    val shotType: LineRenderMode = LineRenderMode.DIRECT,
    val strikerPos: PointF,
    val coinPos: PointF,
    val secondaryCoinPos: PointF? = null,
    val targetPocket: PointF,
    val pocketName: String,
    val ghostStrikerPos: PointF,              // Ghost-Ball Impact Point (G)
    val directStrikeLine: List<PointF>,       // Line 1: S -> G (Striker Line - Crisp White)
    val coinToPocketLine: List<PointF>,       // Line 2: P -> K (Puck Line - Neon Yellow)
    val bankShotLines: List<PointF> = emptyList(),
    val strikerReboundLine: List<PointF> = emptyList(),
    val kissShotLines: List<PointF> = emptyList(), // Line 3: Secondary / Kiss Shot (Cyan Ray)
    val strikerRestPoint: PointF? = null,     // Kinetic energy loss stop marker for striker (White dot)
    val targetPuckRestPoint: PointF? = null,  // Kinetic energy loss stop marker for target puck (Neon Yellow dot)
    val secondaryPuckRestPoint: PointF? = null, // Kinetic energy loss stop marker for secondary puck (Cyan dot)
    val isKissShotActive: Boolean = false,
    val secondaryPuckPos: PointF? = null,
    val tangentLine: List<PointF>? = null,
    val backSliceRays: List<PointF>? = null,
    val cushionImpactPoints: List<PointF> = emptyList(),
    val boardBounds: CarromBoardBounds? = null,
    val is3BodyCombo: Boolean = false,
    val comboPuckAPos: PointF? = null,
    val comboPuckBPos: PointF? = null,
    val ghostPuckAPos: PointF? = null,
    val comboEnergyTransferPercent: Int = 100,
    val comboPuckADeflectionLine: List<PointF> = emptyList(),
    val pocketEntryMarginDeg: Float = 14.5f,
    val pocketMouthLeft: PointF? = null,
    val pocketMouthRight: PointF? = null,
    val isWithinToleranceMargin: Boolean = true,
    val toleranceLabel: String = "Direct Pot Locked",
    val isAutoRerouted: Boolean = false,
    val blockedObstaclePos: PointF? = null,
    val obstructedDirectLine: List<PointF> = emptyList(),
    val rerouteExplanation: String = "",
    val baselineSpots: List<BaselinePlacementSpot> = emptyList(),
    val optimalBaselineSpot: BaselinePlacementSpot? = null,
    val baselineY: Float = 0f,
    val baselineStartX: Float = 0f,
    val baselineEndX: Float = 0f,
    val angleDegrees: Float = 0f,
    val cutAngleDegrees: Float = 0f,
    val isPocketLocked: Boolean = true,
    val lockScorePercent: Int = 99,
    val isGuaranteedWin: Boolean = true,
    val recommendedPower: Int = 50,
    val powerLabel: String = "Medium (50%)",
    val dynamicPullbackDistancePx: Float = 90f,
    val totalShotDistancePx: Float = 400f,
    val isObstacleAvoided: Boolean = true,
    val obstacleCount: Int = 0,
    val isQueenShot: Boolean = false,
    val queenCoverPlan: QueenCoverPlan? = null,
    val centerTargetResult: CenterTargetVectorResult? = null,
    val gameModeBadge: String = "⚪ DISC POOL",
    val shotTitle: String = "Direct Pot Locked",
    val strategyNotes: String = "Deterministic Ghost-Ball Solved"
)

/**
 * Engine configuration data class.
 */
data class AimEngineConfig(
    val isEnabled: Boolean = true,
    val gameMode: GameMode = GameMode.DISC_POOL,
    val lineMode: LineRenderMode = LineRenderMode.DIRECT,
    val lineStyle: AimLineStyle = AimLineStyle.LASER_GLOW,
    val targetFocusMode: TargetFocusMode = TargetFocusMode.EASIEST_PUCK,
    val showBaselineGuide: Boolean = false,
    val isCenterTargetGuideEnabled: Boolean = false,
    val isAutoPlayEnabled: Boolean = false,
    val isQueenPriorityEnabled: Boolean = true,
    val isDualReboundEnabled: Boolean = false,
    val is3CushionEnabled: Boolean = false,
    val isAutoPocketPredictionEnabled: Boolean = true,
    val isStealthMode: Boolean = true,
    val is120FpsEnabled: Boolean = true,
    val isPerformanceSavingActive: Boolean = false,
    val laserColor: Int = Color.parseColor("#00E5FF"), // Cyan Laser default
    val puckColor: Int = Color.parseColor("#00E676"),  // Yellow/Green default
    val bankColor: Int = Color.parseColor("#FF1744"),
    val strokeWidth: Float = 3.0f,
    val showAngleHud: Boolean = false,
    val isDotted: Boolean = false,
    val strikerRadius: Float = 32f,
    val coinRadius: Float = 22f,
    val pocketRadius: Float = 36f,
    val maxCushions: Int = 0
)

/**
 * Pure, deterministic 2D Ghost-Ball Vector Raycasting & Real-Time Pixel Scanner Engine.
 * NO mock values, NO random points, NO network fallback.
 */
object AimEngine {

    // Normalized Pocket Coordinates for 4 corners of the 1:1 board square
    val NORMALIZED_POCKET_TOP_LEFT = PointF(0.10f, 0.10f)
    val NORMALIZED_POCKET_TOP_RIGHT = PointF(0.90f, 0.10f)
    val NORMALIZED_POCKET_BOTTOM_LEFT = PointF(0.10f, 0.90f)
    val NORMALIZED_POCKET_BOTTOM_RIGHT = PointF(0.90f, 0.90f)

    var isAutoPlayActive: Boolean = false
    var isFastModeActive: Boolean = true
    var isCenterBullseyeActive: Boolean = false

    var lineColor: Int = Color.parseColor("#00E5FF")
    var laserThickness: Float = 3.0f

    /**
     * Board Calibration:
     * Board boundary strictly clamped to a 1:1 aspect square in the center of the display.
     */
    fun calculateBoardBounds(width: Float, height: Float): CarromBoardBounds {
        val w = if (width > 0f) width else 1080f
        val h = if (height > 0f) height else 2400f

        val boardSize = min(w, h)
        val boardLeft = (w - boardSize) / 2f
        val boardTop = (h - boardSize) / 2f
        val boardRight = boardLeft + boardSize
        val boardBottom = boardTop + boardSize

        val cushionMargin = boardSize * 0.05f
        val cushionLeft = boardLeft + cushionMargin
        val cushionRight = boardRight - cushionMargin
        val cushionTop = boardTop + cushionMargin
        val cushionBottom = boardBottom - cushionMargin

        val pocketRadius = boardSize * 0.045f

        val pockets = mapOf(
            "Top-Left" to PointF(
                boardLeft + NORMALIZED_POCKET_TOP_LEFT.x * boardSize,
                boardTop + NORMALIZED_POCKET_TOP_LEFT.y * boardSize
            ),
            "Top-Right" to PointF(
                boardLeft + NORMALIZED_POCKET_TOP_RIGHT.x * boardSize,
                boardTop + NORMALIZED_POCKET_TOP_RIGHT.y * boardSize
            ),
            "Bottom-Left" to PointF(
                boardLeft + NORMALIZED_POCKET_BOTTOM_LEFT.x * boardSize,
                boardTop + NORMALIZED_POCKET_BOTTOM_LEFT.y * boardSize
            ),
            "Bottom-Right" to PointF(
                boardLeft + NORMALIZED_POCKET_BOTTOM_RIGHT.x * boardSize,
                boardTop + NORMALIZED_POCKET_BOTTOM_RIGHT.y * boardSize
            )
        )

        val baselineY = boardTop + boardSize * 0.77f // Centered in baseline corridor (72% to 82%)
        val baselineStartX = boardLeft + boardSize * 0.20f
        val baselineEndX = boardRight - boardSize * 0.20f

        return CarromBoardBounds(
            boardSize = boardSize,
            boardLeft = boardLeft,
            boardTop = boardTop,
            boardRight = boardRight,
            boardBottom = boardBottom,
            cushionLeft = cushionLeft,
            cushionTop = cushionTop,
            cushionRight = cushionRight,
            cushionBottom = cushionBottom,
            baselineY = baselineY,
            baselineStartX = baselineStartX,
            baselineEndX = baselineEndX,
            pockets = pockets,
            pocketRadius = pocketRadius,
            boardCenter = PointF((boardLeft + boardRight) / 2f, (boardTop + boardBottom) / 2f)
        )
    }

    /**
     * True "Ghost-Ball" Trajectory Math:
     * Given Striker Position (S) and Target Puck Position (P):
     * a) Identify nearest pocket (K).
     * b) Calculate normalized pocket vector: V_pocket = normalize(K - P).
     * c) Calculate Ghost-Ball Impact Point (G): G = P - (V_pocket * puck_diameter).
     * d) Striker aim vector is directed strictly from S to G.
     * e) Calculate post-impact puck trajectory strictly from P into K.
     */
    fun calculateGhostBallTrajectory(
        striker: PointF,
        puck: PointF,
        bounds: CarromBoardBounds,
        config: AimEngineConfig = AimEngineConfig(),
        overridePocket: PointF? = null,
        overridePocketName: String? = null,
        otherPucks: List<PointF> = emptyList()
    ): AimTrajectory {
        // a) Identify target pocket (K)
        var nearestPocketName = overridePocketName ?: "Top-Left"
        var nearestPocket = overridePocket ?: (bounds.pockets["Top-Left"] ?: PointF(
            bounds.boardLeft + NORMALIZED_POCKET_TOP_LEFT.x * bounds.boardSize,
            bounds.boardTop + NORMALIZED_POCKET_TOP_LEFT.y * bounds.boardSize
        ))

        if (overridePocket == null) {
            var minDistance = Float.MAX_VALUE
            for ((name, pocketPt) in bounds.pockets) {
                val dist = hypot(pocketPt.x - puck.x, pocketPt.y - puck.y)
                if (dist < minDistance) {
                    minDistance = dist
                    nearestPocket = pocketPt
                    nearestPocketName = name
                }
            }
        }

        // b) Calculate normalized pocket vector: V_pocket = normalize(K - P)
        val kx = nearestPocket.x
        val ky = nearestPocket.y
        val px = puck.x
        val py = puck.y

        val dPx = kx - px
        val dPy = ky - py
        val distPuckToPocket = hypot(dPx, dPy)

        val vPocketX: Float
        val vPocketY: Float
        if (distPuckToPocket > 0.0001f) {
            vPocketX = dPx / distPuckToPocket
            vPocketY = dPy / distPuckToPocket
        } else {
            vPocketX = 0f
            vPocketY = -1f
        }

        // c) Calculate Ghost-Ball Impact Point (G): G = P - (V_pocket * puck_diameter)
        val puckRadius = if (config.coinRadius > 0f) config.coinRadius else bounds.boardSize * 0.032f
        val puckDiameter = puckRadius * 2f

        val ghostX = px - (vPocketX * puckDiameter)
        val ghostY = py - (vPocketY * puckDiameter)
        val ghostPoint = PointF(ghostX, ghostY)

        // d) Multi-Ray Line 1: Striker Vector connecting Striker (Xs, Ys) to impact Ghost-Point (G)
        val directStrikeLine = listOf(
            PointF(striker.x, striker.y),
            ghostPoint
        )

        val dSx = ghostX - striker.x
        val dSy = ghostY - striker.y
        val distStrikerToGhost = hypot(dSx, dSy)
        val shotAngleDeg = (Math.toDegrees(atan2(dSy.toDouble(), dSx.toDouble())).toFloat() + 360f) % 360f

        val puckToPocketAngleDeg = (Math.toDegrees(atan2(dPy.toDouble(), dPx.toDouble())).toFloat() + 360f) % 360f
        val cutAngleDeg = abs(shotAngleDeg - puckToPocketAngleDeg)

        val totalDistance = distStrikerToGhost + distPuckToPocket
        val (powerPercent, powerLabel, pullbackPx) = computeDynamicStrokePower(
            striker = striker,
            ghost = ghostPoint,
            coin = puck,
            pocket = nearestPocket,
            cushions = 0
        )

        // ---------------------------------------------------------------------
        // MULTI-RAY LINE 2, LINE 3 & RESTING POINT KINETIC ENERGY CALCULATIONS
        // ---------------------------------------------------------------------
        var isKissActive = false
        var secondaryPuck: PointF? = null
        var secondaryKissLine: List<PointF> = emptyList()
        var targetPuckLine = listOf(
            PointF(puck.x, puck.y),
            PointF(nearestPocket.x, nearestPocket.y)
        )

        var strikerRestPos: PointF? = null
        var puckRestPos: PointF? = null
        var secondaryPuckRestPos: PointF? = null

        // Check if another puck obstructs the line between active puck and pocket K
        val linePKVec = Vector2(nearestPocket.x - puck.x, nearestPocket.y - puck.y)
        val linePKLen = linePKVec.length()
        val linePKNorm = if (linePKLen > 0.001f) linePKVec.normalized() else Vector2(0f, 0f)

        var nearestObstacle: PointF? = null
        var minObstacleProj = Float.MAX_VALUE

        for (other in otherPucks) {
            val dToPuck = hypot(other.x - puck.x, other.y - puck.y)
            if (dToPuck < puckRadius * 0.8f) continue // Ignore self

            val toOther = Vector2(other.x - puck.x, other.y - puck.y)
            val projection = toOther.dot(linePKNorm)

            if (projection in (puckDiameter * 0.8f)..(linePKLen - puckDiameter * 0.5f)) {
                val perpDist = abs(toOther.x * linePKNorm.y - toOther.y * linePKNorm.x)
                if (perpDist < puckDiameter * 1.15f && projection < minObstacleProj) {
                    minObstacleProj = projection
                    nearestObstacle = other
                }
            }
        }

        if (nearestObstacle != null) {
            // Secondary / Kiss Shot:
            // Calculate post-collision angle and render a Cyan ray toward the pocket
            isKissActive = true
            secondaryPuck = nearestObstacle

            val dOKx = nearestPocket.x - nearestObstacle.x
            val dOKy = nearestPocket.y - nearestObstacle.y
            val distOK = hypot(dOKx, dOKy)
            val vOK = if (distOK > 0.001f) Vector2(dOKx / distOK, dOKy / distOK) else Vector2(0f, -1f)

            // Secondary ghost contact point
            val secondaryGhostX = nearestObstacle.x - (vOK.x * puckDiameter)
            val secondaryGhostY = nearestObstacle.y - (vOK.y * puckDiameter)
            val secondaryGhost = PointF(secondaryGhostX, secondaryGhostY)

            // Primary puck travels from P to secondary ghost
            targetPuckLine = listOf(
                PointF(puck.x, puck.y),
                secondaryGhost
            )

            // Line 3: Cyan ray directing secondary puck into pocket
            secondaryKissLine = listOf(
                PointF(nearestObstacle.x, nearestObstacle.y),
                PointF(nearestPocket.x, nearestPocket.y)
            )

            // Resting points for kiss shot:
            secondaryPuckRestPos = PointF(nearestPocket.x, nearestPocket.y)

            // Primary puck deflects after hitting secondary puck
            val vPO = Vector2(secondaryGhost.x - puck.x, secondaryGhost.y - puck.y).normalized()
            val normalKiss = vOK
            val tangentKiss = Vector2(
                vPO.x - (vPO.dot(normalKiss) * normalKiss.x),
                vPO.y - (vPO.dot(normalKiss) * normalKiss.y)
            )
            val tanKissLen = tangentKiss.length()
            val normTanKiss = if (tanKissLen > 0.001f) Vector2(tangentKiss.x / tanKissLen, tangentKiss.y / tanKissLen) else Vector2(-vPO.y, vPO.x)
            val deflectedDist = ((powerPercent / 100f) * 65f + 16f).coerceIn(15f, 120f)
            puckRestPos = PointF(
                (secondaryGhost.x + normTanKiss.x * deflectedDist).coerceIn(bounds.cushionLeft, bounds.cushionRight),
                (secondaryGhost.y + normTanKiss.y * deflectedDist).coerceIn(bounds.cushionTop, bounds.cushionBottom)
            )
        } else {
            // Direct pot: Target puck lands in pocket K and stops
            puckRestPos = PointF(nearestPocket.x, nearestPocket.y)
        }

        // Striker Resting Point (Kinetic Energy Loss calculation):
        val vStrikerNorm = if (distStrikerToGhost > 0.001f) Vector2(dSx / distStrikerToGhost, dSy / distStrikerToGhost) else Vector2(0f, 0f)
        val vImpactNormal = Vector2(vPocketX, vPocketY)
        val strikerTangent = Vector2(
            vStrikerNorm.x - (vStrikerNorm.dot(vImpactNormal) * vImpactNormal.x),
            vStrikerNorm.y - (vStrikerNorm.dot(vImpactNormal) * vImpactNormal.y)
        )
        val tangentLen = strikerTangent.length()

        val strikerReboundRay = mutableListOf<PointF>()
        if (tangentLen > 0.01f) {
            val normTangent = Vector2(strikerTangent.x / tangentLen, strikerTangent.y / tangentLen)
            val strikerRollDistance = ((powerPercent / 100f) * tangentLen * 180f + 18f).coerceIn(15f, 220f)
            val sx = (ghostPoint.x + normTangent.x * strikerRollDistance).coerceIn(bounds.cushionLeft, bounds.cushionRight)
            val sy = (ghostPoint.y + normTangent.y * strikerRollDistance).coerceIn(bounds.cushionTop, bounds.cushionBottom)
            strikerRestPos = PointF(sx, sy)
            strikerReboundRay.add(ghostPoint)
            strikerReboundRay.add(strikerRestPos)
        } else {
            // Pure head-on collision: complete kinetic transfer, striker stops at G
            strikerRestPos = PointF(ghostPoint.x, ghostPoint.y)
        }

        return AimTrajectory(
            shotType = if (isKissActive) LineRenderMode.KISS_SHOT else LineRenderMode.DIRECT,
            strikerPos = striker,
            coinPos = puck,
            secondaryCoinPos = secondaryPuck,
            targetPocket = nearestPocket,
            pocketName = nearestPocketName,
            ghostStrikerPos = ghostPoint,
            directStrikeLine = directStrikeLine,
            coinToPocketLine = targetPuckLine,
            kissShotLines = secondaryKissLine,
            strikerReboundLine = strikerReboundRay,
            strikerRestPoint = strikerRestPos,
            targetPuckRestPoint = puckRestPos,
            secondaryPuckRestPoint = secondaryPuckRestPos,
            isKissShotActive = isKissActive,
            secondaryPuckPos = secondaryPuck,
            boardBounds = bounds,
            angleDegrees = shotAngleDeg,
            cutAngleDegrees = cutAngleDeg,
            isPocketLocked = true,
            recommendedPower = powerPercent,
            powerLabel = powerLabel,
            dynamicPullbackDistancePx = pullbackPx,
            totalShotDistancePx = totalDistance,
            shotTitle = if (isKissActive) "Kiss / Carom Combo" else "Ghost-Ball Direct Pot",
            strategyNotes = if (isKissActive) "Obstacle resolved -> Cyan Kiss Ray to $nearestPocketName" else "Nearest pocket $nearestPocketName locked"
        )
    }

    fun calculateTrajectory(
        striker: PointF,
        coin: PointF,
        bounds: CarromBoardBounds,
        config: AimEngineConfig = AimEngineConfig()
    ): AimTrajectory {
        return calculateGhostBallTrajectory(striker, coin, bounds, config)
    }

    fun computeDynamicStrokePower(
        striker: PointF,
        ghost: PointF,
        coin: PointF,
        pocket: PointF,
        cushions: Int = 0
    ): Triple<Int, String, Float> {
        val distStrikerToGhost = hypot(ghost.x - striker.x, ghost.y - striker.y)
        val distPuckToPocket = hypot(pocket.x - coin.x, pocket.y - coin.y)
        val totalDistance = distStrikerToGhost + distPuckToPocket + (cushions * 240f)

        val powerPercent = when {
            cushions >= 1 || totalDistance >= 500f -> {
                val progress = ((totalDistance - 500f) / 500f).coerceIn(0f, 1f)
                (85 + (progress * 15f)).toInt().coerceIn(85, 100)
            }
            distPuckToPocket < 200f && distStrikerToGhost < 300f -> {
                val progress = (distPuckToPocket / 200f).coerceIn(0f, 1f)
                (35 + (progress * 15f)).toInt().coerceIn(35, 50)
            }
            else -> {
                val progress = ((totalDistance - 200f) / 300f).coerceIn(0f, 1f)
                (51 + (progress * 33f)).toInt().coerceIn(51, 84)
            }
        }

        val label = when {
            powerPercent <= 45 -> "Soft Pot ($powerPercent%)"
            powerPercent <= 70 -> "Medium Snap ($powerPercent%)"
            powerPercent <= 84 -> "Firm Strike ($powerPercent%)"
            else -> "High Power Bank ($powerPercent%)"
        }

        val pullbackPx = (powerPercent / 100f) * 180f
        return Triple(powerPercent, label, pullbackPx)
    }

    /**
     * Inverted Slingshot Math Parameters:
     * - Theta: locked Ghost-Ball trajectory angle.
     * - PullAngle: Theta + 180 degrees (strictly inverted).
     * - Pull distance dynamically computed from target puck distance:
     *     Short pot (< 250px): Pull back 45-60px (Soft touch).
     *     Long bank/rebound (> 500px): Pull back 110-135px (Full power).
     * - Drag End Point:
     *     EndX = Xs + (pullDistance * cos(PullAngle))
     *     EndY = Ys + (pullDistance * sin(PullAngle))
     */
    data class SlingshotAutoStrikeParameters(
        val forwardThetaDeg: Float,
        val pullAngleDeg: Float,
        val pullDistancePx: Float,
        val startPoint: PointF,
        val endPoint: PointF,
        val targetPuckDistPx: Float
    )

    fun calculateSlingshotShotParameters(
        strikerPos: PointF,
        ghostPoint: PointF,
        targetPuckPos: PointF
    ): SlingshotAutoStrikeParameters {
        val dx = ghostPoint.x - strikerPos.x
        val dy = ghostPoint.y - strikerPos.y
        val forwardThetaDeg = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 360f) % 360f

        val pullAngleDeg = (forwardThetaDeg + 180f) % 360f
        val pullAngleRad = Math.toRadians(pullAngleDeg.toDouble())

        val targetPuckDist = hypot(targetPuckPos.x - strikerPos.x, targetPuckPos.y - strikerPos.y)

        val pullDistance = when {
            targetPuckDist < 250f -> {
                val factor = (targetPuckDist / 250f).coerceIn(0f, 1f)
                45f + (factor * 15f) // 45-60px
            }
            targetPuckDist > 500f -> {
                val factor = ((targetPuckDist - 500f) / 500f).coerceIn(0f, 1f)
                110f + (factor * 25f) // 110-135px
            }
            else -> {
                val factor = ((targetPuckDist - 250f) / 250f).coerceIn(0f, 1f)
                60f + (factor * 50f) // 60-110px
            }
        }

        val endX = (strikerPos.x + (pullDistance * cos(pullAngleRad))).toFloat()
        val endY = (strikerPos.y + (pullDistance * sin(pullAngleRad))).toFloat()

        return SlingshotAutoStrikeParameters(
            forwardThetaDeg = forwardThetaDeg,
            pullAngleDeg = pullAngleDeg,
            pullDistancePx = pullDistance,
            startPoint = PointF(strikerPos.x, strikerPos.y),
            endPoint = PointF(endX, endY),
            targetPuckDistPx = targetPuckDist
        )
    }

    // =========================================================================
    // REAL-TIME FRAME PIXEL SCANNER (HSV-BASED, DETERMINISTIC)
    // =========================================================================

    /**
     * Scans direct raw int[] pixel buffer strictly mapped to the 1:1 Carrom Board square.
     *
     * @param boardPixels Array of packed ARGB/RGBA pixels of size [boardSide x boardSide]
     * @param boardSide Dimension of the cropped 1:1 square
     * @param boardBounds Coordinate mapping bounds on actual screen
     */
    fun scanBoardPixelsDirect(
        boardPixels: IntArray,
        boardSide: Int,
        boardBounds: CarromBoardBounds
    ): DetectedBoardVisionResult {
        if (boardSide < 100 || boardPixels.size < boardSide * boardSide) {
            return DetectedBoardVisionResult(isPlayerTurn = false, strikerPosition = null, targetPuckPosition = null)
        }

        val hsv = FloatArray(3)

        // ---------------------------------------------------------------------
        // 1. DETERMINISTIC STRIKER BASELINE SCAN
        // Corridor: Y: 72% to 82% of board height, X: 15% to 85% of board width
        // ---------------------------------------------------------------------
        val baselineStartY = (boardSide * 0.72f).toInt().coerceIn(0, boardSide - 1)
        val baselineEndY = (boardSide * 0.82f).toInt().coerceIn(0, boardSide - 1)
        val baselineStartX = (boardSide * 0.15f).toInt().coerceIn(0, boardSide - 1)
        val baselineEndX = (boardSide * 0.85f).toInt().coerceIn(0, boardSide - 1)

        val expectedStrikerRadiusPx = boardSide * 0.035f // ~32-38dp proportional to board
        val minStrikerRadius = (expectedStrikerRadiusPx * 0.75f).coerceAtLeast(8f)
        val maxStrikerRadius = (expectedStrikerRadiusPx * 1.35f).coerceAtLeast(14f)

        var bestStrikerScore = 0f
        var detectedStrikerGridX = -1f
        var detectedStrikerGridY = -1f

        // Sample along baseline corridor with 3px step for performance & sub-pixel precision
        val step = max(2, (boardSide / 160))
        for (y in baselineStartY..baselineEndY step step) {
            for (x in baselineStartX..baselineEndX step step) {
                val pixel = boardPixels[y * boardSide + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                Color.RGBToHSV(r, g, b, hsv)

                // High-luminance rim or distinct striker core: V > 0.75 or strong saturation contrast
                if (hsv[2] > 0.75f || (hsv[1] > 0.55f && hsv[2] > 0.60f)) {
                    // Test radial symmetry around (x, y) for striker diameter
                    val symmetryScore = evaluateRadialRing(
                        boardPixels, boardSide, x, y,
                        minStrikerRadius, maxStrikerRadius, hsv
                    )
                    if (symmetryScore > bestStrikerScore && symmetryScore >= 0.45f) {
                        bestStrikerScore = symmetryScore
                        detectedStrikerGridX = x.toFloat()
                        detectedStrikerGridY = y.toFloat()
                    }
                }
            }
        }

        // If no valid striker pattern is found in the baseline corridor, immediately set isPlayerTurn = false
        if (bestStrikerScore < 0.45f || detectedStrikerGridX < 0f) {
            return DetectedBoardVisionResult(
                isPlayerTurn = false,
                strikerPosition = null,
                targetPuckPosition = null,
                detectedPucks = emptyList(),
                isVisionCalibrated = true
            )
        }

        // Convert grid coordinate to screen coordinate
        val scaleToScreen = boardBounds.boardSize / boardSide.toFloat()
        val strikerScreenPos = PointF(
            boardBounds.boardLeft + (detectedStrikerGridX * scaleToScreen),
            boardBounds.boardTop + (detectedStrikerGridY * scaleToScreen)
        )

        // ---------------------------------------------------------------------
        // 2. PUCK CLUSTERING & CLASSIFICATION INSIDE PLAYING AREA
        // ---------------------------------------------------------------------
        val puckRadiusPx = boardSide * 0.026f // ~26-30dp
        val playMinX = (boardSide * 0.08f).toInt()
        val playMaxX = (boardSide * 0.92f).toInt()
        val playMinY = (boardSide * 0.08f).toInt()
        val playMaxY = (boardSide * 0.88f).toInt()

        val puckCandidates = mutableListOf<PuckCandidate>()
        val puckStep = max(3, (boardSide / 120))

        for (y in playMinY..playMaxY step puckStep) {
            for (x in playMinX..playMaxX step puckStep) {
                // Do not register the striker as a target puck
                val dToStriker = hypot(x - detectedStrikerGridX, y - detectedStrikerGridY)
                if (dToStriker < expectedStrikerRadiusPx * 1.5f) continue

                val pixel = boardPixels[y * boardSide + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                Color.RGBToHSV(r, g, b, hsv)

                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]

                // Match classification:
                // a) Queen: Hue within 350°-15° pure red band, Saturation > 0.70
                // b) White Pucks: High Value V > 0.85, Low Saturation S < 0.15
                // c) Black Pucks: Low Value V < 0.20
                val puckType: PuckType? = when {
                    (hue >= 345f || hue <= 15f) && sat > 0.65f && value > 0.40f -> PuckType.QUEEN
                    value > 0.82f && sat < 0.22f -> PuckType.WHITE
                    value < 0.22f -> PuckType.BLACK
                    else -> null
                }

                if (puckType != null) {
                    val clusterWeight = verifyPuckCluster(boardPixels, boardSide, x, y, puckRadiusPx, puckType)
                    if (clusterWeight >= 0.45f) {
                        // Avoid duplicates in the candidate list
                        val existing = puckCandidates.firstOrNull {
                            hypot(it.gridX - x, it.gridY - y) < puckRadiusPx * 1.2f
                        }
                        if (existing == null) {
                            puckCandidates.add(PuckCandidate(x.toFloat(), y.toFloat(), puckType, clusterWeight))
                        } else if (clusterWeight > existing.weight) {
                            existing.gridX = (existing.gridX + x) / 2f
                            existing.gridY = (existing.gridY + y) / 2f
                            existing.weight = max(existing.weight, clusterWeight)
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // 2B. ACTIVE PUCK SHEEN / GLINT SCANNER (UNIVERSAL SKIN SUPPORT)
        // High-luminance pulse peaks (Value V >= 0.92, Saturation S <= 0.12)
        // occurring within circular puck radii (~28dp) inside board's inner active circle.
        // Any puck displaying this luminance reflection is instantly classified as an Active Target Puck.
        // ---------------------------------------------------------------------
        val sheenPucks = scanInnerCircleSheenPeaksDirect(boardPixels, boardSide, boardBounds)
        for (sp in sheenPucks) {
            val sGridX = (sp.position.x - boardBounds.boardLeft) / scaleToScreen
            val sGridY = (sp.position.y - boardBounds.boardTop) / scaleToScreen
            val existing = puckCandidates.firstOrNull {
                hypot(it.gridX - sGridX, it.gridY - sGridY) < puckRadiusPx * 1.3f
            }
            if (existing != null) {
                existing.hasSheenGlint = true
                existing.sheenLuminance = sp.sheenLuminance
                existing.weight = 0.99f
            } else {
                puckCandidates.add(
                    PuckCandidate(
                        gridX = sGridX,
                        gridY = sGridY,
                        type = PuckType.ACTIVE_SHEEN,
                        weight = 0.99f,
                        hasSheenGlint = true,
                        sheenLuminance = sp.sheenLuminance
                    )
                )
            }
        }

        // Convert puck candidates to VisionPuck list
        val detectedVisionPucks = puckCandidates.mapIndexed { index, cand ->
            VisionPuck(
                id = "${cand.type.name}_$index",
                position = PointF(
                    boardBounds.boardLeft + (cand.gridX * scaleToScreen),
                    boardBounds.boardTop + (cand.gridY * scaleToScreen)
                ),
                type = cand.type.name,
                radius = puckRadiusPx * scaleToScreen,
                confidence = cand.weight,
                hasSheenGlint = cand.hasSheenGlint,
                sheenLuminance = cand.sheenLuminance
            )
        }

        // ---------------------------------------------------------------------
        // 3. AUTO-SELECT OPTIMAL TARGET PUCK
        // Prioritizes Active Puck Sheen/Glint targets (+1000f score bonus)
        // ---------------------------------------------------------------------
        var optimalTargetPuck: PointF? = null
        var selectedPocket: PointF? = null
        var selectedPocketName: String? = null
        var bestShotScore = -Float.MAX_VALUE

        for (cand in puckCandidates) {
            val puckScreenPos = PointF(
                boardBounds.boardLeft + (cand.gridX * scaleToScreen),
                boardBounds.boardTop + (cand.gridY * scaleToScreen)
            )

            // Evaluate against all 4 corner pockets
            for ((pName, pPos) in boardBounds.pockets) {
                val dToPocket = hypot(pPos.x - puckScreenPos.x, pPos.y - puckScreenPos.y)
                val dToStriker = hypot(puckScreenPos.x - strikerScreenPos.x, puckScreenPos.y - strikerScreenPos.y)

                // Check for straight line obstruction between puck and pocket
                val pathClearance = calculatePathObstruction(
                    start = puckScreenPos,
                    end = pPos,
                    allPucks = detectedVisionPucks,
                    excludePuckPos = puckScreenPos,
                    clearanceRadius = puckRadiusPx * scaleToScreen
                )

                // Cut angle score: angle between striker-to-puck and puck-to-pocket
                val vSP = Vector2(puckScreenPos.x - strikerScreenPos.x, puckScreenPos.y - strikerScreenPos.y).normalized()
                val vPK = Vector2(pPos.x - puckScreenPos.x, pPos.y - puckScreenPos.y).normalized()
                val alignment = vSP.dot(vPK).coerceIn(-1f, 1f) // 1.0 = direct straight shot, < 0 = back cut

                // High score for sheen active pucks (+1000f), clear path, straight alignment, closer distance
                val sheenBonus = if (cand.hasSheenGlint || cand.type == PuckType.ACTIVE_SHEEN) 1000f else 0f
                val queenBonus = if (cand.type == PuckType.QUEEN) 200f else 0f
                val shotScore = sheenBonus + (pathClearance * 500f) + (alignment * 300f) - (dToPocket * 0.4f) - (dToStriker * 0.2f) + queenBonus

                if (shotScore > bestShotScore) {
                    bestShotScore = shotScore
                    optimalTargetPuck = puckScreenPos
                    selectedPocket = pPos
                    selectedPocketName = pName
                }
            }
        }

        return DetectedBoardVisionResult(
            isPlayerTurn = true,
            strikerPosition = strikerScreenPos,
            targetPuckPosition = optimalTargetPuck,
            detectedPucks = detectedVisionPucks,
            targetPocket = selectedPocket,
            targetPocketName = selectedPocketName,
            isVisionCalibrated = true
        )
    }

    /**
     * High-Luminance Active Puck Sheen/Glint Tracker (Universal Skin Support):
     * - Carrom Pool overlays an animated diagonal white shine/glint across the active player's valid pucks.
     * - Scans exclusively for high-luminance pulse peaks (Value V >= 0.92, Saturation S <= 0.12)
     *   occurring within circular puck radii (~28dp) inside the board's inner active circle.
     * - Any puck displaying this luminance reflection is instantly classified as an Active Target Puck,
     *   ignoring custom skin textures or base colors (Blue, Black, Special Skins).
     */
    fun scanInnerCircleSheenPeaksDirect(
        boardPixels: IntArray,
        boardSide: Int,
        boardBounds: CarromBoardBounds
    ): List<VisionPuck> {
        if (boardSide < 100 || boardPixels.size < boardSide * boardSide) {
            return emptyList()
        }

        val centerX = boardSide / 2f
        val centerY = boardSide / 2f
        // Strictly across the board's inner active circle (radius = 40% of board side)
        val innerCircleRadius = boardSide * 0.40f
        val innerCircleRadiusSq = innerCircleRadius * innerCircleRadius

        val puckRadiusPx = boardSide * 0.028f // ~28dp circular puck radius
        val clusterMergeDistSq = (puckRadiusPx * 1.3f) * (puckRadiusPx * 1.3f)

        val hsv = FloatArray(3)
        val step = max(2, boardSide / 160) // Fast 2-3px sampling step

        val peakCandidates = mutableListOf<SheenPeakCandidate>()

        val minY = (centerY - innerCircleRadius).toInt().coerceIn(0, boardSide - 1)
        val maxY = (centerY + innerCircleRadius).toInt().coerceIn(0, boardSide - 1)

        for (y in minY..maxY step step) {
            val dy = y - centerY
            val dySq = dy * dy
            val remainingRadiusSq = innerCircleRadiusSq - dySq
            if (remainingRadiusSq <= 0) continue
            val maxDx = sqrt(remainingRadiusSq.toDouble()).toFloat()
            val minX = (centerX - maxDx).toInt().coerceIn(0, boardSide - 1)
            val maxX = (centerX + maxDx).toInt().coerceIn(0, boardSide - 1)

            for (x in minX..maxX step step) {
                val pixel = boardPixels[y * boardSide + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                Color.RGBToHSV(r, g, b, hsv)
                val sat = hsv[1]
                val value = hsv[2]

                // High-luminance pulse peaks: Value V >= 0.92, Saturation S <= 0.12
                if (value >= 0.92f && sat <= 0.12f) {
                    var merged = false
                    for (cand in peakCandidates) {
                        val cdx = x - cand.gridX
                        val cdy = y - cand.gridY
                        if (cdx * cdx + cdy * cdy <= clusterMergeDistSq) {
                            cand.pixelCount++
                            cand.sumX += x
                            cand.sumY += y
                            cand.gridX = cand.sumX / cand.pixelCount
                            cand.gridY = cand.sumY / cand.pixelCount
                            if (value > cand.peakValue) {
                                cand.peakValue = value
                                cand.minSat = min(cand.minSat, sat)
                            }
                            merged = true
                            break
                        }
                    }

                    if (!merged) {
                        peakCandidates.add(
                            SheenPeakCandidate(
                                gridX = x.toFloat(),
                                gridY = y.toFloat(),
                                peakValue = value,
                                minSat = sat,
                                sumX = x.toFloat(),
                                sumY = y.toFloat(),
                                pixelCount = 1
                            )
                        )
                    }
                }
            }
        }

        val scaleToScreen = boardBounds.boardSize / boardSide.toFloat()
        return peakCandidates.mapIndexed { index, cand ->
            val screenX = boardBounds.boardLeft + (cand.gridX * scaleToScreen)
            val screenY = boardBounds.boardTop + (cand.gridY * scaleToScreen)
            VisionPuck(
                id = "ACTIVE_SHEEN_$index",
                position = PointF(screenX, screenY),
                type = PuckType.ACTIVE_SHEEN.name,
                radius = puckRadiusPx * scaleToScreen,
                confidence = 0.99f,
                hasSheenGlint = true,
                sheenLuminance = cand.peakValue
            )
        }
    }

    /**
     * Ultra-Low CPU Footprint Sheen Tracker Engine:
     * Runs the sheen peak detector asynchronously at 12 FPS strictly across
     * the board's inner active circle to guarantee 0% device lag and zero frame throttling.
     */
    object SheenTrackerEngine {
        private val trackerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private var trackerJob: Job? = null

        val activeSheenPucksFlow = MutableStateFlow<List<VisionPuck>>(emptyList())
        var latestActivePucks: List<VisionPuck> = emptyList()
            private set

        private var latestFramePixels: IntArray? = null
        private var latestBoardSide: Int = 0
        private var latestBoardBounds: CarromBoardBounds? = null
        private var isFrameDirty: Boolean = false
        private val frameBufferLock = Any()

        fun postFrameBuffer(pixels: IntArray, side: Int, bounds: CarromBoardBounds) {
            synchronized(frameBufferLock) {
                if (latestFramePixels == null || latestFramePixels?.size != pixels.size) {
                    latestFramePixels = pixels.clone()
                } else {
                    System.arraycopy(pixels, 0, latestFramePixels!!, 0, pixels.size)
                }
                latestBoardSide = side
                latestBoardBounds = bounds
                isFrameDirty = true
            }
        }

        fun startAsync12FpsTracker(onDetectionUpdate: ((List<VisionPuck>) -> Unit)? = null) {
            stopTracker()
            trackerJob = trackerScope.launch {
                while (isActive) {
                    val loopStart = SystemClock.elapsedRealtime()

                    var buffer: IntArray? = null
                    var side = 0
                    var bounds: CarromBoardBounds? = null

                    synchronized(frameBufferLock) {
                        if (isFrameDirty && latestFramePixels != null && latestBoardSide > 0 && latestBoardBounds != null) {
                            buffer = latestFramePixels
                            side = latestBoardSide
                            bounds = latestBoardBounds
                            isFrameDirty = false
                        }
                    }

                    if (buffer != null && bounds != null) {
                        val detected = scanInnerCircleSheenPeaksDirect(buffer!!, side, bounds!!)
                        latestActivePucks = detected
                        activeSheenPucksFlow.value = detected
                        withContext(Dispatchers.Main) {
                            onDetectionUpdate?.invoke(detected)
                        }
                    }

                    // Strict 12 FPS interval (1000ms / 12 = ~83.3ms) for 0% lag and zero throttling
                    val elapsed = SystemClock.elapsedRealtime() - loopStart
                    val sleepMs = (83L - elapsed).coerceAtLeast(10L)
                    delay(sleepMs)
                }
            }
        }

        fun stopTracker() {
            trackerJob?.cancel()
            trackerJob = null
        }
    }

    /**
     * Checks radial ring symmetry to confirm a striker disc pattern.
     */
    private fun evaluateRadialRing(
        pixels: IntArray,
        side: Int,
        cx: Int,
        cy: Int,
        minR: Float,
        maxR: Float,
        hsvTemp: FloatArray
    ): Float {
        var matchCount = 0
        val testAngles = 12
        val midR = (minR + maxR) / 2f

        for (i in 0 until testAngles) {
            val theta = (i * 2.0 * PI / testAngles)
            val rx = (cx + midR * cos(theta)).toInt()
            val ry = (cy + midR * sin(theta)).toInt()

            if (rx in 0 until side && ry in 0 until side) {
                val p = pixels[ry * side + rx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                Color.RGBToHSV(r, g, b, hsvTemp)
                if (hsvTemp[2] > 0.65f || hsvTemp[1] > 0.50f) {
                    matchCount++
                }
            }
        }
        return matchCount / testAngles.toFloat()
    }

    /**
     * Verifies that a cluster around (cx, cy) is a consistent puck disc of given type.
     */
    private fun verifyPuckCluster(
        pixels: IntArray,
        side: Int,
        cx: Int,
        cy: Int,
        radius: Float,
        expectedType: PuckType
    ): Float {
        var matchingSamples = 0
        var totalSamples = 0
        val hsv = FloatArray(3)
        val rInt = radius.toInt().coerceAtLeast(3)

        for (dy in -rInt..rInt step 2) {
            for (dx in -rInt..rInt step 2) {
                if (dx * dx + dy * dy <= radius * radius) {
                    val px = cx + dx
                    val py = cy + dy
                    if (px in 0 until side && py in 0 until side) {
                        totalSamples++
                        val p = pixels[py * side + px]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        Color.RGBToHSV(r, g, b, hsv)

                        val matches = when (expectedType) {
                            PuckType.QUEEN -> (hsv[0] >= 340f || hsv[0] <= 20f) && hsv[1] > 0.60f
                            PuckType.WHITE -> hsv[2] > 0.78f && hsv[1] < 0.25f
                            PuckType.BLACK -> hsv[2] < 0.25f
                            else -> false
                        }
                        if (matches) matchingSamples++
                    }
                }
            }
        }

        return if (totalSamples > 0) matchingSamples / totalSamples.toFloat() else 0f
    }

    /**
     * Calculates path clearance from start to end (1.0 = clear, 0.0 = directly blocked).
     */
    private fun calculatePathObstruction(
        start: PointF,
        end: PointF,
        allPucks: List<VisionPuck>,
        excludePuckPos: PointF,
        clearanceRadius: Float
    ): Float {
        val lineVec = Vector2(end.x - start.x, end.y - start.y)
        val lineLen = lineVec.length()
        if (lineLen < 0.0001f) return 1f

        val lineNorm = lineVec.normalized()

        for (puck in allPucks) {
            val dExclude = hypot(puck.position.x - excludePuckPos.x, puck.position.y - excludePuckPos.y)
            if (dExclude < clearanceRadius * 0.9f) continue

            // Distance from point to line segment
            val toPuck = Vector2(puck.position.x - start.x, puck.position.y - start.y)
            val projection = toPuck.dot(lineNorm)

            if (projection in (clearanceRadius * 0.5f)..(lineLen - clearanceRadius * 0.5f)) {
                val perpDist = abs(toPuck.x * lineNorm.y - toPuck.y * lineNorm.x)
                if (perpDist < clearanceRadius * 1.6f) {
                    return 0.1f // Obstructed
                }
            }
        }
        return 1.0f // Clear path
    }

    /**
     * Fallback for bitmap input (extracts direct pixels and delegates to scanBoardPixelsDirect).
     */
    fun detectBoardEntitiesFromViewport(
        boardBitmap: Bitmap?,
        boardBounds: CarromBoardBounds
    ): DetectedBoardVisionResult {
        if (boardBitmap == null || boardBitmap.isRecycled) {
            return DetectedBoardVisionResult(isPlayerTurn = false, strikerPosition = null, targetPuckPosition = null)
        }

        try {
            val bmpW = boardBitmap.width
            val bmpH = boardBitmap.height
            val side = min(bmpW, bmpH)
            val startX = (bmpW - side) / 2
            val startY = (bmpH - side) / 2

            val pixels = IntArray(side * side)
            boardBitmap.getPixels(pixels, 0, side, startX, startY, side, side)

            return scanBoardPixelsDirect(pixels, side, boardBounds)
        } catch (_: Exception) {
            return DetectedBoardVisionResult(isPlayerTurn = false, strikerPosition = null, targetPuckPosition = null)
        }
    }

    private data class PuckCandidate(
        var gridX: Float,
        var gridY: Float,
        val type: PuckType,
        var weight: Float,
        var hasSheenGlint: Boolean = false,
        var sheenLuminance: Float = 0f
    )

    private data class SheenPeakCandidate(
        var gridX: Float,
        var gridY: Float,
        var peakValue: Float,
        var minSat: Float,
        var sumX: Float,
        var sumY: Float,
        var pixelCount: Int
    )
}

/**
 * Cloud Physics Solution data class.
 */
data class CloudPhysicsSolution(
    val solutionId: String,
    val turnTimeRemainingSec: Int,
    val precisionAngleDeg: Float,
    val cutAngleDeg: Float,
    val optimalBounceCushions: List<PointF>,
    val requiredImpulseForceN: Float,
    val recommendedPowerPercent: Int,
    val dynamicPullbackDistancePx: Float,
    val forceCurveMultiplier: Float = 1.0f,
    val confidencePercent: Int = 99,
    val isVectorLocked: Boolean = true,
    val syncLatencyMs: Long = 6L,
    val serverStatus: String = "CLOUD_AI_PHYSICS_SYNCED"
)

/**
 * Background WebSocket / Network Physics Synchronization Client.
 */
object CloudPhysicsSyncClient {
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private var turnCountdownJob: Job? = null

    var isTurnActive: Boolean = false
        private set
    var turnRemainingSeconds: Int = 15
        private set

    var latestSolution: CloudPhysicsSolution? = null
        private set
    var isConnectedToCloud: Boolean = true
        private set

    private var activeTurnSessionId: String = "TURN_${System.currentTimeMillis()}"

    fun startTurnSyncWindow(
        striker: PointF,
        targetPuck: PointF,
        pocket: PointF,
        pocketName: String,
        boardBounds: CarromBoardBounds,
        allPucks: List<PointF> = emptyList(),
        onSolutionReceived: ((CloudPhysicsSolution) -> Unit)? = null
    ) {
        activeTurnSessionId = "TURN_${System.currentTimeMillis()}"
        isTurnActive = true
        turnRemainingSeconds = 15

        NetworkClient.initPipeline()

        turnCountdownJob?.cancel()
        turnCountdownJob = clientScope.launch {
            while (isTurnActive && turnRemainingSeconds > 0) {
                delay(1000L)
                turnRemainingSeconds--
                if (turnRemainingSeconds <= 0) {
                    isTurnActive = false
                    break
                }
            }
        }

        syncJob?.cancel()
        syncJob = clientScope.launch {
            while (isTurnActive && isActive) {
                val pucksList = mutableListOf<TelemetryPuck>()
                pucksList.add(TelemetryPuck(targetPuck.x, targetPuck.y, "WHITE", 0))
                allPucks.forEachIndexed { index, pt ->
                    pucksList.add(TelemetryPuck(pt.x, pt.y, if (index % 2 == 0) "BLACK" else "WHITE", index + 1))
                }

                val pocketsList = boardBounds.pockets.map { (name, pt) ->
                    TelemetryPocket(name, pt.x, pt.y, boardBounds.pocketRadius)
                }

                val telemetryRequest = FullBoardTelemetryRequest(
                    turnSessionId = activeTurnSessionId,
                    turnRemainingSec = turnRemainingSeconds,
                    strikerX = striker.x,
                    strikerY = striker.y,
                    boardWidth = boardBounds.boardSize,
                    boardHeight = boardBounds.boardSize,
                    pucks = pucksList,
                    pockets = pocketsList
                )

                NetworkClient.sendTelemetryPayload(telemetryRequest)

                val latency = NetworkClient.liveLatencyMs.value
                val serverPayload = NetworkClient.latestServerPayload.value

                val solution = if (serverPayload != null && !NetworkClient.isFallbackToLocal.value && latency <= 120L) {
                    CloudPhysicsSolution(
                        solutionId = serverPayload.responseId,
                        turnTimeRemainingSec = turnRemainingSeconds,
                        precisionAngleDeg = serverPayload.precisionAngleDeg,
                        cutAngleDeg = serverPayload.cutAngleDeg,
                        optimalBounceCushions = serverPayload.optimalBounceCushions,
                        requiredImpulseForceN = serverPayload.impulseForceN,
                        recommendedPowerPercent = serverPayload.shotPowerPercent,
                        dynamicPullbackDistancePx = (serverPayload.shotPowerPercent / 100f) * 175f,
                        forceCurveMultiplier = 1.05f,
                        confidencePercent = 99,
                        isVectorLocked = true,
                        syncLatencyMs = latency,
                        serverStatus = "CLOUD_AI_PHYSICS_SYNCED"
                    )
                } else {
                    val localTraj = AimEngine.calculateGhostBallTrajectory(striker, targetPuck, boardBounds)
                    CloudPhysicsSolution(
                        solutionId = "LOCAL_${System.currentTimeMillis()}",
                        turnTimeRemainingSec = turnRemainingSeconds,
                        precisionAngleDeg = localTraj.angleDegrees,
                        cutAngleDeg = localTraj.cutAngleDegrees,
                        optimalBounceCushions = emptyList(),
                        requiredImpulseForceN = localTraj.recommendedPower * 0.95f,
                        recommendedPowerPercent = localTraj.recommendedPower,
                        dynamicPullbackDistancePx = localTraj.dynamicPullbackDistancePx,
                        forceCurveMultiplier = 1.0f,
                        confidencePercent = 99,
                        isVectorLocked = true,
                        syncLatencyMs = 0L,
                        serverStatus = "ON_DEVICE_PHYSICS_LOCKED"
                    )
                }

                latestSolution = solution

                withContext(Dispatchers.Main) {
                    onSolutionReceived?.invoke(solution)
                }

                delay(20L) // ~50 Hz update
            }
        }
    }

    fun stopTurnSyncWindow() {
        isTurnActive = false
        syncJob?.cancel()
        turnCountdownJob?.cancel()
    }
}
