package com.v2ray.ang.handler

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.HomeActivity
import com.v2ray.ang.util.LogUtil
import kotlin.math.max

/**
 * Cyperio: new app versions. The panel offers them in the X-Cyperio-Update header of the subscription
 * ("android-<abi>=<version>,<file>; android=<version>,<file>"). A newer version is announced once as a
 * notification (tap = download), and is downloaded with the system DownloadManager, because some phones have
 * no browser that opens links from other apps. When the download ends the installer is opened.
 */
object CyperioUpdate {
    const val KEY_LATEST = "cyperio_latest"
    const val KEY_LATEST_FILE = "cyperio_latest_file"
    const val EXTRA_UPDATE = "cyperio_update"
    private const val KEY_NOTIFIED = "cyperio_update_notified"
    private const val KEY_DOWNLOAD = "cyperio_update_download"
    private const val CHANNEL = "cyperio_messages"
    private const val NOTIFY_ID = 7_000_001

    /** (version, file) offered for this phone, or null. */
    fun pick(offered: String): Pair<String, String>? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val m = Regex("android-" + Regex.escape(abi) + "=([0-9.]+),([A-Za-z0-9._-]+)").find(offered)
            ?: Regex("android=([0-9.]+),([A-Za-z0-9._-]+)").find(offered)
        return m?.let { it.groupValues[1] to it.groupValues[2] }
    }

    fun isNewer(version: String): Boolean {
        val a = version.split(".").map { it.toIntOrNull() ?: 0 }
        val b = BuildConfig.VERSION_NAME.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until max(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Remember what the panel offers and announce a newer version once. */
    fun offer(context: Context, offered: String) {
        val p = pick(offered)
        MmkvManager.encodeSettings(KEY_LATEST, p?.first.orEmpty())
        MmkvManager.encodeSettings(KEY_LATEST_FILE, p?.second.orEmpty())
        if (p == null || !isNewer(p.first)) return
        if (MmkvManager.decodeSettingsString(KEY_NOTIFIED, "") == p.first) return
        if (notify(context, p.first)) MmkvManager.encodeSettings(KEY_NOTIFIED, p.first)
    }

    private fun notify(context: Context, version: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.cyperio_messages_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, HomeActivity::class.java).putExtra(EXTRA_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = context.getString(R.string.cyperio_update_notice, version)
        nm.notify(NOTIFY_ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Cyperio")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
        return true
    }

    /** Start downloading the offered version (subUrl = the customer's link: the file is on the same panel). */
    fun download(context: Context, subUrl: String): Boolean {
        val version = MmkvManager.decodeSettingsString(KEY_LATEST, "").orEmpty()
        val file = MmkvManager.decodeSettingsString(KEY_LATEST_FILE, "").orEmpty()
        val i = subUrl.indexOf("/sub/")
        if (i < 0 || file.isEmpty() || !isNewer(version)) return false
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFY_ID)
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(subUrl.substring(0, i) + "/dl/" + file))
                .setTitle("Cyperio $version")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Cyperio-$version.apk")
                .addRequestHeader("User-Agent", "Cyperio-Android/${BuildConfig.VERSION_NAME}")
            MmkvManager.encodeSettings(KEY_DOWNLOAD, dm.enqueue(req))
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Cyperio: update download failed", e)
            false
        }
    }

    /** Download finished (DownloadManager broadcast): open the installer if it was our update. */
    fun install(context: Context, id: Long) {
        if (id != MmkvManager.decodeSettingsLong(KEY_DOWNLOAD, -1L)) return
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = dm.getUriForDownloadedFile(id) ?: return
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Cyperio: opening the installer failed", e)
        }
    }
}
