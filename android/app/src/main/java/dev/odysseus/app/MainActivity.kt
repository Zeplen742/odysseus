package dev.odysseus.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * The Odysseus web UI in a full-screen WebView, plus a native side that follows
 * running replies (see [RunWatcher]) so they finish and notify in the background.
 */
class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_CHANGE_SERVER = "dev.odysseus.app.CHANGE_SERVER"
        private const val PREFS = "odysseus"
        private const val KEY_URL = "url"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private lateinit var webHolder: FrameLayout
    private var web: WebView? = null
    private var api: OdysseusApi? = null

    private var base by mutableStateOf<String?>(null)
    private var showSetup by mutableStateOf(false)
    private var loadError by mutableStateOf<String?>(null)

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
            fileCallback = null
        }

    private val askMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pendingPermission ?: return@registerForActivityResult
            pendingPermission = null
            if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else req.deny()
        }

    override fun onStart() { super.onStart(); TurnHost.appVisible = true }
    override fun onStop() {
        TurnHost.appVisible = false
        CookieManager.getInstance().flush()     // keep the login across process death
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.edit().remove("token").apply()      // v0.1 pairing token, no longer used
        base = prefs.getString(KEY_URL, null)?.let(Urls::normalizeBase)
        showSetup = base == null || intent?.action == ACTION_CHANGE_SERVER

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        webHolder = FrameLayout(this)
        val overlay = ComposeView(this).apply {
            setContent {
                MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                    when {
                        showSetup -> Surface(Modifier.fillMaxSize()) { SetupScreen() }
                        loadError != null -> Surface(Modifier.fillMaxSize()) { ErrorScreen(loadError!!) }
                    }
                }
            }
        }
        setContentView(FrameLayout(this).apply {
            addView(webHolder, ViewGroup.LayoutParams(-1, -1))
            addView(overlay, ViewGroup.LayoutParams(-1, -1))
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = web
                when {
                    showSetup && base != null && w != null -> showSetup = false
                    !showSetup && w != null && w.canGoBack() -> w.goBack()
                    else -> finish()
                }
            }
        })

        if (!showSetup) startWeb(intent?.getStringExtra(Notifier.EXTRA_SESSION))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_CHANGE_SERVER) { showSetup = true; return }
        val session = intent.getStringExtra(Notifier.EXTRA_SESSION) ?: return
        if (!Urls.isSessionId(session)) return
        Notifier.clear(this, session)
        val w = web
        val b = base ?: return
        if (w == null) startWeb(session)
        else if (Urls.isServerUrl(b, w.url ?: "")) w.evaluateJavascript("location.hash = '#$session';", null)
        else w.loadUrl(Urls.chatUrl(b, session))
    }

    override fun onDestroy() {
        web?.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- web view

    @SuppressLint("SetJavaScriptEnabled")
    private fun startWeb(session: String?) {
        val b = base ?: return
        web?.let { webHolder.removeView(it); it.destroy() }
        api = OdysseusApi(b) { CookieManager.getInstance().getCookie(b) }

        val w = WebView(this)
        CookieManager.getInstance().setAcceptCookie(true)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false   // read-aloud starts from a tap the page handles
            allowFileAccess = false
            userAgentString = "$userAgentString OdysseusAndroid/${BuildConfig.VERSION_NAME}"
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(w, "OdysseusNative", setOf(b)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) onPageMessage(message.data ?: return@addWebMessageListener)
            }
        }
        val earlyScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (earlyScript) WebViewCompat.addDocumentStartJavaScript(w, ShellScript.JS, setOf(b))

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (Urls.isServerUrl(b, url)) return false
                // anything off the server opens in the phone's browser
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!earlyScript && url != null && Urls.isServerUrl(b, url)) view.evaluateJavascript(ShellScript.JS, null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) loadError = "${error.description}"
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, resp: WebResourceResponse) {
                if (request.isForMainFrame && resp.statusCode >= 500) {
                    loadError = "The server answered ${resp.statusCode}. If Odysseus is restarting, give it a minute."
                }
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try { pickFiles.launch(params.createIntent()); true }
                catch (_: Exception) { fileCallback = null; false }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsMic = PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources
                if (!wantsMic || !Urls.isServerUrl(b, request.origin.toString())) { request.deny(); return }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    pendingPermission?.deny()
                    pendingPermission = request
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        w.setDownloadListener { url, userAgent, contentDisposition, mime, _ ->
            Downloads.enqueue(this, url, userAgent, contentDisposition, mime)
        }

        webHolder.addView(w, ViewGroup.LayoutParams(-1, -1))
        web = w
        loadError = null
        w.loadUrl(if (session != null && Urls.isSessionId(session)) Urls.chatUrl(b, session) else "$b/")
    }

    private fun onPageMessage(raw: String) {
        val o = try { JSONObject(raw) } catch (_: Exception) { return }
        when (o.optString("t")) {
            "run" -> api?.let { RunWatcher.follow(this, it, o.optString("s")) }
            "view" -> {
                val s = o.optString("s").takeIf(Urls::isSessionId)
                RunWatcher.viewingSession = s
                if (s != null) Notifier.clear(this, s)
            }
            "file" -> Downloads.saveBase64(
                this, o.optString("name"), o.optString("mime", "application/octet-stream"), o.optString("data"),
            )
        }
    }

    // ---------------------------------------------------------------- screens

    @Composable
    private fun SetupScreen() {
        var input by remember { mutableStateOf(base ?: "") }
        var error by remember { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Odysseus", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = input, onValueChange = { input = it; error = null },
                label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                val b = Urls.normalizeBase(input)
                if (b == null) { error = "Enter a URL like https://odysseus.example.com"; return@Button }
                prefs.edit().putString(KEY_URL, b).apply()
                val changed = b != base
                base = b
                showSetup = false
                if (changed || web == null) startWeb(null)
            }, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
        }
    }

    @Composable
    private fun ErrorScreen(message: String) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Can't reach Odysseus", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(message)
            Spacer(Modifier.height(4.dp))
            Text(base ?: "", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { loadError = null; web?.reload() }, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
            OutlinedButton(onClick = { showSetup = true }, modifier = Modifier.fillMaxWidth()) { Text("Change server") }
        }
    }
}
