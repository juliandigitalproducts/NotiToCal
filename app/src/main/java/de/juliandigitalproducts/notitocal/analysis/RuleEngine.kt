// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.analysis

import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.Why
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Offline, rule-based detector for events and tasks in seven languages. Fast and private; also used
 * as a pre-filter in hybrid mode so the LLM only sees messages that look relevant.
 */
object RuleEngine {

  /** Words that indicate an appointment, with a weight. */
  private val EVENT_WORDS: Map<String, Double> = mapOf(
    // de
    "termin" to 0.45, "treffen" to 0.4, "meeting" to 0.45, "besprechung" to 0.45, "verabredung" to 0.45, "arzt" to 0.35,
    "zahnarzt" to 0.45, "abendessen" to 0.3, "mittagessen" to 0.3, "frühstück" to 0.25, "essen gehen" to 0.35, "kino" to 0.3,
    "party" to 0.3, "geburtstag" to 0.3, "hochzeit" to 0.35, "telefonat" to 0.35, "konferenz" to 0.4, "vorstellungsgespräch" to 0.5,
    "abholen" to 0.15, "training" to 0.25, "konzert" to 0.35, "feier" to 0.3, "sitzung" to 0.4, "webinar" to 0.4, "videocall" to 0.4,
    "sehen uns" to 0.4, "treffen wir uns" to 0.5, "lass uns" to 0.2, "hast du zeit" to 0.35, "zeit für" to 0.2, "kommst du" to 0.25,
    "eingeladen" to 0.3, "einladung" to 0.35, "gespräch" to 0.35, "untersuchung" to 0.4, "kurs" to 0.25, "reservierung" to 0.4, "reserviert" to 0.35, "flug" to 0.3, "abfahrt" to 0.25, "zugestellt" to 0.2,
    // en
    "appointment" to 0.5, "meet" to 0.35, "meetup" to 0.4, "dinner" to 0.3, "lunch" to 0.3, "breakfast" to 0.25, "call" to 0.2,
    "conference" to 0.4, "interview" to 0.4, "doctor" to 0.35, "dentist" to 0.45, "concert" to 0.35, "wedding" to 0.35,
    "birthday" to 0.3, "see you" to 0.35, "let's meet" to 0.5, "are you free" to 0.35, "invitation" to 0.35, "invited" to 0.3,
    "reservation" to 0.4, "booked" to 0.3, "flight" to 0.3, "session" to 0.25, "zoom" to 0.25, "catch up" to 0.3, "hang out" to 0.3,
    // fr
    "rendez-vous" to 0.5, "rdv" to 0.45, "réunion" to 0.45, "reunion" to 0.4, "dîner" to 0.3, "diner" to 0.25, "déjeuner" to 0.3,
    "médecin" to 0.35, "dentiste" to 0.45, "on se voit" to 0.45, "anniversaire" to 0.3, "mariage" to 0.3, "soirée" to 0.25, "rencontre" to 0.35,
    // es
    "cita" to 0.5, "reunión" to 0.45, "quedamos" to 0.5, "quedar" to 0.35, "nos vemos" to 0.45, "cena" to 0.3, "almuerzo" to 0.3,
    "comida" to 0.2, "médico" to 0.35, "medico" to 0.3, "dentista" to 0.45, "cumpleaños" to 0.3, "boda" to 0.35, "fiesta" to 0.3,
    // it
    "appuntamento" to 0.5, "riunione" to 0.45, "incontro" to 0.4, "ci vediamo" to 0.45, "cena" to 0.3, "pranzo" to 0.3,
    "compleanno" to 0.3, "matrimonio" to 0.3, "festa" to 0.3, "visita" to 0.3,
    // pt
    "compromisso" to 0.45, "reunião" to 0.45, "reuniao" to 0.4, "encontro" to 0.4, "jantar" to 0.3, "almoço" to 0.3,
    "consulta" to 0.4, "aniversário" to 0.3, "casamento" to 0.3, "festa" to 0.3, "vemo-nos" to 0.45,
    // pl
    "spotkanie" to 0.5, "spotkajmy" to 0.5, "wizyta" to 0.45, "lekarz" to 0.35, "dentysta" to 0.45, "kolacja" to 0.3, "obiad" to 0.3,
    "urodziny" to 0.3, "ślub" to 0.35, "impreza" to 0.3, "zebranie" to 0.45, "do zobaczenia" to 0.35, "rozmowa kwalifikacyjna" to 0.5,
  ) + Lexicon.EVENT

  /** Words that indicate something the user should do, with a weight. */
  private val TASK_WORDS: Map<String, Double> = mapOf(
    // de
    "vergiss nicht" to 0.55, "nicht vergessen" to 0.55, "denk dran" to 0.5, "denk daran" to 0.5, "erinnere" to 0.45, "erinnerung" to 0.4,
    "kannst du" to 0.35, "könntest du" to 0.35, "würdest du" to 0.3, "bitte" to 0.15, "musst" to 0.35, "muss" to 0.25, "müssen" to 0.25,
    "sollst" to 0.3, "solltest" to 0.3, "erledigen" to 0.45, "kaufen" to 0.4, "besorgen" to 0.4, "mitbringen" to 0.4, "bezahlen" to 0.45,
    "überweisen" to 0.45, "zurückrufen" to 0.5, "ruf mich an" to 0.4, "schicken" to 0.3, "senden" to 0.25, "abgeben" to 0.4,
    "einreichen" to 0.45, "deadline" to 0.5, "frist" to 0.45, "fällig" to 0.45, "todo" to 0.5, "to-do" to 0.5, "aufgabe" to 0.4,
    "einkaufen" to 0.4, "kündigen" to 0.4, "anmelden" to 0.35, "buchen" to 0.35, "reservieren" to 0.35, "unterschreiben" to 0.4,
    // en
    "don't forget" to 0.55, "dont forget" to 0.55, "remember to" to 0.55, "remind me" to 0.5, "reminder" to 0.4, "please" to 0.15,
    "can you" to 0.35, "could you" to 0.35, "would you" to 0.3, "need to" to 0.4, "needs to" to 0.35, "have to" to 0.35, "must" to 0.3,
    "should" to 0.2, "buy" to 0.35, "pick up" to 0.35, "pay" to 0.35, "call back" to 0.5, "call me" to 0.4, "send" to 0.25,
    "submit" to 0.45, "due" to 0.4, "to do" to 0.4, "task" to 0.4, "renew" to 0.4, "cancel" to 0.3, "sign" to 0.25, "book" to 0.25,
    // fr
    "n'oublie pas" to 0.55, "noublie pas" to 0.55, "rappelle-toi" to 0.5, "rappelle moi" to 0.45, "peux-tu" to 0.35, "pourrais-tu" to 0.35,
    "tu peux" to 0.3, "il faut" to 0.35, "dois" to 0.3, "acheter" to 0.4, "payer" to 0.45, "envoyer" to 0.3, "rappeler" to 0.4,
    "à faire" to 0.45, "échéance" to 0.45, "tâche" to 0.4, "s'il te plaît" to 0.15, "stp" to 0.15,
    // es
    "no olvides" to 0.55, "no te olvides" to 0.55, "recuerda" to 0.45, "recuérdame" to 0.5, "puedes" to 0.3, "podrías" to 0.35,
    "tienes que" to 0.4, "hay que" to 0.35, "debes" to 0.35, "comprar" to 0.4, "pagar" to 0.45, "enviar" to 0.3, "llamar" to 0.3,
    "llámame" to 0.4, "fecha límite" to 0.5, "tarea" to 0.4, "por favor" to 0.15, "pendiente" to 0.35,
    // it
    "non dimenticare" to 0.55, "ricorda" to 0.45, "ricordati" to 0.5, "puoi" to 0.3, "potresti" to 0.35, "devi" to 0.35,
    "bisogna" to 0.35, "comprare" to 0.4, "pagare" to 0.45, "inviare" to 0.3, "mandare" to 0.3, "chiamare" to 0.3, "richiamare" to 0.45,
    "scadenza" to 0.5, "compito" to 0.35, "per favore" to 0.15, "da fare" to 0.45,
    // pt
    "não esqueças" to 0.55, "não esqueça" to 0.55, "nao esqueca" to 0.5, "não te esqueças" to 0.55, "lembra-te" to 0.5, "lembra" to 0.35,
    "lembrete" to 0.4, "podes" to 0.3, "pode" to 0.2, "poderia" to 0.3, "tens de" to 0.4, "tem que" to 0.35, "precisa" to 0.3,
    "precisas" to 0.35, "comprar" to 0.4, "pagar" to 0.45, "enviar" to 0.3, "ligar" to 0.3, "liga-me" to 0.4, "prazo" to 0.5,
    "tarefa" to 0.4, "por favor" to 0.15,
    // pl
    "nie zapomnij" to 0.55, "pamiętaj" to 0.5, "pamietaj" to 0.5, "przypomnij" to 0.45, "przypomnienie" to 0.4, "możesz" to 0.3,
    "mozesz" to 0.3, "mógłbyś" to 0.35, "mogłabyś" to 0.35, "musisz" to 0.4, "trzeba" to 0.35, "kupić" to 0.4, "kup" to 0.35,
    "zapłacić" to 0.45, "zapłać" to 0.45, "wysłać" to 0.3, "wyślij" to 0.35, "zadzwoń" to 0.4, "oddzwoń" to 0.5, "zadanie" to 0.4,
    "proszę" to 0.15, "prosze" to 0.15,
  ) + Lexicon.TASK

