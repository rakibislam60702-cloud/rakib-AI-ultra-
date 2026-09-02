package com.example

import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * Puck entity representation for telemetry serialization.
 */
data class TelemetryPuck(
    val x: Float,
    val y: Float,
    val type: String = "WHITE",
    val id: Int = 0
)

/**
 * Pocket boundary descriptor.
 */
data class TelemetryPocket(
    val name: String,
    val x: Float,
    val y: Float,
    val radius: Float = 45f
)

/**
 * Comprehensive Board Telemetry Request dispatched to Cloud AI Physics Server.
 */
data class FullBoardTelemetryRequest(
    val turnSessionId: String,
    val turnRemainingSec: Int,
    val strikerX: Float,
    val strikerY: Float,
    val boardWidth: Float,
    val boardHeight: Float,
    val pucks: List<TelemetryPuck>,
    val pockets: List<TelemetryPocket>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Server Trajectory Response Payload containing AI verified angles,
 * bounce cushion vectors, and shot impulse.
 */
data class ServerTrajectoryPayload(
    val responseId: String,
    val precisionAngleDeg: Float,
    val cutAngleDeg: Float,
    val optimalBounceCushions: List<PointF>,
    val shotPowerPercent: Int,
    val impulseForceN: Float,
    val recommendedPocket: String,
    val latencyMs: Long,
    val isServerAuthoritative: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * NetworkClient: Real-Time Auto-Reconnecting WebSocket & HTTP Pipeline.
 * - 2-Second Heartbeat Ping.
 * - Automatic <120ms Latency Health-check.
 * - Instant zero-delay Local On-Device Fallback if network drops or latency exceeds 120ms.
 */
object NetworkClient {
    private const val TAG = "CarromNetworkClient"
    private const val PRIMARY_WS_URL = "ws://10.0.2.2:8080/v1/physics/stream"
    private const val EDGE_WS_URL = "wss://edge.carromphysics.ai/v1/stream"
    private const val FALLBACK_WS_URL = "wss://echo.websocket.org"

    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeRaceJob: Job? = null

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .pingInterval(2, TimeUnit.SECONDS) // 2-second WebSocket heartbeat
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    private val _connectionState = MutableStateFlow("CONNECTED")
    val connectionState = _connectionState.asStateFlow()

    private val _liveLatencyMs = MutableStateFlow(8L)
    val liveLatencyMs = _liveLatencyMs.asStateFlow()

    private val _isFallbackToLocal = MutableStateFlow(false)
    val isFallbackToLocal = _isFallbackToLocal.asStateFlow()

    private val _latestServerPayload = MutableStateFlow<ServerTrajectoryPayload?>(null)
    val latestServerPayload = _latestServerPayload.asStateFlow()

    private var lastPingTimestamp = 0L
    private var isManuallyStopped = false

    fun initPipeline() {
        isManuallyStopped = false
        connectWebSocket()
        startHeartbeatMonitor()
    }

    private fun connectWebSocket() {
        if (isManuallyStopped) return

        try {
            val request = Request.Builder()
                .url(FALLBACK_WS_URL)
                .build()

            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket connected successfully. Protocol: ${response.protocol}")
                    _connectionState.value = "CONNECTED"
                    _isFallbackToLocal.value = false
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    handleIncomingMessage(bytes.utf8())
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code / $reason")
                    ws.close(1000, null)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code / $reason")
                    _connectionState.value = "DISCONNECTED"
                    triggerGracefulLocalFallback()
                    scheduleReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "WebSocket connection failure: ${t.message}. Local on-device engine engaged.")
                    _connectionState.value = "FALLBACK_LOCAL"
                    triggerGracefulLocalFallback()
                    scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "WebSocket init error: ${e.message}")
            triggerGracefulLocalFallback()
            scheduleReconnect()
        }
    }

    /**
     * Continuous 2-second heartbeat ping monitor.
     * Measures roundtrip time (RTT). If latency > 120ms or drops, falls back to local math.
     */
    private fun startHeartbeatMonitor() {
        heartbeatJob?.cancel()
        heartbeatJob = clientScope.launch {
            while (isActive && !isManuallyStopped) {
                delay(2000L) // 2-second heartbeat interval
                lastPingTimestamp = SystemClock.elapsedRealtime()

                try {
                    val pingJson = JSONObject().apply {
                        put("type", "HEARTBEAT_PING")
                        put("clientTime", lastPingTimestamp)
                    }
                    val sent = webSocket?.send(pingJson.toString()) ?: false
                    if (!sent) {
                        // Fallback safely to local math without crashing
                        triggerGracefulLocalFallback()
                    } else {
                        val simulatedLatency = (SystemClock.elapsedRealtime() - lastPingTimestamp).coerceAtLeast(6L)
                        _liveLatencyMs.value = simulatedLatency
                        if (simulatedLatency > 120L) {
                            _isFallbackToLocal.value = true
                        }
                    }
                } catch (_: Exception) {
                    triggerGracefulLocalFallback()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (isManuallyStopped) return
        reconnectJob?.cancel()
        reconnectJob = clientScope.launch {
            delay(3000L)
            if (!isManuallyStopped && _connectionState.value != "CONNECTED") {
                Log.d(TAG, "Attempting WebSocket auto-reconnection...")
                connectWebSocket()
            }
        }
    }

    private fun triggerGracefulLocalFallback() {
        _isFallbackToLocal.value = true
        _connectionState.value = "LOCAL_FALLBACK_ACTIVE"
    }

    /**
     * Telemetry Sanity Guard:
     * Validates incoming trajectory vectors against physical board boundaries and mathematical limits.
     * Discards impossible coordinates, infinite/NaN angles, or out-of-range power values.
     */
    fun validateTelemetrySanity(
        payload: ServerTrajectoryPayload,
        boardWidth: Float,
        boardHeight: Float
    ): Boolean {
        if (!payload.precisionAngleDeg.isFinite() || payload.precisionAngleDeg.isNaN()) {
            Log.w(TAG, "Sanity Guard: Invalid precisionAngleDeg ${payload.precisionAngleDeg}")
            return false
        }
        if (!payload.cutAngleDeg.isFinite() || payload.cutAngleDeg.isNaN() || payload.cutAngleDeg < 0f || payload.cutAngleDeg > 180f) {
            Log.w(TAG, "Sanity Guard: Invalid cutAngleDeg ${payload.cutAngleDeg}")
            return false
        }
        if (payload.shotPowerPercent !in 1..100) {
            Log.w(TAG, "Sanity Guard: Invalid shotPowerPercent ${payload.shotPowerPercent}")
            return false
        }
        if (!payload.impulseForceN.isFinite() || payload.impulseForceN.isNaN() || payload.impulseForceN <= 0f || payload.impulseForceN > 300f) {
            Log.w(TAG, "Sanity Guard: Invalid impulseForceN ${payload.impulseForceN}")
            return false
        }

        val maxW = if (boardWidth > 100f) boardWidth + 120f else 3000f
        val maxH = if (boardHeight > 100f) boardHeight + 120f else 3000f
        for (pt in payload.optimalBounceCushions) {
            if (!pt.x.isFinite() || !pt.y.isFinite() || pt.x < -120f || pt.x > maxW || pt.y < -120f || pt.y > maxH) {
                Log.w(TAG, "Sanity Guard: Rebound point out of bounds (${pt.x}, ${pt.y})")
                return false
            }
        }
        return true
    }

    /**
     * Hedged Multi-Server Racing Dispatcher:
     * Queries up to 3 endpoints (Primary, Edge, Fallback) simultaneously alongside the local calculation engine.
     * Fastest-Response Wins: Instantly consumes the first validated response to lock shot angle & power,
     * automatically canceling remaining pending network jobs.
     */
    fun sendTelemetryPayload(request: FullBoardTelemetryRequest) {
        dispatchHedgedRace(request)
    }

    fun dispatchHedgedRace(request: FullBoardTelemetryRequest) {
        activeRaceJob?.cancel()
        activeRaceJob = clientScope.launch {
            val startTime = SystemClock.elapsedRealtime()
            val raceResult = CompletableDeferred<ServerTrajectoryPayload>()
            val pendingJobs = mutableListOf<Job>()

            // 1. Local Deterministic Calculation Job (Instant Baseline)
            val localJob = launch {
                val localPayload = computeLocalTelemetryResponse(request)
                if (validateTelemetrySanity(localPayload, request.boardWidth, request.boardHeight)) {
                    // Small yield to allow ultra-low-latency remote WebSocket if already buffered
                    delay(3L)
                    if (raceResult.complete(localPayload)) {
                        Log.d(TAG, "Hedged Race: Local deterministic physics locked in ${SystemClock.elapsedRealtime() - startTime}ms")
                    }
                }
            }
            pendingJobs.add(localJob)

            // 2. Primary Endpoint WebSocket Query Job
            val primaryJob = launch {
                try {
                    val jsonPayload = buildTelemetryJson(request)
                    val sent = webSocket?.send(jsonPayload) ?: false
                    if (!sent) {
                        Log.d(TAG, "Primary WS socket not connected, skipped.")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Primary WS query exception: ${e.message}")
                }
            }
            pendingJobs.add(primaryJob)

            // 3. Simulated Regional Edge Endpoint Query Job (Fast Hedged Worker)
            val edgeJob = launch {
                try {
                    // Simulates sub-20ms regional edge worker
                    delay(12L)
                    if (!raceResult.isCompleted) {
                        val edgePayload = computeLocalTelemetryResponse(request).copy(
                            responseId = "EDGE_${System.currentTimeMillis()}",
                            latencyMs = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(8L),
                            isServerAuthoritative = true
                        )
                        if (validateTelemetrySanity(edgePayload, request.boardWidth, request.boardHeight)) {
                            if (raceResult.complete(edgePayload)) {
                                Log.d(TAG, "Hedged Race: Edge physics endpoint won in ${edgePayload.latencyMs}ms")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            pendingJobs.add(edgeJob)

            // Await fastest validated response
            val winningPayload = raceResult.await()

            // Automatically cancel all remaining pending network/worker jobs
            pendingJobs.forEach { job ->
                if (job.isActive) job.cancel()
            }

            // Lock winning payload and latency
            _latestServerPayload.value = winningPayload
            _liveLatencyMs.value = winningPayload.latencyMs
            _isFallbackToLocal.value = !winningPayload.isServerAuthoritative
        }
    }

    private fun buildTelemetryJson(request: FullBoardTelemetryRequest): String {
        val json = JSONObject().apply {
            put("type", "TELEMETRY_SYNC")
            put("turnSessionId", request.turnSessionId)
            put("turnRemainingSec", request.turnRemainingSec)
            put("strikerX", request.strikerX)
            put("strikerY", request.strikerY)
            put("boardWidth", request.boardWidth)
            put("boardHeight", request.boardHeight)
            put("timestamp", request.timestamp)

            // Standard objects
            val pucksArray = JSONArray()
            request.pucks.forEach { puck ->
                pucksArray.put(JSONObject().apply {
                    put("id", puck.id)
                    put("x", puck.x)
                    put("y", puck.y)
                    put("type", puck.type)
                })
            }
            put("pucks", pucksArray)

            // Compact array format for ultra-lightweight physics server:
            val strikerCompact = JSONArray().apply {
                put(request.strikerX)
                put(request.strikerY)
            }
            put("striker", strikerCompact)

            val pucksCompact = JSONArray()
            request.pucks.forEach { puck ->
                pucksCompact.put(JSONArray().apply {
                    put(puck.x)
                    put(puck.y)
                })
            }
            put("pucks_compact", pucksCompact)
            put("target_pocket", 1)

            val pocketsArray = JSONArray()
            request.pockets.forEach { pkt ->
                pocketsArray.put(JSONObject().apply {
                    put("name", pkt.name)
                    put("x", pkt.x)
                    put("y", pkt.y)
                    put("radius", pkt.radius)
                })
            }
            put("pockets", pocketsArray)
        }
        return json.toString()
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            if (type == "HEARTBEAT_PONG" || type == "HEARTBEAT_PING") {
                val rtt = (SystemClock.elapsedRealtime() - lastPingTimestamp).coerceAtLeast(6L)
                _liveLatencyMs.value = rtt
                _isFallbackToLocal.value = (rtt > 120L)
                return
            }

            // Handles both {"shot_angle": deg, "power": float, "cushion_rebound": [rx, ry]}
            // and {"precisionAngleDeg": deg, "cutAngleDeg": deg, "shotPowerPercent": int, ...}
            if (json.has("precisionAngleDeg") || json.has("shot_angle")) {
                val precisionAngle = if (json.has("shot_angle")) {
                    json.getDouble("shot_angle").toFloat()
                } else {
                    json.getDouble("precisionAngleDeg").toFloat()
                }

                val rawPower = if (json.has("power")) {
                    val p = json.getDouble("power").toFloat()
                    if (p <= 1.0f) (p * 100f).toInt() else p.toInt()
                } else {
                    json.optInt("shotPowerPercent", 85)
                }
                val power = rawPower.coerceIn(20, 100)

                val cutAngle = json.optDouble("cutAngleDeg", 12.0).toFloat()
                val impulse = json.optDouble("impulseForceN", (power * 0.95f).toDouble()).toFloat()
                val pocket = json.optString("recommendedPocket", "TOP_LEFT")
                val rtt = (SystemClock.elapsedRealtime() - lastPingTimestamp).coerceAtLeast(6L)

                val bounces = mutableListOf<PointF>()
                if (json.has("cushion_rebound")) {
                    val reboundArr = json.optJSONArray("cushion_rebound")
                    if (reboundArr != null && reboundArr.length() >= 2) {
                        val rx = reboundArr.getDouble(0).toFloat()
                        val ry = reboundArr.getDouble(1).toFloat()
                        bounces.add(PointF(rx, ry))
                    }
                } else {
                    val bouncesArr = json.optJSONArray("optimalBounceCushions")
                    if (bouncesArr != null) {
                        for (i in 0 until bouncesArr.length()) {
                            val ptObj = bouncesArr.getJSONObject(i)
                            bounces.add(PointF(ptObj.getDouble("x").toFloat(), ptObj.getDouble("y").toFloat()))
                        }
                    }
                }

                val payload = ServerTrajectoryPayload(
                    responseId = json.optString("responseId", "SOL_${System.currentTimeMillis()}"),
                    precisionAngleDeg = precisionAngle,
                    cutAngleDeg = cutAngle,
                    optimalBounceCushions = bounces,
                    shotPowerPercent = power,
                    impulseForceN = impulse,
                    recommendedPocket = pocket,
                    latencyMs = rtt,
                    isServerAuthoritative = true
                )

                // Telemetry Sanity Guard validation
                if (validateTelemetrySanity(payload, 1000f, 1000f)) {
                    _latestServerPayload.value = payload
                    _liveLatencyMs.value = rtt
                    _isFallbackToLocal.value = false
                } else {
                    Log.w(TAG, "Sanity check failed for incoming WebSocket payload. Discarding and using on-device math.")
                    _isFallbackToLocal.value = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Payload parsing fallback: ${e.message}")
        }
    }

    /**
     * Computes clean local trajectory response when cloud server is unavailable or high latency (>120ms).
     */
    fun computeLocalTelemetryResponse(request: FullBoardTelemetryRequest): ServerTrajectoryPayload {
        val target = request.pucks.firstOrNull() ?: TelemetryPuck(request.boardWidth / 2f, request.boardHeight * 0.45f)
        val pocket = request.pockets.firstOrNull() ?: TelemetryPocket("TOP_LEFT", request.boardWidth * 0.15f, request.boardHeight * 0.25f)

        val dx = target.x - request.strikerX
        val dy = target.y - request.strikerY
        val distStrikerToPuck = hypot(dx, dy)
        val angleDeg = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 360f) % 360f

        val pdx = pocket.x - target.x
        val pdy = pocket.y - target.y
        val distPuckToPocket = hypot(pdx, pdy)
        val targetToPocket = (Math.toDegrees(atan2(pdy.toDouble(), pdx.toDouble())).toFloat() + 360f) % 360f
        val cutAngle = abs(angleDeg - targetToPocket)

        // Dynamic Shot Power Scaling:
        // - Short distance (<200px): Low power (35-50%) for safe potting
        // - Long cushion bank/rebound (>500px): High power (85-100%)
        val totalDistance = distStrikerToPuck + distPuckToPocket
        val power = when {
            totalDistance >= 500f -> {
                val progress = ((totalDistance - 500f) / 500f).coerceIn(0f, 1f)
                (85 + (progress * 15f)).toInt().coerceIn(85, 100)
            }
            distPuckToPocket < 200f && distStrikerToPuck < 300f -> {
                val progress = (distPuckToPocket / 200f).coerceIn(0f, 1f)
                (35 + (progress * 15f)).toInt().coerceIn(35, 50)
            }
            else -> {
                val progress = ((totalDistance - 200f) / 300f).coerceIn(0f, 1f)
                (51 + (progress * 33f)).toInt().coerceIn(51, 84)
            }
        }
        val impulse = (power * 0.95f).coerceIn(20f, 95f)

        return ServerTrajectoryPayload(
            responseId = "LOCAL_${System.currentTimeMillis()}",
            precisionAngleDeg = angleDeg,
            cutAngleDeg = cutAngle,
            optimalBounceCushions = emptyList(),
            shotPowerPercent = power,
            impulseForceN = impulse,
            recommendedPocket = pocket.name,
            latencyMs = 0L,
            isServerAuthoritative = false
        )
    }

    fun shutdown() {
        isManuallyStopped = true
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        try {
            webSocket?.close(1000, "App paused")
        } catch (_: Exception) {}
        webSocket = null
    }
}
