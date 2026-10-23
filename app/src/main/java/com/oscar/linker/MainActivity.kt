package com.oscar.linker

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
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

    private lateinit var statusBar: TextView
    private lateinit var webView: WebView
    private lateinit var settingsView: ScrollView
    private lateinit var btnSettings: Button
    private lateinit var btnMarket: Button

    private lateinit var tvLinkStatus: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvPL: TextView
    private lateinit var btnLink: Button
    private lateinit var spinnerAccount: Spinner
    private lateinit var spinnerMode: Spinner
    private lateinit var etTradeValue: EditText
    private lateinit var etStopLoss: EditText
    private lateinit var etStopProfit: EditText
    private lateinit var tvValueHint: TextView
    private lateinit var tvLossHint: TextView
    private lateinit var tvProfitHint: TextView
    private lateinit var btnSave: Button
    private lateinit var btnToggle: Button
    private lateinit var btnUnlink: Button
    private lateinit var btnDebug: Button

    private var currentTab = "settings"
    private var linkingMode = false
    private lateinit var licenseKey: String
    private lateinit var prefs: SharedPreferences
    private var isLinked = false
    private var statsHandler: Handler? = null
    private var cookieSent = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val BASE_URL = "https://signal-bott.onrender.com"
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

        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = android.view.View.GONE
        }

        settingsView = createSettingsView().apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        container.addView(webView)
        container.addView(settingsView)

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
        root.addView(container)
        root.addView(bottomBar)
        setContentView(root)
        configureWebView()
    }

    private fun createSettingsView(): ScrollView {
        val sv = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.parseColor("#0a0a0a"))
        }

        tvLinkStatus = TextView(this).apply {
            text = "⏳ جاري التحميل..."
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(30, 40, 30, 40)
            setBackgroundColor(Color.parseColor("#131313"))
        }
        layout.addView(tvLinkStatus)

        btnLink = Button(this).apply {
            text = "🔗 ربط حساب Quotex"
            visibility = android.view.View.GONE
            setBackgroundColor(Color.parseColor("#d4af37"))
            setTextColor(Color.BLACK)
            setPadding(20, 30, 20, 30)
        }
        btnLink.setOnClickListener {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("🔗 ربط الحساب")
                .setMessage("ستُفتح صفحة Quotex. سجّل دخولك، وسيتم التقاط الجلسة تلقائياً.")
                .setPositiveButton("متابعة") { _, _ -> showMarket() }
                .setNegativeButton("إلغاء", null)
                .show()
        }
        layout.addView(btnLink)

        tvBalance = TextView(this).apply {
            text = "الرصيد: —"
            textSize = 15f
            setTextColor(Color.parseColor("#d4af37"))
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 10)
        }
        layout.addView(tvBalance)
        tvPL = TextView(this).apply {
            text = "الربح/الخسارة: —"
            textSize = 15f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }
        layout.addView(tvPL)

        layout.addView(makeLabel("نوع الحساب:"))
        spinnerAccount = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("تجريبي (Demo)", "حقيقي (Live)"))
        }
        layout.addView(spinnerAccount)

        layout.addView(makeLabel("طريقة الدخول:"))
        spinnerMode = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("نسبة مئوية من الرصيد", "مبلغ ثابت بالدولار"))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    updateHints()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        layout.addView(spinnerMode)

        tvValueHint = makeLabel("قيمة الدخول (%):")
        layout.addView(tvValueHint)
        etTradeValue = makeNumberInput("1.0")
        layout.addView(etTradeValue)

        tvLossHint = makeLabel("🛑 إيقاف عند الخسارة (%):")
        layout.addView(tvLossHint)
        etStopLoss = makeNumberInput("0")
        layout.addView(etStopLoss)

        tvProfitHint = makeLabel("✅ إيقاف عند الربح (%):")
        layout.addView(tvProfitHint)
        etStopProfit = makeNumberInput("0")
        layout.addView(etStopProfit)

        btnSave = Button(this).apply {
            text = "💾 حفظ الإعدادات"
            setBackgroundColor(Color.parseColor("#d4af37"))
            setTextColor(Color.BLACK)
            setPadding(20, 30, 20, 30)
            setOnClickListener { saveSettings() }
        }
        layout.addView(btnSave)

        btnToggle = Button(this).apply {
            text = "▶️ تفعيل التداول الآلي"
            setBackgroundColor(Color.parseColor("#2e7d32"))
            setTextColor(Color.WHITE)
            setPadding(20, 30, 20, 30)
            visibility = android.view.View.GONE
            setOnClickListener { toggleCopy() }
        }
        layout.addView(btnToggle)

        btnUnlink = Button(this).apply {
            text = "🔌 فصل الحساب"
            setBackgroundColor(Color.parseColor("#c62828"))
            setTextColor(Color.WHITE)
            setPadding(20, 30, 20, 30)
            visibility = android.view.View.GONE
            setOnClickListener { unlinkAccount() }
        }
        layout.addView(btnUnlink)

        btnDebug = Button(this).apply {
            text = "🔍 تشخيص (لعرض الكوكيز)"
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.parseColor("#d4af37"))
            setPadding(20, 20, 20, 20)
            setOnClickListener { showDebugInfo() }
        }
        layout.addView(btnDebug)

        sv.addView(layout)
        return sv
    }

    private fun makeLabel(txt: String): TextView = TextView(this).apply {
        text = txt
        textSize = 14f
        setTextColor(Color.parseColor("#d4af37"))
        setPadding(0, 25, 0, 8)
        setTypeface(null, Typeface.BOLD)
    }

    private fun makeNumberInput(default: String): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(default)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#1a1a1a"))
        setPadding(20, 25, 20, 25)
    }

    private fun updateHints() {
        val fixed = spinnerMode.selectedItemPosition == 1
        if (fixed) {
            tvValueHint.text = "قيمة الدخول (\$):"
            tvLossHint.text = "🛑 إيقاف عند الخسارة (\$):"
            tvProfitHint.text = "✅ إيقاف عند الربح (\$):"
        } else {
            tvValueHint.text = "قيمة الدخول (%):"
            tvLossHint.text = "🛑 إيقاف عند الخسارة (%):"
            tvProfitHint.text = "✅ إيقاف عند الربح (%):"
        }
    }

    private fun showSettings() {
        currentTab = "settings"
        webView.visibility = android.view.View.GONE
        settingsView.visibility = android.view.View.VISIBLE
        statusBar.text = "📊 الإعدادات"
        updateTabButtons()
        fetchUserInfo()
        startStatsPolling()
    }

    private fun showMarket() {
        currentTab = "market"
        settingsView.visibility = android.view.View.GONE
        webView.visibility = android.view.View.VISIBLE
        statusBar.text = "📈 السوق — سجّل دخولك إلى Quotex"
        updateTabButtons()
        linkingMode = true
        cookieSent = false
        if (webView.url == null || !webView.url!!.contains("quotex.com")) {
            webView.loadUrl("$QX_DOMAIN/ar/trade")
        }
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

    private fun fetchUserInfo() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url("$BASE_URL/api/app/user_info/$licenseKey").build()
                val res = client.newCall(req).execute()
                val json = JSONObject(res.body?.string() ?: "{}")
                withContext(Dispatchers.Main) {
                    if (!json.optBoolean("ok")) {
                        val err = json.optString("error", "خطأ")
                        tvLinkStatus.text = "❌ $err"
                        return@withContext
                    }
                    isLinked = json.optBoolean("linked")
                    val email = json.optString("email", "")
                    if (isLinked) {
                        tvLinkStatus.text = "✅ الحساب مرتبط${if (email.isNotEmpty()) " — $email" else ""}"
                        tvLinkStatus.setTextColor(Color.parseColor("#81c784"))
                        btnLink.visibility = android.view.View.GONE
                        btnToggle.visibility = android.view.View.VISIBLE
                        btnUnlink.visibility = android.view.View.VISIBLE
                    } else {
                        tvLinkStatus.text = "⚠️ الحساب غير مرتبط\nاذهب إلى تبويب السوق وسجّل الدخول"
                        tvLinkStatus.setTextColor(Color.parseColor("#f0e0a0"))
                        btnLink.visibility = android.view.View.VISIBLE
                        btnToggle.visibility = android.view.View.GONE
                        btnUnlink.visibility = android.view.View.GONE
                    }
                    spinnerAccount.setSelection(if (json.optString("account_type") == "live") 1 else 0)
                    spinnerMode.setSelection(if (json.optString("trade_mode") == "fixed") 1 else 0)
                    etTradeValue.setText(json.optDouble("trade_value", 1.0).toString())
                    etStopLoss.setText(json.optDouble("max_loss_value", 0.0).toString())
                    etStopProfit.setText(json.optDouble("max_profit_value", 0.0).toString())
                    updateHints()
                    updateToggleButton(json.optBoolean("is_copying"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvLinkStatus.text = "⚠️ خطأ اتصال: ${e.message}"
                }
            }
        }
    }

    private fun updateToggleButton(copying: Boolean) {
        if (copying) {
            btnToggle.text = "⏸️ إيقاف التداول الآلي"
            btnToggle.setBackgroundColor(Color.parseColor("#c62828"))
        } else {
            btnToggle.text = "▶️ تفعيل التداول الآلي"
            btnToggle.setBackgroundColor(Color.parseColor("#2e7d32"))
        }
    }

    private fun saveSettings() {
        val payload = JSONObject().apply {
            put("account_type", if (spinnerAccount.selectedItemPosition == 1) "live" else "demo")
            put("trade_mode", if (spinnerMode.selectedItemPosition == 1) "fixed" else "percent")
            put("trade_value", etTradeValue.text.toString().toDoubleOrNull() ?: 1.0)
            put("max_loss_value", etStopLoss.text.toString().toDoubleOrNull() ?: 0.0)
            put("max_profit_value", etStopProfit.text.toString().toDoubleOrNull() ?: 0.0)
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder()
                    .url("$BASE_URL/api/app/save_settings/$licenseKey")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                val json = JSONObject(res.body?.string() ?: "{}")
                withContext(Dispatchers.Main) {
                    if (json.optBoolean("ok")) {
                        Toast.makeText(this@MainActivity, "✅ تم الحفظ", Toast.LENGTH_SHORT).show()
                        fetchUserInfo()
                    } else {
                        Toast.makeText(this@MainActivity,
                            "❌ ${json.optString("error")}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun toggleCopy() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder()
                    .url("$BASE_URL/api/app/toggle_copy/$licenseKey")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                val json = JSONObject(res.body?.string() ?: "{}")
                withContext(Dispatchers.Main) {
                    if (json.optBoolean("ok")) {
                        val copying = json.optBoolean("is_copying")
                        updateToggleButton(copying)
                        Toast.makeText(this@MainActivity,
                            if (copying) "✅ تم التفعيل" else "⛔ تم الإيقاف",
                            Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity,
                            "❌ ${json.optString("error")}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun unlinkAccount() {
        AlertDialog.Builder(this)
            .setTitle("فصل الحساب")
            .setMessage("سيتم مسح الجلسة. متأكد؟")
            .setPositiveButton("نعم") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val req = Request.Builder()
                            .url("$BASE_URL/api/app/unlink/$licenseKey")
                            .post("{}".toRequestBody("application/json".toMediaType()))
                            .build()
                        client.newCall(req).execute()
                        withContext(Dispatchers.Main) { fetchUserInfo() }
                    } catch (e: Exception) {}
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun startStatsPolling() {
        statsHandler?.removeCallbacksAndMessages(null)
        statsHandler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (currentTab == "settings" && isLinked) fetchStats()
                statsHandler?.postDelayed(this, 15000)
            }
        }
        statsHandler?.postDelayed(runnable, 1000)
    }

    private fun fetchStats() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder()
                    .url("$BASE_URL/api/app/session_stats/$licenseKey").build()
                val res = client.newCall(req).execute()
                val json = JSONObject(res.body?.string() ?: "{}")
                withContext(Dispatchers.Main) {
                    if (json.optBoolean("ok")) {
                        val balance = json.optDouble("balance", 0.0)
                        val diff = json.optDouble("diff", 0.0)
                        val pct = json.optDouble("pct", 0.0)
                        tvBalance.text = String.format("الرصيد: $%.2f", balance)
                        if (diff > 0) {
                            tvPL.text = String.format("+$%.2f (+%.2f%%)", diff, pct)
                            tvPL.setTextColor(Color.parseColor("#4caf50"))
                        } else if (diff < 0) {
                            tvPL.text = String.format("-$%.2f (%.2f%%)", Math.abs(diff), pct)
                            tvPL.setTextColor(Color.parseColor("#f44336"))
                        } else {
                            tvPL.text = "الربح/الخسارة: $0.00"
                            tvPL.setTextColor(Color.GRAY)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun showDebugInfo() {
        val cookies = CookieManager.getInstance().getCookie(QX_DOMAIN) ?: "(none)"
        val js = """
            (function() {
                var out = [];
                out.push('=== LOCALSTORAGE ===');
                for (var i = 0; i < localStorage.length; i++) {
                    var k = localStorage.key(i);
                    out.push(k + ' = ' + (localStorage.getItem(k) || '').substring(0, 150));
                }
                out.push('');
                out.push('=== SESSIONSTORAGE ===');
                for (var i = 0; i < sessionStorage.length; i++) {
                    var k = sessionStorage.key(i);
                    out.push(k + ' = ' + (sessionStorage.getItem(k) || '').substring(0, 150));
                }
                return out.join('\n');
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            val decoded = result.trim('"').replace("\\n", "\n").replace("\\\"", "\"")
            val full = "=== COOKIES ===\n$cookies\n\n$decoded"
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val payload = JSONObject().apply {
                        put("cookies", cookies)
                        put("localStorage", decoded)
                    }
                    val req = Request.Builder()
                        .url("$BASE_URL/api/app/debug_cookies")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(req).execute()
                } catch (e: Exception) {}
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("🔍 بيانات التشخيص")
                    .setMessage(full)
                    .setPositiveButton("نسخ") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("debug", full))
                        Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("إغلاق", null)
                    .show()
            }
        }
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
            override fun onReceivedSslError(v: WebView?, h: android.webkit.SslErrorHandler?, e: android.net.http.SslError?) {
                h?.proceed()
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("quotex.com")) {
                    injectInterceptor()
                    if (linkingMode && (url.contains("/trade") || url.contains("/cabinet") || url.contains("/dashboard"))) {
                        statusBar.text = "✅ تم اكتشاف تسجيل الدخول — جاري التقاط الجلسة..."
                        Handler(Looper.getMainLooper()).postDelayed({ sendCookiesNow() }, 2500)
                        Handler(Looper.getMainLooper()).postDelayed({ sendCookiesNow() }, 6000)
                        Handler(Looper.getMainLooper()).postDelayed({ sendCookiesNow() }, 10000)
                    }
                }
            }
        }
    }

    private fun sendCookiesNow() {
        if (!linkingMode || cookieSent) return
        val cookies = CookieManager.getInstance().getCookie(QX_DOMAIN) ?: ""
        if (cookies.isBlank()) {
            statusBar.text = "⚠️ لا توجد كوكيز بعد. تابع تسجيل الدخول ثم انتظر..."
            return
        }
        cookieSent = true
        statusBar.text = "🔗 جاري إرسال الكوكيز للسيرفر (${cookies.length} حرف)..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val payload = JSONObject().apply {
                    put("license_key", licenseKey)
                    put("ssid", "")
                    put("cookies", cookies)
                    put("email", "")
                }
                val req = Request.Builder()
                    .url("$BASE_URL/api/link")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                val json = JSONObject(res.body?.string() ?: "{}")

                withContext(Dispatchers.Main) {
                    if (json.optBoolean("ok")) {
                        statusBar.text = "✅ تم ربط الحساب بنجاح!"
                        Toast.makeText(this@MainActivity, "✅ تم الربط", Toast.LENGTH_LONG).show()
                        linkingMode = false
                        Handler(Looper.getMainLooper()).postDelayed({ showSettings() }, 800)
                    } else {
                        val err = json.optString("error", "خطأ")
                        statusBar.text = "⚠️ فشل: $err — إعادة المحاولة..."
                        cookieSent = false
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (linkingMode) sendCookiesNow()
                        }, 8000)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusBar.text = "⚠️ خطأ اتصال: ${e.message}"
                    cookieSent = false
                }
            }
        }
    }

    private fun injectInterceptor() {
        val js = """
            (function() {
                if (window.__oscarHooked3) return;
                window.__oscarHooked3 = true;
                function push(v) {
                    if (v && typeof v === 'string' && v.length > 20 && v.length < 5000) {
                        if (window.OscarBridge && window.OscarBridge.onSsid) window.OscarBridge.onSsid(v);
                    }
                }
                var OWS = window.WebSocket;
                var hooked = function(url, protocols) {
                    try {
                        if (url) {
                            var pats = [/[?&]token=([^&]+)/, /[?&]session=([^&]+)/, /[?&]ssid=([^&]+)/];
                            for (var i = 0; i < pats.length; i++) {
                                var m = url.match(pats[i]);
                                if (m && m[1]) push(decodeURIComponent(m[1]));
                            }
                        }
                    } catch(e){}
                    var ws = protocols ? new OWS(url, protocols) : new OWS(url);
                    try {
                        ws.addEventListener('message', function(ev) {
                            try {
                                var d = ev.data;
                                if (typeof d !== 'string') return;
                                var ps = [/"session"\s*:\s*"([^"]+)"/, /"token"\s*:\s*"([^"]+)"/,
                                          /"ssid"\s*:\s*"([^"]+)"/, /"authorization"\s*:\s*"([^"]+)"/];
                                for (var i = 0; i < ps.length; i++) {
                                    var m = d.match(ps[i]);
                                    if (m && m[1]) { push(m[1]); break; }
                                }
                            } catch(e){}
                        });
                    } catch(e){}
                    return ws;
                };
                hooked.prototype = OWS.prototype;
                hooked.CONNECTING = OWS.CONNECTING; hooked.OPEN = OWS.OPEN;
                hooked.CLOSING = OWS.CLOSING; hooked.CLOSED = OWS.CLOSED;
                window.WebSocket = hooked;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun onSsid(ssid: String) {
            // لم نعد نستخدم SSID منفصل — نرسل كل الكوكيز
            if (linkingMode && !cookieSent) {
                Handler(Looper.getMainLooper()).post { sendCookiesNow() }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTab == "market") showSettings()
        else if (::webView.isInitialized && webView.canGoBack() && webView.visibility == android.view.View.VISIBLE) webView.goBack()
        else super.onBackPressed()
    }
}
