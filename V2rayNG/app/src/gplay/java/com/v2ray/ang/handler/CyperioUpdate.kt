package com.v2ray.ang.handler

import android.content.Context

/**
 * Cyperio, Google Play build: the app never updates itself - Google Play does that. The panel still sends
 * X-Cyperio-Update with the subscription; it is ignored here, so no "new version" notice, no download and no
 * installer (and no REQUEST_INSTALL_PACKAGES). Same API as the APK build's CyperioUpdate (src/direct).
 */
object CyperioUpdate {
    const val EXTRA_UPDATE = "cyperio_update"

    fun offer(context: Context, offered: String) {}

    fun download(context: Context, subUrl: String): Boolean = false

    fun install(context: Context, id: Long) {}
}
