package com.oscar.linker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID

/** أدوات مشتركة بين الواجهة والخدمة: الإشعارات ومعرّف الجهاز. */
object Notifs {
    const val BASE_URL = "https://signal-bott.onrender.com"
    const val CH_TRADES = "trades"
    const val CH_SERVICE = "service"

    fun deviceId(ctx: Context): String {
        val sp = ctx.getSharedPreferences("oscar_prefs", Context.MODE_PRIVATE)
        var id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        if (id.length < 8 || id == "9774d56d682e549c") {
            id = sp.getString("fallback_device_id", null)
                ?: UUID.randomUUID().toString().replace("-", "").also { sp.edit().putString("fallback_device_id", it).apply() }
        }
        return "and-$id"
    }

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_TRADES, "الصفقات", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "تنفيذ الصفقات ونهاية الجلسة"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 150, 250)
                    setSound(null, null)   // الصوت يُشغَّل من التطبيق نفسه (صوت المال)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_SERVICE, "مراقبة الصفقات", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "يبقي التطبيق مستعداً لإشعارك بالصفقات"
                }
            )
        }
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(ctx: Context, title: String, text: String, asset: String?, direction: String? = null) {
        val prefs = ctx.getSharedPreferences("oscar_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notif_enabled", true) || !canNotify(ctx)) return
        try {
            ensureChannels(ctx)
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (asset != null) {
                    putExtra("open_asset", asset)
                    putExtra("open_dir", direction ?: "")
                }
            }
            val rid = (System.currentTimeMillis() % 100000).toInt()
            val pi = PendingIntent.getActivity(ctx, rid, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, CH_TRADES)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVibrate(longArrayOf(0, 250, 150, 250))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify((System.currentTimeMillis() % 1000000).toInt(), n)
        } catch (e: Exception) {
        }
    }

    fun prettyAsset(raw: String): String {
        val otc = raw.endsWith("_otc", ignoreCase = true)
        val base = raw.replace("_otc", "", ignoreCase = true)
        val name = if (base.length == 6 && base.all { it.isLetter() }) base.substring(0, 3) + "/" + base.substring(3) else base
        return if (otc) "$name (OTC)" else name
    }

    fun dirAr(d: String): String = when (d.lowercase()) {
        "call" -> "صعود ⬆️"
        "put" -> "هبوط ⬇️"
        else -> ""
    }
}
