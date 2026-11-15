package app.bloom.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Converts a streamed video (HLS) into a local MP4 with Media3 Transformer. */
object MediaExport {
    suspend fun hlsToMp4(ctx: Context, url: String, out: File): Unit = suspendCancellableCoroutine { c ->
        // Transformer must be driven from a thread with a Looper: use the main thread.
        Handler(Looper.getMainLooper()).post {
            val t = Transformer.Builder(ctx)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) { if (c.isActive) c.resume(Unit) }
                    override fun onError(composition: Composition, result: ExportResult, e: ExportException) { if (c.isActive) c.resumeWithException(e) }
                })
                .build()
            t.start(MediaItem.fromUri(url), out.absolutePath)
            c.invokeOnCancellation { Handler(Looper.getMainLooper()).post { t.cancel() } }
        }
    }
}
