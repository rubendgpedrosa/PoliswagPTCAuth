package com.poliswag.passport

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

class WebViewJobRunner(
    context: Context,
    val job: IncomingMessage.Job,
    private val proxy: ProxyEntry?,
    private val onResult: (jobId: String, status: String, loginCode: String?, reason: String?, lastState: String?) -> Unit,
    private val onChallenge: (jobId: String, session: ChallengeSession) -> Unit,
    private val onStateChange: (jobId: String, state: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val contextWrapper = MutableContextWrapper(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var webView: WebView? = null
    private var overlayHost: FrameLayout? = null
    private var pageWidth = 1080
    private var pageHeight = 1920
    private var finished = false
    private var challengeReported = false
    private var proxyAuthTries = 0
    var lastState: String? = null
        private set

    private val timeoutRunnable = Runnable {
        finishWithResult(Protocol.STATUS_ERROR, null, "local 10m timeout")
    }

    fun start() {
        mainHandler.post { startOnMain() }
    }

    fun cancel() {
        mainHandler.post {
            if (finished) return@post
            finished = true
            cleanupWebView()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startOnMain() {
        if (finished) return
        // The page script reports back through a web message listener. Without it the
        // job could never finish, so fail now instead of hanging until the timeout.
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            finishWithResult(Protocol.STATUS_ERROR, null, "Android System WebView is too old; update it from the Play Store")
            return
        }
        // A proxy that cannot be applied must fail the job. Logging in without it would
        // show PTC this device's own IP.
        if (proxy != null && !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            finishWithResult(Protocol.STATUS_ERROR, null, "this Android System WebView cannot use a proxy; update it or clear the proxy list")
            return
        }

        val combinedScript = try {
            PageScriptBuilder.loadCombinedScript(appContext)
        } catch (e: Exception) {
            finishWithResult(Protocol.STATUS_ERROR, null, "failed to load page script: ${e.message}")
            return
        }

        val wv = try {
            WebView(contextWrapper)
        } catch (e: Exception) {
            finishWithResult(Protocol.STATUS_ERROR, null, "failed to create WebView: ${e.message}")
            return
        }
        webView = wv
        // The app usually sits behind another app (the game) with this WebView off
        // screen. Keep its renderer at full priority so Android does not slow or kill it.
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        // Give the page a phone-sized viewport so responsive pages render properly.
        val dm = appContext.resources.displayMetrics
        pageWidth = if (dm.widthPixels > 0) dm.widthPixels else 1080
        pageHeight = if (dm.heightPixels > 0) dm.heightPixels else 1920
        placeOffScreen(wv)

        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = UrlUtils.sanitizeUserAgent(settings.userAgentString)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }
        presentAsChrome(settings)

        val allowedOrigins = setOf(ALLOWED_ORIGIN)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                wv,
                BRIDGE_NAME,
                allowedOrigins
            ) { view, message, _, isMainFrame, replyProxy ->
                if (!isMainFrame) return@addWebMessageListener
                val raw = message.data ?: return@addWebMessageListener
                val msg = try {
                    JSONObject(raw)
                } catch (_: Exception) {
                    return@addWebMessageListener
                }
                val type = msg.optString("type", "")
                if (finished || view !== webView) {
                    if (type == "need-credentials") {
                        replyProxy.postMessage(
                            JSONObject()
                                .put("type", "credentials")
                                .put("job", false)
                                .toString()
                        )
                    }
                    return@addWebMessageListener
                }
                when (type) {
                    "need-credentials" -> {
                        replyProxy.postMessage(
                            JSONObject()
                                .put("type", "credentials")
                                .put("job", true)
                                .put("username", job.username)
                                .put("password", job.password)
                                .toString()
                        )
                    }
                    "state" -> {
                        val state = msg.optString("state", "")
                        if (state.isNotEmpty()) {
                            lastState = state
                            onStateChange(job.id, state)
                        }
                    }
                    "challenge" -> {
                        if (!challengeReported) {
                            challengeReported = true
                            val session = ChallengeSession(
                                jobId = job.id,
                                username = job.username,
                                webView = wv,
                                onAttachContext = { activityCtx ->
                                    contextWrapper.baseContext = activityCtx
                                },
                                onDetachContext = {
                                    contextWrapper.baseContext = appContext
                                    // Back from the captcha screen: return to the tiny window.
                                    if (!finished && webView === wv && wv.parent == null) placeOffScreen(wv)
                                }
                            )
                            onChallenge(job.id, session)
                        }
                    }
                    "result" -> {
                        val status = msg.optString("status", Protocol.STATUS_ERROR)
                        val loginCode = msg.optString("login_code", "").ifEmpty { null }
                        finishWithResult(status, loginCode, null)
                    }
                }
            }
        }

        val useDocumentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (useDocumentStart) {
            WebViewCompat.addDocumentStartJavaScript(wv, combinedScript, allowedOrigins)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url?.toString() ?: return false
                val code = UrlUtils.extractLoginCodeFromRedirectUrl(url)
                if (code != null) {
                    WorkerState.log("job ${Protocol.shortId(job.id)} — captured login code from redirect")
                    finishWithResult(Protocol.STATUS_SUCCESS, code, null)
                    return true
                }
                if (url.startsWith("pokemongo://", ignoreCase = true)) {
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                val code = UrlUtils.extractLoginCodeFromRedirectUrl(url)
                if (code != null) {
                    view.stopLoading()
                    WorkerState.log("job ${Protocol.shortId(job.id)} — captured login code from redirect")
                    finishWithResult(Protocol.STATUS_SUCCESS, code, null)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!useDocumentStart && !finished && UrlUtils.validateJobUrl(url).ok) {
                    view.evaluateJavascript(combinedScript, null)
                }
            }

            // A proxy asking for its username and password. Any other login prompt
            // (a website's own) is refused.
            override fun onReceivedHttpAuthRequest(
                view: WebView,
                handler: HttpAuthHandler,
                host: String?,
                realm: String?
            ) {
                val p = proxy
                if (p != null && p.hasCredentials && host.equals(p.host, ignoreCase = true) && proxyAuthTries < 2) {
                    proxyAuthTries += 1
                    handler.proceed(p.username, p.password)
                    return
                }
                handler.cancel()
                if (p != null && host.equals(p.host, ignoreCase = true)) {
                    finishWithResult(Protocol.STATUS_ERROR, null, "proxy ${p.label} refused the username/password")
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (finished || !request.isForMainFrame) return
                val url = request.url?.toString() ?: ""
                if (url.startsWith("pokemongo://", ignoreCase = true)) return
                finishWithResult(
                    Protocol.STATUS_ERROR,
                    null,
                    "page load error (${error.errorCode}: ${error.description})"
                )
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                finishWithResult(Protocol.STATUS_ERROR, null, "WebView render process gone")
                return true
            }
        }

        mainHandler.postDelayed(timeoutRunnable, LOCAL_TIMEOUT_MS)

        // No PTC session may survive from the previous account: wipe all cookies and web
        // storage. Then put back only the bot check's own cookies (see BotCheckCookies),
        // so this device is not treated as a first-time visitor on every login.
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)
        val kept = BotCheckCookies.select(cookieManager.getCookie(ALLOWED_ORIGIN))
        WebStorage.getInstance().deleteAllData()
        cookieManager.removeAllCookies {
            for ((name, value) in kept) {
                cookieManager.setCookie(ALLOWED_ORIGIN, BotCheckCookies.restoreHeader(name, value))
            }
            cookieManager.flush()
            applyProxyThen {
                if (!finished && webView === wv) {
                    wv.loadUrl(job.url)
                }
            }
        }
    }

    /**
     * Sets this job's proxy for the app's WebViews (or removes the last job's), then
     * continues. The rule has no direct fallback: if the proxy is down the page fails
     * to load. The WebSocket to the helper is not a WebView and stays direct.
     */
    private fun applyProxyThen(next: () -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            next() // only reached with no proxy configured
            return
        }
        val controller = ProxyController.getInstance()
        val onMain = java.util.concurrent.Executor { mainHandler.post(it) }
        if (proxy == null) {
            controller.clearProxyOverride(onMain, next)
        } else {
            val config = ProxyConfig.Builder().addProxyRule(proxy.rule).build()
            controller.setProxyOverride(config, onMain, next)
        }
    }

    /**
     * Puts the WebView where the user never sees it. With the "display over other apps"
     * permission it goes into a real 1-pixel window in a screen corner: the page is
     * full-size inside (the window just clips it) and, being in a window, it counts as
     * visible, so its timers and animations run as in a normal browser. Touches pass
     * through to whatever app is on screen. Without the permission the WebView is only
     * laid out in memory, and the page may see itself as hidden.
     */
    private fun placeOffScreen(wv: WebView) {
        if (overlayHost == null && Settings.canDrawOverlays(appContext)) {
            try {
                val host = FrameLayout(appContext)
                val params = WindowManager.LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.BOTTOM or Gravity.END }
                (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(host, params)
                overlayHost = host
            } catch (_: Exception) {
                overlayHost = null // fall back to the in-memory layout below
            }
        }
        val host = overlayHost
        if (host != null) {
            host.addView(wv, FrameLayout.LayoutParams(pageWidth, pageHeight))
            return
        }
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(pageWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(pageHeight, View.MeasureSpec.EXACTLY)
        )
        wv.layout(0, 0, pageWidth, pageHeight)
    }

    private fun removeOverlayHost() {
        val host = overlayHost ?: return
        overlayHost = null
        try {
            host.removeAllViews()
            (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeViewImmediate(host)
        } catch (_: Exception) {
        }
    }

    /**
     * The User-Agent text above already reads as Chrome. The browser also sends a brand
     * list (Sec-CH-UA, navigator.userAgentData) that still says "Android WebView";
     * rename that entry too so both agree. Versions and platform stay the real ones.
     */
    private fun presentAsChrome(settings: android.webkit.WebSettings) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        try {
            val current = WebSettingsCompat.getUserAgentMetadata(settings)
            val brands = current.brandVersionList.map { brand ->
                if (brand.brand != "Android WebView") return@map brand
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Google Chrome")
                    .setMajorVersion(brand.majorVersion)
                    .setFullVersion(brand.fullVersion)
                    .build()
            }
            WebSettingsCompat.setUserAgentMetadata(
                settings,
                UserAgentMetadata.Builder(current).setBrandVersionList(brands).build()
            )
        } catch (_: Exception) {
            // Keep the WebView's own values rather than fail the job.
        }
    }

    private fun finishWithResult(status: String, loginCode: String?, reason: String?) {
        mainHandler.post {
            if (finished) return@post
            finished = true
            val finalState = lastState
            cleanupWebView()
            onResult(job.id, status, loginCode, reason, finalState)
        }
    }

    private fun cleanupWebView() {
        mainHandler.removeCallbacks(timeoutRunnable)
        WorkerState.clearChallengeSession(job.id)
        val wv = webView
        webView = null
        if (wv != null) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            contextWrapper.baseContext = appContext
            try {
                wv.stopLoading()
                wv.webViewClient = WebViewClient()
                wv.loadUrl("about:blank")
                wv.removeAllViews()
                wv.destroy()
            } catch (_: Exception) {
            }
        }
        removeOverlayHost()
    }

    companion object {
        private const val ALLOWED_ORIGIN = "https://access.pokemon.com"
        private const val BRIDGE_NAME = "poliswagBridge"
        private const val LOCAL_TIMEOUT_MS = 10L * 60L * 1000L
    }
}
