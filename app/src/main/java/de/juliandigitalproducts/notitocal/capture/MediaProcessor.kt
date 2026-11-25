package de.juliandigitalproducts.notitocal.capture

import androidx.core.graphics.scale
import android.content.Context
import android.util.Log
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.notify.Notifier
import de.juliandigitalproducts.notitocal.pipeline.Pipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import de.juliandigitalproducts.notitocal.model.runSafely

/** Screenshots / images (OCR or vision LLM) and the text of Anti-Vocale transcripts. */
object MediaProcessor {

  // ------------------------------------------------------------------ Images

  suspend fun ocr(context: Context, uri: Uri): String = suspendCancellableCoroutine { cont ->
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val image = runSafely { InputImage.fromFilePath(context, uri) }.getOrElse { cont.resumeWithException(it); return@suspendCancellableCoroutine }
    recognizer.process(image)
      .addOnSuccessListener { cont.resume(it.text); recognizer.close() }
      .addOnFailureListener { cont.resumeWithException(it); recognizer.close() }
  }

  suspend fun ocrBitmap(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val image = InputImage.fromBitmap(bitmap, 0)
    recognizer.process(image)
      .addOnSuccessListener { cont.resume(it.text); recognizer.close() }
      .addOnFailureListener { cont.resumeWithException(it); recognizer.close() }
  }

  fun bitmapToJpegBytes(bitmap: Bitmap, maxDim: Int = 1600): ByteArray {
    val width = bitmap.width
    val height = bitmap.height
    val max = maxOf(width, height)
    val scaled = if (max > maxDim) {
      val scale = maxDim.toFloat() / max
      bitmap.scale((width * scale).toInt(), (height * scale).toInt())
    } else {
      bitmap
    }
    return ByteArrayOutputStream().use { out ->
      scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
      out.toByteArray()
    }
  }

  /** JPEG, longest side at most 1600 px – small enough for vision APIs. */
  suspend fun jpegBytes(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
      val max = maxOf(info.size.width, info.size.height)
      if (max > 1600) decoder.setTargetSampleSize((max + 1599) / 1600)
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
  }

  /** [fileName] carries the app the screenshot was taken in ("…_WhatsApp.jpg"); the app filter is applied to it. */
  suspend fun processScreenshot(app: NotiToCalApp, uri: Uri, fileName: String?) {
    val s = app.settings.current
    if (!s.screenshotsEnabled || s.paused) return
    if (!Screenshots.allowed(app, fileName, s)) {
      Log.i("NotiToCal", "screenshot skipped by app filter: ${Screenshots.appOf(fileName)}")
      return
    }
    runSafely { processImage(app, uri, SourceType.SCREENSHOT, userInitiated = false) }
  }

  /**
   * Reads an image (OCR, optionally vision) and analyses it. [caption] is text shared together with the image
   * (e.g. the message or caption of a WhatsApp image): both are analysed as one input, because the date is often
   * in one and the subject in the other ("Termin bestätigt" + a screenshot of the booking).
   */
  suspend fun processImage(app: NotiToCalApp, uri: Uri, source: SourceType, userInitiated: Boolean, caption: String = ""): Pipeline.Result? {
    val s = app.settings.current
    val ocrText = Screenshots.dropStatusBar(runSafely { ocr(app, uri) }.getOrDefault("").trim())
    val vision = s.visionForImages()
    Log.i("NotiToCal", "ocr_chars=${ocrText.length} caption_chars=${caption.length} vision=$vision")
    if (ocrText.isBlank() && caption.isBlank() && !vision) error(app.getString(R.string.ocr_empty_no_vision))
    val bytes = if (vision) runSafely { jpegBytes(app, uri) }.getOrNull() else null
    val combined = combineImageText(caption, ocrText).ifBlank { app.getString(R.string.image_without_text) }
    if (de.juliandigitalproducts.notitocal.analysis.RuleEngine.isSelfOrDebugText(combined)) {
      Log.i("NotiToCal", "processImage: ignored self/debug screenshot")
      return null
    }
    val res = app.pipeline.process(
      Captured(
        source = source,
        appLabel = app.getString(if (source == SourceType.SCREENSHOT) R.string.source_screenshot else R.string.source_image),
        text = combined,
        imageBytes = bytes,
        imageMime = "image/jpeg",
      ),
      userInitiated = userInitiated,
    )
    if (res != null && s.storeScreenshotHistory) {
      runSafely {
        val dir = java.io.File(app.filesDir, "screenshots").apply { mkdirs() }
        val targetFile = java.io.File(dir, "screenshot_${res.logId}.jpg")
        val imgBytes = bytes ?: runSafely { jpegBytes(app, uri) }.getOrNull()
        if (imgBytes != null) {
          targetFile.writeBytes(imgBytes)
        }
      }
    }
    return res
  }

