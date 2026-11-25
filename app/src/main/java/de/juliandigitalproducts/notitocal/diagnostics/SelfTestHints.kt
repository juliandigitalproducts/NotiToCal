package de.juliandigitalproducts.notitocal.diagnostics

/** Concrete reasons a self-test step cannot succeed with the current device and settings. */
enum class SelfTestTip {
  ANTI_VOCALE_FOLDER,
  ANTI_VOCALE_OFF,
  OCR_EMPTY_NO_VISION,
}

/** Voice messages need Anti-Vocale switched on and its export folder chosen. */
fun antiVocaleTips(enabled: Boolean, hasFolder: Boolean): List<SelfTestTip> = when {
  !enabled -> listOf(SelfTestTip.ANTI_VOCALE_OFF)
  !hasFolder -> listOf(SelfTestTip.ANTI_VOCALE_FOLDER)
  else -> emptyList()
}

/** Empty OCR plus vision off is explained on its own; it is not a generic "nothing found". */
fun ocrEmptyTips(textBlank: Boolean, visionOn: Boolean): List<SelfTestTip> =
  if (textBlank && !visionOn) listOf(SelfTestTip.OCR_EMPTY_NO_VISION) else emptyList()
