package com.airtv.receiver.update

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.airtv.receiver.BuildConfig
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal object UpdateAccess {
    class Required : IOException()

    val enabled: Boolean get() = BuildConfig.OTA_GITHUB_CLIENT_ID.isNotEmpty()
    fun deviceAuth() = GitHubDeviceAuth(BuildConfig.OTA_GITHUB_CLIENT_ID)

    @Synchronized
    fun client(context: Context): ReleaseClient {
        if (!enabled) return ReleaseClient()
        val credential = runCatching { read(context) }.getOrNull() ?: throw Required()
        val current = if (credential.expiresAt - System.currentTimeMillis() > 60_000) credential else {
            if (credential.refresh.isEmpty()) throw Required()
            deviceAuth().refresh(credential.refresh).also { write(context, it) }
        }
        return ReleaseClient.privateFeed(current.access)
    }

    fun authorize(context: Context, credential: GitHubDeviceAuth.Credential) {
        ReleaseClient.privateFeed(credential.access).verifyPrivateAccess()
        synchronized(this) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            write(context, credential)
        }
    }

    private fun read(context: Context): GitHubDeviceAuth.Credential? {
        val file = storage(context)
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        if (bytes.size !in 29..4096) throw IOException("Invalid OTA credential file")
        val secret = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, bytes.copyOf(IV_SIZE)))
        }
        val data = JSONObject(String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8))
        if (data.optString("client") != BuildConfig.OTA_GITHUB_CLIENT_ID) return null
        return GitHubDeviceAuth.Credential(data.getString("access"), data.getString("refresh"), data.getLong("expires"))
    }

    private fun write(context: Context, credential: GitHubDeviceAuth.Credential) {
        val secret = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
                generateKey()
            }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secret) }
        check(cipher.iv.size == IV_SIZE)
        val data = JSONObject().put("client", BuildConfig.OTA_GITHUB_CLIENT_ID)
            .put("access", credential.access).put("refresh", credential.refresh).put("expires", credential.expiresAt)
        val file = storage(context)
        val stream = file.startWrite()
        try {
            stream.write(cipher.iv)
            stream.write(cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8)))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun storage(context: Context) = AtomicFile(File(context.noBackupFilesDir, "ota-github.bin"))
    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "airscreen-private-ota"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
}
