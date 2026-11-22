package com.airtv.receiver

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.airtv.receiver.ui.BroadcastWebActivity

/** Opens received links from a resumed screen so Android can allow the launch. */
class FreeCastApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var resumedActivity: Activity? = null
    private var pendingPage: String? = null

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

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
