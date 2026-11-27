// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.pipeline.ChatScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScopeTest {
  private val wa = "com.whatsapp"

  @Test fun sameSenderSameAppIsSameChat() {
    assertTrue(ChatScope.sameChat(wa, "Anna", wa, "Anna"))
  }

  @Test fun otherChatOfSameAppIsNotSameChat() {
    assertFalse(ChatScope.sameChat(wa, "Anna", wa, "Ben"))
    assertFalse(ChatScope.sameChat(wa, "Anna @ Familie", wa, "Ben @ Verein"))
  }

  @Test fun groupMembersShareTheGroup() {
    assertTrue(ChatScope.sameChat(wa, "Anna @ Familie", wa, "Ben @ Familie"))
    assertFalse(ChatScope.sameSender(wa, "Anna @ Familie", wa, "Ben @ Familie"))
  }

  @Test fun directChatAndGroupWithSameNameDiffer() {
    assertFalse(ChatScope.sameChat(wa, "Familie", wa, "Anna @ Familie"))
  }

  @Test fun sameSenderInOtherAppIsNotSameChat() {
    assertFalse(ChatScope.sameChat(wa, "Anna", "org.telegram.messenger", "Anna"))
  }

  @Test fun emptyAppOrSenderNeverMatches() {
    assertFalse(ChatScope.sameChat("", "", "", ""))
    assertFalse(ChatScope.sameChat("", "Anna", "", "Anna"))
    assertFalse(ChatScope.sameChat(wa, "", wa, ""))
    assertFalse(ChatScope.sameChat(wa, " @ ", wa, " @ "))
    assertFalse(ChatScope.sameSender("", "Anna", "", "Anna"))
    assertFalse(ChatScope.sameSender(wa, "", wa, ""))
  }

  // NTC-R6: a group message without a person is stored with the group title only.
  @Test fun groupMessageWithoutPersonGetsTheGroupKey() {
    assertEquals("group:Familie", ChatScope.chatOf("Familie", isGroup = true))
    assertEquals("group:Familie", ChatScope.chatOf("Anna @ Familie", isGroup = true))
    assertEquals("Familie", ChatScope.chatOf("Familie"))
    assertTrue(ChatScope.sameChat(wa, "Familie", wa, "Anna @ Familie", groupA = true, groupB = true))
    assertTrue(ChatScope.sameChat(wa, "Familie", wa, "Familie", groupA = true, groupB = true))
    assertFalse(ChatScope.sameChat(wa, "Familie", wa, "Familie", groupA = true, groupB = false))
    assertFalse(ChatScope.sameChat(wa, " ", wa, " ", groupA = true, groupB = true))
  }
}
