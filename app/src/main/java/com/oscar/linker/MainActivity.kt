package com.oscar.linker

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
    
    private lateinit var btnEnableCopy: Button
    private lateinit var btnEnableAuto: Button
    private lateinit var btnStop: Button
    private lateinit var btnUnlink: Button

    private var currentTab = "settings"
    private lateinit var licenseKey: String
    private lateinit var prefs: SharedPreferences
    private var isLinked = false
    private var isLocked = false
    private var statsHandler: Handler? = null
    private var isProcessingSSID = false // لمنع الإرسال المتكرر

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val BASE_URL = "https://signal-bott.onrender.com"
    private val QX_DOMAIN = "https://quotex.com"

    private val EARLY_HOOK_JS = """
    (function() {
        if (window.__oscarEarlyHook) return;
        window.__oscarEarlyHook = true;
        function extractAndSend(text) {
            try {
                if (typeof text !== 'string' || text.length < 5) return;
                var pats = [ /"session"\s*:\s*"([^"]+)"/, /"token"\s*:\s*"([^"]+)"/ ];
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
        var OWS = window.WebSocket;
        if (OWS) {
            var HookedWS = function(url, protocols) {
                var ws = protocols ? new OWS(url, protocols) : new OWS(url);
                try {
                    var origAdd = ws.addEventListener.bind(ws);
                    ws.addEventListener = function(type, listener, opts) {
                        if (type === 'message') {
                            return origAdd(type, function(ev) {
                                if (typeof ev.data === 'string') extractAndSend(ev.data);
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
        if (window.WebSocket && window.WebSocket.prototype && window.WebSocket.prototype.send) {
            var origSend = window.WebSocket.prototype.send;
            window.WebSocket.prototype.send = function(data) {
                if (typeof data === 'string') extractAndSend(data);
                return origSend.apply(this, arguments);
            };
        }
    })();
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("oscar_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("license_key", null)
        
        if (saved.isNullOrBlank()) {
            showKeyDialog()
        } else {
            licenseKey = saved
            validateKey(saved) { ok, err ->
                if (ok) { setupUi(); showSettings() }
                else {
                    prefs.edit().remove("license_key").apply()
                    Toast.makeText(this, "❌ $err", Toast.LENGTH_LONG).show()
                    showKeyDialog()
                }
            }
        }
    }

    private fun showKeyDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }
        val input = EditText(this).apply {
            hint = "OSCAR-XXXX-XXXX-XXXX"
            setSingleLine()
            setTextColor(Color.BLACK)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        container.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle("🔑 تفعيل Oskarix")
            .setMessage("يرجى إدخال مفتاح التفعيل الخاص بك")
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("إلغاء") { _, _ -> finish() }
            .setPositiveButton("متابعة", null)
            .create()

        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val k = input.text.toString().trim().uppercase()
                if (k.isEmpty()) return@setOnClickListener
                btn.isEnabled = false
                btn.text = "جاري التحقق..."
                validateKey(k) { ok, err ->
                    if (ok) {
                        licenseKey = k
                        prefs.edit().putString("license_key", k).apply()
                        dialog.dismiss()
                        setupUi()
                        showSettings()
                    } else {
                        btn.isEnabled = true
                        btn.text = "متابعة"
                        Toast.makeText(this, "❌ $err", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun validateKey(key: String, onResult: (Boolean, String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url("$BASE_URL/api/app/user_info/$key").build()
                val res = client.newCall(req).execute()
                val json = JSONObject(res.body?.string() ?: "{}")
                val ok = json.optBoolean("ok")
                val err = json.optString("error", "المفتاح غير صالح، معطل، أو منتهي")
                withContext(Dispatchers.Main) { onResult(ok, err) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, "خطأ اتصال: ${e.message}") }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        statusBar = TextView(this).apply {
            text = "📊 الإعدادات"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
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
            setPadding(10, 10, 10, 10)
        }
        
        btnSettings = Button(this).apply {
            text = "📊 الإعدادات"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(5, 0, 5, 0) }
            setOnClickListener { showSettings() }
        }
        
        btnMarket = Button(this).apply {
            text = "📈 السوق"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(5, 0, 5, 0) }
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
        val bgDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#0a0a0a"), Color.parseColor("#151515"), Color.parseColor("#0a0a0a"))
        )
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = bgDrawable
        }

        tvLinkStatus = TextView(this).apply {
            text = "⏳ جاري التحميل..."
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
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
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 20, 0, 20) }
            setPadding(20, 30, 20, 30)
        }
        btnLink.setOnClickListener {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("🔗 ربط الحساب")
                .setMessage("ستُفتح صفحة Quotex. سجّل دخولك، وسيتم الربط تلقائياً.")
                .setPositiveButton("متابعة") { _, _ -> showMarket() }
                .setNegativeButton("إلغاء", null)
                .show()
        }
        layout.addView(btnLink)

        tvBalance = TextView(this).apply {
            text = "الرصيد: —"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#d4af37"))
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 10)
        }
        layout.addView(tvBalance)
        
        tvPL = TextView(this).apply {
            text = "الربح/الخسارة: —"
            textSize = 16f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }
        layout.addView(tvPL)

        layout.addView(makeLabel("نوع الحساب:"))
        spinnerAccount = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("تجريبي (Demo)", "حقيقي (Live)"))
        }
        layout.addView(spinnerAccount)

        layout.addView(makeLabel("طريقة الدخول:"))
        spinnerMode = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("نسبة مئوية من الرصيد", "مبلغ ثابت بالدولار"))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { updateHints() }
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

        btnEnableCopy = Button(this).apply {
            text = "👥 تفعيل النسخ (Copy Trading)"
            setBackgroundColor(Color.parseColor("#d4af37"))
            setTextColor(Color.BLACK)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 30, 0, 15) }
            setPadding(20, 30, 20, 30)
            setOnClickListener { saveAndEnable("copy") }
        }
        layout.addView(btnEnableCopy)

        btnEnableAuto = Button(this).apply {
            text = "🤖 تفعيل التداول الآلي (Auto)"
            setBackgroundColor(Color.parseColor("#2e7d32"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 15) }
            setPadding(20, 30, 20, 30)
            setOnClickListener { saveAndEnable("auto") }
        }
        layout.addView(btnEnableAuto)

        btnStop = Button(this).apply {
            text = "⏸️ إيقاف التداول والنسخ"
            setBackgroundColor(Color.parseColor("#c62828"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 15) }
            setPadding(20, 30, 20, 30)
            visibility = android.view.View.GONE
            setOnClickListener { stopTrading() }
        }
        layout.addView(btnStop)

        btnUnlink = Button(this).apply {
            text = "🔌 فصل الحساب"
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.parseColor("#ef9a9a"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 20, 0, 30) }
            setPadding(20, 25, 20, 25)
            visibility = android.view.View.GONE
            setOnClickListener { unlinkAccount() }
        }
        layout.addView(btnUnlink)

        sv.addView(layout)
        return sv
    }

    private fun makeLabel(txt: String): TextView = TextView(this).apply {
        text = txt
        textSize = 15f
        setTextColor(Color.parseColor("#d4af37"))
        setPadding(0, 30, 0, 10)
        setTypeface(null, Typeface.BOLD)
    }

    private fun makeNumberInput(default: String): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(default)
        setTextColor(Color.WHITE)
        textSize = 16f
        setBackgroundColor(Color.parseColor("#1a1a1a"))
        setPadding(30, 30, 30, 30)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 15) }
    }

    private fun updateHints() {
        val fixed = spinnerMode.selectedItemPosition == 1
        tvValueHint.text = if (fixed) "قيمة الدخول (\$):" else "قيمة الدخول (%):"
        tvLossHint.text = if (fixed) "🛑 إيقاف عند الخسارة (\$):" else "🛑 إيقاف عند الخسارة (%):"
        tvProfitHint.text = if (fixed) "✅ إيقاف عند الربح (\$):" else "✅ إيقاف عند الربح (%):"
    }

    private fun setFieldsEnabled(enabled: Boolean) {
        spinnerAccount.isEnabled = enabled
        spinnerMode.isEnabled = enabled
        etTradeValue.isEnabled = enabled
        etStopLoss.isEnabled = enabled
        etStopProfit.isEnabled = enabled
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
        statusBar.text = "📈 السوق"
        updateTabButtons()
        
        // إذا لم تكن الصفحة محملة، حملها. لا نعيد التحميل في كل مرة للحفاظ على الجلسة
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
                    if (!json.optBoolean("ok")) return@withContext
                    isLinked = json.optBoolean("linked")
                    
                    if (isLinked) {
                        tvLinkStatus.text = "✅ الحساب مرتبط"
                        tvLinkStatus.setTextColor(Color.parseColor("#81c784"))
                        btnLink.visibility = android.view.View.GONE
                        btnUnlink.visibility = android.view.View.VISIBLE
                    } else {
                        tvLinkStatus.text = "⚠️ الحساب غير مرتبط"
                        tvLinkStatus.setTextColor(Color.parseColor("#f0e0a0"))
                        btnLink.visibility = android.view.View.VISIBLE
                        btnUnlink.visibility = android.view.View.GONE
                    }

                    val copying = json.optBoolean("is_copying")
                    val auto = json.optBoolean("is_auto_trading")
                    isLocked = json.optBoolean("settings_locked")

                    if (isLocked) {
                        setFieldsEnabled(false)
                        btnEnableCopy.visibility = android.view.View.GONE
                        btnEnableAuto.visibility = android.view.View.GONE
                        btnStop.visibility = android.view.View.VISIBLE
                        if (copying) tvLinkStatus.append("\n👥 النسخ مفعّل")
                        else if (auto) tvLinkStatus.append("\n🤖 التداول الآلي مفعّل")
                    } else {
                        setFieldsEnabled(true)
                        btnEnableCopy.visibility = if (isLinked) android.view.View.VISIBLE else android.view.View.GONE
                        btnEnableAuto.visibility = if (isLinked) android.view.View.VISIBLE else android.view.View.GONE
                        btnStop.visibility = android.view.View.GONE
                    }

                    spinnerAccount.setSelection(if (json.optString("account_type") == "live") 1 else 0)
                    spinnerMode.setSelection(if (json.optString("trade_mode") == "fixed") 1 else 0)
                    etTradeValue.setText(json.optDouble("trade_value", 1.0).toString())
                    etStopLoss.setText(json.optDouble("max_loss_value", 0.0).toString())
                    etStopProfit.setText(json.optDouble("max_profit_value", 0.0).toString())
                    updateHints()
                }
            } catch (e: Exception) {}
        }
    }

    private fun saveAndEnable(type: String) {
        val payload = JSONObject().apply {
            put("account_type", if (spinnerAccount.selectedItemPosition == 1) "live" else "demo")
            put("trade_mode", if (spinnerMode.selectedItemPosition == 1) "fixed" else "percent")
            put("trade_value", etTradeValue.text.toString().toDoubleOrNull() ?: 1.0)
            put("max_loss_value", etStopLoss.text.toString().toDoubleOrNull() ?: 0.0)
            put("max_profit_value", etStopProfit.text.toString().toDoubleOrNull() ?: 0.0)
            put("enable_type", type)
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder()
                    .url("$BASE_URL/api/app/save_and_enable/$licenseKey")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                val json = JSONObject(res.body?.string() ?: "{}")
                withContext(Dispatchers.Main) {
                    if (json.optBoolean("ok")) {
                        Toast.makeText(this@MainActivity, "✅ تم التفعيل", Toast.LENGTH_SHORT).show()
                        fetchUserInfo()
                    } else {
                        Toast.makeText(this@MainActivity, "❌ ${json.optString("error")}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stopTrading() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder()
                    .url("$BASE_URL/api/app/stop_trading/$licenseKey")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                val json = JSONObject(res.body?.string() ?: "{}")
                withContext(Dispatchers.Main) {
                    if (json.optBoolean("ok")) {
                        Toast.makeText(this@MainActivity, "⛔ تم الإيقاف", Toast.LENGTH_SHORT).show()
                        fetchUserInfo()
                    } else {
                        Toast.makeText(this@MainActivity, "❌ ${json.optString("error")}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun unlinkAccount() {
        AlertDialog.Builder(this)
            .setTitle("فصل الحساب")
            .setMessage("سيتم مسح الجلسة وتسجيل الخروج من المتصفح. متأكد؟")
            .setPositiveButton("نعم") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val req = Request.Builder()
                            .url("$BASE_URL/api/app/unlink/$licenseKey")
                            .post("{}".toRequestBody("application/json".toMediaType()))
                            .build()
                        client.newCall(req).execute()
                        
                        withContext(Dispatchers.Main) {
                            // مسح الكوكيز والذاكرة المؤقتة بالكامل
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.removeAllCookies(null)
                            cookieManager.flush()
                            webView.clearCache(true)
                            webView.clearHistory()
                            webView.loadUrl("about:blank") // تفريغ الصفحة
                            
                            fetchUserInfo()
                            Toast.makeText(this@MainActivity, "تم فصل الحساب", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "حدث خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
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
                val req = Request.Builder().url("$BASE_URL/api/app/session_stats/$licenseKey").build()
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

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(Bridge(), "OscarBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }
            
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url?.contains("quotex.com") == true) view?.evaluateJavascript(EARLY_HOOK_JS, null)
            }
            
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("quotex.com")) {
                    view.evaluateJavascript(EARLY_HOOK_JS, null)
                    
                    // إذا كان في صفحة تسجيل الدخول، فهذا يعني أنه مسجل خروجه
                    if (url.contains("/sign-in")) {
                        if (isLinked) {
                            // المستخدم سجل خروجه من المتصفح، نقوم بفصل الحساب تلقائياً من السيرفر
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val req = Request.Builder().url("$BASE_URL/api/app/unlink/$licenseKey").post("{}".toRequestBody("application/json".toMediaType())).build()
                                    client.newCall(req).execute()
                                    withContext(Dispatchers.Main) { fetchUserInfo() }
                                } catch (e: Exception) {}
                            }
                        }
                    }
                }
            }
        }
    }

    private fun sendToServer(ssid: String) {
        if (isProcessingSSID || ssid.length < 25) return
        isProcessingSSID = true
        val cookies = CookieManager.getInstance().getCookie(QX_DOMAIN) ?: ""

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val payload = JSONObject().apply {
                    put("license_key", licenseKey)
                    put("ssid", ssid)
                    put("cookies", cookies)
                    put("email", "")
                }
                val req = Request.Builder().url("$BASE_URL/api/link").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
                val res = client.newCall(req).execute()
                val json = JSONObject(res.body?.string() ?: "{}")

                withContext(Dispatchers.Main) {
                    if (json.optBoolean("ok")) {
                        fetchUserInfo() // تحديث الواجهة بصمت
                    }
                    isProcessingSSID = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isProcessingSSID = false }
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun onSsid(ssid: String) {
            if (ssid.length < 25) return
            // إرسال بصمت في الخلفية، بدون تحديث الواجهة
            sendToServer(ssid)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTab == "market") showSettings()
        else if (::webView.isInitialized && webView.canGoBack() && webView.visibility == android.view.View.VISIBLE) webView.goBack()
        else super.onBackPressed()
    }
}
