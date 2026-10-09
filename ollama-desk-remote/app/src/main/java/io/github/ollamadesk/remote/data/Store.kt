package io.github.ollamadesk.remote.data

import android.content.Context

/**
 * Small settings kept in the app's private storage. The access token lives here too: other apps can't read it,
 * and backups are switched off in the manifest so it never leaves the phone.
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)

    var server: Server?
        get() = prefs.getString("server", null)?.let { Server.fromJson(it) }
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove("server") else editor.putString("server", value.toJson())
            editor.apply()
        }

    /** The model new chats use; null means the computer's own default. */
    var model: String?
        get() = prefs.getString("model", null)
        set(value) = prefs.edit().putString("model", value).apply()

    var agent: Boolean
        get() = prefs.getBoolean("agent", true)
        set(value) = prefs.edit().putBoolean("agent", value).apply()

    var askedNotifications: Boolean
        get() = prefs.getBoolean("asked_notifications", false)
        set(value) = prefs.edit().putBoolean("asked_notifications", value).apply()
}
