package com.airtv.receiver.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.airtv.receiver.BuildConfig
import com.airtv.receiver.R
import com.airtv.receiver.update.ReleaseClient
import com.airtv.receiver.update.ReleaseInfo
import com.airtv.receiver.update.ReleaseVersion
import java.io.File

/** Downloads a signed GitHub Release and hands it to the TV's package installer. */
class UpdateActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button
    private var release: ReleaseInfo? = null
    private var downloaded: File? = null
    private var pendingInstall = false
    private var busy = false
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 21f
            setTextColor(Color.WHITE)
            text = getString(R.string.update_checking)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = ProgressBar.GONE
        }
        action = Button(this).apply {
            text = getString(R.string.action_refresh)
            isEnabled = false
            setOnClickListener {
                if (release == null) checkLatest() else downloadAndInstall()
            }
        }
        val back = Button(this).apply {
            text = getString(R.string.action_done)
            setOnClickListener { finish() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(64, 32, 64, 32)
            addView(TextView(this@UpdateActivity).apply {
                text = getString(R.string.update_title)
                textSize = 30f
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 24
            })
            addView(progress, LinearLayout.LayoutParams(-1, 20).apply { topMargin = 24 })
            addView(action, LinearLayout.LayoutParams(-1, 60).apply { topMargin = 28 })
            addView(back, LinearLayout.LayoutParams(-1, 60).apply { topMargin = 12 })
        }
        setContentView(root)
        action.requestFocus()
        checkLatest()
    }

    override fun onResume() {
        super.onResume()
        if (pendingInstall && downloaded != null && canInstall()) {
            pendingInstall = false
            openInstaller(downloaded!!)
        }
    }

    override fun onDestroy() {
        closed = true
        super.onDestroy()
    }

    private fun checkLatest() {
        if (busy) return
        busy = true
        action.isEnabled = false
        status.setText(R.string.update_checking)
        Thread {
            val result = runCatching { ReleaseClient().latest() }
            runOnUiThread {
                if (closed) return@runOnUiThread
                busy = false
                result.onSuccess { found ->
                    val current = ReleaseVersion.parse(BuildConfig.VERSION_NAME)
                    release = found?.takeIf { current == null || it.version > current }
                    status.text = when {
                        found == null -> getString(R.string.update_no_release)
                        release == null -> getString(R.string.update_current, BuildConfig.VERSION_NAME)
                        else -> getString(R.string.update_available, found.version.toString())
                    }
                    action.text = getString(if (release == null) R.string.action_refresh else R.string.update_download)
                    action.isEnabled = true
                    action.setOnClickListener {
                        if (release == null) checkLatest() else downloadAndInstall()
                    }
                }.onFailure { showError(it) }
            }
        }.start()
    }

    private fun downloadAndInstall() {
        val selected = release ?: return
        if (busy) return
        busy = true
        action.isEnabled = false
        progress.visibility = ProgressBar.VISIBLE
        progress.progress = 0
        status.setText(R.string.update_downloading)
        Thread {
            val result = runCatching {
                val directory = File(cacheDir, "updates").apply { mkdirs() }
                val temporary = File(directory, "download.tmp.apk")
                ReleaseClient().download(selected, temporary) { percent ->
                    runOnUiThread { if (!closed) progress.progress = percent }
                }
                try {
                    val info = packageManager.getPackageArchiveInfo(temporary.absolutePath, 0)
                        ?: error("Android не распознал APK")
                    require(info.packageName == packageName) { "APK принадлежит другому приложению" }
                    require(info.versionName == selected.version.toString()) { "Версия APK не совпадает с релизом" }
                    val newCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                    require(newCode > BuildConfig.VERSION_CODE) { "Номер сборки APK не выше установленного" }
                    val target = File(directory, "FreeCast-TV-${selected.version}.apk")
                    if (target.exists()) target.delete()
                    check(temporary.renameTo(target)) { "Не удалось сохранить APK" }
                    target
                } catch (error: Exception) {
                    temporary.delete()
                    throw error
                }
            }
            runOnUiThread {
                if (closed) return@runOnUiThread
                busy = false
                progress.visibility = ProgressBar.GONE
                result.onSuccess { file ->
                    downloaded = file
                    status.setText(R.string.update_ready)
                    action.isEnabled = true
                    action.setText(R.string.update_install)
                    action.setOnClickListener { requestInstall(file) }
                    requestInstall(file)
                }.onFailure { showError(it) }
            }
        }.start()
    }

    private fun requestInstall(file: File) {
        if (!canInstall()) {
            if (Build.VERSION.SDK_INT >= 26) {
                pendingInstall = true
                status.setText(R.string.update_allow_install)
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")))
                } catch (_: ActivityNotFoundException) {
                    pendingInstall = false
                    status.setText(R.string.update_installer_missing)
                }
            }
            return
        }
        openInstaller(file)
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun openInstaller(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("FreeCast TV", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            status.setText(R.string.update_installer_missing)
        } catch (error: SecurityException) {
            showError(error)
        }
    }

    private fun showError(error: Throwable) {
        status.text = getString(R.string.update_error, error.localizedMessage.orEmpty())
        action.setText(R.string.action_refresh)
        action.isEnabled = true
        action.setOnClickListener {
            release = null
            checkLatest()
        }
    }
}