  /** Signals for promotions, codes and automated noise. */
  private val NOISE_WORDS = listOf(
    "rabatt", "angebot", "gutschein", "gewinnspiel", "newsletter", "abmelden", "sale", "% off", "discount", "coupon", "promo",
    "unsubscribe", "offer", "deal", "verification code", "bestätigungscode", "sicherheitscode", "einmalpasswort", "otp", "your code",
    "ihr code", "dein code", "code:", "tan:", "réduction", "soldes", "offre", "code de vérification", "descuento", "oferta",
    "código", "sconto", "offerta", "codice", "desconto", "promoção", "código de verificação", "rabat", "promocja", "kod weryfikacyjny",
    "kod:", "wurde zugestellt", "was delivered", "has been delivered", "sendungsverfolgung", "tracking number", "akku", "battery",
    "update available", "backup", "download", "screenshot saved", "bildschirmfoto",
  ) + Lexicon.NOISE

  /** Blessings and good-morning chain messages: they mention "heute" or a weekday but are no appointment. */
  private val BLESSING = rx(
    """\b(?:möge\s+gott|möge\s+er\s+uns|gott\s+segne|gottes\s+segen|god\s+bless|may\s+god|may\s+the\s+lord|amen|gesegnete[nrs]?|blessed\s+(?:day|morning|week|sunday)|dio\s+ti\s+benedica|que\s+dios\s+te\s+bendiga|deus\s+te\s+abençoe)\b""",
  )

  /**
   * Cancellations: an event is not to be created. Also with words in between ("fällt leider aus", "findet morgen
   * nicht statt"), which a plain substring list missed.
   */
  private val CANCEL = rx(
    """\b(?:abgesagt|absagen|storniert|entfällt|entfallen|fällt\s+(?:(?!nicht\b)\p{L}+\s+){0,3}aus\b|findet\s+(?:\p{L}+\s+){0,3}nicht\s+statt|""" +
      """cancelled|canceled|called\s+off|annulé|annule|cancelad[oa]|annullat[oa]|disdetto|odwołan[ey]|odwolane)""",
  )

  /**
   * Looking back ("war gestern ein super Abend"): without any date or time ahead such a message is no appointment, so
   * the pre-filter lowers its relevance.
   */
  private val PAST_ONLY = rx("""\b(?:gestern|vorgestern|letzte\s+woche|letzten\s+(?:montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|wochenende)|yesterday|last\s+(?:night|week|weekend)|hier\s+soir|ayer|ieri|ontem|wczoraj)\b""")

  /**
   * Parcel notices (third review). The tracking sentence ("Sendungsverfolgung unter …", "Track your package") is noise
   * on its own, but a notice that announces a delivery day or window is an appointment (being at home): then the
   * tracking words do not count against it. Already delivered or "on its way" without a day stays noise.
   */
  private val DELIVERY_ITEM = rx("""\b(?:paket\w*|päckchen|sendung(?:en)?|lieferung|zustellung|package|parcel|shipment|delivery)\b""")
  private val DELIVERY_ANNOUNCED = rx(
    """\b(?:wird|werden|kommt|kommen|trifft|erfolgt|voraussichtlich|geplant|zwischen|will\s+(?:be\s+delivered|arrive)|arriving|arrives|expected|scheduled|estimated|between)\b|zustellung\s+(?:am|ihres|ihrer|des|der)""",
  )
  private val DELIVERED_ALREADY = rx(
    """\b(?:wurde|ist|wurden|sind)\s+(?:heute\s+|gerade\s+|erfolgreich\s+)?(?:zugestellt|geliefert|abgegeben|angekommen)\b|\b(?:was|were|has\s+been|have\s+been)\s+(?:successfully\s+)?delivered\b|\bdelivered\s+(?:today|at)\b""",
  )
  private val TRACKING_WORDS = setOf("sendungsverfolgung", "tracking number")

  /** A notice that announces when a parcel comes: a delivery word, a future day or time, and not delivered already. */
  internal fun isDeliveryAnnouncement(text: String, w: ParsedWhen): Boolean =
    (w.dateExplicit || w.time != null) && DELIVERY_ITEM.containsMatchIn(text) && DELIVERY_ANNOUNCED.containsMatchIn(text) &&
      !DELIVERED_ALREADY.containsMatchIn(text)


