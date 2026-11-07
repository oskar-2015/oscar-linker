package com.oscar.linker

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    // ───────── الألوان ─────────
    private val cBg = Color.parseColor("#070b14")
    private val cCard = Color.parseColor("#D90f1626")
    private val cCardStroke = Color.parseColor("#24ffffff")
    private val cGold = Color.parseColor("#d4af37")
    private val cGreen = Color.parseColor("#26d0a0")
    private val cRed = Color.parseColor("#ff5c6c")
    private val cText = Color.parseColor("#f2f4f8")
    private val cMuted = Color.parseColor("#8d97ab")
    private val cChip = Color.parseColor("#1b2438")

    // ───────── العناصر ─────────
    private lateinit var tvTitleChip: TextView
    private lateinit var tvScreen: TextView
    private lateinit var webView: WebView
    private lateinit var settingsView: ScrollView
    private lateinit var btnSettings: TextView
    private lateinit var btnMarket: TextView

    private lateinit var tvLinkStatus: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvPL: TextView
    private lateinit var btnLink: TextView
    private lateinit var btnUnlink: TextView

    private lateinit var segAccount: List<TextView>
    private lateinit var segMode: List<TextView>
    private lateinit var segKind: List<TextView>

    private lateinit var etTradeValue: EditText
    private lateinit var etStopLoss: EditText
    private lateinit var etStopProfit: EditText
    private lateinit var tvValueHint: TextView
    private lateinit var tvLossHint: TextView
    private lateinit var tvProfitHint: TextView
    private val stepButtons = ArrayList<TextView>()

    private lateinit var strategyCard: LinearLayout
    private lateinit var strategyRow: LinearLayout
    private lateinit var tvStrategyDesc: TextView

    private lateinit var powerButton: FrameLayout
    private lateinit var tvPowerIcon: TextView
    private lateinit var tvPowerLabel: TextView
    private lateinit var tvPowerNote: TextView
    private var pulse: ValueAnimator? = null

    // ───────── الحالة ─────────
    private var currentTab = "settings"
    private lateinit var licenseKey: String
    private lateinit var prefs: SharedPreferences
    private var isLinked = false
    private var isLocked = false
    private var isCopying = false
    private var isAutoTrading = false
    private var adminAutoEnabled = true
    private var statsHandler: Handler? = null
    private var isProcessingSSID = false
    private var isNotificationShowing = false
    private var isShowingKeyDialog = false
    private var currentDialog: AlertDialog? = null
    private var tickCount = 0
    private var busy = false
    private var lastStatsAt = 0L

    // ★★★ منع تصفير الحقول: نملأها مرة واحدة فقط
    private var uiPopulated = false
    private var lastServerLocked = false
    private var lastServerAccountType = "demo"

    // وضع الراحة، الأحداث، الأصوات، الخلفية
    private var restMode = false
    private var qxBlockMin = 0
    private var lastAutoUnlink = 0L
    private var pairAsset: String = ""
    private var pairTries = 0
    private var pairDone = false
    private var lastEventId = -1L
    private var eventsBusy = false
    private var bgBusy = false
    private var pendingOpenAsset: String? = null
    private val recentEvents = ArrayList<JSONObject>()
    private val sound by lazy { SoundFx() }
    private lateinit var tvRest: TextView
    private lateinit var eventsCard: LinearLayout
    private lateinit var eventsList: LinearLayout
    private lateinit var pairChip: TextView
    private lateinit var bgImage: ImageView
    private lateinit var candleView: CandleBackgroundView

    // اختيارات الواجهة
    private var accountLive = false
    private var modeFixed = false
    private var autoKind = true            // true = تداول آلي، false = نسخ صفقات المدير
    private var selectedStrategies: MutableSet<String> = mutableSetOf("bot")
    private var strategiesJson = ""
    private var strategyItems: List<JSONObject> = emptyList()

    // معرّف الجهاز: يُربط به المفتاح، فلا يعمل المفتاح نفسه على جهاز آخر
    private val deviceId: String by lazy { Notifs.deviceId(this) }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Device-Id", deviceId).build())
            }
            .build()
    }

    private fun reasonOf(body: String?, fallback: String): String =
        try { JSONObject(body ?: "{}").optString("error", fallback).ifEmpty { fallback } } catch (e: Exception) { fallback }

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

    // ───────── أدوات مساعدة للتصميم ─────────
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun oval(color: Int, stroke: Int? = null, strokeDp: Int = 2): GradientDrawable =
        GradientDrawable().apply {
            setShape(GradientDrawable.OVAL)
            setColor(color)
            if (stroke != null) setStroke(dp(strokeDp), stroke)
        }

    private fun lp(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    @SuppressLint("ClickableViewAccessibility")
    private fun pressable(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    private fun text(
        t: String, size: Float, color: Int = cText, bold: Boolean = false, gravity: Int = Gravity.START
    ) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        this.gravity = gravity
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(cCard, 24, cCardStroke)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = lp(MATCH, WRAP, 0, 0, 0, 14)
    }

    private fun roundButton(label: String, bg: Int, fg: Int, sizeSp: Float = 15f): TextView =
        TextView(this).apply {
            text = label
            textSize = sizeSp
            setTextColor(fg)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = shape(bg, 30)
            setPadding(dp(18), dp(15), dp(18), dp(15))
            isClickable = true
            pressable(this)
        }

    private fun nowMs() = System.currentTimeMillis()

    private fun fmt(d: Double): String {
        val s = String.format(Locale.US, "%.2f", d)
        return s.trimEnd('0').trimEnd('.')
    }

    // ───────── دورة الحياة ─────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        prefs = getSharedPreferences("oscar_prefs", Context.MODE_PRIVATE)
        Notifs.ensureChannels(this)
        askNotificationPermission()
        pendingOpenAsset = intent?.getStringExtra("open_asset")
        val saved = prefs.getString("license_key", null)

        if (saved.isNullOrBlank()) {
            showBackgroundOnly()
            showKeyDialog()
        } else {
            licenseKey = saved
            showBackgroundOnly()
            validateKeyWithRetry(saved) { ok, err ->
                if (ok) { setupUi(); showSettings() }
                else { kickUserOut(err) }
            }
        }
    }

    private fun showBackgroundOnly() {
        val root = FrameLayout(this)
        root.addView(CandleBackgroundView(this), FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val a = intent.getStringExtra("open_asset")
        if (!a.isNullOrEmpty() && ::webView.isInitialized) openMarketForAsset(a, intent.getStringExtra("open_dir") ?: "")
    }

    override fun onResume() {
        super.onResume()
        if (::licenseKey.isInitialized && ::settingsView.isInitialized) fetchUserInfo()
    }

    override fun onDestroy() {
        super.onDestroy()
        pulse?.cancel()
        statsHandler?.removeCallbacksAndMessages(null)
    }

    private fun kickUserOut(reason: String) {
        try { currentDialog?.dismiss() } catch (_: Exception) {}
        currentDialog = null
        isNotificationShowing = false

        Toast.makeText(this, "❌ $reason", Toast.LENGTH_LONG).show()
        prefs.edit().remove("license_key").apply()
        try { stopService(Intent(this, EventService::class.java)) } catch (e: Exception) {}

        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            if (::webView.isInitialized) {
                webView.clearCache(true)
                webView.clearHistory()
                webView.loadUrl("about:blank")
            }
        } catch (e: Exception) {}

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
            setPadding(dp(22), dp(10), dp(22), dp(4))
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

    // ───────── بناء الواجهة ─────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupUi() {
        val frame = FrameLayout(this)
        frame.layoutDirection = View.LAYOUT_DIRECTION_RTL
        frame.setBackgroundColor(cBg)
        bgImage = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(bgImage, FrameLayout.LayoutParams(MATCH, MATCH))
        candleView = CandleBackgroundView(this)
        frame.addView(candleView, FrameLayout.LayoutParams(MATCH, MATCH))
        applyBackgroundFile()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // الشريط العلوي
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        titleBox.addView(text("Oskarix", 22f, cGold, true))
        tvScreen = text("📊 الإعدادات", 13f, cMuted)
        titleBox.addView(tvScreen)
        tvTitleChip = text("⚪ متوقف", 12f, cMuted, true, Gravity.CENTER).apply {
            background = shape(cChip, 20)
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        header.addView(titleBox)
        header.addView(tvTitleChip)

        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
        }
        settingsView = createSettingsView().apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        container.addView(webView)
        container.addView(settingsView)
        pairChip = text("", 13f, Color.BLACK, true, Gravity.CENTER).apply {
            background = shape(cGold, 24)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        container.addView(pairChip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(10)
        })

        // الشريط السفلي
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(Color.parseColor("#E60b1120"), 32, cCardStroke)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = lp(MATCH, WRAP, 14, 6, 14, 12)
        }
        btnSettings = roundButton("📊 الإعدادات", cChip, cText, 14f).apply {
            layoutParams = lp(0, WRAP, 4, 0, 4, 0).also { it.weight = 1f }
            setOnClickListener { showSettings() }
        }
        btnMarket = roundButton("📈 السوق", cChip, cText, 14f).apply {
            layoutParams = lp(0, WRAP, 4, 0, 4, 0).also { it.weight = 1f }
            setOnClickListener { showMarket() }
        }
        bottomBar.addView(btnSettings)
        bottomBar.addView(btnMarket)

        root.addView(header)
        root.addView(container)
        root.addView(bottomBar)
        frame.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(frame)
        configureWebView()
        startStatsPolling()
        startEventService()
    }

    private fun startEventService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, EventService::class.java))
        } catch (e: Exception) {
        }
    }

    private fun segment(labels: List<String>, onPick: (Int) -> Unit): Pair<LinearLayout, List<TextView>> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(Color.parseColor("#0d1424"), 26)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = lp(MATCH, WRAP, 0, 0, 0, 12)
        }
        val items = ArrayList<TextView>()
        labels.forEachIndexed { i, l ->
            val tv = text(l, 14f, cMuted, true, Gravity.CENTER).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                isClickable = true
                pressable(this)
                setOnClickListener { onPick(i) }
            }
            items.add(tv)
            box.addView(tv)
        }
        return Pair(box, items)
    }

    private fun styleSegment(items: List<TextView>, selected: Int, enabled: Boolean) {
        items.forEachIndexed { i, tv ->
            val on = i == selected
            tv.background = if (on) shape(if (enabled) cGold else Color.parseColor("#6b5d2b"), 22) else null
            tv.setTextColor(if (on) Color.BLACK else cMuted)
            tv.alpha = if (enabled) 1f else 0.7f
            tv.isEnabled = enabled
        }
    }

    private fun stepper(et: EditText, step: () -> Double, minV: () -> Double): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(MATCH, WRAP, 0, 0, 0, 4)
        }
        fun current(): Double = et.text.toString().toDoubleOrNull() ?: 0.0
        val minus = roundButton("−", cChip, cText, 22f).apply {
            setPadding(0, 0, 0, 0)
            background = oval(cChip, cCardStroke, 1)
            setOnClickListener {
                if (!et.isEnabled) return@setOnClickListener
                val v = maxOf(minV(), current() - step())
                et.setText(fmt(v))
            }
        }
        val plus = roundButton("+", cGold, Color.BLACK, 22f).apply {
            setPadding(0, 0, 0, 0)
            background = oval(cGold)
            setOnClickListener {
                if (!et.isEnabled) return@setOnClickListener
                et.setText(fmt(current() + step()))
            }
        }
        // أبعاد الأزرار بالبكسل الحقيقي
        minus.layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
        plus.layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
        stepButtons.add(minus)
        stepButtons.add(plus)

        et.apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(cText)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = shape(Color.parseColor("#0d1424"), 26, cCardStroke)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setSelectAllOnFocus(true)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(10), 0, dp(10), 0) }
        }
        row.addView(plus)
        row.addView(et)
        row.addView(minus)
        return row
    }

    private fun createSettingsView(): ScrollView {
        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(14))
        }

        // 1) الحساب والرصيد
        val accountCard = card()
        tvRest = text("🛠️ السيرفر في وضع الراحة مؤقتاً — لا يمكن التشغيل الآن", 13f, Color.BLACK, true, Gravity.CENTER).apply {
            background = shape(cGold, 16)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            layoutParams = lp(MATCH, WRAP, 0, 0, 0, 12)
        }
        accountCard.addView(tvRest)
        tvLinkStatus = text("⏳ جاري التحميل...", 16f, cText, true, Gravity.CENTER)
        accountCard.addView(tvLinkStatus)
        tvBalance = text("الرصيد: —", 26f, cGold, true, Gravity.CENTER).apply { setPadding(0, dp(10), 0, dp(2)) }
        accountCard.addView(tvBalance)
        tvPL = text("الربح/الخسارة: —", 15f, cMuted, false, Gravity.CENTER)
        accountCard.addView(tvPL)
        btnLink = roundButton("🔗 ربط حساب Quotex", cGold, Color.BLACK, 16f).apply {
            visibility = View.GONE
            layoutParams = lp(MATCH, WRAP, 0, 14, 0, 0)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("🔗 ربط الحساب")
                    .setMessage("ستُفتح صفحة Quotex. سجّل دخولك.")
                    .setPositiveButton("متابعة") { _, _ -> showMarket() }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        }
        accountCard.addView(btnLink)
        layout.addView(accountCard)

        // 2) نوع التشغيل
        val kindCard = card()
        kindCard.addView(text("طريقة التشغيل", 13f, cMuted, true).apply { setPadding(0, 0, 0, dp(8)) })
        val kind = segment(listOf("🤖 تداول آلي بالاستراتيجيات", "👥 نسخ صفقات المدير")) { i ->
            if (isLocked) return@segment
            autoKind = (i == 0)
            refreshAll()
        }
        segKind = kind.second
        kindCard.addView(kind.first)
        layout.addView(kindCard)

        // 3) بانر الاستراتيجيات
        strategyCard = card()
        strategyCard.addView(text("🎯 الاستراتيجيات", 17f, cGold, true))
        strategyCard.addView(text("يمكنك اختيار أكثر من استراتيجية معاً — اختيارك لك وحدك ولا يؤثر على غيرك", 12f, cMuted).apply {
            setPadding(0, dp(2), 0, dp(10))
        })
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        strategyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hs.addView(strategyRow)
        strategyCard.addView(hs)
        tvStrategyDesc = text("", 13f, cText).apply {
            setPadding(dp(4), dp(12), dp(4), 0)
            setLineSpacing(0f, 1.15f)
        }
        strategyCard.addView(tvStrategyDesc)
        layout.addView(strategyCard)

        // 4) الإعدادات
        val setCard = card()
        setCard.addView(text("⚙️ إعدادات التداول", 17f, cGold, true).apply { setPadding(0, 0, 0, dp(12)) })

        setCard.addView(text("نوع الحساب", 13f, cMuted, true).apply { setPadding(0, 0, 0, dp(6)) })
        val acc = segment(listOf("تجريبي (Demo)", "حقيقي (Live)")) { i ->
            if (isLocked) return@segment
            val live = (i == 1)
            accountLive = live
            refreshAll()
            if (uiPopulated && isLinked && ::licenseKey.isInitialized) {
                val newType = if (live) "live" else "demo"
                if (newType != lastServerAccountType) {
                    lastServerAccountType = newType
                    tvBalance.text = "الرصيد: ⏳ جاري التبديل..."
                    tvPL.text = ""
                    fetchStatsForAccount(newType)
                }
            }
        }
        segAccount = acc.second
        setCard.addView(acc.first)

        setCard.addView(text("طريقة الدخول", 13f, cMuted, true).apply { setPadding(0, 0, 0, dp(6)) })
        val mode = segment(listOf("نسبة % من الرصيد", "مبلغ ثابت \$")) { i ->
            if (isLocked) return@segment
            modeFixed = (i == 1)
            refreshAll()
        }
        segMode = mode.second
        setCard.addView(mode.first)

        // قيمة الدخول
        tvValueHint = text("قيمة الدخول (%)", 14f, cText, true).apply { setPadding(dp(4), dp(4), 0, dp(6)) }
        setCard.addView(tvValueHint)
        etTradeValue = EditText(this).apply { setText("1") }
        setCard.addView(stepper(etTradeValue, { if (modeFixed) 1.0 else 0.5 }, { if (modeFixed) 1.0 else 0.1 }))

        // الإيقاف عند الخسارة
        tvLossHint = text("🛑 إيقاف عند الخسارة (%)", 14f, cText, true).apply { setPadding(dp(4), dp(14), 0, dp(2)) }
        setCard.addView(tvLossHint)
        setCard.addView(text("اكتب 0 لتعطيل الحد", 11f, cMuted).apply { setPadding(dp(4), 0, 0, dp(6)) })
        etStopLoss = EditText(this).apply { setText("0") }
        setCard.addView(stepper(etStopLoss, { 5.0 }, { 0.0 }))

        // الإيقاف عند الربح
        tvProfitHint = text("✅ إيقاف عند الربح (%)", 14f, cText, true).apply { setPadding(dp(4), dp(14), 0, dp(2)) }
        setCard.addView(tvProfitHint)
        setCard.addView(text("اكتب 0 لتعطيل الحد", 11f, cMuted).apply { setPadding(dp(4), 0, 0, dp(6)) })
        etStopProfit = EditText(this).apply { setText("0") }
        setCard.addView(stepper(etStopProfit, { 5.0 }, { 0.0 }))
        layout.addView(setCard)

        // 5) زر التشغيل الدائري
        val powerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = lp(MATCH, WRAP, 0, 4, 0, 6)
        }
        powerButton = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(132), dp(132))
            isClickable = true
            pressable(this)
            setOnClickListener { onPowerPressed() }
        }
        val powerInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        tvPowerIcon = text("⏻", 40f, Color.BLACK, true, Gravity.CENTER)
        tvPowerLabel = text("تشغيل", 15f, Color.BLACK, true, Gravity.CENTER)
        powerInner.addView(tvPowerIcon)
        powerInner.addView(tvPowerLabel)
        powerButton.addView(powerInner, FrameLayout.LayoutParams(MATCH, MATCH))
        powerBox.addView(powerButton)
        tvPowerNote = text("", 12f, cMuted, false, Gravity.CENTER).apply { setPadding(dp(10), dp(10), dp(10), 0) }
        powerBox.addView(tvPowerNote)
        layout.addView(powerBox)

        // 5.5) آخر الصفقات (اضغط لفتح الزوج في السوق)
        eventsCard = card().apply { visibility = View.GONE }
        eventsCard.addView(text("🕒 آخر العمليات", 17f, cGold, true))
        eventsCard.addView(text("اضغط على أي صفقة لفتح زوجها في السوق", 12f, cMuted).apply { setPadding(0, dp(2), 0, dp(10)) })
        eventsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        eventsCard.addView(eventsList)
        layout.addView(eventsCard)

        val btnNotif = roundButton("🔔 إعدادات الإشعارات والأصوات", cChip, cText, 14f).apply {
            layoutParams = lp(MATCH, WRAP, 40, 6, 40, 0)
            setOnClickListener { showNotificationSettings() }
        }
        layout.addView(btnNotif)

        // 6) فصل الحساب
        btnUnlink = roundButton("🔌 فصل الحساب", Color.parseColor("#2a1d24"), Color.parseColor("#ef9a9a"), 14f).apply {
            visibility = View.GONE
            layoutParams = lp(MATCH, WRAP, 40, 10, 40, 24)
            setOnClickListener { unlinkAccount() }
        }
        layout.addView(btnUnlink)

        sv.addView(layout)
        refreshAll()
        return sv
    }

    // ───────── تحديث الواجهة حسب الحالة ─────────
    private fun refreshAll() {
        if (!::segAccount.isInitialized || !::segMode.isInitialized || !::segKind.isInitialized) return
        val editable = !isLocked
        styleSegment(segKind, if (autoKind) 0 else 1, editable)
        styleSegment(segAccount, if (accountLive) 1 else 0, editable)
        styleSegment(segMode, if (modeFixed) 1 else 0, editable)

        val unit = if (modeFixed) "\$" else "%"
        tvValueHint.text = "قيمة الدخول ($unit)"
        tvLossHint.text = "🛑 إيقاف عند الخسارة ($unit)"
        tvProfitHint.text = "✅ إيقاف عند الربح ($unit)"

        for (et in listOf(etTradeValue, etStopLoss, etStopProfit)) {
            et.isEnabled = editable
            et.alpha = if (editable) 1f else 0.6f
        }
        for (b in stepButtons) b.alpha = if (editable) 1f else 0.4f

        strategyCard.visibility = if (autoKind) View.VISIBLE else View.GONE
        renderStrategies()
        refreshPower()
        refreshHeaderChip()
    }

    private fun refreshHeaderChip() {
        if (!::tvTitleChip.isInitialized) return
        if (isLocked && isAutoTrading) {
            tvTitleChip.text = "🟢 تداول آلي يعمل"
            tvTitleChip.setTextColor(cGreen)
        } else if (isLocked && isCopying) {
            tvTitleChip.text = "🟢 النسخ يعمل"
            tvTitleChip.setTextColor(cGreen)
        } else {
            tvTitleChip.text = "⚪ متوقف"
            tvTitleChip.setTextColor(cMuted)
        }
    }

    private var lastPowerKey = ""

    private fun refreshPower(force: Boolean = false) {
        if (!::powerButton.isInitialized) return
        val running = isLocked
        val canStart = isLinked && !restMode && !(autoKind && !adminAutoEnabled)
        val key = "$running|$canStart|$autoKind|$isAutoTrading|$isLinked|$adminAutoEnabled|$restMode"
        if (!force && key == lastPowerKey) return
        lastPowerKey = key
        pulse?.cancel()
        powerButton.scaleX = 1f
        powerButton.scaleY = 1f

        when {
            running -> {
                powerButton.background = oval(cRed, Color.parseColor("#55ff5c6c"), 6)
                tvPowerIcon.setTextColor(Color.WHITE)
                tvPowerLabel.setTextColor(Color.WHITE)
                tvPowerLabel.text = "إيقاف"
                tvPowerNote.text = if (isAutoTrading) "التداول الآلي يعمل الآن — اضغط للإيقاف"
                else "النسخ يعمل الآن — اضغط للإيقاف"
                startPulse()
            }
            canStart -> {
                powerButton.background = oval(cGreen, Color.parseColor("#5526d0a0"), 6)
                tvPowerIcon.setTextColor(Color.BLACK)
                tvPowerLabel.setTextColor(Color.BLACK)
                tvPowerLabel.text = "تشغيل"
                tvPowerNote.text = if (autoKind) "ابدأ التداول الآلي بالاستراتيجية المختارة" else "ابدأ نسخ صفقات المدير"
            }
            else -> {
                powerButton.background = oval(Color.parseColor("#3a4256"), Color.parseColor("#33ffffff"), 4)
                tvPowerIcon.setTextColor(cMuted)
                tvPowerLabel.setTextColor(cMuted)
                tvPowerLabel.text = "تشغيل"
                tvPowerNote.text = when {
                    restMode -> "🛠️ السيرفر في وضع الراحة مؤقتاً، حاول لاحقاً"
                    !isLinked -> "اربط حساب Quotex أولاً"
                    else -> "🚫 التداول الآلي متوقف من المدير حالياً"
                }
            }
        }
    }

    private fun startPulse() {
        pulse = ValueAnimator.ofFloat(1f, 1.07f).apply {
            duration = 1100
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                powerButton.scaleX = v
                powerButton.scaleY = v
            }
            start()
        }
    }

    private var lastStrategySig = ""

    private fun renderStrategies() {
        if (!::strategyRow.isInitialized) return
        val sig = strategiesJson + "|" + selectedStrategies.sorted().joinToString(",") + "|" + isLocked
        if (sig == lastStrategySig) return
        lastStrategySig = sig
        strategyRow.removeAllViews()
        for (item in strategyItems) {
            val sid = item.optString("id")
            val isEn = item.optBoolean("enabled", true)
            val isOn = selectedStrategies.contains(sid)

            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = if (isOn) shape(Color.parseColor("#26d4af37"), 22, cGold)
                else shape(Color.parseColor("#0d1424"), 22, cCardStroke)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(dp(150), WRAP).apply { setMargins(0, 0, dp(10), 0) }
                alpha = if (isEn) 1f else 0.45f
                isClickable = true
                pressable(this)
                setOnClickListener {
                    if (isLocked) {
                        Toast.makeText(this@MainActivity, "أوقف التشغيل أولاً ثم غيّر الاستراتيجيات", Toast.LENGTH_SHORT).show()
                    } else if (!isEn) {
                        Toast.makeText(this@MainActivity, "هذه الاستراتيجية معطّلة من المدير حالياً", Toast.LENGTH_SHORT).show()
                    } else {
                        if (selectedStrategies.contains(sid)) {
                            if (selectedStrategies.size > 1) selectedStrategies.remove(sid)
                            else Toast.makeText(this@MainActivity, "يجب اختيار استراتيجية واحدة على الأقل", Toast.LENGTH_SHORT).show()
                        } else {
                            selectedStrategies.add(sid)
                        }
                        renderStrategies()
                    }
                }
            }
            val badge = text(item.optString("icon", "🎯"), 26f, cText, false, Gravity.CENTER).apply {
                background = oval(if (isOn) cGold else cChip)
                layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
            }
            c.addView(badge)
            c.addView(text(item.optString("name"), 14f, if (isOn) cGold else cText, true, Gravity.CENTER).apply {
                setPadding(0, dp(8), 0, dp(2))
            })
            c.addView(text(if (isEn) item.optString("tagline") else "معطّلة من المدير", 11f,
                if (isEn) cMuted else cRed, false, Gravity.CENTER))
            c.addView(text(if (isOn) "✔ مختارة" else "اضغط للاختيار", 11f,
                if (isOn) cGreen else cMuted, true, Gravity.CENTER).apply { setPadding(0, dp(6), 0, 0) })
            strategyRow.addView(c)
        }
        val chosen = strategyItems.filter { selectedStrategies.contains(it.optString("id")) }
        tvStrategyDesc.text = when {
            chosen.isEmpty() -> ""
            chosen.size == 1 -> chosen[0].optString("desc")
            else -> "تعمل معاً: " + chosen.joinToString(" + ") { it.optString("name") }
        }
    }

    private fun onPowerPressed() {
        if (busy) return
        if (isLocked) { stopTrading(); return }
        if (!isLinked) {
            Toast.makeText(this, "اربط حساب Quotex أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        if (restMode) {
            Toast.makeText(this, "السيرفر في وضع الراحة مؤقتاً، حاول لاحقاً", Toast.LENGTH_SHORT).show()
            return
        }
        if (autoKind && !adminAutoEnabled) {
            Toast.makeText(this, "التداول الآلي متوقف من المدير حالياً", Toast.LENGTH_SHORT).show()
            return
        }
        if (autoKind && selectedStrategies.isEmpty()) {
            Toast.makeText(this, "اختر استراتيجية واحدة على الأقل", Toast.LENGTH_SHORT).show()
            return
        }
        saveAndEnable(if (autoKind) "auto" else "copy")
    }

    private fun showSettings() {
        currentTab = "settings"
        webView.visibility = View.GONE
        settingsView.visibility = View.VISIBLE
        tvScreen.text = "📊 الإعدادات"
        if (::pairChip.isInitialized) pairChip.visibility = View.GONE
        updateTabButtons()
        fetchUserInfo()
        pendingOpenAsset?.let { a -> pendingOpenAsset = null; openMarketForAsset(a, "") }
    }

    private fun showMarket() {
        currentTab = "market"
        settingsView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        tvScreen.text = "📈 السوق"
        updateTabButtons()
        if (webView.url == null || !webView.url!!.contains("quotex.com")) {
            webView.loadUrl("$QX_DOMAIN/ar/trade")
        }
    }

    private fun updateTabButtons() {
        val on = currentTab == "settings"
        btnSettings.background = shape(if (on) cGold else cChip, 26)
        btnSettings.setTextColor(if (on) Color.BLACK else cText)
        btnMarket.background = shape(if (!on) cGold else cChip, 26)
        btnMarket.setTextColor(if (!on) Color.BLACK else cText)
    }

    // ───────── الاتصال بالخادم ─────────
    private fun fetchUserInfo() {
        if (!::licenseKey.isInitialized) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url("$BASE_URL/api/app/user_info/$licenseKey").build()
                val res = client.newCall(req).execute()

                if (res.code == 403 || res.code == 401) {
                    val why = reasonOf(res.body?.string(), "تم إيقاف المفتاح أو حذفه")
                    withContext(Dispatchers.Main) { kickUserOut(why) }
                    return@launch
                }

                val json = JSONObject(res.body?.string() ?: "{}")
                withContext(Dispatchers.Main) {
                    if (!json.optBoolean("ok")) {
                        kickUserOut(json.optString("error", "المفتاح غير صالح"))
                        return@withContext
                    }

                    isLinked = json.optBoolean("linked")
                    adminAutoEnabled = json.optBoolean("admin_auto_enabled", true)
                    val newLocked = json.optBoolean("settings_locked")
                    isCopying = json.optBoolean("is_copying")
                    isAutoTrading = json.optBoolean("is_auto_trading")
                    val serverAccountType = json.optString("account_type", "demo")
                    val serverStrategy = json.optString("strategy", "bot")
                    restMode = json.optBoolean("rest_mode", false)
                    val serverSelected = LinkedHashSet<String>()
                    val selArr = json.optJSONArray("selected_strategies")
                    if (selArr != null) for (i in 0 until selArr.length()) serverSelected.add(selArr.getString(i))
                    if (serverSelected.isEmpty()) serverSelected.add(serverStrategy)
                    isLocked = newLocked

                    // قائمة الاستراتيجيات (نعيد الرسم فقط عند التغيير)
                    val arr = json.optJSONArray("strategies")
                    if (arr != null) {
                        val s = arr.toString()
                        if (s != strategiesJson) {
                            strategiesJson = s
                            val list = ArrayList<JSONObject>()
                            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                            strategyItems = list
                        }
                    }

                    if (isLinked) {
                        tvLinkStatus.text = "✅ الحساب مرتبط"
                        tvLinkStatus.setTextColor(cGreen)
                        btnLink.visibility = View.GONE
                        btnUnlink.visibility = View.VISIBLE
                    } else {
                        tvLinkStatus.text = "⚠️ الحساب غير مرتبط"
                        tvLinkStatus.setTextColor(Color.parseColor("#f0e0a0"))
                        btnLink.visibility = View.VISIBLE
                        btnUnlink.visibility = View.GONE
                    }
                    if (isLocked) {
                        val base = if (isLinked) "✅ الحساب مرتبط" else "⚠️ الحساب غير مرتبط"
                        tvLinkStatus.text = base + if (isCopying) "\n👥 النسخ مفعّل" else if (isAutoTrading) "\n🤖 التداول الآلي مفعّل" else ""
                    }

                    // ★★★ نملأ الحقول فقط في المرة الأولى، أو عندما يصبح الحساب مقفلاً
                    val shouldPopulate = !uiPopulated || (newLocked && !lastServerLocked)
                    if (shouldPopulate) {
                        lastServerAccountType = serverAccountType
                        accountLive = serverAccountType == "live"
                        modeFixed = json.optString("trade_mode") == "fixed"
                        etTradeValue.setText(fmt(json.optDouble("trade_value", 1.0)))
                        etStopLoss.setText(fmt(json.optDouble("max_loss_value", 0.0)))
                        etStopProfit.setText(fmt(json.optDouble("max_profit_value", 0.0)))
                        selectedStrategies = serverSelected.toMutableSet()
                        uiPopulated = true
                    }
                    if (isLocked) {
                        // أثناء التشغيل نعرض ما هو مُفعّل فعلاً على الخادم
                        autoKind = isAutoTrading || !isCopying
                        selectedStrategies = serverSelected.toMutableSet()
                        accountLive = serverAccountType == "live"
                    }
                    lastServerLocked = newLocked

                    qxBlockMin = json.optInt("qx_block_min", 0)
                    if (restMode) {
                        tvRest.text = "🛠️ السيرفر في وضع الراحة مؤقتاً — لا يمكن التشغيل الآن"
                        tvRest.visibility = View.VISIBLE
                    } else if (qxBlockMin > 0) {
                        tvRest.text = "⏳ Quotex يحظر اتصال السيرفر مؤقتاً (نحو " + qxBlockMin + " دقيقة). لا يمكن الاتصال الآن، وسيعمل التداول تلقائياً بعد فك الحظر"
                        tvRest.visibility = View.VISIBLE
                    } else {
                        tvRest.visibility = View.GONE
                    }
                    refreshAll()

                    if (lastEventId < 0) {
                        val savedId = prefs.getLong("last_event_id_" + licenseKey, -1L)
                        lastEventId = if (savedId >= 0) savedId else json.optLong("last_event_id", 0L)
                        prefs.edit().putLong("last_event_id_" + licenseKey, lastEventId).apply()
                    }
                    syncBackground(json.optString("background_version", "0"))

                    // أثناء التشغيل نحدّث الرصيد كل 15 ثانية تقريباً
                    if (isLinked && isLocked && uiPopulated && nowMs() - lastStatsAt > 15000) {
                        fetchStatsForAccount(serverAccountType)
                    }

                    val notification = json.optString("notification", "")
                    if (notification.isNotEmpty() && !isNotificationShowing) {
                        showCenterNotification(notification)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun showCenterNotification(msg: String) {
        isNotificationShowing = true
        currentDialog = AlertDialog.Builder(this)
            .setTitle("🔔 إشعار هام")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("حسناً") { _, _ ->
                isNotificationShowing = false
                currentDialog = null
                clearNotification()
            }
            .show()
    }

    private fun clearNotification() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder()
                    .url("$BASE_URL/api/app/clear_notification/$licenseKey")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute()
                withContext(Dispatchers.Main) { fetchUserInfo() }
            } catch (e: Exception) {}
        }
    }

    private fun saveAndEnable(type: String) {
        busy = true
        tvPowerLabel.text = "..."
        val accountType = if (accountLive) "live" else "demo"
        val payload = JSONObject().apply {
            put("account_type", accountType)
            put("trade_mode", if (modeFixed) "fixed" else "percent")
            put("trade_value", etTradeValue.text.toString().toDoubleOrNull() ?: 1.0)
            put("max_loss_value", etStopLoss.text.toString().toDoubleOrNull() ?: 0.0)
            put("max_profit_value", etStopProfit.text.toString().toDoubleOrNull() ?: 0.0)
            put("enable_type", type)
            val chosen = if (type == "auto") selectedStrategies.toList() else listOf("bot")
            put("strategies", JSONArray(chosen))
            put("strategy", chosen.firstOrNull() ?: "bot")
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder()
                    .url("$BASE_URL/api/app/save_and_enable/$licenseKey")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                if (res.code == 403) {
                    val why = reasonOf(res.body?.string(), "تم إيقاف المفتاح")
                    withContext(Dispatchers.Main) { busy = false; kickUserOut(why) }
                    return@launch
                }
                val json = JSONObject(res.body?.string() ?: "{}")
                withContext(Dispatchers.Main) {
                    busy = false
                    if (json.optBoolean("ok")) {
                        val bal = json.optDouble("balance", 0.0)
                        val accName = if (accountType == "live") "حقيقي" else "تجريبي"
                        if (!json.optBoolean("pending")) {
                            tvBalance.text = String.format(Locale.US, "\$%.2f", bal)
                            tvPL.text = "الرصيد ($accName)"
                            tvPL.setTextColor(cMuted)
                        }
                        if (json.optBoolean("pending")) {
                            tvBalance.text = "الرصيد: بانتظار الاتصال"
                            tvPL.text = "⏳ Quotex يحظر الاتصال مؤقتاً، سيبدأ التداول تلقائياً عند فك الحظر"
                            Toast.makeText(this@MainActivity, "✅ تم التفعيل — بانتظار فك حظر Quotex", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "✅ تم التفعيل", Toast.LENGTH_SHORT).show()
                        }
                        uiPopulated = true
                        lastPowerKey = ""
                        fetchUserInfo()
                    } else {
                        val err = json.optString("error", "خطأ")
                        Toast.makeText(this@MainActivity, "❌ $err", Toast.LENGTH_LONG).show()
                        refreshPower(true)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    busy = false
                    refreshPower(true)
                    Toast.makeText(this@MainActivity, "خطأ اتصال: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stopTrading() {
        busy = true
        tvPowerLabel.text = "..."
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder()
                    .url("$BASE_URL/api/app/stop_trading/$licenseKey")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute()
                withContext(Dispatchers.Main) {
                    busy = false
                    Toast.makeText(this@MainActivity, "⛔ تم الإيقاف", Toast.LENGTH_SHORT).show()
                    lastPowerKey = ""
                    fetchUserInfo()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { busy = false; refreshPower(true) }
            }
        }
    }

    private fun isLoginUrl(u: String?): Boolean {
        val x = (u ?: "").lowercase()
        return x.contains("/sign-in") || x.contains("/signin") || x.contains("/login")
    }

    // إذا خرج المستخدم من Quotex (أو انتهت الجلسة) نفصل الحساب في الإعدادات تلقائياً
    private fun checkLoggedOut(url: String) {
        if (!isLinked || !::licenseKey.isInitialized || !isLoginUrl(url)) return
        if (System.currentTimeMillis() - lastAutoUnlink < 30000) return
        lastAutoUnlink = System.currentTimeMillis()
        Handler(Looper.getMainLooper()).postDelayed({
            if (isLinked && isLoginUrl(webView.url)) {
                Toast.makeText(this, "🔌 تم فصل الحساب تلقائياً لأنك خرجت من Quotex", Toast.LENGTH_LONG).show()
                doUnlink()
            }
        }, 2500)
    }

    private fun doUnlink() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder()
                    .url("$BASE_URL/api/app/unlink/$licenseKey")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute()
                withContext(Dispatchers.Main) {
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies(null)
                    cm.flush()
                    webView.clearCache(true)
                    webView.clearHistory()
                    webView.loadUrl("about:blank")
                    uiPopulated = false
                    tvBalance.text = "الرصيد: —"
                    tvPL.text = ""
                    fetchUserInfo()
                }
            } catch (e: Exception) {
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
                        withContext(Dispatchers.Main) {
                            val cm = CookieManager.getInstance()
                            cm.removeAllCookies(null)
                            cm.flush()
                            webView.clearCache(true)
                            webView.clearHistory()
                            webView.loadUrl("about:blank")
                            uiPopulated = false  // ★ إعادة التهيئة
                            tvBalance.text = "الرصيد: —"
                            tvPL.text = ""
                            fetchUserInfo()
                        }
                    } catch (e: Exception) {}
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun startStatsPolling() {
        statsHandler?.removeCallbacksAndMessages(null)
        statsHandler = Handler(Looper.getMainLooper())
        tickCount = 0
        val runnable = object : Runnable {
            override fun run() {
                tickCount++
                if (::licenseKey.isInitialized) {
                    // user_info كل 5 ثوانٍ: حتى ينطفئ الزر بسرعة عندما يوقف المدير التداول
                    fetchUserInfo()
                    fetchEvents()
                    // الرصيد كل دقيقة تقريباً على شاشة الإعدادات عند عدم التشغيل
                    if (currentTab == "settings" && isLinked && !isLocked && tickCount % 12 == 0) {
                        fetchStatsForAccount(if (accountLive) "live" else "demo")
                    }
                }
                statsHandler?.postDelayed(this, 5000)
            }
        }
        statsHandler?.postDelayed(runnable, 3000)
    }

    private fun fetchStatsForAccount(accType: String) {
        if (!::licenseKey.isInitialized) return
        lastStatsAt = nowMs()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url("$BASE_URL/api/app/session_stats/$licenseKey?account=$accType").build()
                val res = client.newCall(req).execute()

                if (res.code == 401 || res.code == 403) {
                    val why = reasonOf(res.body?.string(), "تم إيقاف المفتاح")
                    withContext(Dispatchers.Main) { kickUserOut(why) }
                    return@launch
                }

                val json = JSONObject(res.body?.string() ?: "{}")
                withContext(Dispatchers.Main) {
                    val accName = if (accType == "live") "حقيقي" else "تجريبي"
                    if (json.optBoolean("ok")) {
                        val balance = json.optDouble("balance", 0.0)
                        val diff = json.optDouble("diff", 0.0)
                        val pct = json.optDouble("pct", 0.0)
                        tvBalance.text = String.format(Locale.US, "\$%.2f", balance)
                        if (diff > 0) {
                            tvPL.text = String.format(Locale.US, "▲ +\$%.2f (+%.2f%%) • %s", diff, pct, accName)
                            tvPL.setTextColor(cGreen)
                        } else if (diff < 0) {
                            tvPL.text = String.format(Locale.US, "▼ -\$%.2f (%.2f%%) • %s", Math.abs(diff), pct, accName)
                            tvPL.setTextColor(cRed)
                        } else {
                            tvPL.text = "الرصيد ($accName) • بدون تغيير"
                            tvPL.setTextColor(cMuted)
                        }
                    } else {
                        val err = json.optString("error", "")
                        val msg = when {
                            err.contains("cooldown") -> "⏳ الخادم محظور مؤقتاً"
                            err.contains("rejected") -> "⚠️ أعد الربط من السوق"
                            err.contains("no_session") -> "⚠️ الحساب غير مرتبط"
                            else -> "تعذر الجلب"
                        }
                        tvBalance.text = "الرصيد ($accName)"
                        tvPL.text = msg
                        tvPL.setTextColor(cMuted)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvPL.text = "تعذر الاتصال"
                }
            }
        }
    }

    // ───────── الإشعارات والأصوات والأحداث ─────────
    private fun askNotificationPermission() {
        if (!Notifs.canNotify(this)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        }
    }

    private fun prettyAsset(raw: String): String = Notifs.prettyAsset(raw)
    private fun dirAr(d: String): String = Notifs.dirAr(d)

    private fun fetchEvents() {
        if (!::licenseKey.isInitialized || lastEventId < 0 || eventsBusy) return
        eventsBusy = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url("$BASE_URL/api/app/events/$licenseKey?after=$lastEventId").build()
                val res = client.newCall(req).execute()
                val body = res.body?.string() ?: "{}"
                if (res.isSuccessful) {
                    val arr = JSONObject(body).optJSONArray("events")
                    val list = ArrayList<JSONObject>()
                    if (arr != null) for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                    if (list.isNotEmpty()) withContext(Dispatchers.Main) { handleEvents(list) }
                }
            } catch (e: Exception) {
            } finally {
                eventsBusy = false
            }
        }
    }

    private fun handleEvents(list: List<JSONObject>) {
        // الأصوات والإشعارات تتولاها EventService؛ هنا نحدّث القائمة فقط
        for (ev in list) {
            val id = ev.optLong("id")
            if (id <= lastEventId) continue
            lastEventId = id
            recentEvents.add(0, ev)
        }
        while (recentEvents.size > 15) recentEvents.removeAt(recentEvents.size - 1)
        prefs.edit().putLong("last_event_id_" + licenseKey, lastEventId).apply()
        renderEvents()
    }

    private fun renderEvents() {
        if (!::eventsList.isInitialized) return
        eventsList.removeAllViews()
        eventsCard.visibility = if (recentEvents.isEmpty()) View.GONE else View.VISIBLE
        for (ev in recentEvents) {
            val kind = ev.optString("kind")
            val asset = ev.optString("asset")
            val dir = ev.optString("direction")
            val line1: String
            val line2: String
            val amt = ev.optDouble("amount", 0.0)
            when (kind) {
                "trade_open" -> {
                    line1 = "تم تنفيذ صفقة على " + prettyAsset(asset) + " " + dirAr(dir)
                    line2 = "باستراتيجية: " + ev.optString("strategy_name") + " • مبلغ " + fmt(amt) + "\$ • اضغط للفتح في السوق"
                }
                "trade_win" -> {
                    line1 = "✅ ربحت صفقة " + prettyAsset(asset)
                    line2 = (if (amt > 0) "ربح +" + fmt(amt) + "\$ • " else "") + ev.optString("strategy_name")
                }
                "trade_loss" -> {
                    line1 = "❌ خسرت صفقة " + prettyAsset(asset)
                    line2 = "خسارة " + fmt(Math.abs(amt)) + "\$ • " + ev.optString("strategy_name")
                }
                "trade_draw" -> {
                    line1 = "➖ تعادل في صفقة " + prettyAsset(asset)
                    line2 = "استُرد المبلغ • " + ev.optString("strategy_name")
                }
                "session_win" -> { line1 = "🎉 انتهت الجلسة بربح"; line2 = "وصلت لحد الربح" }
                else -> { line1 = "⚠️ انتهت الجلسة بخسارة"; line2 = "وصلت لحد الخسارة" }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(Color.parseColor("#0d1424"), 18, cCardStroke)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = lp(MATCH, WRAP, 0, 0, 0, 8)
                if (kind.startsWith("trade_")) {
                    isClickable = true
                    pressable(this)
                    setOnClickListener { openMarketForAsset(asset, dir) }
                }
            }
            row.addView(text(line1, 14f, cText, true))
            row.addView(text(line2, 11f, cMuted))
            eventsList.addView(row)
        }
    }

    private val SELECT_ASSET_JS = """
(function(raw){
  var done=false;
  function finish(ok){ if(done) return; done=true; try{ OscarBridge.onPair(ok); }catch(e){} }
  try {
    var wantRaw=String(raw);
    var isOtc=/_otc${'$'}/i.test(wantRaw);
    var want=wantRaw.replace(/_otc${'$'}/i,'').toUpperCase().replace(/[^A-Z0-9]/g,'');
    function norm(t){ return String(t||'').toUpperCase().replace(/[^A-Z0-9]/g,''); }
    function vis(e){ var r=e.getBoundingClientRect(); return r.width>0 && r.height>0; }
    function fire(e){
      try{ e.scrollIntoView({block:'center'}); }catch(x){}
      ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t){
        try{ e.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window})); }catch(x){}
      });
    }
    function findItem(){
      var nodes=document.querySelectorAll('li,div,span,a,button');
      var best=null, bestLen=1e9;
      for(var i=0;i<nodes.length;i++){
        var e=nodes[i]; if(!vis(e)) continue;
        var t=norm(e.innerText); if(!t || t.indexOf(want)<0) continue;
        var hasOtc=t.indexOf('OTC')>=0; if(isOtc!==hasOtc) continue;
        if(t.length<bestLen && t.length<=want.length+22){ best=e; bestLen=t.length; }
      }
      return best;
    }
    var it=findItem();
    if(it){ fire(it); finish(true); return; }
    var openers=document.querySelectorAll('[class*="asset-select"],[class*="current-asset"],[class*="assets-select"],[class*="asset__"],[class*="Asset"]');
    for(var i=0;i<openers.length;i++){ if(vis(openers[i])){ fire(openers[i]); break; } }
    setTimeout(function(){
      var inp=document.querySelector('input[type="search"],input[class*="search"],input[placeholder*="earch"],input[placeholder*="بحث"]');
      if(inp){
        inp.focus();
        var setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
        setter.call(inp,want); inp.dispatchEvent(new Event('input',{bubbles:true}));
      }
      setTimeout(function(){ var it2=findItem(); if(it2){ fire(it2); finish(true); } else { finish(false); } }, 900);
    }, 700);
  } catch(e) { finish(false); }
})(__ASSET__);
""".trimIndent()

    // لقطة تشخيص: تجمع وصف العناصر التي تحمل اسم الزوج لتساعد في ضبط الاختيار التلقائي
    private val PAIR_DEBUG_JS = """
(function(raw){
  try {
    var want=String(raw).replace(/_otc${'$'}/i,'').toUpperCase().replace(/[^A-Z0-9]/g,'');
    var out=[];
    var nodes=document.querySelectorAll('li,div,span,a,button,input');
    for(var i=0;i<nodes.length && out.length<14;i++){
      var e=nodes[i]; var t=(e.innerText||e.placeholder||'').toUpperCase().replace(/[^A-Z0-9]/g,'');
      if(t && t.indexOf(want)>=0 && t.length<=want.length+24){
        out.push(e.tagName+'.'+String(e.className).slice(0,80)+' | '+(e.innerText||'').replace(/\s+/g,' ').slice(0,40));
      }
    }
    var inputs=document.querySelectorAll('input');
    for(var j=0;j<inputs.length && j<6;j++){ out.push('INPUT.'+String(inputs[j].className).slice(0,60)+' ph='+inputs[j].placeholder); }
    OscarBridge.onPairDebug(out.join('\n'));
  } catch(e) { OscarBridge.onPairDebug('error '+e); }
})(__ASSET__);
""".trimIndent()

    private fun runPairSelect() {
        if (pairDone || pairAsset.isEmpty() || currentTab != "market") return
        pairTries++
        webView.evaluateJavascript(SELECT_ASSET_JS.replace("__ASSET__", JSONObject.quote(pairAsset)), null)
        if (pairTries < 4) {
            Handler(Looper.getMainLooper()).postDelayed({ runPairSelect() }, 3500)
        } else if (!pairDone) {
            pairChip.text = "⚠️ اختر الزوج يدوياً: " + prettyAsset(pairAsset) + "   (اضغط مطولاً للتشخيص)"
        }
    }

    private fun openMarketForAsset(asset: String, dir: String) {
        showMarket()
        pairAsset = asset
        pairTries = 0
        pairDone = false
        pairChip.text = "📌 جارٍ فتح الزوج: " + prettyAsset(asset) + (if (dir.isNotEmpty()) " · " + dirAr(dir) else "") + " ..."
        pairChip.visibility = View.VISIBLE
        pairChip.setOnClickListener { pairChip.visibility = View.GONE }
        pairChip.setOnLongClickListener {
            webView.evaluateJavascript(PAIR_DEBUG_JS.replace("__ASSET__", JSONObject.quote(pairAsset)), null)
            true
        }
        Handler(Looper.getMainLooper()).postDelayed({ runPairSelect() }, 2000)
    }

    private fun showNotificationSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }
        fun row(label: String, key: String) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            r.addView(TextView(this).apply {
                text = label
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            r.addView(Switch(this).apply {
                isChecked = prefs.getBoolean(key, true)
                setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(key, c).apply() }
            })
            box.addView(r)
        }
        row("إظهار الإشعارات", "notif_enabled")
        row("🔊 صوت المال عند تنفيذ صفقة", "snd_open")
        row("🔔 رنين المال عند الانتهاء بربح", "snd_win")
        row("😢 صوت حزين عند الانتهاء بخسارة", "snd_loss")
        val tests = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        fun test(label: String, play: () -> Unit) {
            tests.addView(roundButton(label, cChip, cText, 12f).apply {
                layoutParams = lp(0, WRAP, 3, 0, 3, 0).also { it.weight = 1f }
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener { play() }
            })
        }
        test("تجربة: صفقة") { sound.cash() }
        test("تجربة: ربح") { sound.win() }
        test("تجربة: خسارة") { sound.loss() }
        box.addView(tests)
        box.addView(roundButton("🔋 السماح بالعمل في الخلفية", cChip, cText, 12f).apply {
            layoutParams = lp(MATCH, WRAP, 0, 12, 0, 0)
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) {}
            }
        })
        box.addView(text("لتصلك الإشعارات والأصوات والتطبيق مغلق: اسمح للتطبيق بالعمل في الخلفية بدون تقييد البطارية.", 11f, cMuted).apply {
            setPadding(0, dp(6), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("🔔 إعدادات الإشعارات والأصوات")
            .setView(box)
            .setPositiveButton("تم", null)
            .show()
    }

    // ───────── خلفية من المدير ─────────
    private fun bgFile() = File(filesDir, "oscar_bg.img")

    private fun applyBackgroundFile() {
        try {
            val f = bgFile()
            if (!f.exists() || !::bgImage.isInitialized) return
            val bmp = BitmapFactory.decodeFile(f.path) ?: return
            bgImage.setImageBitmap(bmp)
            if (::candleView.isInitialized) candleView.setImageMode(true)
        } catch (e: Exception) {
        }
    }

    private fun syncBackground(version: String) {
        if (!::bgImage.isInitialized) return
        if (version.isEmpty() || version == "0") {
            if (bgFile().exists()) {
                bgFile().delete()
                prefs.edit().remove("bg_version").apply()
                bgImage.setImageDrawable(null)
                candleView.setImageMode(false)
            }
            return
        }
        if (version == prefs.getString("bg_version", "") || bgBusy) return
        bgBusy = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url("$BASE_URL/api/app/background/$licenseKey").build()
                val res = client.newCall(req).execute()
                if (res.isSuccessful) {
                    val bytes = res.body?.bytes()
                    if (bytes != null && bytes.size > 100) {
                        bgFile().writeBytes(bytes)
                        prefs.edit().putString("bg_version", version).apply()
                        withContext(Dispatchers.Main) { applyBackgroundFile() }
                    }
                }
            } catch (e: Exception) {
            } finally {
                bgBusy = false
            }
        }
    }

    // ───────── متصفح السوق (كما هو دون تغيير) ─────────
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
                if (url.contains("quotex.com")) view.evaluateJavascript(EARLY_HOOK_JS, null)
                checkLoggedOut(url)
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
                    if (json.optBoolean("ok")) fetchUserInfo()
                    isProcessingSSID = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isProcessingSSID = false }
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun onPair(ok: Boolean) {
            runOnUiThread {
                if (ok) {
                    pairDone = true
                    pairChip.text = "✅ تم فتح الزوج: " + prettyAsset(pairAsset)
                    Handler(Looper.getMainLooper()).postDelayed({ pairChip.visibility = View.GONE }, 4000)
                }
            }
        }

        @JavascriptInterface
        fun onPairDebug(info: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("oscar-debug", info))
                Toast.makeText(this@MainActivity, "تم نسخ بيانات التشخيص، الصقها وأرسلها للمطوّر", Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun onSsid(ssid: String) {
            if (ssid.length < 25) return
            runOnUiThread {
                if (currentTab == "market") sendToServer(ssid)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!::webView.isInitialized) { super.onBackPressed(); return }
        if (currentTab == "market") showSettings()
        else if (webView.canGoBack() && webView.visibility == View.VISIBLE) webView.goBack()
        else super.onBackPressed()
    }
}
