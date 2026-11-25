package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.Activity
import de.juliandigitalproducts.notitocal.analysis.ConversationAggregator
import de.juliandigitalproducts.notitocal.analysis.TextLang
import de.juliandigitalproducts.notitocal.analysis.TimeCues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class WordingTest {
  @Test fun languageFollowsTheMessage() {
    assertEquals(TextLang.DE, TextLang.of("Wir treffen uns morgen um 18 Uhr am Kino"))
    assertEquals(TextLang.EN, TextLang.of("Let's meet tomorrow at the cinema, see you then"))
  }

  @Test fun activitiesInBothLanguages() {
    assertEquals(Activity.CINEMA, Activity.find("Kinoabend am Freitag"))
    assertEquals(Activity.CINEMA, Activity.find("movies on Friday"))
    assertEquals(Activity.MEAL, Activity.find("Abendessen bei Oma"))
    assertEquals(Activity.MEAL, Activity.find("dinner at 7"))
    assertEquals(Activity.DOCTOR, Activity.find("Zahnarzt um 9"))
    assertEquals(Activity.DOCTOR, Activity.find("dentist at 9 am"))
    // "Interessen" / "vergessen" are no meal.
    assertNull(Activity.find("Hast du die Interessen nicht vergessen?"))
    assertEquals("Cinema", Activity.CINEMA.title(TextLang.EN))
    assertEquals("Kino", Activity.CINEMA.title(TextLang.DE))
  }

  @Test fun endTimesInBothLanguages() {
    assertEquals(LocalTime.of(22, 0), TimeCues.end("sollte so 22 Uhr zu Ende sein"))
    assertEquals(LocalTime.of(22, 0), TimeCues.end("should be over by 10 pm"))
    assertEquals(LocalTime.of(23, 30), TimeCues.end("we finish around 11:30 pm"))
    assertNull(TimeCues.end("bis morgen"))
  }

  @Test fun spansInBothLanguages() {
    assertEquals(LocalTime.of(18, 0) to LocalTime.of(22, 0), TimeCues.span("18-22 Uhr"))
    assertEquals(LocalTime.of(18, 0) to LocalTime.of(22, 0), TimeCues.span("from 6 to 10 pm"))
    assertEquals(LocalTime.of(11, 0) to LocalTime.of(13, 0), TimeCues.span("11 am to 1 pm"))
    assertNull(TimeCues.span("3 to 4 people"))
  }

  @Test fun englishChatGetsAnEnglishTitle() {
    val now = System.currentTimeMillis()
    val state = ConversationAggregator.evaluateThread(
      listOf(
        ConversationAggregator.SessionMessage("Mark", "Let's meet at the cinema tomorrow at 6 pm", now - 10_000),
        ConversationAggregator.SessionMessage("Mark", "should be over by 10 pm, sounds good", now),
      ),
      java.time.LocalDateTime.of(2026, 11, 10, 12, 0),
      de.juliandigitalproducts.notitocal.data.AppSettings(),
      "Mark",
    )
    assertTrue(state.title, state.title?.startsWith("Cinema (18–22)") == true)
  }
}
