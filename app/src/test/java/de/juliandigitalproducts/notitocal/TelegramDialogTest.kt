package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.capture.TelegramUserApiSource
import de.juliandigitalproducts.notitocal.data.FilterListMode
import de.juliandigitalproducts.notitocal.data.TelegramDialogItem
import de.juliandigitalproducts.notitocal.data.TelegramDialogKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telegram group/person list: discovery, merging and the "Alle außer" / "Nur folgende" filter. */
class TelegramDialogTest {
  @Test fun senderBecomesGroupOrPerson() {
    val grp = TelegramUserApiSource.dialogFromSender("Anna @ Sportverein", isGroup = false)
    assertEquals("Sportverein", grp?.title)
    assertEquals(TelegramDialogKind.GROUP, grp?.kind)
    val both = TelegramUserApiSource.dialogsFromSender("Anna @ Sportverein", isGroup = false)
    assertEquals(2, both.size)
    assertTrue(both.any { it.title == "Sportverein" && it.kind == TelegramDialogKind.GROUP })
    assertTrue(both.any { it.title == "Anna" && it.kind == TelegramDialogKind.PERSON })
    val person = TelegramUserApiSource.dialogFromSender("Max Mustermann", isGroup = false)
    assertEquals(TelegramDialogKind.PERSON, person?.kind)
    assertNull(TelegramUserApiSource.dialogFromSender("Telegram", isGroup = false))
    assertNull(TelegramUserApiSource.dialogFromSender(" ", isGroup = true))
  }

  @Test fun botChatTypesAreMapped() {
    val group = TelegramUserApiSource.dialogFromBotChat(
      JsonObject(mapOf("id" to JsonPrimitive(-100123), "type" to JsonPrimitive("supergroup"), "title" to JsonPrimitive("Nachbarn"))),
    )
    assertNotNull(group)
    assertEquals("tgchat_-100123", group!!.id)
    assertEquals(TelegramDialogKind.GROUP, group.kind)
    val private = TelegramUserApiSource.dialogFromBotChat(
      JsonObject(mapOf("id" to JsonPrimitive(42), "type" to JsonPrimitive("private"), "first_name" to JsonPrimitive("Lisa"))),
    )
    assertEquals("Lisa", private?.title)
    assertEquals(TelegramDialogKind.PERSON, private?.kind)

    val update = JsonObject(
      mapOf(
        "update_id" to JsonPrimitive(1),
        "message" to JsonObject(
          mapOf(
            "chat" to JsonObject(mapOf("id" to JsonPrimitive(-100123), "type" to JsonPrimitive("group"), "title" to JsonPrimitive("Nachbarn"))),
            "from" to JsonObject(mapOf("id" to JsonPrimitive(77), "first_name" to JsonPrimitive("Paul"))),
          ),
        ),
      ),
    )
    val fromUpdate = TelegramUserApiSource.dialogsFromBotUpdate(update)
    assertEquals(2, fromUpdate.size)
    assertTrue(fromUpdate.any { it.title == "Nachbarn" && it.kind == TelegramDialogKind.GROUP })
    assertTrue(fromUpdate.any { it.title == "Paul" && it.kind == TelegramDialogKind.PERSON })
  }

  @Test fun mergeKeepsSelectionAndAddsNew() {
    val existing = listOf(TelegramDialogItem(id = "a", title = "Sportverein", selected = true))
    val found = listOf(
      TelegramDialogItem(id = "x", title = "sportverein", username = "sv_club", selected = false),
      TelegramDialogItem(id = "b", title = "Familie", selected = false),
    )
    val merged = TelegramUserApiSource.merge(existing, found)
    assertEquals(2, merged.size)
    assertTrue(merged.first { it.id == "a" }.selected)
    assertEquals("sv_club", merged.first { it.id == "a" }.username)
    assertFalse(merged.first { it.id == "b" }.selected)
  }

  @Test fun filterModesUseCheckedEntries() {
    val dialogs = listOf(TelegramDialogItem(id = "tgchat_-100999", title = "Großgruppe", selected = true))
    assertFalse(TelegramUserApiSource.isAllowedByFilter("Anna @ Großgruppe", "Großgruppe", FilterListMode.ALL_EXCEPT, dialogs))
    assertFalse(TelegramUserApiSource.isAllowedByFilter("Anna", "Unbekannt", FilterListMode.ALL_EXCEPT, dialogs, chatId = "-100999"))
    assertTrue(TelegramUserApiSource.isAllowedByFilter("Anna @ Familie", "Familie", FilterListMode.ALL_EXCEPT, dialogs))
    assertTrue(TelegramUserApiSource.isAllowedByFilter("Anna @ Großgruppe", "Großgruppe", FilterListMode.ONLY_SELECTED, dialogs))
    assertTrue(TelegramUserApiSource.isAllowedByFilter("Anna", "Unbekannt", FilterListMode.ONLY_SELECTED, dialogs, chatId = "-100999"))
    assertFalse(TelegramUserApiSource.isAllowedByFilter("Anna @ Familie", "Familie", FilterListMode.ONLY_SELECTED, dialogs))
  }
}
