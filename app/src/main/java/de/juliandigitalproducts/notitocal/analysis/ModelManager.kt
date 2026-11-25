package de.juliandigitalproducts.notitocal.analysis

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Notes shown on a model card. */
enum class ModelNote { ENGLISH_ONLY, THINKS_SLOWLY, MULTILINGUAL, TEST_ONLY }

/**
 * A downloadable on-device model for MediaPipe LLM Inference. [speed] and [quality] are 1–5 ratings for this
 * app's job (short multilingual messages → JSON) on a mid-range 2023 phone; [ramGb] is the device RAM it needs.
 */
data class ModelSpec(
  val id: String,
  val name: String,
  val url: String,
  val sizeMb: Int,
  val template: String,
  val needsToken: Boolean,
  val speed: Int,
  val quality: Int,
  val ramGb: Int,
  val recommended: Boolean = false,
  val notes: List<ModelNote> = emptyList(),
) {
  val fileName: String get() = url.substringAfterLast('/')
}

data class DownloadState(val running: Boolean, val done: Long, val total: Long, val failed: Boolean)

/** Downloads, imports and lists local .task models (stored in the app's external files dir). */
object ModelManager {
  private const val HF = "https://huggingface.co"

  val catalog = listOf(
    ModelSpec(
      "qwen2.5-1.5b", "Qwen 2.5 · 1.5B Instruct (q8)",
      "$HF/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
      1524, "qwen", needsToken = false, speed = 3, quality = 4, ramGb = 4, recommended = true, notes = listOf(ModelNote.MULTILINGUAL),
    ),
    ModelSpec(
      "qwen2.5-0.5b", "Qwen 2.5 · 0.5B Instruct (q8)",
      "$HF/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
      521, "qwen", needsToken = false, speed = 5, quality = 2, ramGb = 3,
    ),
    ModelSpec(
      "gemma3-1b-q4", "Gemma 3 · 1B IT (q4)",
      "$HF/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task",
      528, "gemma", needsToken = true, speed = 4, quality = 3, ramGb = 3, notes = listOf(ModelNote.MULTILINGUAL),
    ),
    ModelSpec(
      "gemma3-1b-q8", "Gemma 3 · 1B IT (q8)",
      "$HF/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q8_ekv1280.task",
      1005, "gemma", needsToken = true, speed = 3, quality = 3, ramGb = 4, notes = listOf(ModelNote.MULTILINGUAL),
    ),
    ModelSpec(
      "gemma3n-e2b", "Gemma 3n · E2B IT (int4)",
      "$HF/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task",
      2990, "gemma", needsToken = true, speed = 2, quality = 4, ramGb = 6, notes = listOf(ModelNote.MULTILINGUAL),
    ),
    ModelSpec(
      "gemma3n-e4b", "Gemma 3n · E4B IT (int4)",
      "$HF/google/gemma-3n-E4B-it-litert-preview/resolve/main/gemma-3n-E4B-it-int4.task",
      4201, "gemma", needsToken = true, speed = 1, quality = 5, ramGb = 8, notes = listOf(ModelNote.MULTILINGUAL),
    ),
    ModelSpec(
      "phi4-mini", "Phi-4 mini · 3.8B Instruct (q8)",
      "$HF/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task",
      3761, "phi", needsToken = false, speed = 1, quality = 4, ramGb = 8,
    ),
    ModelSpec(
      "deepseek-r1-1.5b", "DeepSeek R1 Distill · Qwen 1.5B (q8)",
      "$HF/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
      1774, "deepseek", needsToken = false, speed = 1, quality = 3, ramGb = 4, notes = listOf(ModelNote.THINKS_SLOWLY),
    ),
    ModelSpec(
      "gemma3-270m", "Gemma 3 · 270M IT (q8)",
      "$HF/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.task",
      289, "gemma", needsToken = true, speed = 5, quality = 1, ramGb = 2, notes = listOf(ModelNote.TEST_ONLY),
    ),
    ModelSpec(
      "smollm-135m", "SmolLM · 135M Instruct (q8)",
      "$HF/litert-community/SmolLM-135M-Instruct/resolve/main/SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task",
      159, "qwen", needsToken = false, speed = 5, quality = 1, ramGb = 2, notes = listOf(ModelNote.ENGLISH_ONLY, ModelNote.TEST_ONLY),
    ),
  )

  /** Total device RAM in GB (rounded up, as advertised). */
  fun deviceRamGb(context: Context): Int {
    val mi = android.app.ActivityManager.MemoryInfo()
    context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
    return ((mi.totalMem + (1L shl 29)) / (1L shl 30)).toInt()
  }

  fun dir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

  fun fileFor(context: Context, spec: ModelSpec) = File(dir(context), spec.fileName)

  fun installed(context: Context): List<File> =
    dir(context).listFiles()?.filter { it.isFile && (it.name.endsWith(".task") || it.name.endsWith(".bin")) && it.length() > 1_000_000 }
      ?.sortedBy { it.name }.orEmpty()

  fun templateFor(file: String): String {
    val n = file.lowercase()
    return when {
      "deepseek" in n -> "deepseek"
      "gemma" in n -> "gemma"
      "llama" in n -> "llama"
      "phi" in n -> "phi"
      else -> "qwen"
    }
  }

  fun download(context: Context, spec: ModelSpec, hfToken: String): Long {
    fileFor(context, spec).delete()
    val req = DownloadManager.Request(spec.url.toUri())
      .setTitle(spec.name)
      .setDescription("NotiToCal on-device model")
      .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
      .setDestinationInExternalFilesDir(context, "models", spec.fileName)
      .setAllowedOverMetered(true)
    if (hfToken.isNotBlank()) req.addRequestHeader("Authorization", "Bearer $hfToken")
    return context.getSystemService(DownloadManager::class.java).enqueue(req)
  }

  fun state(context: Context, spec: ModelSpec): DownloadState? {
    val dm = context.getSystemService(DownloadManager::class.java)
    dm.query(DownloadManager.Query()).use { c ->
      while (c.moveToNext()) {
        val uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
        if (uri != spec.url) continue
        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        return DownloadState(
          running = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED,
          done = done, total = total, failed = status == DownloadManager.STATUS_FAILED,
        )
      }
    }
    return null
  }

  fun cancel(context: Context, spec: ModelSpec) {
    val dm = context.getSystemService(DownloadManager::class.java)
    dm.query(DownloadManager.Query()).use { c ->
      while (c.moveToNext()) {
        if (c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) == spec.url) {
          dm.remove(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)))
        }
      }
    }
  }

  /** Imports a .task file the user picked (e.g. downloaded on a computer). */
  suspend fun import(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    var name = "imported.task"
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
      if (c.moveToFirst()) name = c.getString(0) ?: name
    }
    name = safeFileName(name)
    if (!name.endsWith(".task") && !name.endsWith(".bin")) name += ".task"
    val target = File(dir(context), name)
    val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("The file cannot be read")
    input.use { inp -> target.outputStream().use { inp.copyTo(it, 1 shl 20) } }
    target
  }

  /** Base name only (no "../" or folders from the other app), safe characters, never empty. */
  fun safeFileName(raw: String): String =
    raw.substringAfterLast('/').substringAfterLast('\\')
      .replace(Regex("[^A-Za-z0-9._-]"), "_")
      .trimStart('.')
      .take(120)
      .ifBlank { "imported.task" }
}
