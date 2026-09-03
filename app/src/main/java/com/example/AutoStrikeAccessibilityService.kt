package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.*

/**
 * AutoStrikeAccessibilityService allows the app to dispatch precision touch gestures
 * for aiming and automated strike execution in Carrom Pool:
 * 1. Autonomous Turn & Stability Trigger.
 * 2. Inverted Slingshot Math:
 *      Theta = locked Ghost-Ball trajectory angle.
 *      PullAngle = Theta + 180 degrees.
 *      Short pot (< 250px): Pull back 45-60px (Soft touch).
 *      Long bank/rebound (> 500px): Pull back 110-135px (Full power).
 *      EndX = Xs + (pullDistance * cos(PullAngle))
 *      EndY = Ys + (pullDistance * sin(PullAngle))
 * 3. Humanized Gesture Dispatch:
 *      Path() with a subtle quadratic Bezier curve (simulating natural human thumb drag)
 *      over a duration of 120-160ms. Release cleanly at (EndX, EndY).
 * 4. Cooldown Lock:
 *      Locks Auto-Strike for 2.5 seconds (2500ms) to allow pucks to finish rolling before looking for the next turn.
 */
open class AutoStrikeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoStrikeService"

        // State observable across HUD and Engine
        val isServiceConnected = MutableStateFlow(false)
        val isAutoPlayExecuting = MutableStateFlow(false)
        val lastExecutedShotInfo = MutableStateFlow("Ready for Auto-Play Strike")

        var instance: AutoStrikeAccessibilityService? = null

        // Cooldown lock to prevent duplicate shots while balls are rolling
        @Volatile
        var isCooldownLocked: Boolean = false
            private set
        private var lastShotDispatchTimestamp = 0L
        private const val COOLDOWN_LOCK_MS = 2500L

        /**
         * Checks if the 2.5-second cooldown lock is currently active.
         */
        fun isShotCooldownActive(): Boolean {
            val now = System.currentTimeMillis()
            if (isCooldownLocked && now - lastShotDispatchTimestamp >= COOLDOWN_LOCK_MS) {
                isCooldownLocked = false
            }
            return isCooldownLocked
        }

        fun triggerCooldownLock() {
            lastShotDispatchTimestamp = System.currentTimeMillis()
            isCooldownLocked = true
            Handler(Looper.getMainLooper()).postDelayed({
                isCooldownLocked = false
            }, COOLDOWN_LOCK_MS)
        }

        /**
         * Verifies whether the Accessibility Service permission is granted in Android Settings.
         */
        fun isAccessibilitySettingsOn(context: Context): Boolean {
            var accessibilityEnabled = 0
            val autoStrikeService = "${context.packageName}/${AutoStrikeAccessibilityService::class.java.canonicalName}"
            val carromService = "${context.packageName}/${CarromAutoPlayService::class.java.canonicalName}"

            try {
                accessibilityEnabled = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED
                )
            } catch (e: Settings.SettingNotFoundException) {
                Log.e(TAG, "Error checking accessibility setting: ${e.message}")
            }

            val mStringColonSplitter = TextUtils.SimpleStringSplitter(':')

            if (accessibilityEnabled == 1) {
                val settingValue = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                if (settingValue != null) {
                    mStringColonSplitter.setString(settingValue)
                    while (mStringColonSplitter.hasNext()) {
                        val serviceName = mStringColonSplitter.next()
                        if (serviceName.equals(autoStrikeService, ignoreCase = true) ||
                            serviceName.equals(carromService, ignoreCase = true) ||
                            (serviceName.contains(context.packageName) &&
                                    (serviceName.contains("AutoStrike") || serviceName.contains("CarromAutoPlay")))
                        ) {
                            return true
                        }
                    }
                }
            }
            return false
        }

        /**
         * Dispatches true autonomous slingshot auto-strike injection with humanized curve:
         *
         * @param strikerPos (Xs, Ys)
         * @param shotAngleDeg (Theta: Ghost-ball forward aim angle)
         * @param targetPuckDist Distance to target puck in pixels
         * @param durationMs Gesture duration (120-160ms)
         * @param isFastMode Optional fast mode
         * @param onComplete Callback upon completion
         */
        fun performSlingshotAutoStrike(
            strikerPos: PointF,
            shotAngleDeg: Float,
            targetPuckDist: Float,
            durationMs: Long = 140L,
            isFastMode: Boolean = false,
            onComplete: ((Boolean) -> Unit)? = null
        ) {
            val service = instance
            if (service == null) {
                Log.w(TAG, "AutoStrikeAccessibilityService is not connected or enabled.")
                onComplete?.invoke(false)
                return
            }

            if (isShotCooldownActive()) {
                Log.d(TAG, "Auto-Strike in cooldown lock (2.5s). Skipping.")
                onComplete?.invoke(false)
                return
            }

            service.executeSlingshotShot(strikerPos, shotAngleDeg, targetPuckDist, durationMs, isFastMode, onComplete)
        }

        /**
         * Dispatches true inverted slingshot swipe gesture matching exact parameters:
         * Angle = target_angle + 180 degrees, pull distance = 160px.
         * Starts at Striker (x, y) -> drags backward to (pull_x, pull_y) over 100ms -> release (ACTION_UP).
         */
        fun performReverseSlingshotStrike(
            strikerPos: PointF,
            ghostPoint: PointF,
            pullDistancePx: Float = 160f,
            durationMs: Long = 100L,
            onComplete: ((Boolean) -> Unit)? = null
        ) {
            val service = instance
            if (service == null) {
                Log.w(TAG, "AutoStrikeAccessibilityService is not connected or enabled.")
                onComplete?.invoke(false)
                return
            }
            service.executeReverseSlingshot(strikerPos, ghostPoint, pullDistancePx, durationMs, onComplete)
        }

        /**
         * Backward compatibility dispatch:
         */
        fun performAutoStrike(
            strikerPos: PointF,
            aimTargetPos: PointF,
            powerPercent: Int = 85,
            durationMs: Long = 140L,
            isFastMode: Boolean = false,
            onComplete: ((Boolean) -> Unit)? = null
        ) {
            val dx = aimTargetPos.x - strikerPos.x
            val dy = aimTargetPos.y - strikerPos.y
            val dist = hypot(dx, dy)
            val angleDeg = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 360f) % 360f
            performSlingshotAutoStrike(strikerPos, angleDeg, dist, durationMs, isFastMode, onComplete)
        }

        fun performAutoStrikeByAngle(
            strikerPos: PointF,
            shotAngleDeg: Float,
            powerPercent: Int = 85,
            durationMs: Long = 140L,
            isFastMode: Boolean = false,
            onComplete: ((Boolean) -> Unit)? = null
        ) {
            val estimatedPuckDist = when {
                powerPercent >= 85 -> 550f
                powerPercent <= 45 -> 180f
                else -> 350f
            }
            performSlingshotAutoStrike(strikerPos, shotAngleDeg, estimatedPuckDist, durationMs, isFastMode, onComplete)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceConnected.value = true
        Log.i(TAG, "AutoStrikeAccessibilityService connected and ready for dispatch.")
        Toast.makeText(this, "⚡ Auto-Strike Accessibility Service Connected!", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility event monitoring
    }

    override fun onInterrupt() {
        Log.w(TAG, "AutoStrikeAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        isServiceConnected.value = false
        isAutoPlayExecuting.value = false
        Log.i(TAG, "AutoStrikeAccessibilityService destroyed.")
    }

    /**
     * Executes True Autonomous Slingshot Gesture with Quadratic Bezier Humanized Curve:
     *
     * 1. Inverted Slingshot Math:
     *      PullAngle = Theta + 180 degrees.
     *      Short pot (< 250px): Pull back 45-60px (Soft touch).
     *      Long bank/rebound (> 500px): Pull back 110-135px (Full power).
     *      EndX = Xs + (pullDistance * cos(PullAngle))
     *      EndY = Ys + (pullDistance * sin(PullAngle))
     * 2. Humanized Gesture Dispatch:
     *      Path() with a subtle quadratic Bezier curve (simulating natural human thumb drag)
     *      over a duration of 120-160ms. Release cleanly at (EndX, EndY).
     * 3. Cooldown Lock:
     *      Locks Auto-Strike for 2.5 seconds (2500ms) to allow pucks to finish rolling.
     */
    private fun executeSlingshotShot(
        strikerPos: PointF,
        shotAngleDeg: Float,
        targetPuckDist: Float,
        durationMs: Long,
        isFastMode: Boolean,
        onComplete: ((Boolean) -> Unit)?
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "GestureDescription requires Android 7.0 (API 24)+")
            onComplete?.invoke(false)
            return
        }

        // 1. Inverted Slingshot Math
        // Read locked Ghost-Ball trajectory angle (Theta)
        // Slingshot pull angle is strictly inverted: PullAngle = Theta + 180 degrees
        val pullAngleDeg = (shotAngleDeg + 180f) % 360f
        val pullAngleRad = Math.toRadians(pullAngleDeg.toDouble())

        // Calculate pull distance based on target puck distance:
        // Short pot (< 250px): Pull back 45-60px (Soft touch).
        // Long bank/rebound (> 500px): Pull back 110-135px (Full power).
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

        // Compute Drag End Point:
        // EndX = Xs + (pullDistance * cos(PullAngle))
        // EndY = Ys + (pullDistance * sin(PullAngle))
        val endX = (strikerPos.x + (pullDistance * cos(pullAngleRad))).toFloat()
        val endY = (strikerPos.y + (pullDistance * sin(pullAngleRad))).toFloat()

        // 2. Humanized Gesture Dispatch:
        // Dispatch the gesture using Path() with a subtle quadratic Bezier curve
        // (simulating natural human thumb drag) over a duration of 120-160ms.
        val path = Path().apply {
            moveTo(strikerPos.x, strikerPos.y)

            // Calculate subtle quadratic Bezier control point with slight natural perpendicular deviation (2-4px)
            val midX = (strikerPos.x + endX) / 2f
            val midY = (strikerPos.y + endY) / 2f

            // Perpendicular unit vector to the pull direction for realistic human thumb arc
            val perpAngleRad = pullAngleRad + (PI / 2.0)
            val thumbArchOffset = 3.5f
            val controlX = (midX + thumbArchOffset * cos(perpAngleRad)).toFloat()
            val controlY = (midY + thumbArchOffset * sin(perpAngleRad)).toFloat()

            quadTo(controlX, controlY, endX, endY)
        }

        // Duration: 120-160ms (or 80ms in Fast Mode)
        val gestureDuration = if (isFastMode) 80L else durationMs.coerceIn(120L, 160L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, gestureDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        isAutoPlayExecuting.value = true
        // 4. Cooldown Lock for 2.5 seconds
        triggerCooldownLock()

        lastExecutedShotInfo.value = "Auto Slingshot: Pull ${pullDistance.toInt()}px in ${gestureDuration}ms"

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                isAutoPlayExecuting.value = false
                Log.d(TAG, "Slingshot Auto-Strike executed cleanly at ($endX, $endY) in ${gestureDuration}ms.")
                mainHandler.post {
                    Toast.makeText(
                        this@AutoStrikeAccessibilityService,
                        "🎯 Slingshot Strike Fired! (${pullDistance.toInt()}px, ${gestureDuration}ms)",
                        Toast.LENGTH_SHORT
                    ).show()
                    onComplete?.invoke(true)
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                isAutoPlayExecuting.value = false
                Log.w(TAG, "Slingshot Auto-Strike gesture was cancelled.")
                mainHandler.post {
                    onComplete?.invoke(false)
                }
            }
        }, null)

        if (!dispatched) {
            isAutoPlayExecuting.value = false
            Log.e(TAG, "Failed to dispatch slingshot auto-strike gesture.")
            mainHandler.post {
                onComplete?.invoke(false)
            }
        }
    }

    /**
     * Executes the one-tap inverted slingshot gesture:
     * - Reverse slingshot vector: angle = target_angle + 180 degrees, pull distance = 160px.
     * - Starts at Striker (x, y) -> drags backward to (pull_x, pull_y) over 100ms -> release (ACTION_UP).
     * - Zero delay and zero CPU throttling.
     */
    fun executeReverseSlingshot(
        strikerPos: PointF,
        ghostPoint: PointF,
        pullDistancePx: Float = 160f,
        durationMs: Long = 100L,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "GestureDescription requires Android 7.0 (API 24)+")
            onComplete?.invoke(false)
            return
        }

        // Calculate target forward angle
        val dx = ghostPoint.x - strikerPos.x
        val dy = ghostPoint.y - strikerPos.y
        val targetAngleRad = atan2(dy.toDouble(), dx.toDouble())

        // Inverted slingshot: angle = target_angle + 180 degrees (PI radians)
        val pullAngleRad = targetAngleRad + PI
        val pullX = (strikerPos.x + pullDistancePx * cos(pullAngleRad)).toFloat()
        val pullY = (strikerPos.y + pullDistancePx * sin(pullAngleRad)).toFloat()

        val path = Path().apply {
            moveTo(strikerPos.x, strikerPos.y)
            lineTo(pullX, pullY)
        }

        val gestureDuration = durationMs.coerceAtLeast(60L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, gestureDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        isAutoPlayExecuting.value = true
        lastExecutedShotInfo.value = "Reverse Slingshot: 160px pull in ${gestureDuration}ms"

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                isAutoPlayExecuting.value = false
                Log.d(TAG, "One-Tap Reverse Slingshot executed to ($pullX, $pullY) in ${gestureDuration}ms.")
                mainHandler.post {
                    Toast.makeText(
                        this@AutoStrikeAccessibilityService,
                        "⚡ Slingshot Strike Dispatched (160px, ${gestureDuration}ms)!",
                        Toast.LENGTH_SHORT
                    ).show()
                    onComplete?.invoke(true)
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                isAutoPlayExecuting.value = false
                Log.w(TAG, "Reverse Slingshot gesture cancelled.")
                mainHandler.post {
                    onComplete?.invoke(false)
                }
            }
        }, null)

        if (!dispatched) {
            isAutoPlayExecuting.value = false
            Log.e(TAG, "Failed to dispatch reverse slingshot gesture.")
            mainHandler.post {
                onComplete?.invoke(false)
            }
        }
    }
}
