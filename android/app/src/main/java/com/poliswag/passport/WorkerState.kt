package com.poliswag.passport

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

enum class ConnectionStatus(val label: String) {
    STOPPED("Stopped"),
    CONNECTING("Connecting..."),
    CONNECTED("Connected"),
    DISCONNECTED("Disconnected"),
    REPLACED("Replaced")
}

data class ChallengeSession(
    val jobId: String,
    val username: String,
    val webView: WebView,
    val onAttachContext: (android.content.Context) -> Unit,
    val onDetachContext: () -> Unit
)

data class WorkerSnapshot(
    val status: ConnectionStatus,
    val statusDetail: String,
    val isServiceRunning: Boolean,
    val activeJobLabel: String?,
    val queuedCount: Int,
    val logs: List<String>
)

object WorkerState {
    private const val LOG_CAP = 200
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var status: ConnectionStatus = ConnectionStatus.STOPPED
    private var statusDetail: String = ""
    private var isServiceRunning: Boolean = false
    private var activeJobLabel: String? = null
    private var queuedCount: Int = 0
    private val logEntries = ArrayDeque<String>(LOG_CAP)

    private var challengeSession: ChallengeSession? = null

    private val stateListeners = CopyOnWriteArraySet<(WorkerSnapshot) -> Unit>()
    private val challengeListeners = CopyOnWriteArraySet<(ChallengeSession?) -> Unit>()

    fun snapshot(): WorkerSnapshot = synchronized(lock) {
        WorkerSnapshot(
            status = status,
            statusDetail = statusDetail,
            isServiceRunning = isServiceRunning,
            activeJobLabel = activeJobLabel,
            queuedCount = queuedCount,
            logs = logEntries.toList()
        )
    }

    fun currentChallenge(): ChallengeSession? = synchronized(lock) { challengeSession }

    fun addStateListener(listener: (WorkerSnapshot) -> Unit) {
        stateListeners.add(listener)
        val snap = snapshot()
        mainHandler.post { listener(snap) }
    }

    fun removeStateListener(listener: (WorkerSnapshot) -> Unit) {
        stateListeners.remove(listener)
    }

    fun addChallengeListener(listener: (ChallengeSession?) -> Unit) {
        challengeListeners.add(listener)
        val current = currentChallenge()
        mainHandler.post { listener(current) }
    }

    fun removeChallengeListener(listener: (ChallengeSession?) -> Unit) {
        challengeListeners.remove(listener)
    }

    fun updateConnection(
        newStatus: ConnectionStatus,
        detail: String = "",
        running: Boolean? = null
    ) {
        val snap = synchronized(lock) {
            status = newStatus
            statusDetail = detail
            if (running != null) {
                isServiceRunning = running
            }
            snapshotLocked()
        }
        notifyState(snap)
    }

    fun updateJobs(activeLabel: String?, queued: Int) {
        val snap = synchronized(lock) {
            activeJobLabel = activeLabel
            queuedCount = queued
            snapshotLocked()
        }
        notifyState(snap)
    }

    fun log(message: String) {
        val snap = synchronized(lock) {
            val ts = timeFormat.format(Date())
            logEntries.addFirst("$ts  $message")
            while (logEntries.size > LOG_CAP) {
                logEntries.removeLast()
            }
            snapshotLocked()
        }
        notifyState(snap)
    }

    fun clearLogs() {
        val snap = synchronized(lock) {
            logEntries.clear()
            snapshotLocked()
        }
        notifyState(snap)
    }

    fun setChallengeSession(session: ChallengeSession?) {
        synchronized(lock) {
            challengeSession = session
        }
        mainHandler.post {
            for (listener in challengeListeners) {
                listener(session)
            }
        }
    }

    fun clearChallengeSession(jobId: String) {
        var changed = false
        synchronized(lock) {
            if (challengeSession?.jobId == jobId) {
                challengeSession = null
                changed = true
            }
        }
        if (changed) {
            mainHandler.post {
                for (listener in challengeListeners) {
                    listener(null)
                }
            }
        }
    }

    private fun snapshotLocked(): WorkerSnapshot {
        return WorkerSnapshot(
            status = status,
            statusDetail = statusDetail,
            isServiceRunning = isServiceRunning,
            activeJobLabel = activeJobLabel,
            queuedCount = queuedCount,
            logs = logEntries.toList()
        )
    }

    private fun notifyState(snap: WorkerSnapshot) {
        mainHandler.post {
            for (listener in stateListeners) {
                listener(snap)
            }
        }
    }
}
