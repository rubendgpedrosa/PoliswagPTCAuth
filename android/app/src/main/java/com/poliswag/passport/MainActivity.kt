package com.poliswag.passport

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: WorkerPreferences
    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var proxiesInput: EditText
    private lateinit var overlayButton: Button
    private lateinit var workerIdText: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var statusDetailText: TextView
    private lateinit var jobInfoText: TextView
    private lateinit var startStopButton: Button
    private lateinit var batteryButton: Button
    private lateinit var openChallengeButton: Button
    private lateinit var logText: TextView

    private var isRunning = false

    private val stateListener: (WorkerSnapshot) -> Unit = { snap ->
        renderState(snap)
    }

    private val challengeListener: (ChallengeSession?) -> Unit = { session ->
        openChallengeButton.visibility = if (session != null) View.VISIBLE else View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = WorkerPreferences(this)

        setContentView(buildUi())

        urlInput.setText(prefs.helperUrl)
        tokenInput.setText(prefs.workerToken)
        proxiesInput.setText(prefs.proxies)
        workerIdText.text = prefs.workerId

        requestNotificationsPermissionIfNeeded()
        updateBatteryButton()
        updateOverlayButton()
    }

    override fun onStart() {
        super.onStart()
        WorkerState.addStateListener(stateListener)
        WorkerState.addChallengeListener(challengeListener)
        updateBatteryButton()
        updateOverlayButton()
    }

    override fun onStop() {
        WorkerState.removeStateListener(stateListener)
        WorkerState.removeChallengeListener(challengeListener)
        saveInputs()
        super.onStop()
    }

    private fun buildUi(): View {
        window.statusBarColor = Ui.BACKGROUND
        window.navigationBarColor = Ui.BACKGROUND

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // Header: app name, then the status card.
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTextColor(Ui.TEXT)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "PTC login worker"
            textSize = 13f
            setTextColor(Ui.TEXT_MUTED)
        })

        val statusCard = Ui.card(this)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = Ui.dot(this, Ui.TEXT_SUBTLE)
        statusRow.addView(statusDot)
        statusText = TextView(this).apply {
            text = "Stopped"
            textSize = 17f
            setTextColor(Ui.TEXT)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        statusRow.addView(statusText)
        statusCard.addView(statusRow)
        statusDetailText = TextView(this).apply {
            textSize = 13f
            setTextColor(Ui.TEXT_MUTED)
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        statusCard.addView(statusDetailText)
        jobInfoText = TextView(this).apply {
            textSize = 13f
            setTextColor(Ui.GOLD)
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        statusCard.addView(jobInfoText)

        startStopButton = Button(this).apply {
            text = "Start"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
            setOnClickListener { toggleWorker() }
        }
        Ui.styleButton(startStopButton, Ui.ButtonKind.PRIMARY)
        statusCard.addView(startStopButton)

        openChallengeButton = Button(this).apply {
            text = "Open captcha to solve"
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ChallengeActivity::class.java))
            }
        }
        Ui.styleButton(openChallengeButton, Ui.ButtonKind.SECONDARY)
        openChallengeButton.setTextColor(Ui.GOLD)
        statusCard.addView(openChallengeButton)
        root.addView(statusCard)

        // Connection settings.
        val helperCard = Ui.card(this)
        helperCard.addView(Ui.label(this, "Helper address"))
        urlInput = EditText(this).apply {
            hint = "ws://192.168.1.10:5090/ws"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        Ui.styleField(urlInput)
        helperCard.addView(urlInput)

        helperCard.addView(Ui.label(this, "Worker token"))
        tokenInput = EditText(this).apply {
            hint = "WORKER_TOKEN from the helper"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        Ui.styleField(tokenInput)
        helperCard.addView(tokenInput)

        helperCard.addView(Ui.label(this, "Proxies (optional, one per line)"))
        proxiesInput = EditText(this).apply {
            hint = "host:port:user:pass"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = false
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
        }
        Ui.styleField(proxiesInput)
        proxiesInput.textSize = 13f
        helperCard.addView(proxiesInput)

        helperCard.addView(Ui.label(this, "Worker ID"))
        workerIdText = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Ui.TEXT_MUTED)
            setTextIsSelectable(true)
        }
        helperCard.addView(workerIdText)
        root.addView(helperCard)

        // One-time permissions that keep the worker alive and its page visible.
        val setupCard = Ui.card(this)
        setupCard.addView(Ui.label(this, "Device setup"))
        batteryButton = Button(this).apply {
            setOnClickListener { requestIgnoreBatteryOptimizations() }
        }
        Ui.styleButton(batteryButton, Ui.ButtonKind.SECONDARY)
        setupCard.addView(batteryButton)
        overlayButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { requestOverlayPermission() }
        }
        Ui.styleButton(overlayButton, Ui.ButtonKind.SECONDARY)
        setupCard.addView(overlayButton)
        root.addView(setupCard)

        // Event log.
        val logCard = Ui.card(this)
        val logHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logHeaderRow.addView(Ui.label(this, "Event log").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, 0, 0)
        })
        logHeaderRow.addView(TextView(this).apply {
            text = "Clear"
            textSize = 13f
            setTextColor(Ui.PRIMARY_LIGHT)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(12), dp(6), 0, dp(6))
            setOnClickListener { WorkerState.clearLogs() }
        })
        logCard.addView(logHeaderRow)

        val logScroll = NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(260)
            ).apply { topMargin = dp(8) }
            background = Ui.rounded(Ui.BACKGROUND, 12, this@MainActivity, Ui.BORDER)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Ui.TEXT_MUTED)
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        logScroll.addView(logText)
        logCard.addView(logScroll)
        root.addView(logCard)

        return NestedScrollView(this).apply {
            setBackgroundColor(Ui.BACKGROUND)
            isFillViewport = true
            addView(root)
        }
    }

    private fun saveInputs() {
        prefs.helperUrl = urlInput.text.toString()
        prefs.workerToken = tokenInput.text.toString()
        prefs.proxies = proxiesInput.text.toString()
    }

    private fun toggleWorker() {
        if (isRunning) {
            val stopIntent = Intent(this, WorkerService::class.java).apply {
                action = WorkerService.ACTION_STOP
            }
            startService(stopIntent)
            return
        }

        saveInputs()
        val url = prefs.helperUrl
        if (!url.startsWith("ws://", ignoreCase = true) && !url.startsWith("wss://", ignoreCase = true)) {
            Toast.makeText(this, "Helper URL must start with ws:// or wss://", Toast.LENGTH_LONG).show()
            return
        }

        val proxyErrors = Proxies.parseList(prefs.proxies).errors
        if (proxyErrors.isNotEmpty()) {
            Toast.makeText(this, "Proxy ${proxyErrors.first()}", Toast.LENGTH_LONG).show()
            return
        }

        val startIntent = Intent(this, WorkerService::class.java).apply {
            action = WorkerService.ACTION_START
        }
        ContextCompat.startForegroundService(this, startIntent)
    }

    private fun renderState(snap: WorkerSnapshot) {
        isRunning = snap.isServiceRunning
        startStopButton.text = if (isRunning) "Stop" else "Start"
        Ui.styleButton(startStopButton, if (isRunning) Ui.ButtonKind.DANGER else Ui.ButtonKind.PRIMARY)
        urlInput.isEnabled = !isRunning
        tokenInput.isEnabled = !isRunning
        proxiesInput.isEnabled = !isRunning

        statusText.text = snap.status.label
        Ui.setDotColor(
            statusDot,
            when (snap.status) {
                ConnectionStatus.CONNECTED -> Ui.GREEN
                ConnectionStatus.CONNECTING -> Ui.GOLD
                ConnectionStatus.DISCONNECTED, ConnectionStatus.REPLACED -> Ui.RED
                ConnectionStatus.STOPPED -> Ui.TEXT_SUBTLE
            }
        )
        statusDetailText.text = snap.statusDetail
        statusDetailText.visibility = if (snap.statusDetail.isNotBlank()) View.VISIBLE else View.GONE

        val jobParts = mutableListOf<String>()
        if (snap.activeJobLabel != null) {
            jobParts.add("Active: ${snap.activeJobLabel}")
        }
        if (snap.queuedCount > 0) {
            jobParts.add("Queued: ${snap.queuedCount}")
        }
        if (jobParts.isNotEmpty()) {
            jobInfoText.text = jobParts.joinToString(" · ")
            jobInfoText.visibility = View.VISIBLE
        } else {
            jobInfoText.visibility = View.GONE
        }

        logText.text = if (snap.logs.isEmpty()) {
            "No events yet."
        } else {
            snap.logs.joinToString("\n")
        }
    }

    private fun requestNotificationsPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_POST_NOTIFICATIONS
                )
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show()
            updateOverlayButton()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun updateOverlayButton() {
        val allowed = Settings.canDrawOverlays(this)
        overlayButton.text = if (allowed) "✓  Background page window allowed" else "Allow background page window"
        overlayButton.isEnabled = !allowed
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already ignoring battery optimizations", Toast.LENGTH_SHORT).show()
            updateBatteryButton()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun updateBatteryButton() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        batteryButton.text = if (ignoring) "✓  Battery saving turned off for this app" else "Turn off battery saving for this app"
        batteryButton.isEnabled = !ignoring
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 100
    }
}
