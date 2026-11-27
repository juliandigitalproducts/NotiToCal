// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.AppJson
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.SettingsMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMigrationTest {
  @Test fun oldDocumentKeepsValuesAndGainsDefaults() {
    val raw = """{"onboardingDone":true,"keepLogDays":14,"voiceFolderUri":"content://tree/voice","engine":"CLOUD","dedupeMinutes":90}"""
    val version = AppJson.parseToJsonElement(raw).let { element ->
      val obj = element as kotlinx.serialization.json.JsonObject
      obj["settingsVersion"]?.toString()?.trim('"')?.toIntOrNull() ?: 3
    }
    val decoded = AppJson.decodeFromString(AppSettings.serializer(), raw)
    val migrated = SettingsMigration.migrate(decoded, version)
    assertTrue(migrated.onboardingDone)
    assertEquals(14, migrated.keepLogDays)
    assertEquals(90, migrated.dedupeMinutes)
    assertEquals(AiEngine.CLOUD, migrated.engine)
    // The removed WhatsApp voice-folder key is ignored; the Anti-Vocale check starts with its default interval.
    assertEquals(30, migrated.antiVocaleCheckMinutes)
    assertEquals(SettingsMigration.CURRENT, migrated.settingsVersion)
    assertTrue(migrated.mergeDuplicateEvents)
    assertTrue(migrated.restoreAliases)
    assertTrue(migrated.aliases.isEmpty())
    assertEquals("event_clean", migrated.eventTemplateId)
    assertEquals("task_check", migrated.taskTemplateId)
  }

  @Test fun switchedOffTranscriberKeepsAntiVocaleOff() {
    val raw = """{"settingsVersion":6,"transcriber":"OFF","whisperModel":"whisper-large-v3-turbo"}"""
    val decoded = AppJson.decodeFromString(AppSettings.serializer(), raw)
    assertEquals(false, SettingsMigration.migrate(decoded, 6, "OFF").antiVocaleEnabled)
    assertTrue(SettingsMigration.migrate(decoded, 6, "ANTI_VOCALE").antiVocaleEnabled)
  }

  @Test fun currentDocumentIsNotRewritten() {
    val current = AppSettings(settingsVersion = SettingsMigration.CURRENT, antiVocaleCheckMinutes = 180, antiVocaleFolderUri = "content://av")
    val migrated = SettingsMigration.migrate(current, SettingsMigration.CURRENT)
    assertEquals(180, migrated.antiVocaleCheckMinutes)
    assertEquals("content://av", migrated.antiVocaleFolderUri)
  }
}