  private val QUESTION_INVITE = rx("""\b(hast du|habt ihr|wollen wir|sollen wir|are you|shall we|should we|can we|could we|do you want|tu es libre|on se|est-ce qu|¿?quieres|¿?te viene|¿?podemos|vuoi|ti va|possiamo|queres|podemos|masz czas|może)\b""")
  private val LEADING_FILLER = rx("""^\s*(?:(?:hey|hi|hallo|hello|moin|servus|salut|bonjour|hola|ciao|olá|ola|cześć|czesc|hej|guten\s+(?:morgen|tag|abend)|liebe[rns]?|dear)\b(?:\s+(?:liebe[rns]?|meine?\s+liebe[rns]?|zusammen|alle|leute))?(?:\s+[A-ZÄÖÜ][\p{L}\-]+(?:\s+und\s+[A-ZÄÖÜ][\p{L}\-]+)?)?[,!.\s]*)+""")
  private val INVITE_FILLER = rx("""^\s*(?:wir\s+möchten\s+(?:euch|dich|sie)\s+(?:gerne\s+|herzlich\s+)?einladen|wir\s+laden\s+(?:euch|dich|sie)\s+(?:gerne\s+|herzlich\s+)?ein|ich\s+lade\s+(?:euch|dich|sie)\s+(?:gerne\s+|herzlich\s+)?ein|ich\s+möchte\s+(?:euch|dich|sie)\s+(?:gerne\s+|herzlich\s+)?einladen)\b[\s,]*""")
  private val TASK_PREFIX = rx("""^\s*(?:und\s+|and\s+|et\s+|y\s+|e\s+|i\s+)?(?:vergiss nicht|nicht vergessen|denk dran|denk daran|bitte|kannst du( bitte)?|könntest du( bitte)?|don't forget to|dont forget to|don't forget|remember to|please|can you( please)?|could you( please)?|n'oublie pas de|n'oublie pas|peux-tu|pourrais-tu|no olvides|no te olvides de|recuerda|puedes|podrías|non dimenticare di|non dimenticare|ricordati di|puoi|potresti|não esqueças de|não esqueça de|lembra-te de|podes|nie zapomnij|pamiętaj,? żeby|pamiętaj|możesz)\b[\s,:]*""")
  private val DANGLING_PREP = rx("""\b(?:um|gegen|ab|am|bis|zwischen|auf|between|at|on|by|until|from|à|vers|le|dès|a las|a la|el|hasta|alle|il|entro|às|as|no|na|até|o|w|we|do)\s*$""")
  private val SENTENCE_SPLIT = Regex("""(?<=[.!?])(?<!\b(?:Dr|Prof|med|Fr|Hr|Hrn|Nr|Str|St|ca|Tel|bzw|inkl|evtl|ggf|Mo|Di|Mi|Do|Sa|So)\.)\s+|\s*\n\s*""")
  /** Confirmation phrasing of booking mails ("hiermit bestätigen wir Ihren Beratungstermin …") is no title. */
  private val CONFIRM_FILLER = rx("""^\s*(?:hiermit\s+)?(?:bestätigen\s+wir\s+(?:ihnen\s+)?(?:hiermit\s+)?|wir\s+bestätigen\s+(?:ihnen\s+)?(?:hiermit\s+)?|we\s+(?:hereby\s+|are\s+pleased\s+to\s+)?confirm\s+)(?:ihren|ihre|ihr|deinen|deine|dein|den|die|das|your|the)?\s*""")
  /** "Raum A", "Room B", "Raum 3.14": after a room word a single letter or a dotted number belongs to the place. */
  private const val ROOM_SUFFIX =
    """(?<=\b(?:Raum|Räume|Room|Zimmer|Saal|Halle|Hall|Salle|Sala|Stanza|Sali|Pokój|Gebäude|Building|Haus|Etage|Floor|Gate|Gleis|Track))\s+(?:[A-ZÄÖÜ](?![\w'’\-])|\d+(?:\.\d+)+[a-z]?)"""
  private val LOCATION_PREFIX = rx("""(?:(?<!\S)@|\b(?:am|im|in der|in|bei|beim|an der|an dem|an|at|chez|au|à la|en el|en la|en|nel|nella|al|presso|no|na|em|w|we))\s+((?:[A-ZÄÖÜÉÈÀÁÍÓÚ][\w'’\-]+)(?:$ROOM_SUFFIX|\s+(?:[A-ZÄÖÜÉÈÀÁÍÓÚ][\w'’\-]+|\d+[a-z]?)){0,3})""", ignoreCase = false)
  private val LABELLED_LOCATION = rx("""(?i)\b(?:Ort|Location|Treffpunkt|Adresse|Address|Wo|Place|Lieu|Ubicación|Posizione|Lokalizacja)\s*[:=]\s*([^\n\r;!]+)""")
  private val CRYPTIC_GLUE_CHARS = "%&$#*~^=_+|\\<>{}@"
  private val CRYPTIC_GLUED_TOKEN = Regex("""[\p{L}\d]+(?:[%&$#*~^=_+|\\<>{}]+[\p{L}\d]+)+""")
  private val COMMON_TITLE_WORDS = setOf(
    "am", "an", "auf", "bei", "beim", "bis", "das", "dem", "den", "der", "die", "ein", "eine", "einem", "einen", "einer",
    "für", "im", "in", "ins", "mit", "nach", "um", "und", "von", "vor", "zu", "zum", "zur", "at", "in", "on", "to", "with", "for", "the", "a", "an",
  )
  private val STREET_ADDRESS = rx("""\b((?:(?:[A-ZÄÖÜ][a-zäöüß\-]+|an\s+der|in\s+der|auf\s+der|am)\s+){0,3}[A-ZÄÖÜa-zäöüß\-]+(?:straße|strasse|str\.|weg|platz|allee|gasse|ring|chaussee|damm|ufer|pfad|zeile|street|st\.|road|rd\.|avenue|ave\.|lane|ln\.|drive|dr\.|boulevard|blvd\.|rue|via|calle|ulica|ul\.)\s+\d+[a-zA-Z]?(?:,\s*\d{4,5}\s+[A-ZÄÖÜ][a-zäöüß]+)?)\b""")
  private val GREETING_RECIPIENT = Regex(
    """(?:^|\n)\s*(?:hallo|hi|hey|servus|moin|guten\s+(?:morgen|tag|abend)|liebe[rns]?|dear|hello|bonjour|salut|hola|ciao)\s+(?:liebe[rns]?\s+|meine?\s+liebe[rns]?\s+)?([A-ZÄÖÜ][\p{L}\-]+)(?:\s+(?:und|&)\s+([A-ZÄÖÜ][\p{L}\-]+))?""",
    RegexOption.IGNORE_CASE,
  )
  private val SIGN_OFF_SENDER = Regex(
    """(?:\b(?:viele\s+grüße|liebe\s+grüße|beste\s+grüße|herzliche\s+grüße|schöne\s+grüße|mit\s+freundlichen\s+grüßen|lg|vg|mfg|dein[e]?|euer|eure|best\s+regards|kind\s+regards|many\s+thanks|yours|cheers|cordialement|saludos|un\s+saludo|cari\s+saluti|pozdrawiam)\b)\s*[,!:]?\s*\n?\s*(?:(?:ihre?|your)[ \t]+)?([A-ZÄÖÜ][\p{L}\-]+(?:[ \t]+[A-ZÄÖÜ][\p{L}\-]+){0,2})""",
    RegexOption.IGNORE_CASE,
  )
  private val AT_RECIPIENT_EXPLICIT = rx(
    """\b(?:bei\s+(?:dir|euch)\s+zu\s*hause|zu\s+(?:dir|euch)\s+nach\s+hause|wir\s+kommen\s+zu\s+(?:dir|euch)|komme\s+zu\s+(?:dir|euch)|treffen\s+(?:wir\s+)?uns\s+bei\s+(?:dir|euch)|at\s+your\s+(?:place|house|home)|chez\s+(?:toi|vous))\b""",
  )
  private val AT_SENDER_INVITE = rx(
    """\b(?:bei\s+uns|zu\s+uns|bei\s+mir|zu\s+mir|laden\s+(?:euch|dich|sie)\s+(?:gerne\s+|herzlich\s+)?ein|möchten\s+(?:euch|dich|sie)\s+(?:gerne\s+|herzlich\s+)?einladen|kommst\s+du\s+zu\s+uns|kommt\s+ihr\s+zu\s+uns|at\s+(?:our|my)\s+(?:place|house|home)|chez\s+(?:nous|moi))\b""",
  )
  private val SYSTEM_DEBUG_MARKERS = listOf(
    "automatisch eingetragen",
    "automatically added",
    "die sicherheit von",
    "the confidence of",
    "erreicht deine schwelle von",
    "reaches your threshold of",
    "schwelle von",
    "threshold of",
    "—— notitocal ——",
    "-- notitocal --",
    "notitocal",
    "in kalender-app öffnen",
    "in kalender öffnen",
    "eintrag hinzufügen",
    "service_calendar",
    "status:event_created",
    "status:task_created",
    "device_calendar:",
    "task_provider:",
    "confidence:",
    "< termin",
    "<termin",
  )
  private val GENERIC_SENDER_LABELS = setOf(
    "test", "manual", "manuell", "screenshot", "bildschirmfoto", "anti-vocale", "sprachnachricht", "voice memo", "notitocal", "image", "bild",
  )

