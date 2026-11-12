package com.v2ray.ang.handler

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.HomeActivity
import com.v2ray.ang.util.LogUtil
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cyperio: messages from the panel (expiry / data warnings, the seller's own messages) arrive with the
 * subscription in the X-Cyperio-Notice header - base64url JSON [{"id": .., "text": ..}] - and are shown as
 * ordinary Android notifications. No Google push service involved, so this works inside Iran too; a
 * message shows up at the next subscription refresh (app opened, or the background update every few hours).
 */
object CyperioNotices {
    private const val KEY_SEEN = "cyperio_notice_seen"
    private const val CHANNEL = "cyperio_messages"

    /** Background update: fetch the subscription headers once and show what is new. */
    fun check(context: Context, subUrl: String) {
        try {
            val conn = URL(subUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Cyperio-Android/${BuildConfig.VERSION_NAME}")
            val ok = conn.responseCode == 200
            val header = if (ok) conn.getHeaderField("X-Cyperio-Notice").orEmpty() else ""
            val offered = if (ok) conn.getHeaderField("X-Cyperio-Update").orEmpty() else ""
            conn.disconnect()
            handle(context, header)
            if (ok) CyperioUpdate.offer(context, offered)   // a new app version: one notification
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Cyperio: notice check failed", e)
        }
    }

    /** Show the messages newer than the last one shown. */
    fun handle(context: Context, header: String) {
        if (header.isBlank()) return
        try {
            val list = JSONArray(String(Base64.decode(header, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8))
            val seen = MmkvManager.decodeSettingsLong(KEY_SEEN, 0L)
            var newest = seen
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val id = o.optLong("id")
                val text = o.optString("text")
                if (id <= seen || text.isBlank()) continue
                show(context, id, text)
                if (id > newest) newest = id
            }
            if (newest > seen) MmkvManager.encodeSettings(KEY_SEEN, newest)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Cyperio: bad notice header", e)
        }
    }

    private fun show(context: Context, id: Long, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.cyperio_messages_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Cyperio")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify((id % Int.MAX_VALUE).toInt(), n)
    }
}
