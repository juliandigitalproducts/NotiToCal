package de.juliandigitalproducts.notitocal.capture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import de.juliandigitalproducts.notitocal.R
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Data class representing an available localized version of the Telethon bridge script.
 */
data class BridgeScriptLanguage(
  val code: String,
  val label: String,
  val assetPath: String,
)

/**
 * Handles exporting, downloading and sharing the Telethon Python bridge script
 * (notitocal_telegram_bridge.py) for Termux and local PC execution across 7 languages.
 */
object TelegramBridgeExporter {

  const val SCRIPT_FILE_NAME = "notitocal_telegram_bridge.py"

  val SUPPORTED_LANGUAGES: List<BridgeScriptLanguage> = listOf(
    BridgeScriptLanguage("de", "Deutsch", "bridge_scripts/notitocal_telegram_bridge_de.py"),
    BridgeScriptLanguage("en", "English", "bridge_scripts/notitocal_telegram_bridge_en.py"),
    BridgeScriptLanguage("es", "Español", "bridge_scripts/notitocal_telegram_bridge_es.py"),
    BridgeScriptLanguage("fr", "Français", "bridge_scripts/notitocal_telegram_bridge_fr.py"),
    BridgeScriptLanguage("it", "Italiano", "bridge_scripts/notitocal_telegram_bridge_it.py"),
    BridgeScriptLanguage("pl", "Polski", "bridge_scripts/notitocal_telegram_bridge_pl.py"),
    BridgeScriptLanguage("pt", "Português", "bridge_scripts/notitocal_telegram_bridge_pt.py"),
  )

  /**
   * Detects the best matching language code based on current system locale,
   * falling back to "en" if not directly supported.
   */
  fun detectDefaultLanguageCode(): String {
    val current = Locale.getDefault().language.lowercase()
    return if (SUPPORTED_LANGUAGES.any { it.code == current }) current else "en"
  }