  private val VAGUE_PLACE = rx(
    """^\s*(?:(?:bei|zu|nach|at|to|in|im|am|beim|chez|da|en|a|w|u)\s+)?(?:uns|mir|dir|euch|ihnen|ihm|ihr|mich|dich|hause|zuhause|haus|daheim|home|my\s+place|our\s+place|your\s+place|my\s+house|our\s+house|hier|dort|da|drinnen|draußen|here|there|online|nous|moi|toi|vous|casa|mnie|ciebie|nas)\s*[.!]?\s*$""",
  )

  /** "bei uns", "zu Hause", "at my place": a real place for the people involved, but nothing a map can find. */
  fun isVaguePlace(location: String?): Boolean = location.isNullOrBlank() || VAGUE_PLACE.matches(location.trim())

  /** True when the text is a screenshot/copy of NotiToCal's own UI or system status text. */
  fun isSelfOrDebugText(raw: String): Boolean {
    // OCR breaks lines anywhere ("… nie automatisch\neingetragen"), so markers are matched across line breaks.
    val low = raw.lowercase().replace(Regex("""\s+"""), " ")
    if (SYSTEM_DEBUG_MARKERS.any { low.contains(it) }) return true
    if (low.contains("\"kind\":") && low.contains("\"confidence\":") && low.contains("\"title\":")) return true
    return false
  }

