package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.ConversationAggregator
import de.juliandigitalproducts.notitocal.analysis.PipelineA
import de.juliandigitalproducts.notitocal.analysis.PipelineB
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.CsvExporter
import de.juliandigitalproducts.notitocal.data.LogEntry
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class OptimizationV3Test {

  @Test
  fun testPipelineAContextDurationAndStyles() {
    val s = AppSettings(homeCity = "Wuppertal")
    val msg = PipelineA.PipelineMessage(id = "msg_1", text = "Morgen treffen wir uns um 14 Uhr am Kino")
    val userHist = listOf(
      PipelineA.UserHistoryItem(
        title = "Kino",
        location = "CinemaxX Wuppertal, Bundesallee 250, 42103 Wuppertal",
        dateIso = "2026-10-15",
      ),
    )
    val res = PipelineA.processAlgorithmic(
      messages = listOf(msg),
      userHistory = userHist,
      nearbyCandidates = emptyList(),
      settings = s,
    )

    assertEquals(2, res.algorithmVersion)
    assertTrue(res.isEventDetected)
    assertEquals(180, res.timeData.durationMinutes)
    assertTrue(res.timeData.isEndEstimated)
    assertEquals("HISTORY", res.locationData.resolutionSource)
    assertEquals("CinemaxX Wuppertal, Bundesallee 250, 42103 Wuppertal", res.locationData.resolvedAddress)

    // Check all 4 style variants are generated
    assertNotNull(res.styleVariants.compact.title)
    assertEquals("14:00–17:00 Uhr", res.styleVariants.compact.timeFormatted)
    assertNotNull(res.styleVariants.standard.title)
    assertNotNull(res.styleVariants.detailed.title)
    assertNotNull(res.styleVariants.customApplied.title)
  }

  @Test
  fun testPipelineBTimespanMergeAndLocationEnrichment() {
    val s = AppSettings(homeCity = "Wuppertal")
    val now = System.currentTimeMillis()
    val event1 = PipelineB.CandidateEvent(
      id = "evt_1",
      title = "Treffen am Kino",
      startMillis = now,
      endMillis = now + 60 * 60 * 1000,
      location = "Kino",
      description = "18 Uhr am Kino",
    )
    val event2 = PipelineB.CandidateEvent(
      id = "evt_2",
      title = "Kino Ende",
      startMillis = now + 3 * 60 * 60 * 1000,
      endMillis = now + 4 * 60 * 60 * 1000,
      location = "",
      description = "Sollte so 22 Uhr zu Ende sein",
    )
    val userHist = listOf(
      PipelineA.UserHistoryItem(
        title = "Kino",
        location = "CinemaxX Wuppertal, Bundesallee 250",
        dateIso = "2026-10-15",
      ),
    )

    val resB = PipelineB.processAlgorithmic(
      candidateEvents = listOf(event1, event2),
      userHistory = userHist,
      nearbyCandidates = emptyList(),
      settings = s,
    )

    assertEquals(2, resB.algorithmVersion)
    assertEquals(1, resB.mergeGroups.size)
    val mg = resB.mergeGroups.first()
    assertEquals("evt_1", mg.primaryKeepId)
    assertEquals(listOf("evt_2"), mg.idsToDelete)
    assertEquals("HISTORY", mg.mergedResult.locationSource)
    assertEquals("CinemaxX Wuppertal, Bundesallee 250", mg.mergedResult.locationAddress)
  }

  @Test
  fun testConversationAggregatorTimespanMerge() {
    val s = AppSettings(homeCity = "Wuppertal")
    val now = LocalDateTime.of(2026, 11, 10, 12, 0)
    val msgs = listOf(
      ConversationAggregator.SessionMessage("Markus", "Wir treffen uns um 18 Uhr am Kino", System.currentTimeMillis() - 10000),
      ConversationAggregator.SessionMessage("Markus", "Sollte so 22 Uhr zu Ende sein", System.currentTimeMillis()),
    )

    val state = ConversationAggregator.evaluateThread(msgs, now, s, "Markus")
    assertNotNull(state.time)
    assertEquals(18, state.time?.hour)
    assertNotNull(state.endTime)
    assertEquals(22, state.endTime?.hour)
    assertEquals(240, state.durationMinutes)
    assertFalse(state.isEndEstimated)
    assertTrue(state.title?.contains("Kino (18–22 Uhr)") == true)
  }

  @Test
  fun testCsvExporterRfc4180Conformity() {
    val entry1 = LogEntry(
      id = 101L,
      created = 1762732800000L,
      source = SourceType.WHATSAPP,
      packageName = "com.whatsapp",
      appLabel = "WhatsApp",
      sender = "Markus Müller",
      text = "Kinoabend, \"Popcorn\" inklusive!",
      detection = Detection(
        kind = Kind.EVENT,
        title = "Kinoabend",
        start = "2026-11-10T18:00",
        end = "2026-11-10T22:00",
        location = "CinemaxX, Wuppertal",
        confidence = 0.95,
        reason = "Kontext Kino",
      ),
      status = Status.ADDED,
      result = "Delivered to Calendar",
    )

    // Without AI columns
    val csvWithoutAi = CsvExporter.generateCsv(listOf(entry1), includeAi = false)
    assertTrue(csvWithoutAi.startsWith("\uFEFF"))
    assertTrue(csvWithoutAi.contains("ID,Datum,Quelle,Paket,Absender,Nachrichtentext,Medientyp\r\n"))
    assertTrue(csvWithoutAi.contains("\"Kinoabend, \"\"Popcorn\"\" inklusive!\""))

    // With AI columns
    val csvWithAi = CsvExporter.generateCsv(listOf(entry1), includeAi = true)
    assertTrue(csvWithAi.startsWith("\uFEFF"))
    assertTrue(csvWithAi.contains("Art,Titel,Startzeit,Endzeit,Dauer_Minuten,Ort,Konfidenz,KI_Begruendung"))
    assertTrue(csvWithAi.contains("EVENT"))
    assertTrue(csvWithAi.contains("240"))
    assertTrue(csvWithAi.contains("\"CinemaxX, Wuppertal\""))
  }

  @Test
  fun testSelfTestFlagDecoupling() {
    val c = Captured(
      source = SourceType.SCREENSHOT,
      text = "Termin morgen um 15 Uhr",
      isSelfTest = true,
    )
    assertTrue(c.isSelfTest)
  }

  @Test
  fun testAiProgressEstimatorCurveAndPhases() {
    // 0s: start of INIT phase
    assertEquals(0.0f, de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.calculateProgress(0L, false), 0.001f)
    assertEquals(de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.Phase.INIT, de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.determinePhase(0L, false))

    // 7.5s: halfway through INIT phase (~17.5%)
    val p7s = de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.calculateProgress(7500L, false)
    assertEquals(0.175f, p7s, 0.01f)
    assertEquals(de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.Phase.INIT, de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.determinePhase(7500L, false))

    // 15s: start of ANALYZING phase (35%)
    val p15s = de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.calculateProgress(15000L, false)
    assertEquals(0.35f, p15s, 0.01f)
    assertEquals(de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.Phase.ANALYZING, de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.determinePhase(15000L, false))

    // 27.5s: halfway through ANALYZING phase (~55%)
    val p27s = de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.calculateProgress(27500L, false)
    assertEquals(0.55f, p27s, 0.01f)
    assertEquals(de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.Phase.ANALYZING, de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.determinePhase(27500L, false))

    // 40s: start of MATCHING phase (75%)
    val p40s = de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.calculateProgress(40000L, false)
    assertEquals(0.75f, p40s, 0.01f)
    assertEquals(de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.Phase.MATCHING, de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.determinePhase(40000L, false))

    // 58s: end of MATCHING deceleration (~94%)
    val p58s = de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.calculateProgress(58000L, false)
    assertEquals(0.94f, p58s, 0.01f)
    assertEquals(de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.Phase.MATCHING, de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.determinePhase(58000L, false))

    // >58s: asymptotic creep, remains strictly <= 0.96
    val p75s = de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.calculateProgress(75000L, false)
    assertTrue(p75s >= 0.94f)
    assertTrue(p75s <= 0.96f)

    // Completion: snaps to 100% and DONE phase
    assertEquals(1.0f, de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.calculateProgress(12000L, true), 0.001f)
    assertEquals(de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.Phase.DONE, de.juliandigitalproducts.notitocal.ui.AiProgressEstimator.determinePhase(12000L, true))
  }

  @Test
  fun testReactiveAllCheckedButtonLogic() {
    // Empty list: allChecked = false
    val emptyClusters = emptyList<DuplicateMatcher.DuplicateCluster>()
    val allCheckedEmpty = emptyClusters.isNotEmpty() && emptyClusters.all { it.aiAdvice != null }
    assertFalse(allCheckedEmpty)

    // Cluster without AI advice
    val eventA = DuplicateMatcher.CalendarEventItem(
      eventId = 1L,
      calendarId = 1L,
      title = "Meeting",
      startMillis = 1000L,
      endMillis = 2000L,
    )
    val cluster1 = DuplicateMatcher.DuplicateCluster(
      id = "c1",
      events = listOf(eventA),
      confidence = 0.9,
      category = DuplicateMatcher.DuplicateCategory.CERTAIN,
      reasons = emptyList(),
      defaultPrimaryId = 1L,
      proposal = DuplicateMatcher.buildMergeProposal(listOf(eventA), 1L),
      aiAdvice = null,
    )
    val clustersPartial = listOf(cluster1)
    val allCheckedPartial = clustersPartial.isNotEmpty() && clustersPartial.all { it.aiAdvice != null }
    assertFalse(allCheckedPartial)

    // Cluster with AI advice
    val clusterChecked = cluster1.copy(
      aiAdvice = DuplicateMatcher.AiDuplicateAdvice(
        isDuplicate = true,
        confidence = 0.95,
        explanation = "Same time and title",
        recommendedPrimaryId = 1L,
        recommendedTitle = "Meeting",
        recommendedLocation = null,
        recommendedDescription = null,
      ),
    )
    val clustersDone = listOf(clusterChecked)
    val allCheckedDone = clustersDone.isNotEmpty() && clustersDone.all { it.aiAdvice != null }
    assertTrue(allCheckedDone)
  }
}
