package com.airtv.receiver.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.airtv.receiver.BuildConfig
import com.airtv.receiver.R
import com.airtv.receiver.update.GitHubDeviceAuth
import com.airtv.receiver.update.LocalUpdateServer
import com.airtv.receiver.update.ReleaseInfo
import com.airtv.receiver.update.ReleaseVersion
import com.airtv.receiver.update.UpdateAccess
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.security.SecureRandom
import kotlin.math.roundToInt

/** Downloads a signed GitHub Release and hands it to the TV's package installer. */
class UpdateActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button
    private lateinit var localAction: Button
    private lateinit var authAction: Button
    private lateinit var content: FrameLayout
    private lateinit var overview: LinearLayout
    private var localServer: LocalUpdateServer? = null
    private var localPanel: TvPairingPanel? = null
    private var authPanel: TvPairingPanel? = null
    private var authWorker: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    private var release: ReleaseInfo? = null
    private var downloaded: File? = null
    private var pendingInstall = false
    private var busy = false
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TvChrome.label(this, getString(R.string.update_checking), 22f)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = ProgressBar.GONE
        }
        action = TvChrome.action(this, getString(R.string.action_refresh), true).apply {
            minHeight = dp(60)
            isEnabled = false
            setOnClickListener {
                if (release == null) checkLatest() else downloadAndInstall()
            }
        }
        localAction = TvChrome.action(this, getString(R.string.update_local_action)).apply {
            setOnClickListener { openLocalUpdate() }
        }
        authAction = TvChrome.action(this, getString(R.string.update_github_login), true).apply {
            visibility = View.GONE
            setOnClickListener { openGitHubLogin() }
        }
        overview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val panel = TvChrome.panel(this)
        panel.addView(TvChrome.label(this, "ВЕРСИЯ ПРИЛОЖЕНИЯ", 13f).apply {
            setTextColor(getColor(R.color.accent))
        })
        panel.addView(TvChrome.label(this, BuildConfig.VERSION_NAME, 36f),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        panel.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        panel.addView(progress, LinearLayout.LayoutParams(-1, dp(20)).apply { topMargin = dp(24) })
        overview.addView(panel, LinearLayout.LayoutParams(-1, -2))
        overview.addView(LinearLayout(this).apply {
            addView(action, LinearLayout.LayoutParams(0, dp(60), 1f))
            addView(authAction, LinearLayout.LayoutParams(0, dp(60), 1f))
            addView(localAction, LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginStart = dp(12) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        content = FrameLayout(this).apply { addView(overview, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(TvChrome.page(this, TvChrome.Section.UPDATES,
            "Обновления", "Установка новой версии по сети", content, onBack = ::goBack))
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
        localServer?.stop()
        authWorker?.interrupt()
        super.onDestroy()
    }

    override fun onStop() {
        if (localPanel != null) closeLocalUpdate()
        if (authPanel != null) closeGitHubLogin()
        super.onStop()
    }

    @Deprecated("Android TV Back first closes an open update form")
    override fun onBackPressed() = goBack()

    private fun goBack() {
        when {
            authPanel != null -> closeGitHubLogin()
            localPanel != null -> closeLocalUpdate()
            else -> finish()
        }
    }

    private fun openGitHubLogin() {
        if (authPanel != null || !UpdateAccess.enabled) return
        val panel = TvPairingPanel(this, getString(R.string.update_github_qr),
            getString(R.string.update_github_login), getString(R.string.update_github_help))
        val back = TvChrome.action(this, getString(R.string.update_local_back)).apply {
            setOnClickListener { closeGitHubLogin() }
        }
        panel.setAction(back)
        panel.showFeedback(getString(R.string.update_github_requesting))
        authPanel = panel
        overview.visibility = View.GONE
        content.addView(panel, FrameLayout.LayoutParams(-1, -1))
        back.requestFocus()
        authWorker = Thread {
            try {
                val auth = UpdateAccess.deviceAuth()
                val request = auth.start()
                val deadline = SystemClock.elapsedRealtime() + request.expiresIn * 1000L
                showLoginResult(panel) {
                    panel.showCode(GitHubDeviceAuth.VERIFY_URL, request.userCode)
                    panel.showFeedback(getString(R.string.update_github_waiting))
                }
                var interval = request.interval * 1000L
                while (SystemClock.elapsedRealtime() + interval < deadline) {
                    Thread.sleep(interval)
                    val result = auth.poll(request)
                    val credential = result.credential
                    if (credential != null) {
                        UpdateAccess.authorize(this, credential)
                        showLoginResult(panel) {
                            closeGitHubLogin()
                            checkLatest()
                        }
                        return@Thread
                    }
                    when (result.state) {
                        GitHubDeviceAuth.State.SLOW_DOWN -> interval += 5000
                        GitHubDeviceAuth.State.PENDING -> Unit
                        GitHubDeviceAuth.State.EXPIRED -> break
                        GitHubDeviceAuth.State.DENIED -> {
                            showLoginResult(panel) { panel.showFeedback(getString(R.string.update_github_denied)) }
                            return@Thread
                        }
                        null -> error("Invalid device authorization result")
                    }
                }
                showLoginResult(panel) { panel.showFeedback(getString(R.string.update_github_expired)) }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: UpdateAccess.Required) {
                showLoginResult(panel) { panel.showFeedback(getString(R.string.update_github_no_access)) }
            } catch (_: Exception) {
                showLoginResult(panel) { panel.showFeedback(getString(R.string.update_github_failed)) }
            }
        }.also { it.start() }
    }

    private fun showLoginResult(panel: TvPairingPanel, action: () -> Unit) {
        runOnUiThread { if (!closed && authPanel === panel) action() }
    }

    private fun closeGitHubLogin() {
        authWorker?.interrupt()
        authWorker = null
        authPanel?.let(content::removeView)
        authPanel = null
        overview.visibility = View.VISIBLE
        authAction.requestFocus()
    }

    private fun openLocalUpdate() {
        val address = LanPairingAddress.find(this)
        if (address == null) {
            status.setText(R.string.pairing_network_missing)
            return
        }
        val code = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        val pairing = TvPairingPanel(this, getString(R.string.update_local_qr),
            getString(R.string.update_local_action), getString(R.string.update_local_help))
        val cancel = TvChrome.action(this, getString(R.string.update_local_back)).apply {
            setOnClickListener { closeLocalUpdate() }
        }
        pairing.setAction(cancel)
        val receiver = LocalUpdateServer(code, File(cacheDir, "updates"), { validateApk(it) }) { file ->
            main.postDelayed({
                if (closed || localPanel !== pairing) file.delete() else {
                    closeLocalUpdate()
                    readyToInstall(file)
                }
            }, 300)
        }
        try {
            receiver.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            localServer = receiver
            localPanel = pairing
            val url = "http://$address:${receiver.listeningPort}/"
            pairing.showCode(url, code)
            pairing.addDetail(TvChrome.label(this, url, 16f, muted = true))
            overview.visibility = FrameLayout.GONE
            content.addView(pairing, FrameLayout.LayoutParams(-1, -1))
            cancel.requestFocus()
        } catch (_: Exception) {
            receiver.stop()
            closeLocalUpdate()
            status.setText(R.string.update_local_server_error)
        }
    }

    private fun closeLocalUpdate() {
        localServer?.stop()
        localServer = null
        localPanel?.let(content::removeView)
        localPanel = null
        overview.visibility = FrameLayout.VISIBLE
        localAction.requestFocus()
    }

    private fun checkLatest() {
        if (busy) return
        busy = true
        action.isEnabled = false
        status.setText(R.string.update_checking)
        val previousFile = downloaded
        Thread {
            val result = runCatching { UpdateAccess.client(this).latest() }
            runOnUiThread {
                if (closed) return@runOnUiThread
                busy = false
                if (downloaded !== previousFile) return@runOnUiThread
                result.onSuccess { found ->
                    val restoreFocus = authAction.hasFocus()
                    authAction.visibility = View.GONE
                    action.visibility = View.VISIBLE
                    val current = ReleaseVersion.parse(BuildConfig.VERSION_NAME)
                    release = found?.takeIf { current == null || it.version > current }
                    status.text = when {
                        found == null -> getString(R.string.update_no_release)
                        release == null -> getString(R.string.update_current, BuildConfig.VERSION_NAME)
                        BuildConfig.DEBUG -> getString(R.string.update_debug_migration, found.version.toString())
                        else -> getString(R.string.update_available, found.version.toString())
                    }
                    action.text = getString(when {
                        release == null -> R.string.action_refresh
                        BuildConfig.DEBUG -> R.string.update_open_release
                        else -> R.string.update_download
                    })
                    action.isEnabled = true
                    if (restoreFocus) action.requestFocus()
                    action.setOnClickListener {
                        when {
                            release == null -> checkLatest()
                            BuildConfig.DEBUG -> openReleasePage(release!!)
                            else -> downloadAndInstall()
                        }
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
        localAction.isEnabled = false
        progress.visibility = ProgressBar.VISIBLE
        progress.progress = 0
        status.setText(R.string.update_downloading)
        Thread {
            val result = runCatching {
                val directory = File(cacheDir, "updates").apply { mkdirs() }
                val temporary = File(directory, "download.tmp.apk")
                UpdateAccess.client(this).download(selected, temporary) { percent ->
                    runOnUiThread { if (!closed) progress.progress = percent }
                }
                try {
                    validateApk(temporary, selected.version.toString())
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
                localAction.isEnabled = true
                progress.visibility = ProgressBar.GONE
                result.onSuccess(::readyToInstall).onFailure(::showError)
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, expectedVersion: String? = null) {
        // Some Android builds collect archive signing data only when GET_SIGNATURES is set.
        val flags = PackageManager.GET_SIGNATURES or
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val candidate = requireNotNull(packageManager.getPackageArchiveInfo(file.absolutePath, flags)) {
            getString(R.string.update_invalid_apk)
        }
        require(candidate.packageName == packageName) { getString(R.string.update_wrong_package) }
        require(expectedVersion == null || candidate.versionName == expectedVersion) { getString(R.string.update_wrong_version) }
        val newCode = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        require(newCode > BuildConfig.VERSION_CODE) { getString(R.string.update_old_apk) }
        val installed = packageManager.getPackageInfo(packageName, flags)
        val signers = apkSigners(candidate)
        val installedSigners = apkSigners(installed)
        require(signers.isNotEmpty() && installedSigners.isNotEmpty()) {
            getString(R.string.update_signature_unavailable)
        }
        require(signers == installedSigners) { getString(R.string.update_wrong_signer) }
    }

    @Suppress("DEPRECATION")
    private fun apkSigners(info: PackageInfo) =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null)
            ?.takeIf { it.isNotEmpty() }?.toSet() ?: info.signatures.orEmpty().toSet()

    private fun readyToInstall(file: File) {
        downloaded?.takeIf { it != file }?.delete()
        downloaded = file
        status.setText(R.string.update_ready)
        action.isEnabled = true
        action.setText(R.string.update_install)
        action.setOnClickListener { requestInstall(file) }
        requestInstall(file)
    }

    private fun openReleasePage(selected: ReleaseInfo) {
        val url = "${selected.downloadUrl.substringBefore("/download/")}/tag/v${selected.version}"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            status.setText(R.string.update_browser_missing)
        }
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
            clipData = ClipData.newRawUri(getString(R.string.app_name), uri)
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
        if (error is UpdateAccess.Required) {
            release = null
            status.setText(R.string.update_github_required)
            action.visibility = View.GONE
            authAction.visibility = View.VISIBLE
            if (!localAction.hasFocus() || !overview.hasFocus()) authAction.requestFocus()
            return
        }
        status.text = getString(R.string.update_error, error.localizedMessage.orEmpty())
        action.setText(R.string.action_refresh)
        action.isEnabled = true
        action.setOnClickListener {
            release = null
            checkLatest()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
