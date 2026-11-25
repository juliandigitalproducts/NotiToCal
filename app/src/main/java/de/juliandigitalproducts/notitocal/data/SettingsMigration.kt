package de.juliandigitalproducts.notitocal.data

/**
 * Brings a stored settings document up to [CURRENT] without dropping fields the user already saved.
 * Missing keys are filled by [AppSettings] defaults during JSON decode; unknown keys of removed
 * settings (the WhatsApp voice-note folders, version 5 and older; transcriber and Whisper settings, version 6 and older)
 * are ignored by [AppJson].
 */
object SettingsMigration {
  const val CURRENT = 7

  /** [legacyTranscriber]: value of the removed "transcriber" key; "OFF" meant Anti-Vocale was switched off. */
  fun migrate(settings: AppSettings, fromVersion: Int, legacyTranscriber: String? = null): AppSettings {
    if (fromVersion >= CURRENT) return settings.copy(settingsVersion = CURRENT)
    return settings.copy(
      antiVocaleEnabled = legacyTranscriber != "OFF",
      settingsVersion = CURRENT,
    )
  }
}