  /**
   * True when [raw] is a cryptic symbol-glued string (e.g. "kex%treffen&an&Tarverne", query fragments, or encoded noise)
   * rather than a human phrase with spaces like "treffen an Terverne".
   */
  fun isCrypticMessage(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return false
    val withoutUrls = MAP_URL.replace(trimmed, "").trim()
    if (withoutUrls.isEmpty()) return false
    val tokens = withoutUrls.split(Regex("""\s+""")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return false
    val crypticCount = tokens.count { tok -> CRYPTIC_GLUED_TOKEN.containsMatchIn(tok) }
    if (crypticCount == 0) return false
    // If the entire non-date content or majority of tokens are symbol-glued ("kex%treffen&an&Tarverne"), it's cryptic.
    return tokens.size == 1 || crypticCount * 2 >= tokens.size
  }

  /**
   * Cleans any cryptic symbol-glued tokens (e.g. "kex%treffen&an&Tarverne" -> "Treffen an Tarverne")
   * so cryptic strings are never set raw as an event or task title.
   */
  fun cleanCrypticTitle(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val knownKeywords = EVENT_WORDS.keys + TASK_WORDS.keys
    val cleanedTokens = trimmed.split(Regex("""\s+""")).mapNotNull { token ->
      if (CRYPTIC_GLUED_TOKEN.containsMatchIn(token)) {
        val parts = token.split(Regex("""[%&$#*~^=_+|\\<>{}@]+"""))
          .map { it.trim() }
          .filter { it.length >= 2 && it.all { ch -> ch.isLetter() || ch == '-' || ch == '\'' } }
        if (parts.isEmpty()) {
          null
        } else {
          // If the first glued segment is an unknown prefix (like "kex" in "kex%treffen&an&Tarverne")
          // followed by a known event/task keyword or common preposition, strip the cryptic prefix.
          val firstKeywordIdx = parts.indexOfFirst { p ->
            val low = p.lowercase()
            low in knownKeywords || COMPOUND_TAILS.any { low.endsWith(it) }
          }
          val sliced = when {
            firstKeywordIdx > 0 -> parts.drop(firstKeywordIdx)
            parts.size > 1 && parts[0].lowercase() !in knownKeywords && parts[0].lowercase() !in COMMON_TITLE_WORDS &&
              parts[1].lowercase() in (knownKeywords + COMMON_TITLE_WORDS) -> parts.drop(1)
            else -> parts
          }
          sliced.joinToString(" ").ifBlank { null }
        }
      } else if (token.count { it in CRYPTIC_GLUE_CHARS } >= 2) {
        null
      } else {
        token.trim('%', '&', '$', '#', '*', '~', '^', '=', '_', '|', '\\', '<', '>', '{', '}')
          .takeIf { it.isNotBlank() }
      }
    }
    val joined = cleanedTokens.joinToString(" ")
      .replace(Regex("""\s{2,}"""), " ")
      .trim(' ', ',', '.', '!', '?', ':', ';', '-')
    return joined.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
  }

  /** Strips any system/debug status lines or appended NotiToCal blocks from a text or description. */
  fun stripDebugAndSystemText(raw: String): String {
    val beforeMarker = raw.substringBefore("—— NotiToCal ——").substringBefore("-- NotiToCal --")
    return beforeMarker.lineSequence()
      .filterNot { line ->
        val l = line.trim().lowercase()
        (l == "< termin" || l == "< aufgabe" || l == "< event" || l == "< task" ||
          l == "<termin" || l == "<aufgabe" || l == "<event" || l == "<task") ||
          l.startsWith("automatisch eingetragen") || l.startsWith("automatically added") ||
          l.startsWith("nicht eingetragen") || l.startsWith("not added") ||
          l.startsWith("bitte bestätigen") || l.startsWith("please confirm") ||
          l.startsWith("✓ device_calendar") || l.startsWith("✗ device_calendar") ||
          l.startsWith("service_calendar") || l.startsWith("status:event_created") ||
          l.startsWith("status:task_created") ||
          l.startsWith("rohantwort") || l.startsWith("raw answer") ||
          l.startsWith("confidence:") || l.startsWith("in kalender-app öffnen") ||
          l.startsWith("in kalender öffnen") || l.startsWith("eintrag hinzufügen") ||
          (l.startsWith("{") && l.contains("\"kind\"") && l.contains("\"confidence\""))
      }
      .joinToString("\n")
      .trim()
  }

  /** Extracts recipient names greeted at the start of the message (e.g. "Hallo lieber Julian" -> {"julian"}). */
  fun extractGreetedNames(text: String): Set<String> {
    val out = mutableSetOf<String>()
    GREETING_RECIPIENT.findAll(text).forEach { m ->
      m.groupValues.drop(1).filter { it.isNotBlank() }.forEach { name ->
        val clean = name.trim(',', '.', '!', '?', ':').lowercase()
        if (clean.length >= 2 && !DateWords.isDateWord(clean)) out += clean
      }
    }
    return out
  }

  /** Extracts the sender name from a sign-off ("Viele Grüße Max Mustermann") or the notification sender metadata. */
  fun extractSenderName(text: String, sender: String = ""): String? {
    SIGN_OFF_SENDER.findAll(text).lastOrNull()?.groupValues?.getOrNull(1)?.trim()?.trim(',', '.', '!')?.let { signed ->
      if (signed.isNotBlank() && signed.lowercase() !in GENERIC_SENDER_LABELS) return signed
    }
    val meta = sender.substringBefore(" @ ").trim()
    if (meta.isNotBlank() && meta.lowercase() !in GENERIC_SENDER_LABELS) return meta
    return null
  }

  fun isExplicitlyAtRecipient(text: String): Boolean = AT_RECIPIENT_EXPLICIT.containsMatchIn(text)
  /**
   * Complete postal address: street with house number plus city (C6). "Bahnhofstraße 42, 10115 Berlin",
   * "Via della Spiga 5, Milano", "ul. Długa 5, 80-831 Gdańsk", "221B Baker Street, London", "12 rue de la Paix, 75002 Paris".
   * A place name alone ("im Büro", "bei Aldi") or a street without city is not enough.
   */
  private val ADDRESS_STREET_FIRST = Regex(
    """(?:\p{Lu}[\p{L}.'’\-]*|ul\.|al\.)(?:\s+(?:\p{Lu}[\p{L}.'’\-]*|de|del|della|delle|di|da|do|dos|das|la|le|les|des|du|am|an|auf|der|im|zum|zur|of|the))*?""" +
      """\s+\d{1,4}\s?[a-zA-Z]?(?:\s*,\s*(?:\d{2}-\d{3}\s+|\d{4,5}\s+)?|\s+(?:\d{2}-\d{3}|\d{4,5})\s+)\p{Lu}[\p{L}\-]+""",
  )
  private val ADDRESS_NUMBER_FIRST = Regex(
    """\b\d{1,5}[a-zA-Z]?,?\s+(?:[\p{L}.'’\-]+\s+){0,4}?(?:Street|St\.|Road|Rd\.|Avenue|Ave\.|Lane|Ln\.|Drive|Dr\.|Boulevard|Blvd\.|Way|Place|Court|Square)\s*,\s*\p{Lu}[\p{L}\-]+""" +
      """|\b\d{1,4}(?:\s?(?:bis|ter))?,?\s+(?:rue|avenue|av\.|boulevard|bd|place|chemin|allée|impasse|quai|route)\s+[\p{L}.'’\- ]{2,40}?,\s*(?:\d{5}\s+)?\p{Lu}[\p{L}\-]+""",
  )

  /** The complete postal address in [text], or null. */
  fun fullAddress(text: String): String? =
    listOfNotNull(ADDRESS_STREET_FIRST.find(text), ADDRESS_NUMBER_FIRST.find(text)).minByOrNull { it.range.first }?.value?.trim()

  private val MAP_URL = rx("""\b(?:https?://)?(?:maps\.google\.\w+|goo\.gl/maps|maps\.app\.goo\.gl|maps\.apple\.com)/[^\s]+""")

  fun extractLocation(clean: String, sender: String = ""): String? {
    val greeted = extractGreetedNames(clean)
    val atRecipient = isExplicitlyAtRecipient(clean)
    fun validLoc(candidate: String): String? {
      val trimmed = candidate.trim().removeSuffix(".")
      if (trimmed.isBlank()) return null
      val low = trimmed.lowercase()
      if (low == "julian" || low == "julian daniels") return null
      // "bei Dr. Weber": the title of a person is no place.
      if (low.trimEnd('.') in setOf("dr", "prof", "hr", "fr", "herr", "frau", "mr", "mrs", "ms")) return null
      if (!atRecipient && greeted.any { g -> low == g || low.startsWith("$g ") || low.endsWith(" $g") }) return null
      return trimmed
    }
    LABELLED_LOCATION.find(clean)?.groupValues?.get(1)?.let { labelled ->
      val parts = labelled.split(',')
      val keep = parts.takeWhile { !DateParser.parse(it, LocalDateTime.of(2000, 1, 1, 0, 0)).hasAnything }
      val value = if (keep.isEmpty()) labelled else keep.joinToString(",")
      validLoc(value)?.let { v -> return v }
    }
    STREET_ADDRESS.find(clean)?.groupValues?.get(1)?.let { validLoc(it)?.let { v -> return v } }
    LOCATION_PREFIX.findAll(clean).forEach { m ->
      val loc = m.groupValues[1].trim().removeSuffix(".")
      val l = loc.lowercase()
      if (l.split(' ').none { DateWords.isDateWord(it) }) {
        validLoc(loc)?.let { return it }
      }
    }
    MAP_URL.find(clean)?.value?.trim()?.removeSuffix(".")?.let { return it }
    if (AT_SENDER_INVITE.containsMatchIn(clean)) {
      extractSenderName(clean, sender)?.let { return it }
    }
    return null
  }

  /**
   * Post-processes any detection (from Rules, On-Device, or Cloud LLM) to guarantee:
   * 1. No system/debug status strings or raw JSON ever leak into [Detection.title] or [Detection.description].
   * 2. Greeted recipient names ("Hallo lieber Julian...") are never mistaken for [Detection.location] or "bei <Recipient>" in the title.
   * 3. Invitations to the sender's place ("bei uns", "laden euch ein") without a physical venue use the sender name as location (or null).
   */
  fun sanitizeDetection(d: Detection, rawText: String, sender: String = ""): Detection {
    val cleanText = stripDebugAndSystemText(TextNormalizer.normalize(rawText))
    val greeted = extractGreetedNames(cleanText)
    val atRecipient = isExplicitlyAtRecipient(cleanText)
    val resolvedSender = extractSenderName(cleanText, sender)

    // 1. Clean location
    var loc = d.location?.trim()?.takeIf { it.isNotBlank() && it != "null" && !isSelfOrDebugText(it) }
    if (loc != null) {
      val lowLoc = loc.lowercase().removePrefix("bei ").removePrefix("at ").removePrefix("chez ").trim()
      if (lowLoc == "julian" || lowLoc == "julian daniels" || (!atRecipient && greeted.any { g -> lowLoc == g || lowLoc.startsWith("$g ") || lowLoc.endsWith(" $g") })) {
        loc = null
      }
    }
    if (loc == null && d.kind == Kind.EVENT && AT_SENDER_INVITE.containsMatchIn(cleanText)) {
      loc = resolvedSender
    }

    // 2. Clean title: NO newlines (\r, \n), NO UI fragments (< Termin), NO status messages
    var rawTitle = stripDebugAndSystemText(d.title)
    var title = rawTitle.replace("\r", " ").replace("\n", " ").trim()
    title = title.removePrefix("< Termin").removePrefix("< Aufgabe").removePrefix("< Event").removePrefix("< Task")
      .removePrefix("<termin").removePrefix("<aufgabe").removePrefix("<event").removePrefix("<task")
      .trim()
    if (title.startsWith(":")) title = title.removePrefix(":").trim()
    while (title.startsWith("<") && title.contains(" ")) {
      title = title.substringAfter(" ").trim()
    }
    title = title.replace(Regex("""\s+"""), " ").trim()
    title = cleanCrypticTitle(title)
    if (!atRecipient && greeted.isNotEmpty()) {
      greeted.forEach { g ->
        val beiPattern = Regex("""\s+\b(?:bei|mit|at|chez)\s+${Regex.escape(g)}\b""", RegexOption.IGNORE_CASE)
        if (AT_SENDER_INVITE.containsMatchIn(cleanText)) {
          title = beiPattern.replace(title, if (resolvedSender != null) " bei $resolvedSender" else "")
        } else if (Regex("""\b(?:bei|at|chez)\s+${Regex.escape(g)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(title)) {
          title = Regex("""\s+\b(?:bei|at|chez)\s+${Regex.escape(g)}\b""", RegexOption.IGNORE_CASE).replace(title, "")
        }
      }
    }
    if (isSelfOrDebugText(title) || title.isBlank() || title.startsWith("automatisch eingetragen", ignoreCase = true)) {
      val fallback = cleanCrypticTitle(buildTitle(cleanText, DateParser.parse(cleanText, LocalDateTime.now()), d.kind, resolvedSender.orEmpty()))
      title = fallback.ifBlank { cleanCrypticTitle(cleanText.lineSequence().firstOrNull()?.take(60).orEmpty()) }
    }
    title = title.replace("\r", " ").replace("\n", " ").trim()

    // 3. Clean description
    var desc = stripDebugAndSystemText(d.description)
    if (isSelfOrDebugText(desc) || desc.isBlank()) {
      desc = cleanText
    }

    return d.copy(
      title = title.take(120),
      description = desc,
      location = loc,
      senderName = resolvedSender ?: d.senderName,
    )
  }

  /** Extra weight of an announced delivery day / window (pre-filter and event score). */
  private const val DELIVERY_WEIGHT = 0.3

  /** Confidence of an address-only suggestion: below every automatic threshold. */
  const val ADDRESS_CONFIDENCE = 0.3

  val BIRTHDAY_REGEX = Regex(
    """\b(?:(?:geburtstag\s+von|geburtstag\s*:)\s+([A-ZÄÖÜ][a-zäöüß]+(?:\s+[A-ZÄÖÜ][a-zäöüß]+)?)|([A-ZÄÖÜ][a-zäöüß]+(?:\s+[A-ZÄÖÜ][a-zäöüß]+)?)\s+hat(?:\s+(?:heute|morgen|übermorgen|bald|am\s+\d{1,2}\.?(?:\s+[a-zäöü]+)?))?\s+geburtstag|hat\s+([A-ZÄÖÜ][a-zäöüß]+(?:\s+[A-ZÄÖÜ][a-zäöüß]+)?)\s+geburtstag)\b""",
    RegexOption.IGNORE_CASE,
  )

  /** "Lena's birthday", "birthday of Tom", "Tom's bday" (English counterpart of [BIRTHDAY_REGEX]). */
  private val BIRTHDAY_EN = Regex("""\b(?:([A-Z][a-z]+)'s\s+(?:birthday|bday|b-day)|(?:birthday|bday)\s+of\s+([A-Z][a-z]+))\b""")

  private val ISO_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

  data class Scores(
    val event: Double, val task: Double, val noise: Double, val cancelled: Boolean,
    /** Strongest appointment noun / errand verb found (without request phrases like "please"). */
    val eventNoun: Double = 0.0, val action: Double = 0.0,
  )

  /** Things you do (errands), as opposed to request phrases ("can you") that also appear in invitations. */
  private val ACTION_VERBS = setOf(
    "kaufen", "besorgen", "mitbringen", "bezahlen", "überweisen", "zurückrufen", "abgeben", "einreichen", "einkaufen", "kündigen",
    "unterschreiben", "erledigen", "buy", "pay", "submit", "renew", "call back", "pick up", "acheter", "payer", "envoyer", "rappeler",
    "comprar", "pagar", "enviar", "llamar", "comprare", "pagare", "inviare", "richiamare", "ligar", "kupić", "kup", "zapłacić", "zapłać",
    "wysłać", "wyślij", "oddzwoń", "deadline", "frist", "fällig", "todo", "to-do", "due", "scadenza", "prazo", "fecha límite", "échéance",
  )

  fun scores(text: String, delivery: Boolean = false): Scores {
    val t = " " + text.lowercase() + " "
    fun sum(words: Map<String, Double>): Double {
      var total = 0.0
      var best = 0.0
      words.forEach { (w, weight) ->
        if (containsWord(t, w)) { total += weight; best = maxOf(best, weight) }
      }
      // The strongest hit counts fully, additional hits add a little.
      return (best + (total - best) * 0.35).coerceAtMost(0.9)
    }
    val noise = NOISE_WORDS.count { t.contains(it) && !(delivery && it in TRACKING_WORDS) } * 0.3 +
      if (BLESSING.containsMatchIn(t)) 0.5 else 0.0
    val eventNoun = EVENT_WORDS.filter { (w, weight) -> weight >= 0.35 && containsWord(t, w) }.values.maxOrNull() ?: 0.0
    val action = TASK_WORDS.filter { (w, _) -> w in ACTION_VERBS && containsWord(t, w) }.values.maxOrNull() ?: 0.0
    return Scores(sum(EVENT_WORDS), sum(TASK_WORDS), noise.coerceAtMost(0.9), CANCEL.containsMatchIn(t), eventNoun, action)
  }

  /** German compounds: "Zahnarzttermin", "Teammeeting", "Geburtstagsfeier", "Elterngespräch" … */
  private val COMPOUND_TAILS = setOf(
    "termin", "meeting", "besprechung", "sitzung", "konferenz", "party", "feier", "treffen", "training", "konzert", "essen",
    "abendessen", "mittagessen", "frühstück", "telefonat", "gespräch", "reservierung", "untersuchung", "kurs", "einkaufen",
    "rechnung", "frist", "aufgabe", "ausflug", "prüfung", "klausur", "versammlung", "urlaub",
  )

  private fun containsWord(haystack: String, word: String): Boolean {
    var idx = haystack.indexOf(word)
    while (idx >= 0) {
      val before = haystack.getOrNull(idx - 1) ?: ' '
      val after = haystack.getOrNull(idx + word.length) ?: ' '
      if (before !in CRYPTIC_GLUE_CHARS && after !in CRYPTIC_GLUE_CHARS) {
        val tail = word in COMPOUND_TAILS && before.isLetter()
        // Plural / case endings of compounds ("Zahnarzttermine", "Meetings").
        val endingOk = !after.isLetterOrDigit() || (tail && haystack.getOrNull(idx + word.length + 1)?.isLetterOrDigit() != true && after in "esn")
        if ((!before.isLetterOrDigit() || tail) && endingOk) return true
      }
      idx = haystack.indexOf(word, idx + 1)
    }
    return false
  }

  /** Quick relevance score (0..1) used as pre-filter before calling an LLM. */
  fun relevance(raw: String, now: LocalDateTime, addressSuggestions: Boolean = false): Double {
    val text = TextNormalizer.normalize(raw)
    if (isCrypticMessage(text)) return 0.0
    val w = DateParser.parse(text, now)
    val delivery = isDeliveryAnnouncement(text, w)
    val s = scores(text, delivery)
    var r = maxOf(s.event, s.task)
    if (delivery) r += DELIVERY_WEIGHT
    // A meeting link, calendar file, booking number or flight: an appointment even without the usual words.
    if (Lexicon.strongSignal(text)) r += 0.3
    if (w.dateExplicit) r += 0.25
    if (w.time != null) r += 0.2
    // A place only supports a date or time, it is no signal by itself.
    if (w.hasAnything && extractLocation(text) != null) r += 0.2
    if (addressSuggestions && fullAddress(text) != null) r += 0.45
    if (!w.hasAnything && PAST_ONLY.containsMatchIn(text)) r -= 0.3
    return (r - s.noise).coerceIn(0.0, 1.0)
  }

  fun analyze(
    text: String,
    sender: String = "",
    now: LocalDateTime = LocalDateTime.now(),
    defaultDurationMinutes: Int = 60,
    /** Optional setting: a message with only a complete address becomes a low-priority suggestion. */
    addressSuggestions: Boolean = false,
    provisionalContextDate: LocalDate? = null,
  ): Detection {
    if (isSelfOrDebugText(text)) return Detection(kind = Kind.NONE, confidence = 0.0, reason = "self/debug text", engine = "rules")
    val clean = stripDebugAndSystemText(TextNormalizer.normalize(text)).trim()
    if (clean.length < 4) return Detection(kind = Kind.NONE, confidence = 0.0, reason = "too short", engine = "rules")
    if (isCrypticMessage(clean)) return Detection(kind = Kind.NONE, confidence = 0.0, reason = "cryptic text", engine = "rules")
    val w = DateParser.parse(clean, now, provisionalContextDate = provisionalContextDate)
    val delivery = isDeliveryAnnouncement(clean, w)
    val s = scores(clean, delivery)
    val loc = extractLocation(clean, sender)
    val hasEventAndLocation = s.eventNoun >= 0.4 && loc != null

    var eventScore = s.event + if (delivery) DELIVERY_WEIGHT else 0.0
    var taskScore = s.task
    if (w.dateExplicit) { eventScore += 0.2; taskScore += 0.1 }
    if (w.time != null && !w.vagueTime) eventScore += 0.25
    if (w.time != null && w.vagueTime) eventScore += 0.1
    if (w.endTime != null) eventScore += 0.1
    // "Urlaub vom 21.12. bis 3.1.", "Dec 10-12": a span of days is an all-day entry.
    if (w.endDate != null && w.endDate != w.date) eventScore += 0.15
    if (loc != null && w.hasAnything) eventScore += 0.35
    else if (hasEventAndLocation) eventScore += 0.25
    if (w.deadline) { taskScore += 0.2; eventScore -= 0.1 }
    if (QUESTION_INVITE.containsMatchIn(clean) && w.hasAnything) eventScore += 0.1
    val birthdayMatch = BIRTHDAY_REGEX.find(clean) ?: BIRTHDAY_EN.find(clean)?.takeIf { w.hasAnything }
    if (birthdayMatch != null && (w.hasAnything || clean.contains("geburtstag", ignoreCase = true))) {
      eventScore = maxOf(eventScore, 0.88)
    }
    // Without any date/time (and without an explicit appointment + venue like "treffen an Terverne") an appointment cannot be created.
    if (!w.hasAnything && !hasEventAndLocation) eventScore *= 0.45
    eventScore -= s.noise
    taskScore -= s.noise
    if (s.cancelled) eventScore -= 0.5
    // Separate the two kinds: an errand with a clock time ("buy milk tomorrow at 5") stays a task, an appointment
    // mentioned in a reminder ("don't forget the dentist tomorrow at 10") stays an event.
    val strongEventNoun = s.eventNoun >= 0.4
    val strongAction = s.action >= 0.35
    if (strongAction && !strongEventNoun) eventScore -= 0.2
    if (strongEventNoun && !strongAction && w.time != null) taskScore -= 0.15
    if (strongEventNoun && w.time != null && !w.deadline) taskScore -= 0.1

    eventScore = eventScore.coerceIn(0.0, 0.97)
    taskScore = taskScore.coerceIn(0.0, 0.95)

    val kind = when {
      eventScore < 0.3 && taskScore < 0.3 -> Kind.NONE
      eventScore >= taskScore && (w.hasAnything || birthdayMatch != null || hasEventAndLocation) -> Kind.EVENT
      else -> Kind.TASK
    }
    if (kind == Kind.NONE) {
      val address = if (addressSuggestions && !w.hasAnything) fullAddress(clean) else null
      if (address != null) {
        // Only a suggestion with low confidence; Analyzer.decide never adds it automatically.
        val start = now.toLocalDate().atTime(12, 0)
        return Detection(
          kind = Kind.EVENT,
          title = if (sender.isNotBlank()) "$address ($sender)" else address,
          description = clean,
          start = start.format(ISO_MINUTE),
          end = start.plusMinutes(defaultDurationMinutes.toLong()).format(ISO_MINUTE),
          location = address,
          confidence = ADDRESS_CONFIDENCE,
          reason = "address only '$address'",
          engine = "rules",
          why = Why.LOCATION_ONLY.name,
        )
      }
      return Detection(Kind.NONE, confidence = 1.0 - maxOf(eventScore, taskScore), reason = "no strong signal", engine = "rules")
    }

    val built = buildTitle(clean, w, kind, sender).ifBlank { loc ?: cleanCrypticTitle(clean.take(60)) }
    // "Zugestellt" alone says nothing: a delivery title names the parcel.
    val title = if (delivery && kind == Kind.EVENT && !DELIVERY_ITEM.containsMatchIn(built)) Wording.delivery(TextLang.of(clean)) else built
    val location = loc
    val reasons = buildList {
      if (loc != null) add("location '$loc'")
      if (s.event > 0) add("event words %.2f".format(s.event))
      if (s.task > 0) add("task words %.2f".format(s.task))
      if (w.dateExplicit) add("date")
      if (w.time != null) add(if (w.vagueTime) "vague time" else "time")
      if (w.deadline) add("deadline")
      if (s.noise > 0) add("noise -%.2f".format(s.noise))
      if (s.cancelled) add("cancelled")
    }.joinToString(", ")

    val rawDetection = if (kind == Kind.EVENT) {
      val date = w.date ?: now.toLocalDate()
      val start = w.time?.let { date.atTime(it) }
      val endStr: String? = when {
        w.endDate != null -> {
          w.endTime?.let { w.endDate.atTime(it).format(ISO_MINUTE) } ?: w.endDate.toString()
        }
        start == null -> null
        w.endTime != null -> date.atTime(w.endTime).let { if (it.isAfter(start)) it else it.plusDays(1) }.format(ISO_MINUTE)
        else -> start.plusMinutes((w.durationMinutes ?: defaultDurationMinutes).toLong()).format(ISO_MINUTE)
      }
      Detection(
        kind = Kind.EVENT,
        title = title,
        description = clean,
        start = start?.format(ISO_MINUTE) ?: date.toString(),
        end = endStr,
        allDay = start == null || (w.endDate != null && w.time == null),
        location = location,
        confidence = eventScore,
        reason = reasons,
        engine = "rules",
        isProvisionalDay = w.isProvisionalDay,
      )
    } else {
      val due = when {
        w.date != null && w.time != null && !w.vagueTime -> w.date.atTime(w.time).format(ISO_MINUTE)
        w.date != null -> w.date.toString()
        else -> null
      }
      Detection(
        kind = Kind.TASK,
        title = title,
        description = clean,
        due = due,
        priority = if (rx("\\b(dringend|urgent|asap|sofort|wichtig|important|urgente|importante|pilne|ważne)\\b").containsMatchIn(clean)) 3 else 0,
        confidence = taskScore,
        reason = reasons,
        engine = "rules",
      )
    }
    return sanitizeDetection(rawDetection, clean, sender)
  }

  private val MEET_PHRASE = rx(
    """\b(?:treffen\s+wir\s+uns|wollen\s+wir\s+uns\s+treffen|sollen\s+wir\s+uns\s+treffen|lass(?:t)?\s+uns\s+(?:uns\s+)?treffen|wir\s+treffen\s+uns|shall\s+we\s+meet|should\s+we\s+meet|let'?s\s+meet|want\s+to\s+meet|wanna\s+meet|we\s+meet)\b""",
  )
  private val VENUE_DE = rx("""\b((?:beim|bei\s+dem|im|zum|zur|in\s+der|in\s+die)\s+[\p{L}\-]+)""")
  private val VENUE_EN = rx("""\bat\s+(the\s+[\p{L}'\-]+(?:\s+(?:place|restaurant|bar|café|cafe|pub))?)""")

  /** A meeting question naming a kind of place: the title is the meeting and the place as written. */
  private fun venueMeetingTitle(text: String): String? {
    if (!MEET_PHRASE.containsMatchIn(text)) return null
    val lang = TextLang.of(text)
    val venue = (if (lang == TextLang.DE) VENUE_DE else VENUE_EN).findAll(text).map { it.groupValues[1] }
      .firstOrNull { de.juliandigitalproducts.notitocal.places.VenueKind.inText(it) != null } ?: return null
    return Wording.meetAtVenue(lang, venue)
  }

  /** Overlapping date/time spans ("vom 2. bis 4. Dezember" and "4. Dezember") as one range each. */
  private fun mergeSpans(spans: List<IntRange>): List<IntRange> {
    val out = mutableListOf<IntRange>()
    spans.sortedBy { it.first }.forEach { r ->
      val last = out.lastOrNull()
      if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
    }
    return out
  }

  private fun buildTitle(text: String, w: ParsedWhen, kind: Kind, sender: String): String {
    if (kind == Kind.EVENT) {
      BIRTHDAY_EN.find(text)?.takeIf { BIRTHDAY_REGEX.find(text) == null }?.let { m ->
        val person = m.groupValues[1].ifBlank { m.groupValues[2] }
        if (person.isNotBlank()) return "$person's birthday"
      }
      val bm = BIRTHDAY_REGEX.find(text)
      if (bm != null) {
        val person = bm.groupValues[1].ifBlank { bm.groupValues[2] }.ifBlank { bm.groupValues[3] }.trim()
        if (person.isNotBlank()) {
          return "Geburtstag $person"
        }
      }
    }
    // "Treffen wir uns … beim Italiener?" / "Shall we meet … at the Italian place?" → "Treffen beim Italiener".
    if (kind == Kind.EVENT) venueMeetingTitle(text)?.let { t ->
      val who = extractSenderName(text, sender).orEmpty()
      return if (who.isNotBlank()) "$t ($who)" else t
    }
    // Take the sentence that carries the signal, strip greetings, request phrases and date words.
    val sentences = text.split(SENTENCE_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
    val lower = sentences.map { it.lowercase() }
    val words = if (kind == Kind.EVENT) EVENT_WORDS.keys else TASK_WORDS.keys
    val idx = lower.indexOfFirst { s -> words.any { containsWord(" $s ", it) } }.let { if (it < 0) 0 else it }
    var t = sentences.getOrElse(idx) { text }
    // Remove date/time expressions found by the parser if they are inside this sentence.
    val sentenceStart = text.indexOf(t)
    if (sentenceStart >= 0) {
      mergeSpans(w.spans).sortedByDescending { it.first }.forEach { r ->
        // A span may run over the sentence end ("vom 2. bis 4. Dezember" is split after "4."): the part inside goes.
        val a = maxOf(r.first - sentenceStart, 0)
        val b = minOf(r.last - sentenceStart, t.length - 1)
        if (a <= b) {
          // Also drop the preposition in front of the date/time ("um 15 Uhr", "at 3pm", "am 12.11.").
          val prep = DANGLING_PREP.find(t.substring(0, a))
          t = t.removeRange(prep?.range?.first ?: a, b + 1)
        }
      }
    }
    t = LEADING_FILLER.replace(t, "")
    if (kind == Kind.EVENT) {
      t = INVITE_FILLER.replace(t, "")
      t = CONFIRM_FILLER.replace(t, "")
      t = rx("""^\s*(?:bei|zu)\s+(?:uns|mir)\s+(?:zum|zur|ins|in\s+die|fürs|für\s+das)\s+""").replace(t, "")
      t = rx("""^\s*(?:zum|zur)\s+""").replace(t, "")
    }
    if (kind == Kind.TASK) t = TASK_PREFIX.replace(t, "")
    t = t.replace(rx("""\b(um|at|à|a las|alle|às|o|am|on|le|el|il|bis|by|until)\s*([,.!?]|$)"""), "$2")
      .replace(Regex("""\s{2,}"""), " ")
      .replace(Regex("""\s+([,.!?;:])"""), "$1")
      // Leftovers of cut-out dates/durations: ",,", "( )", a lone ")" of "(2 Std.)", "… is confirmed for".
      .replace(Regex(""",(\s*,)+"""), ",")
      .replace(Regex("""\(\s*\)"""), "")
      .let { if (it.count { c -> c == ')' } > it.count { c -> c == '(' }) it.replace(Regex("""\s*\)"""), "") else it }
      .replace(rx("""\s+(?:is\s+|are\s+|ist\s+|wurde\s+)?(?:confirmed|bestätigt)(?:\s+for)?\s*$"""), "")
      .trim(' ', ',', '.', '!', '?', ':', ';', '-')
    t = cleanCrypticTitle(t)
    if (t.isBlank()) t = cleanCrypticTitle(text.take(60))
    t = t.replaceFirstChar { it.uppercase() }
    if (t.length > 70) t = t.take(67).trimEnd() + "…"
    val effectiveSender = extractSenderName(text, sender).orEmpty()
    return if (kind == Kind.EVENT && effectiveSender.isNotBlank() && !t.contains(effectiveSender, ignoreCase = true)) "$t ($effectiveSender)" else t
  }
}

/** Words that must not be taken as a location. */
internal object DateWords {
  private val set = setOf(
    "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonntag", "monday", "tuesday", "wednesday",
    "thursday", "friday", "saturday", "sunday", "januar", "februar", "märz", "april", "mai", "juni", "juli", "august",
    "september", "oktober", "november", "dezember", "january", "february", "march", "june", "july", "october", "december",
    "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche", "lunes", "martes", "miércoles", "jueves",
    "viernes", "sábado", "domingo", "ordnung", "ruhe", "zukunft", "zeit", "time", "the", "der", "die", "das",
    "uns", "mir", "dir", "euch", "ihnen", "allen", "jedem", "diesem", "welchem", "fragen", "bedarf",
    "morgen", "heute", "übermorgen", "abend", "mittag", "vormittag", "nachmittag", "wochenende", "anfang", "ende",
    "uhr", "tag", "woche", "monat", "jahr", "besten", "liebsten", "meisten",
  )
  fun isDateWord(w: String) = w.lowercase().trim(',', '.') in set
}
