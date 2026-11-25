package de.juliandigitalproducts.notitocal.pipeline

import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.data.LogEntry
import de.juliandigitalproducts.notitocal.data.RoutingRule
import de.juliandigitalproducts.notitocal.data.TargetType
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.model.runSafely
import de.juliandigitalproducts.notitocal.output.Targets
import java.time.LocalDate

/**
 * Applies the current routing rules again to entries that already exist (e.g. after a new rule was added),
 * limited to the events and tasks dated within a range the user picked.
 *
 * Not delivered yet → only the entry's rule changes; it is delivered with the new rule later.
 * Already in the device calendar → the event is moved to the new rule's calendar.
 * Other services keep the item where it is (moving it there could create a second copy); the entry says so.
 */
class Resort(private val app: NotiToCalApp) {

  data class Change(val entry: LogEntry, val newRoute: String?)
  data class Summary(val checked: Int, val changed: Int, val moved: Int, val notMoved: Int)

  /** The entries in [from]..[to] whose rule would change, plus how many entries were looked at. */
  fun plan(from: LocalDate, to: LocalDate): Pair<List<Change>, Int> {
    val inRange = app.log.allEntries().filter { inRange(it, from, to) }
    return changes(app.settings.current.routingRules, inRange) to inRange.size
  }

  suspend fun run(changes: List<Change>, checked: Int, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Summary {
    var moved = 0
    var notMoved = 0
    changes.forEachIndexed { i, change ->
      onProgress(i, changes.size)
      when (apply(change)) {
        Outcome.MOVED -> moved++
        Outcome.NOT_MOVED -> notMoved++
        Outcome.REROUTED -> Unit
      }
    }
    onProgress(changes.size, changes.size)
    return Summary(checked, changes.size, moved, notMoved)
  }

  private enum class Outcome { REROUTED, MOVED, NOT_MOVED }

  private fun apply(change: Change): Outcome {
    val entry = change.entry
    val rerouted = entry.detection.copy(route = change.newRoute)
    if (entry.status != Status.ADDED && entry.status != Status.PARTIAL) {
      app.log.update(entry.id, d = rerouted)
      return Outcome.REROUTED
    }
    val delivered = Targets.succeededTargets(entry.result)
    val lines = mutableListOf<String>()
    var updated = rerouted
    var outcome = Outcome.REROUTED
    if (TargetType.DEVICE_CALENDAR in delivered) {
      val s = Router.apply(app.settings.current, Router.rule(app.settings.current, rerouted))
      val r = runSafely { Targets.moveDeviceCalendarEvent(app, rerouted, s, entry.detection.calendarEventId) }.getOrNull()
      val newId = r?.let { Targets.calendarEventId(it) }
      if (newId != null) updated = updated.copy(calendarEventId = newId)
      outcome = if (r?.ok == true) Outcome.MOVED else Outcome.NOT_MOVED
      lines += "⇄ ${TargetType.DEVICE_CALENDAR.name}: " + (r?.message ?: "not moved")
    }
    delivered.filter { it != TargetType.DEVICE_CALENDAR }.sortedBy { it.ordinal }.forEach { t ->
      lines += "⇄ ${t.name}: kept in the previous place"
      if (outcome == Outcome.REROUTED) outcome = Outcome.NOT_MOVED
    }
    val kept = entry.result.lineSequence().filterNot { it.startsWith("⇄ ") }.joinToString("\n")
    app.log.update(entry.id, d = updated, result = (kept + "\n" + lines.joinToString("\n")).trim())
    return outcome
  }

  companion object {
    private val SORTABLE = setOf(Status.PENDING, Status.ADDED, Status.PARTIAL, Status.FAILED)

    fun inRange(e: LogEntry, from: LocalDate, to: LocalDate): Boolean {
      if (e.status !in SORTABLE || e.detection.kind !in setOf(Kind.EVENT, Kind.TASK)) return false
      val date = (e.detection.startDateTime() ?: e.detection.dueDateTime())?.toLocalDate() ?: return false
      return !date.isBefore(from) && !date.isAfter(to)
    }

    /**
     * Entries whose rule differs under [rules] – the same matching as for new messages.
     * Without the stored message text a lost keyword match can't be told apart from a rule that no longer fits,
     * so such an entry only ever gets a rule, never loses its current one.
     */
    fun changes(rules: List<RoutingRule>, entries: List<LogEntry>): List<Change> = entries.mapNotNull { e ->
      val newRoute = Router.match(rules, e.detection, e.text, e.sender)?.id
      when {
        newRoute == e.detection.route -> null
        newRoute == null && e.text.isBlank() && rules.any { it.id == e.detection.route && it.enabled } -> null
        else -> Change(e, newRoute)
      }
    }
  }
}
