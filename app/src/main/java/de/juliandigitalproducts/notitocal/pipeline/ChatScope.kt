package de.juliandigitalproducts.notitocal.pipeline

/**
 * Which earlier messages belong to the same chat as a new one. Only these may go into an AI prompt or complete
 * an earlier appointment: messages of other chats of the same app stay out (privacy, review NTC-1/4/8).
 * An empty app or sender (shared text, manual entry) never matches anything.
 */
object ChatScope {
  private const val GROUP_SEPARATOR = " @ "

  /**
   * Group messages are stored as "person @ group" (split at the first " @ ", as for Telegram dialogs): the group is the
   * chat. A group message without a (distinct) person is stored with the group title only; with [isGroup] it still gets
   * the key "group:<title>", so it belongs to the same chat as the members' messages and never to a direct chat of the
   * same name (NTC-R6). Otherwise the sender is the chat.
   */
  fun chatOf(sender: String, isGroup: Boolean = false): String {
    if (sender.isBlank()) return ""
    val i = sender.indexOf(GROUP_SEPARATOR)
    return when {
      i >= 0 -> "group:" + sender.substring(i + GROUP_SEPARATOR.length).trim()
      isGroup -> "group:" + sender.trim()
      else -> sender.trim()
    }
  }

  /** Same app and same chat (same person, or same group). */
  fun sameChat(
    pkgA: String, senderA: String, pkgB: String, senderB: String,
    groupA: Boolean = false, groupB: Boolean = false,
  ): Boolean {
    if (pkgA.isBlank() || pkgA != pkgB) return false
    val a = chatOf(senderA, groupA)
    return a.isNotEmpty() && a != "group:" && a == chatOf(senderB, groupB)
  }

  /** Same app and exactly the same sender (in a group: the same person in the same group). */
  fun sameSender(pkgA: String, senderA: String, pkgB: String, senderB: String): Boolean =
    pkgA.isNotBlank() && pkgA == pkgB && senderA.isNotBlank() && senderA.trim() == senderB.trim()
}
