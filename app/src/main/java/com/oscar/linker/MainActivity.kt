package com.oscar.linker

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.*
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
    private val capturedFrames = mutableListOf<String>()
    private val capturedSsids = mutableListOf<String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val BASE_URL = "https://signal-bott.onrender.com"
    private val QX_DOMAIN = "https://quotex.com"

    // ═══════════════════════════════════════════════════════════
    // سكربت الحقن - النسخة المُصححة (يستخرج SSID من send أيضاً)
    // ═══════════════════════════════════════════════════════════
    private val EARLY_HOOK_JS = """
    (function() {
        if (window.__oscarEarlyHook) return;
        window.__oscarEarlyHook = true;
        window.__oscarFrames = [];

        function extractAndSend(text, tag) {
            try {
                if (typeof text !== 'string' || text.length < 5) return;
                if (window.__oscarFrames.length < 200) {
                    window.__oscarFrames.push('[' + tag + '] ' + text.substring(0, 3000));
                }
                if (window.OscarBridge && window.OscarBridge.onFrame) {
                    window.OscarBridge.onFrame('[' + tag + '] ' + text.substring(0, 3000));
                }
                // ★★★ استخراج SSID من كل من send و message ★★★
                var pats = [
                    /"session"\s*:\s*"([^"]+)"/,
                    /"token"\s*:\s*"([^"]+)"/,
                    /"ssid"\s*:\s*"([^"]+)"/,
                    /"authorization"\s*:\s*"([^"]+)"/,
                    /"access_token"\s*:\s*"([^"]+)"/,
                    /"jwt"\s*:\s*"([^"]+)"/,
                    /session=([A-Za-z0-9_\-\.]{30,})/,
                    /token=([A-Za-z0-9_\-\.]{30,})/
                ];
                for (var i = 0; i < pats.length; i++) {
                    var m = text.match(pats[i]);
                    if (m && m[1] && m[1].length > 25) {
                        if (window.OscarBridge && window.OscarBridge.onSsid) {
                            window.OscarBridge.onSsid(m[1]);
                        }
                    }
                }
            } catch(e) {}
        }

        function processIncoming(d) {
            try {
                if (typeof d === 'string') { extractAndSend(d, 'WS_RECV'); return; }
                if (d instanceof Blob) { d.text().then(function(t){ extractAndSend(t, 'WS_RECV'); }); return; }
                if (d instanceof ArrayBuffer) {
                    try {
                        var txt = new TextDecoder('utf-8').decode(d);
                        extractAndSend(txt, 'WS_RECV');
                    } catch(e) {
                        var bytes = new Uint8Array(d);
                        var s = '';
                        for (var i = 0; i < bytes.length && i < 3000; i++) s += String.fromCharCode(bytes[i]);
                        extractAndSend(s, 'WS_RECV');
                    }
                }
            } catch(e) {}
        }

        var OWS = window.WebSocket;
        if (OWS) {
            var HookedWS = function(url, protocols) {
                try {
                    if (url && window.OscarBridge && window.OscarBridge.onFrame) {
                        window.OscarBridge.onFrame('[WS_URL] ' + url.substring(0, 500));
                    }
                    if (url) {
                        var upats = [/[?&]token=([^&]+)/, /[?&]session=([^&]+)/, /[?&]ssid=([^&]+)/];
                        for (var i = 0; i < upats.length; i++) {
                            var um = url.match(upats[i]);
                            if (um && um[1] && um[1].length > 25) {
                                if (window.OscarBridge && window.OscarBridge.onSsid) {
                                    window.OscarBridge.onSsid(decodeURIComponent(um[1]));
                                }
                            }
                        }
                    }
                } catch(e){}
                var ws = protocols ? new OWS(url, protocols) : new OWS(url);
                try {
                    var origAdd = ws.addEventListener.bind(ws);
                    ws.addEventListener = function(type, listener, opts) {
                        if (type === 'message') {
                            return origAdd(type, function(ev) {
                                processIncoming(ev.data);
                                return listener(ev);
                            }, opts);
                        }
                        return origAdd(type, listener, opts);
                    };
                    ws.onmessage = null;
                } catch(e){}
                return ws;
            };
            HookedWS.prototype = OWS.prototype;
            HookedWS.CONNECTING = OWS.CONNECTING; HookedWS.OPEN = OWS.OPEN;
            HookedWS.CLOSING = OWS.CLOSING; HookedWS.CLOSED = OWS.CLOSED;
            window.WebSocket = HookedWS;
        }

        // ★★★ الإصلاح الحاسم: اعتراض send واستخراج SSID ★★★
        if (window.WebSocket && window.WebSocket.prototype && window.WebSocket.prototype.send) {
            var origSend = window.WebSocket.prototype.send;
            window.WebSocket.prototype.send = function(data) {
                try {
                    if (typeof data === 'string') {
                        extractAndSend(data, 'WS_SEND');
                    }
                } catch(e){}
                return origSend.apply(this, arguments);
            };
        }

        // اعتراض fetch للـ Authorization headers
        var origFetch = window.fetch;
        if (origFetch) {
            window.fetch = function() {
                try {
                    var args = arguments;
                    if (args[1] && args[1].headers) {
                        var h = args[1].headers;
                        var authHeader = h['Authorization'] || h['authorization'];
                        if (authHeader && authHeader.length > 25 && window.OscarBridge) {
                            var tok = authHeader.replace(/^Bearer\s+/i, '');
                            if (tok.length > 25) window.OscarBridge.onSsid(tok);
                        }
                    }
                } catch(e){}
                return origFetch.apply(this, arguments);
            };
        }

        // اعتراض XHR
        var OrigXHR = window.XMLHttpRequest;
        if (OrigXHR) {
            var origSetHeader = OrigXHR.prototype.setRequestHeader;
            OrigXHR.prototype.setRequestHeader = function(name, value) {
                try {
                    if (name && name.toLowerCase() === 'authorization' && value && value.length > 25) {
                        if (window.OscarBridge) {
                            var tok = value.replace(/^Bearer\s+/i, '');
                            if (tok.length > 25) window.OscarBridge.onSsid(tok);
                        }
                    }
                } catch(e){}
                return origSetHeader.apply(this, arguments);
            };
        }
    })();
    """.trimIndent()

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
            text = "🔍 تشخيص (frames / cookies)"
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
        capturedFrames.clear()
        capturedSsids.clear()
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
        val sb = StringBuilder()
        sb.append("=== CAPTURED SSIDs (${capturedSsids.size}) ===\n")
        capturedSsids.take(10).forEach { sb.append("• ${it.take(100)}\n") }
        sb.append("\n=== CAPTURED FRAMES (${capturedFrames.size}) ===\n")
        capturedFrames.take(20).forEachIndexed { i, f ->
            sb.append("[${i+1}] ${f.take(400)}\n\n")
        }
        sb.append("\n=== COOKIES ===\n")
        sb.append(cookies.take(2000))

        val full = sb.toString()
        AlertDialog.Builder(this)
            .setTitle("🔍 تشخيص")
            .setMessage(full)
            .setPositiveButton("نسخ") { _, _ ->
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("debug", full))
                Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(Bridge(), "OscarBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) {
                h?.proceed()
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url?.contains("quotex.com") == true) {
                    view?.evaluateJavascript(EARLY_HOOK_JS, null)
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("quotex.com")) {
                    view.evaluateJavascript(EARLY_HOOK_JS, null)
                    if (linkingMode && (url.contains("/trade") || url.contains("/cabinet") || url.contains("/dashboard"))) {
                        statusBar.text = "✅ تم اكتشاف تسجيل الدخول — جاري البحث عن SSID..."
                        Handler(Looper.getMainLooper()).postDelayed({ autoSendCookie() }, 3000)
                        Handler(Looper.getMainLooper()).postDelayed({ autoSendCookie() }, 7000)
                        Handler(Looper.getMainLooper()).postDelayed({ autoSendCookie() }, 12000)
                    }
                }
            }
        }
    }

    private fun autoSendCookie() {
        if (!linkingMode) return
        val ssid = capturedSsids.lastOrNull()
        if (ssid != null && ssid.length > 25) {
            sendToServer(ssid, "")
            return
        }
        if (cookieSent) return
        val cookies = CookieManager.getInstance().getCookie(QX_DOMAIN) ?: ""
        if (cookies.isBlank()) return
        cookieSent = true
        sendToServer("", cookies)
    }

    private fun sendToServer(ssid: String, cookies: String) {
        statusBar.text = if (ssid.isNotEmpty())
            "🔗 جاري التحقق من SSID (${ssid.length} حرف)..."
        else
            "🔗 جاري إرسال الكوكيز (${cookies.length} حرف)..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val payload = JSONObject().apply {
                    put("license_key", licenseKey)
                    put("ssid", ssid)
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
                        statusBar.text = "⚠️ فشل: $err"
                        cookieSent = false
                        val untried = capturedSsids.filter { it != ssid }
                        if (untried.isNotEmpty() && ssid.isNotEmpty()) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (linkingMode) sendToServer(untried.last(), "")
                            }, 1500)
                        } else {
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (linkingMode) autoSendCookie()
                            }, 5000)
                        }
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

    inner class Bridge {
        @JavascriptInterface
        fun onFrame(frame: String) {
            Log.d("OscarWS", frame.take(500))
            if (capturedFrames.size < 200) capturedFrames.add(frame)
        }

        @JavascriptInterface
        fun onSsid(ssid: String) {
            if (ssid.length < 25) return
            if (ssid !in capturedSsids) {
                capturedSsids.add(ssid)
                Log.d("OscarSSID", "Captured: ${ssid.take(100)}")
                runOnUiThread {
                    if (linkingMode) {
                        statusBar.text = "🎯 تم التقاط SSID! (${ssid.length} حرف)"
                        sendToServer(ssid, "")
                    }
                }
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