  // ------------------------------------------------------------------ Voice memos

  data class MemoResult(val transcript: String, val summary: String?, val pipeline: Pipeline.Result?)

  /**
   * Evaluates the text of a voice message transcribed by Anti-Vocale. NotiToCal itself does not transcribe audio,
   * so there is no audio path any more (code review C4/C14: audio was linked to an unrelated transcript).
   */
  suspend fun processTextMemo(
    app: NotiToCalApp, transcript: String, sourceApp: String = "Anti-Vocale", sender: String = "", auto: Boolean = false, quiet: Boolean = false,
  ): MemoResult {
    val s = app.settings.current
    val clean = AntiVocaleParser.cleanTranscript(transcript).trim()
    if (clean.isBlank()) error(app.getString(R.string.memo_empty))
    val summary = if (s.memoSummary && s.engine != AiEngine.RULES) runSafely { summarize(app, clean, s) }.getOrNull() else null
    val result = app.pipeline.process(
      Captured(SourceType.VOICE_MEMO, appLabel = sourceApp.ifBlank { app.getString(R.string.source_voice_memo) }, sender = sender, text = clean),
      userInitiated = !auto,
      quiet = quiet,
    )
    if (auto && s.notifyMemoText) {
      val title = if (sender.isNotBlank()) app.getString(R.string.memo_notif_from, sender) else app.getString(R.string.memo_notif_title)
      Notifier.memo(app, clean.hashCode(), title, summary ?: clean, result?.logId)
    }
    if (result != null && summary != null) {
      // The transcript is the message: written into the entry only with "Keep message text" on.
      val description = if (s.storeMessageText) summary + "\n\n— " + app.getString(R.string.memo_transcript) + " —\n" + clean else summary
      val d = result.detection.copy(description = description)
      app.log.update(result.logId, d = d)
      return MemoResult(clean, summary, result.copy(detection = d))
    }
    return MemoResult(clean, summary, result)
  }

  private suspend fun summarize(app: NotiToCalApp, transcript: String, s: AppSettings): String? {
    val language = de.juliandigitalproducts.notitocal.analysis.Prompt.userLanguage(app)
    val system = "Summarize the following voice message in 1-3 short sentences in $language (the user's language, even if the message is in another language). Mention dates, times, tasks and questions. Plain text only."
    return de.juliandigitalproducts.notitocal.analysis.Pseudonymizer.around(s, system, transcript) { sys, user ->
      when (s.engine) {
        AiEngine.ON_DEVICE -> de.juliandigitalproducts.notitocal.analysis.OnDeviceRunner.complete(app, s, sys, user, 200)
        else -> app.analyzer.client(s)?.complete(sys, user, json = false)
      }?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.takeIf { it.isNotBlank() && !it.startsWith("{") }
    }
  }

  /**
   * Text shared with an image plus the text read from the image. The caption comes first (it is what the
   * sender wrote); a blank line separates it from the image text so neither is mistaken for the other.
   */
  fun combineImageText(caption: String, imageText: String): String {
    val c = caption.trim()
    val i = imageText.trim()
    return when {
      c.isEmpty() -> i
      i.isEmpty() -> c
      i.contains(c) -> i
      else -> "$c\n\n$i"
    }
  }

  /** Largest shared file (image or .ics) that is copied; a bigger one would fill the cache or end in an OOM. */
  const val MAX_SHARED_BYTES = 30L * 1024 * 1024

  /**
   * Copies a shared content:// URI into the cache so it stays readable after the share activity finishes.
   * Stops at [maxBytes]: the partial copy is deleted and an [java.io.IOException] thrown.
   */
  fun copyToCache(context: Context, uri: Uri, name: String, maxBytes: Long = MAX_SHARED_BYTES): Uri {
    val file = File(context.cacheDir, "shared").apply { mkdirs() }.resolve(name)
    try {
      context.contentResolver.openInputStream(uri)!!.use { input ->
        file.outputStream().use { out -> copyLimited(input, out, maxBytes) }
      }
    } catch (e: Exception) {
      file.delete()
      throw e
    }
    return Uri.fromFile(file)
  }

  /** Copies at most [maxBytes]; throws when the input is longer. */
  fun copyLimited(input: java.io.InputStream, out: java.io.OutputStream, maxBytes: Long): Long {
    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
      val n = input.read(buf)
      if (n < 0) return total
      total += n
      if (total > maxBytes) throw java.io.IOException("shared file larger than $maxBytes bytes")
      out.write(buf, 0, n)
    }
  }
}
