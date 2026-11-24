package com.airtv.receiver.update

import android.os.SystemClock
import com.airtv.receiver.link.LAN_FORM_STYLE
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class LocalUpdateServer(
    private val code: String,
    private val directory: File,
    private val validate: (File) -> Unit,
    private val onReady: (File) -> Unit,
) : NanoHTTPD(0) {
    private val receiving = AtomicBoolean(false)
    private val failures = AtomicInteger(0)

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.GET && session.uri == "/") {
            return response(Response.Status.OK, form, "text/html; charset=utf-8").apply {
                addHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; " +
                    "script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'")
            }
        }
        if (session.method != Method.POST || session.uri != "/upload") {
            return response(Response.Status.NOT_FOUND, "Страница не найдена.")
        }
        val supplied = session.headers["x-airscreen-code"].orEmpty()
        if (failures.get() >= 5 || !MessageDigest.isEqual(code.toByteArray(), supplied.toByteArray())) {
            failures.incrementAndGet()
            return response(Response.Status.FORBIDDEN, "Проверьте код. После пяти попыток откройте экран на ТВ заново.")
        }
        val size = session.headers["content-length"]?.toLongOrNull()
        if (size == null || size !in 1..ReleaseClient.MAX_APK_BYTES ||
            session.headers["content-type"] != "application/octet-stream") {
            return response(Response.Status.BAD_REQUEST, "Выберите APK допустимого размера.")
        }
        if (!receiving.compareAndSet(false, true)) {
            return response(Response.Status.CONFLICT, "APK уже передаётся или принят.")
        }
        var file: File? = null
        var accepted = false
        try {
            directory.mkdirs()
            val candidate = File.createTempFile("local-update-", ".apk", directory)
            file = candidate
            candidate.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                val deadline = SystemClock.elapsedRealtime() + 180_000
                var remaining = size
                while (remaining > 0) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw IOException("Upload timed out")
                    val count = session.inputStream.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (count < 0) throw IOException("Incomplete APK upload")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
            validate(candidate)
            onReady(candidate)
            accepted = true
            return response(Response.Status.OK, "APK получен. Подтвердите установку на ТВ.")
        } catch (error: IllegalArgumentException) {
            return response(Response.Status.BAD_REQUEST, error.message.orEmpty())
        } catch (_: Exception) {
            return response(Response.Status.BAD_REQUEST, "Не удалось принять APK. Попробуйте ещё раз.")
        } finally {
            if (!accepted) {
                file?.delete()
                receiving.set(false)
            }
        }
    }

    private fun response(status: Response.Status, text: String, mime: String = "text/plain; charset=utf-8") =
        newFixedLengthResponse(status, mime, text).apply {
            closeConnection(true)
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Referrer-Policy", "no-referrer")
        }

    private val form = """
        <!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Обновление AirScreen</title><style>$LAN_FORM_STYLE</style>
        <h1>Обновление AirScreen</h1><form>
        <label>Файл обновления<input type="file" accept=".apk" required></label>
        <label>Код на ТВ<input name="code" inputmode="numeric" pattern="[0-9]{6}" autocomplete="off" required></label>
        <button type="submit">Передать на ТВ</button></form><p role="status"></p>
        <script>
        const form = document.querySelector('form');
        const button = form.querySelector('button');
        const status = document.querySelector('[role=status]');
        form.addEventListener('submit', async event => {
          event.preventDefault();
          button.disabled = true;
          status.textContent = 'Передаём APK…';
          try {
            const response = await fetch('/upload', {method: 'POST', headers: {
              'Content-Type': 'application/octet-stream', 'X-AirScreen-Code': form.elements.code.value
            }, body: form.querySelector('[type=file]').files[0]});
            status.textContent = await response.text();
            button.disabled = response.ok;
          } catch (_) {
            status.textContent = 'Передача прервана. Проверьте экран ТВ.';
            button.disabled = false;
          }
        });
        </script></html>
    """.trimIndent()
}
