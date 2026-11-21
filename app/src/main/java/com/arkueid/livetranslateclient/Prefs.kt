package com.arkueid.livetranslateclient

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/** Persisted connection settings and overlay geometry. */
object Prefs {
    private const val FILE = "livetranslate_prefs"

    const val KEY_HOST = "host"
    const val KEY_PORT = "port"
    const val KEY_TOKEN = "token"
    const val KEY_OVERLAY_X = "overlay_x"
    const val KEY_OVERLAY_Y = "overlay_y"
    const val KEY_OVERLAY_W = "overlay_w"
    const val KEY_OVERLAY_H = "overlay_h"
    const val KEY_OVERLAY_MANUAL = "overlay_manual"

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String {
        val prefs = get(context)
        val host = prefs.getString(KEY_HOST, "") ?: ""
        val port = prefs.getInt(KEY_PORT, 8766)
        val token = prefs.getString(KEY_TOKEN, "") ?: ""
        val base = "ws://$host:$port/ws/subtitle"
        return if (token.isEmpty()) base else "$base?token=${Uri.encode(token)}"
    }
}
