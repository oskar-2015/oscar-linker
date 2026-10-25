package com.oscar.linker

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.delay
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
    private var isCopying = false
    private var isAutoTrading = false
    private var statsHandler: Handler? = null
    private var isProcessingSSID = false
    private var isNotificationShowing = false
    private var isShowingKeyDialog = false
    private var currentDialog: AlertDialog? = null
    private var tickCount = 0

    // ★★★ منع تصفير الحقول: نملأها مرة واحدة فقط
    private var uiPopulated = false
    private var lastServerLocked = false
    private var lastServerAccountType = "demo"

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
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
                        if (window.OscarBridge && window.OscarBridge.onSsid) window.OscarBridge.onSsid(m[1]);
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
            validateKeyWithRetry(saved) { ok, err ->
                if (ok) { setupUi(); showSettings() }
                else { kickUserOut(err) }
            }
        }
    }

    private fun kickUserOut(reason: String) {
        try { currentDialog?.dismiss() } catch (_: Exception) {}
        currentDialog = null
        isNotificationShowing = false

        Toast.makeText(this, "❌ $reason", Toast.LENGTH_LONG).show()
        prefs.edit().remove("license_key").apply()
        
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            if (::webView.isInitialized) {
                webView.clearCache(true)
                webView.clearHistory()
                webView.loadUrl("about:blank")
            }
        } catch(e: Exception) {}
        
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
            startActivity(Intent(this, MainActivity::class.java))
        }, 1500)
    }

    private fun showKeyDialog() {
        if (isShowingKeyDialog) return
        isShowingKeyDialog = true

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
            .setMessage("يرجى إدخال مفتاح التفعيل")
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("إلغاء") { _, _ -> finish() }
            .setPositiveButton("متابعة", null)
            .create()

        dialog.setOnDismissListener { isShowingKeyDialog = false }

        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val k = input.text.toString().trim().uppercase()
                if (k.isEmpty()) return@setOnClickListener
                btn.isEnabled = false
                btn.text = "جاري التحقق..."
                validateKeyWithRetry(k) { ok, err ->
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

    private fun validateKeyWithRetry(key: String, onResult: (Boolean, String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            var lastErr = "خطأ اتصال"
            for (attempt in 1..3) {
                try {
                    val req = Request.Builder().url("$BASE_URL/api/app/user_info/$key").build()
                    val res = client.newCall(req).execute()
                    
                    if (res.code == 401 || res.code == 403) {
                        val json = JSONObject(res.body?.string() ?: "{}")
                        val err = json.optString("error", "المفتاح غير صالح، معطل، أو منتهي")
                        withContext(Dispatchers.Main) { onResult(false, err) }
                        return@launch
                    }
                    
                    if (res.isSuccessful) {
                        val json = JSONObject(res.body?.string() ?: "{}")
                        withContext(Dispatchers.Main) { onResult(json.optBoolean("ok"), json.optString("error", "")) }
                        return@launch
                    }
                    
                    lastErr = "خطأ من السيرفر (${res.code})"
                } catch (e: Exception) {
                    lastErr = e.message ?: "خطأ اتصال"
                }
                
                if (attempt < 3) delay(2500L * attempt)
            }
            withContext(Dispatchers.Main) { onResult(false, "تعذر الاتصال بالخادم: $lastErr") }
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
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = android.view.View.GONE
        }

        settingsView = createSettingsView().apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
        startStatsPolling()
    }

    private fun createSettingsView(): ScrollView {
        val sv = ScrollView(this)
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#0a0a0a"), Color.parseColor("#151515"), Color.parseColor("#0a0a0a")))
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = bg
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
                .setMessage("ستُفتح صفحة Quotex. سجّل دخولك.")
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
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    // ★ نتجاهل الاختيار الأولي (عند ملء البيانات من السيرفر)
                    if (!uiPopulated) return
                    if (!isLinked || isLocked) return
                    if (!::licenseKey.isInitialized) return
                    val newType = if (pos == 1) "live" else "demo"
                    if (newType == lastServerAccountType) return
                    lastServerAccountType = newType
                    tvBalance.text = "الرصيد: ⏳ جاري التبديل..."
                    tvPL.text = ""
                    fetchStatsForAccount(newType)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
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
            text = "👥 تفعيل النسخ (Copy)"
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
    }

    private fun showMarket() {
        currentTab = "market"
        settingsView.visibility = android.view.View.GONE
        webView.visibility = android.view.View.VISIBLE
        statusBar.text = "📈 السوق"
        updateTabButtons()
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

                if (res.code == 403 || res.code == 401) {
                    withContext(Dispatchers.Main) { kickUserOut("تم إيقاف المفتاح أو حذفه") }
                    return@launch
                }

                val json = JSONObject(res.body?.string() ?: "{}")
                withContext(Dispatchers.Main) {
                    if (!json.optBoolean("ok")) {
                        kickUserOut(json.optString("error", "المفتاح غير صالح"))
                        return@withContext
                    }
                    
                    isLinked = json.optBoolean("linked")
                    val adminAutoEnabled = json.optBoolean("admin_auto_enabled", true)
                    val newLocked = json.optBoolean("settings_locked")
                    val newCopying = json.optBoolean("is_copying")
                    val newAuto = json.optBoolean("is_auto_trading")
                    val serverAccountType = json.optString("account_type", "demo")
                    
                    isLocked = newLocked
                    isCopying = newCopying
                    isAutoTrading = newAuto
                    
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
                    
                    if (isLocked) {
                        setFieldsEnabled(false)
                        btnEnableCopy.visibility = android.view.View.GONE
                        btnEnableAuto.visibility = android.view.View.GONE
                        btnStop.visibility = android.view.View.VISIBLE
                        val base = if (isLinked) "✅ الحساب مرتبط" else "⚠️ الحساب غير مرتبط"
                        tvLinkStatus.text = base + if (isCopying) "\n👥 النسخ مفعّل" else if (isAutoTrading) "\n🤖 التداول الآلي مفعّل" else ""
                    } else {
                        setFieldsEnabled(true)
                        btnEnableCopy.visibility = if (isLinked) android.view.View.VISIBLE else android.view.View.GONE
                        btnEnableAuto.visibility = if (isLinked) android.view.View.VISIBLE else android.view.View.GONE
                        btnStop.visibility = android.view.View.GONE
                    }

                    if (!adminAutoEnabled) {
                        btnEnableAuto.isEnabled = false
                        btnEnableAuto.text = "🚫 التداول الآلي معطّل (من المدير)"
                        btnEnableAuto.setBackgroundColor(Color.parseColor("#555555"))
                    } else {
                        btnEnableAuto.isEnabled = true
                        btnEnableAuto.text = "🤖 تفعيل التداول الآلي (Auto)"
                        btnEnableAuto.setBackgroundColor(Color.parseColor("#2e7d32"))
                    }

                    // ★★★ الحل الجذري: نملأ الح
