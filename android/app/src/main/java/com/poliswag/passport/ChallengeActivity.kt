package com.poliswag.passport

import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ChallengeActivity : AppCompatActivity() {
    private lateinit var headerText: TextView
    private lateinit var webViewContainer: FrameLayout
    private var attachedSession: ChallengeSession? = null

    private val challengeListener: (ChallengeSession?) -> Unit = { session ->
        if (session == null) {
            detachWebView()
            finish()
        } else if (attachedSession?.jobId != session.jobId) {
            attachSession(session)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val pad = (12 * resources.displayMetrics.density).toInt()
        root.setBackgroundColor(Ui.BACKGROUND)
        headerText = TextView(this).apply {
            text = "PTC challenge needs attention"
            textSize = 15f
            setTextColor(Ui.GOLD)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(headerText)

        webViewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(webViewContainer)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        WorkerState.addChallengeListener(challengeListener)
    }

    override fun onStop() {
        WorkerState.removeChallengeListener(challengeListener)
        detachWebView()
        super.onStop()
    }

    private fun attachSession(session: ChallengeSession) {
        detachWebView()
        attachedSession = session
        headerText.text = "Solve PTC challenge for ${session.username}"

        val wv = session.webView
        (wv.parent as? ViewGroup)?.removeView(wv)
        session.onAttachContext(this)
        webViewContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun detachWebView() {
        val session = attachedSession ?: return
        attachedSession = null
        val wv = session.webView
        if (wv.parent === webViewContainer) {
            webViewContainer.removeView(wv)
        }
        session.onDetachContext()
    }
}
