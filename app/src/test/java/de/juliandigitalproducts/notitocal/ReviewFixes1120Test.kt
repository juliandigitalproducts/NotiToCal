package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.output.SourceInfo
import de.juliandigitalproducts.notitocal.output.Targets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Code review 20.11.2026. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NotiToCalApp::class)
class ReviewFixes1120Test {

  @Test fun loneCarriageReturnCannotStartANewIcsLine() {
    val d = Detection(
      Kind.EVENT, title = "Kunde\rX-INJECTED:1", start = "2026-12-03T10:00",
      description = "Zeile 1\rEND:VEVENT\r\nZeile 2\nZeile 3", location = "Ort\rORGANIZER:mailto:x@example.com",
    )
    val ics = Targets.buildIcs(d, AppSettings())
    // Every line break left in the data is a CRLF from the builder itself.
    assertFalse(ics.replace("\r\n", "").contains('\r'))
    assertFalse(ics.lines().any { it.startsWith("X-INJECTED") || it.startsWith("ORGANIZER") })
    assertTrue(ics.contains("SUMMARY:Kunde\\nX-INJECTED:1"))
    assertTrue(ics.contains("DESCRIPTION:Zeile 1\\nEND:VEVENT\\nZeile 2\\nZeile 3"))
    // Exactly one event block.
    assertTrue(ics.split("\r\n").count { it == "END:VEVENT" } == 1)
  }

  @Test fun webhookTemplateKeepsMessageTextOutWhenSourceSharingIsOff() {
    val d = Detection(Kind.EVENT, title = "Kunde", start = "2026-12-03T10:00")
    val src = SourceInfo("Messages", "pkg", "Anna", "geheimer Nachrichtentext", "NOTIFICATION")
    val template = "{\"t\":\"{{title}}\",\"s\":\"{{sender}}\",\"m\":\"{{text}}\",\"j\":{{json}}}"
    val off = Targets.fillTemplate(template, d, src, AppSettings(sendSourceToTargets = false))
    assertFalse(off.contains("Anna"))
    assertFalse(off.contains("geheimer"))
    assertTrue(off.contains("\"t\":\"Kunde\""))
    val on = Targets.fillTemplate(template, d, src, AppSettings(sendSourceToTargets = true))
    assertTrue(on.contains("\"s\":\"Anna\""))
    assertTrue(on.contains("\"m\":\"geheimer Nachrichtentext\""))
  }
}
