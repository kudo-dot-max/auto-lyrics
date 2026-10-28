package com.autolyrics.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaListenerService : NotificationListenerService() {

    private lateinit var sessionManager: MediaSessionManager

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            pickBestSession(this, controllers)
        }

    override fun onCreate() {
        super.onCreate()
        sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsListener,
                ComponentName(this, MediaListenerService::class.java)
            )
            updateSessions()
        } catch (_: SecurityException) { }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) { }
        // Some phones (e.g. Xiaomi) drop the listener silently; ask Android to bind it again.
        try {
            requestRebind(ComponentName(this, MediaListenerService::class.java))
        } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        updateSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    private fun updateSessions() {
        try {
            val controllers = sessionManager.getActiveSessions(
                ComponentName(this, MediaListenerService::class.java)
            )
            pickBestSession(this, controllers)
        } catch (_: SecurityException) { }
    }

    companion object {
        /** True while Android has this listener bound and delivering callbacks. */
        @Volatile
        var isConnected: Boolean = false
            private set

        /**
         * Re-reads the active media sessions right now, without waiting for a system callback,
         * and asks Android to rebind the listener if it is enabled but not connected.
         * Returns false when notification access is not granted.
         */
        fun refreshNow(context: Context): Boolean {
            val app = context.applicationContext
            val component = ComponentName(app, MediaListenerService::class.java)
            return try {
                val manager = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                pickBestSession(app, manager.getActiveSessions(component))
                if (!isConnected) {
                    NotificationListenerService.requestRebind(component)
                }
                true
            } catch (_: SecurityException) {
                false
            } catch (_: Exception) {
                false
            }
        }

        private fun pickBestSession(context: Context, controllers: List<MediaController>?) {
            val filtered = controllers?.filter { it.packageName != context.packageName }
            if (filtered.isNullOrEmpty()) {
                MediaTracker.getInstance(context).onMediaSessionChanged(null)
                return
            }

            val playing = filtered.firstOrNull { controller ->
                controller.playbackState?.state == PlaybackState.STATE_PLAYING
            }

            val best = playing ?: filtered.first()
            MediaTracker.getInstance(context).onMediaSessionChanged(best)
        }
    }
}
