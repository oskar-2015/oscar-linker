package com.oscar.linker

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * خدمة تعمل في الخلفية (بإشعار دائم صغير) لتصلك إشعارات الصفقات وأصواتها
 * حتى لو كان التطبيق مغلقاً. تستعلم من الخادم كل بضع ثوانٍ عن الأحداث الجديدة.
 */
class EventService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val sound by lazy { SoundFx() }
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Device-Id", Notifs.deviceId(applicationContext)).build())
            }
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannels(this)
        val n: Notification = NotificationCompat.Builder(this, Notifs.CH_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Oskarix يعمل")
            .setContentText("يراقب صفقاتك ليصلك الإشعار فوراً")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1001, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        val prefs = getSharedPreferences("oscar_prefs", Context.MODE_PRIVATE)
        while (scope.isActive) {
            try {
                val key = prefs.getString("license_key", null)
                if (key.isNullOrBlank()) {
                    stopSelf()
                    return
                }
                val cursorKey = "svc_event_id_$key"
                var cursor = prefs.getLong(cursorKey, -1L)
                // أول مرة: نبدأ من آخر حدث موجود حتى لا نكرر إشعارات قديمة
                val after = if (cursor < 0) 999999999L else cursor
                val res = client.newCall(Request.Builder().url("${Notifs.BASE_URL}/api/app/events/$key?after=$after").build()).execute()
                val body = res.body?.string() ?: "{}"
                if (res.code == 401 || res.code == 403) {
                    // المفتاح أُوقف أو استُخدم على جهاز آخر: نتوقف
                    stopSelf()
                    return
                }
                if (res.isSuccessful) {
                    val json = JSONObject(body)
                    if (cursor < 0) {
                        cursor = json.optLong("latest_id", 0L)
                        prefs.edit().putLong(cursorKey, cursor).apply()
                    } else {
                        val arr = json.optJSONArray("events")
                        var openPlayed = false
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val ev = arr.getJSONObject(i)
                                val id = ev.optLong("id")
                                if (id <= cursor) continue
                                cursor = id
                                val asset = ev.optString("asset")
                                val dir = ev.optString("direction")
                                when (ev.optString("kind")) {
                                    "trade_open" -> {
                                        if (!openPlayed && prefs.getBoolean("snd_open", true)) { sound.cash(); openPlayed = true }
                                        Notifs.post(
                                            this@EventService,
                                            "تم تنفيذ صفقة",
                                            "على " + Notifs.prettyAsset(asset) + " (" + Notifs.dirAr(dir) + ") — " + ev.optString("strategy_name") +
                                                "\nاضغط لفتح الزوج",
                                            asset, dir
                                        )
                                    }
                                    "session_win" -> {
                                        if (prefs.getBoolean("snd_win", true)) sound.win()
                                        Notifs.post(this@EventService, "🎉 انتهت الجلسة بربح", "تم الوصول إلى حد الربح الذي حددته.", null)
                                    }
                                    "session_loss" -> {
                                        if (prefs.getBoolean("snd_loss", true)) sound.loss()
                                        Notifs.post(this@EventService, "⚠️ انتهت الجلسة بخسارة", "تم الوصول إلى حد الخسارة الذي حددته.", null)
                                    }
                                }
                            }
                        }
                        prefs.edit().putLong(cursorKey, cursor).apply()
                    }
                }
            } catch (e: Exception) {
            }
            delay(6000)
        }
    }
}
