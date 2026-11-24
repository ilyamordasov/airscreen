package com.airtv.receiver

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.airtv.receiver.link.AceStreamEngine
import com.airtv.receiver.service.AirPlayService
import com.airtv.receiver.ui.BroadcastWebActivity
import com.airtv.receiver.vpn.FreeCastVpnService

/** Owns the foreground services and opens received links from a resumed screen. */
class FreeCastApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var resumedActivity: Activity? = null
    private var pendingPage: String? = null
    private val main = Handler(Looper.getMainLooper())
    private var startedActivities = 0
    internal val hasVisibleActivities: Boolean get() = startedActivities > 0
    internal var foregroundSession: Any? = null
        private set
    private val stopServices = Runnable {
        if (startedActivities != 0) return@Runnable
        foregroundSession = null
        AirPlayService.stop(this)
        FreeCastVpnService.disconnect(this)
        AceStreamEngine.stop()
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    fun receivePage(url: String) {
        pendingPage = url
        resumedActivity?.let(::openPendingPage)
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = activity
        if (pendingPage != null) openPendingPage(activity)
    }

    private fun openPendingPage(activity: Activity) {
        activity.window.decorView.post {
            if (resumedActivity !== activity || activity.isFinishing || activity.isDestroyed) return@post
            val page = pendingPage ?: return@post
            pendingPage = null
            activity.startActivity(Intent(activity, BroadcastWebActivity::class.java)
                .putExtra(BroadcastWebActivity.EXTRA_URL, page)
                .putExtra(BroadcastWebActivity.EXTRA_DISCOVER_MEDIA, true))
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity === activity) resumedActivity = null
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        main.removeCallbacks(stopServices)
        if (foregroundSession != null) return
        foregroundSession = Any()
        runCatching { AirPlayService.start(this) }
            .onFailure { Log.w("FreeCastApplication", "Could not start AirPlay", it) }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities--
        if (startedActivities == 0) main.postDelayed(stopServices, 500)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