  /**
   * Retrieves the Python script text from the app assets.
   * If [langCode] is specified, loads the corresponding localized script from `bridge_scripts/`.
   * Otherwise loads the canonical asset [SCRIPT_FILE_NAME].
   */
  fun getScriptContent(context: Context, langCode: String? = null): String {
    val langMatch = langCode?.let { code -> SUPPORTED_LANGUAGES.firstOrNull { it.code == code } }
    val primaryAsset = langMatch?.assetPath ?: SCRIPT_FILE_NAME
    return try {
      context.assets.open(primaryAsset).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) {
      if (primaryAsset != SCRIPT_FILE_NAME) {
        try {
          context.assets.open(SCRIPT_FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (inner: Exception) {
          throw IllegalStateException("Bridge script '$SCRIPT_FILE_NAME' not found: ${inner.message}", inner)
        }
      } else {
        throw IllegalStateException("Bridge script '$SCRIPT_FILE_NAME' not found: ${e.message}", e)
      }
    }
  }

  /**
   * Saves the Python bridge script directly into the public Downloads directory.
   * If [saveAsCanonical] is true, it is stored as `notitocal_telegram_bridge.py` so Termux
   * instructions run out-of-the-box. Otherwise, a language suffix is appended.
   */
  fun saveScriptToDownloads(
    context: Context,
    langCode: String? = null,
    saveAsCanonical: Boolean = true,
  ): Pair<Boolean, String> {
    val targetFileName = if (saveAsCanonical) {
      SCRIPT_FILE_NAME
    } else {
      val code = langCode ?: detectDefaultLanguageCode()
      "notitocal_telegram_bridge_$code.py"
    }
    val content = try {
      getScriptContent(context, langCode)
    } catch (e: Exception) {
      return Pair(false, context.getString(R.string.bridge_load_error, e.message.orEmpty()))
    }
    return saveContentToDownloads(context, targetFileName, content)
  }

  /**
   * Exports all 7 localized versions plus the canonical script into the Downloads directory.
   */
  fun saveAllLanguagesToDownloads(context: Context): Pair<Boolean, String> {
    var savedCount = 0
    val defaultCode = detectDefaultLanguageCode()

    // 1. Save canonical script using system language
    val canonicalContent = getScriptContent(context, defaultCode)
    if (saveContentToDownloads(context, SCRIPT_FILE_NAME, canonicalContent).first) {
      savedCount++
    }

    // 2. Save each language explicitly with its language suffix
    for (lang in SUPPORTED_LANGUAGES) {
      val content = getScriptContent(context, lang.code)
      val fileName = "notitocal_telegram_bridge_${lang.code}.py"
      if (saveContentToDownloads(context, fileName, content).first) {
        savedCount++
      }
    }

    return Pair(
      true,
      context.getString(R.string.bridge_saved_all, savedCount),
    )
  }

  private fun saveContentToDownloads(
    context: Context,
    fileName: String,
    content: String,
  ): Pair<Boolean, String> {
    return try {
      run {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(fileName, "%Download%")
        var existingUri: Uri? = null

        resolver.query(
          MediaStore.Downloads.EXTERNAL_CONTENT_URI,
          projection,
          selection,
          selectionArgs,
          null,
        )?.use { cursor ->
          if (cursor.moveToFirst()) {
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val id = cursor.getLong(idIndex)
            existingUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
          }
        }

        val targetUri = existingUri ?: run {
          val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/x-python")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
          }
          resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }

        if (targetUri != null) {
          resolver.openOutputStream(targetUri, "wt")?.use { os ->
            os.write(content.toByteArray(Charsets.UTF_8))
          }
          Pair(true, context.getString(R.string.bridge_saved, fileName))
        } else {
          saveToDownloadsDirect(context, fileName, content)
        }
      }
    } catch (e: Exception) {
      Pair(false, context.getString(R.string.bridge_save_error, fileName, e.message ?: e.javaClass.simpleName))
    }
  }

  private fun saveToDownloadsDirect(
    context: Context,
    fileName: String,
    content: String,
  ): Pair<Boolean, String> {
    return try {
      val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
      if (!downloadsDir.exists()) downloadsDir.mkdirs()
      val targetFile = File(downloadsDir, fileName)
      targetFile.writeText(content, Charsets.UTF_8)
      Pair(true, context.getString(R.string.bridge_saved, targetFile.name))
    } catch (_: Exception) {
      val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
      val fallbackFile = File(fallbackDir, fileName)
      fallbackFile.writeText(content, Charsets.UTF_8)
      Pair(true, context.getString(R.string.bridge_saved_at, fallbackFile.absolutePath))
    }
  }

  /**
   * Shares the localized Python script via Android's system share sheet (ACTION_SEND).
   */
  fun shareScript(context: Context, langCode: String? = null) {
    try {
      val content = getScriptContent(context, langCode)
      val fileName = if (langCode != null) "notitocal_telegram_bridge_$langCode.py" else SCRIPT_FILE_NAME
      val dir = File(context.cacheDir, "scripts").apply { mkdirs() }
      val file = File(dir, fileName)
      file.writeText(content, Charsets.UTF_8)

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/x-python"
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        putExtra(Intent.EXTRA_TEXT, content)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      val chooser = Intent.createChooser(intent, context.getString(R.string.bridge_share_title, fileName)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(chooser)
    } catch (e: Exception) {
      Toast.makeText(context, context.getString(R.string.bridge_share_error, e.message.orEmpty()), Toast.LENGTH_LONG).show()
    }
  }

  /**
   * Copies the localized Python script code to the clipboard.
   * On Android 13+ (API 33+), the OS clipboard preview handles user feedback.
   */
  fun copyScriptToClipboard(context: Context, langCode: String? = null): Boolean {
    return try {
      val content = getScriptContent(context, langCode)
      val fileName = if (langCode != null) "notitocal_telegram_bridge_$langCode.py" else SCRIPT_FILE_NAME
      val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
      val clip = ClipData.newPlainText(fileName, content)
      cm?.setPrimaryClip(clip)
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(R.string.bridge_copied, fileName), Toast.LENGTH_SHORT).show()
      }
      true
    } catch (e: Exception) {
      Toast.makeText(context, context.getString(R.string.bridge_copy_error, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
      false
    }
  }
}
