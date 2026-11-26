package com.poliswag.passport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class WorkerService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: WorkerPreferences
    private lateinit var notificationManager: NotificationManager
    private lateinit var okHttpClient: OkHttpClient
    private var wakeLock: PowerManager.WakeLock? = null

    private var webSocket: WebSocket? = null
    private var isStopping = false
    private var replaced = false
    private var backoffMs = INITIAL_BACKOFF_MS

    private val pendingResults = ArrayDeque<String>()
    private val jobQueue = ArrayDeque<IncomingMessage.Job>()
    private var activeRunner: WebViewJobRunner? = null
    private var activeJobStartedAt: Long = 0L

    private val reconnectRunnable = Runnable { connectWebSocket() }

    override fun onCreate() {
        super.onCreate()
        prefs = WorkerPreferences(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        okHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // Our own pings: without them a half-open socket (Wi-Fi to LTE, NAT
            // timeout) looks connected forever and never reconnects.
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        createNotificationChannels()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PoliswagPassport:JobWakeLock").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWorker()
            return START_NOT_STICKY
        }

        isStopping = false
        replaced = false
        backoffMs = INITIAL_BACKOFF_MS
        prefs.autoStart = true // start again after a reboot, until the user presses Stop
        if (!android.provider.Settings.canDrawOverlays(this)) {
            WorkerState.log("tip: allow \"Display over other apps\" so login pages count as visible (fewer bot checks)")
        }

        val notification = buildStatusNotification("Connecting...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                STATUS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(STATUS_NOTIFICATION_ID, notification)
        }

        WorkerState.updateConnection(ConnectionStatus.CONNECTING, "", running = true)
        connectWebSocket()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isStopping = true
        mainHandler.removeCallbacks(reconnectRunnable)
        closeWebSocket()
        cancelActiveJobOnly()
        jobQueue.clear()
        releaseWakeLock()
        dismissChallengeNotification()
        if (!replaced) {
            WorkerState.updateConnection(ConnectionStatus.STOPPED, "", running = false)
        }
        WorkerState.updateJobs(null, 0)
        okHttpClient.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    private fun stopWorker() {
        isStopping = true
        prefs.autoStart = false
        WorkerState.log("worker stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun connectWebSocket() {
        if (isStopping || replaced) return
        mainHandler.removeCallbacks(reconnectRunnable)
        closeWebSocket()

        val helperUrl = prefs.helperUrl
        val token = prefs.workerToken
        val workerId = prefs.workerId

        val wsUrl = try {
            Protocol.buildWebSocketUrl(helperUrl, workerId)
        } catch (e: Exception) {
            val msg = e.message ?: "invalid helper URL"
            WorkerState.log("cannot connect: $msg")
            WorkerState.updateConnection(ConnectionStatus.DISCONNECTED, msg, running = false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        WorkerState.updateConnection(ConnectionStatus.CONNECTING, "", running = true)
        updateStatusNotification("Connecting to helper...")

        val requestBuilder = Request.Builder().url(wsUrl)
        if (token.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        webSocket = okHttpClient.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post {
                    if (webSocket !== this@WorkerService.webSocket || isStopping) return@post
                    backoffMs = INITIAL_BACKOFF_MS
                    WorkerState.updateConnection(ConnectionStatus.CONNECTED, "", running = true)
                    WorkerState.log("connected to helper (${helperUrl.trim()})")
                    updateStatusNotification(currentNotificationSummary())
                    flushPendingResults()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                mainHandler.post {
                    if (webSocket !== this@WorkerService.webSocket || isStopping) return@post
                    handleIncomingMessage(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Reply with a plain close: echoing codes like 1005 (no status) throws.
                webSocket.close(1000, null)
                mainHandler.post {
                    if (webSocket !== this@WorkerService.webSocket) return@post
                    handleSocketClosed(code, reason)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mainHandler.post {
                    if (webSocket !== this@WorkerService.webSocket) return@post
                    handleSocketClosed(code, reason)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainHandler.post {
                    if (webSocket !== this@WorkerService.webSocket) return@post
                    handleSocketFailure(Protocol.describeConnectFailure(response?.code, t))
                }
            }
        })
    }

    private fun handleSocketClosed(code: Int, reason: String) {
        webSocket = null
        if (isStopping) return

        if (code == Protocol.CLOSE_CODE_REPLACED) {
            replaced = true
            isStopping = true
            mainHandler.removeCallbacks(reconnectRunnable)
            cancelActiveJobOnly()
            jobQueue.clear()
            releaseWakeLock()
            dismissChallengeNotification()
            val detail = "another connection with this worker ID took over"
            WorkerState.updateConnection(ConnectionStatus.REPLACED, detail, running = false)
            WorkerState.updateJobs(null, 0)
            WorkerState.log(detail)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val detail = if (reason.isNotBlank()) "closed ($code: $reason)" else "closed ($code)"
        scheduleReconnect(detail)
    }

    private fun handleSocketFailure(detail: String) {
        webSocket = null
        if (isStopping || replaced) return
        scheduleReconnect(detail)
    }

    private fun scheduleReconnect(reason: String) {
        val delaySec = backoffMs / 1000L
        WorkerState.updateConnection(
            ConnectionStatus.DISCONNECTED,
            "retrying in ${delaySec}s ($reason)",
            running = true
        )
        WorkerState.log("disconnected ($reason), retrying in ${delaySec}s")
        updateStatusNotification("Disconnected — retrying in ${delaySec}s")
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, backoffMs)
        backoffMs = minOf(backoffMs * 2L, MAX_BACKOFF_MS)
    }

    private fun closeWebSocket() {
        val ws = webSocket
        webSocket = null
        try {
            ws?.close(1000, "stopping")
        } catch (_: Exception) {
        }
    }

    private fun handleIncomingMessage(text: String) {
        when (val msg = Protocol.parseIncoming(text)) {
            is IncomingMessage.Job -> enqueueJob(msg)
            is IncomingMessage.Cancel -> cancelJob(msg.id, msg.status)
            null -> Unit
        }
    }

    private fun enqueueJob(job: IncomingMessage.Job) {
        val check = UrlUtils.validateJobUrl(job.url)
        if (!check.ok) {
            sendResultPayload(Protocol.serializeResult(job.id, Protocol.STATUS_ERROR))
            WorkerState.log("job ${Protocol.shortId(job.id)} ${job.username.ifEmpty { "unknown" }} -> ERROR (${check.reason})")
            return
        }
        jobQueue.addLast(job)
        if (activeRunner != null) {
            WorkerState.log("job ${Protocol.shortId(job.id)} ${job.username} queued (position ${jobQueue.size})")
        }
        updateJobState()
        pumpQueue()
    }

    private fun pumpQueue() {
        if (isStopping || replaced) return
        if (activeRunner != null) return
        val next = jobQueue.removeFirstOrNull()
        if (next == null) {
            releaseWakeLock()
            updateJobState()
            updateStatusNotification(currentNotificationSummary())
            return
        }

        acquireWakeLock()
        activeJobStartedAt = System.currentTimeMillis()
        val proxy = prefs.nextProxy()
        val via = if (proxy != null) " via proxy ${proxy.label}" else ""
        WorkerState.log("job ${Protocol.shortId(next.id)} for ${next.username} — starting$via")

        val runner = WebViewJobRunner(
            context = this,
            job = next,
            proxy = proxy,
            onResult = { jobId, status, loginCode, reason, lastState ->
                handleJobFinished(jobId, next.username, status, loginCode, reason, lastState)
            },
            onChallenge = { jobId, session ->
                handleJobChallenge(jobId, session)
            },
            onStateChange = { jobId, state ->
                if (state != "success" && state != "challenge") {
                    WorkerState.log("job ${Protocol.shortId(jobId)} ${next.username} — page: $state")
                }
            }
        )
        activeRunner = runner
        updateJobState()
        updateStatusNotification(currentNotificationSummary())
        runner.start()
    }

    private fun handleJobFinished(
        jobId: String,
        username: String,
        status: String,
        loginCode: String?,
        reason: String?,
        lastState: String?
    ) {
        if (activeRunner?.job?.id != jobId) return
        activeRunner = null
        dismissChallengeNotification()
        releaseWakeLock()

        val payload = Protocol.serializeResult(
            id = jobId,
            status = status,
            loginCode = if (status == Protocol.STATUS_SUCCESS) loginCode else null
        )
        sendResultPayload(payload)

        val why = when {
            !reason.isNullOrBlank() -> " ($reason)"
            status != Protocol.STATUS_SUCCESS && !lastState.isNullOrBlank() -> " (last page: $lastState)"
            else -> ""
        }
        WorkerState.log("job ${Protocol.shortId(jobId)} $username -> $status in ${formatElapsed(activeJobStartedAt)}$why")
        updateJobState()
        pumpQueue()
    }

    private fun handleJobChallenge(jobId: String, session: ChallengeSession) {
        if (activeRunner?.job?.id != jobId) return
        WorkerState.setChallengeSession(session)
        WorkerState.log("job ${Protocol.shortId(jobId)} ${session.username} — challenge after ${formatElapsed(activeJobStartedAt)}")
        try {
            webSocket?.send(Protocol.serializeChallenge(jobId))
        } catch (_: Exception) {
        }
        postChallengeNotification(session.username)
    }

    private fun cancelJob(id: String, status: String) {
        val current = activeRunner
        if (current != null && current.job.id == id) {
            val lastState = current.lastState
            val username = current.job.username
            activeRunner = null
            current.cancel()
            dismissChallengeNotification()
            releaseWakeLock()
            val last = if (!lastState.isNullOrBlank()) " (last page: $lastState)" else ""
            WorkerState.log("job ${Protocol.shortId(id)} $username -> $status in ${formatElapsed(activeJobStartedAt)} (server cancelled)$last")
            updateJobState()
            pumpQueue()
            return
        }

        val iterator = jobQueue.iterator()
        while (iterator.hasNext()) {
            val queued = iterator.next()
            if (queued.id == id) {
                iterator.remove()
                WorkerState.log("job ${Protocol.shortId(id)} ${queued.username} -> $status (removed from queue)")
                updateJobState()
                return
            }
        }
    }

    private fun cancelActiveJobOnly() {
        val current = activeRunner
        activeRunner = null
        current?.cancel()
    }

    private fun sendResultPayload(payload: String) {
        val ws = webSocket
        if (ws != null && ws.send(payload)) {
            return
        }
        pendingResults.addLast(payload)
    }

    private fun flushPendingResults() {
        val ws = webSocket ?: return
        while (pendingResults.isNotEmpty()) {
            val payload = pendingResults.first()
            if (ws.send(payload)) {
                pendingResults.removeFirst()
            } else {
                break
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            val wl = wakeLock ?: return
            if (!wl.isHeld) {
                wl.acquire(WAKELOCK_TIMEOUT_MS)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            val wl = wakeLock ?: return
            if (wl.isHeld) {
                wl.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun updateJobState() {
        val active = activeRunner?.job
        val label = active?.let { "${it.username} (${Protocol.shortId(it.id)})" }
        WorkerState.updateJobs(label, jobQueue.size)
    }

    private fun currentNotificationSummary(): String {
        val active = activeRunner?.job
        return when {
            active != null && jobQueue.isNotEmpty() ->
                "Running: ${active.username} (+${jobQueue.size} queued)"
            active != null ->
                "Running: ${active.username}"
            else ->
                "Connected — waiting for jobs"
        }
    }

    private fun createNotificationChannels() {
        val statusChannel = NotificationChannel(
            CHANNEL_STATUS,
            "Worker Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent status of the Poliswag Passport worker"
        }
        val challengeChannel = NotificationChannel(
            CHANNEL_CHALLENGE,
            "PTC Challenges",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a PTC login needs manual captcha solving"
        }
        notificationManager.createNotificationChannel(statusChannel)
        notificationManager.createNotificationChannel(challengeChannel)
    }

    private fun buildStatusNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateStatusNotification(text: String) {
        if (isStopping || replaced) return
        notificationManager.notify(STATUS_NOTIFICATION_ID, buildStatusNotification(text))
    }

    private fun postChallengeNotification(username: String) {
        val challengeIntent = Intent(this, ChallengeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            challengeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_CHALLENGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("PTC challenge needs attention")
            .setContentText("Tap to solve captcha for $username")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager.notify(CHALLENGE_NOTIFICATION_ID, notification)
    }

    private fun dismissChallengeNotification() {
        notificationManager.cancel(CHALLENGE_NOTIFICATION_ID)
    }

    private fun formatElapsed(startedAt: Long): String {
        val seconds = maxOf(0L, (System.currentTimeMillis() - startedAt) / 1000L)
        return if (seconds < 60L) "${seconds}s" else "${seconds / 60L}m ${seconds % 60L}s"
    }

    companion object {
        const val ACTION_START = "com.poliswag.passport.ACTION_START"
        const val ACTION_STOP = "com.poliswag.passport.ACTION_STOP"

        private const val CHANNEL_STATUS = "worker_status"
        private const val CHANNEL_CHALLENGE = "challenge_alerts"
        private const val STATUS_NOTIFICATION_ID = 1001
        private const val CHALLENGE_NOTIFICATION_ID = 1002

        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val WAKELOCK_TIMEOUT_MS = 11L * 60L * 1000L
    }
}
