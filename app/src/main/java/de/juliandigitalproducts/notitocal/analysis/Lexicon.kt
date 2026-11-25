package de.juliandigitalproducts.notitocal.analysis

/**
 * The larger vocabulary of the rules and the pre-filter, kept apart from the scoring logic in [RuleEngine]. Words are
 * matched as whole words (German compounds through their tails), so generic words that also appear in unrelated
 * messages ("Bank", "Spiel", "Start") are only listed as phrases. Weights stay moderate: a word alone rarely decides,
 * it lets a message with a date reach the analysis.
 */
object Lexicon {

  /** Appointment vocabulary beyond the core words in [RuleEngine]. */
  val EVENT: Map<String, Double> = buildMap {
    fun put(weight: Double, vararg words: String) = words.forEach { put(it, weight) }
    // de – health, services, authorities
    put(0.45, "arzttermin", "sprechstunde", "impftermin", "kontrolltermin", "behördentermin", "beratungstermin", "kundentermin", "abholtermin", "liefertermin")
    put(0.35, "praxis", "physiotherapie", "krankengymnastik", "impfung", "blutabnahme", "nachsorge", "vorsorge", "behandlung", "therapie", "friseur", "frisör",
      "kosmetik", "massage", "fußpflege", "werkstatt", "inspektion", "hauptuntersuchung", "reifenwechsel", "handwerker", "monteur", "zählerablesung",
      "besichtigung", "wohnungsbesichtigung", "schlüsselübergabe", "bürgeramt", "ausländerbehörde", "finanzamt", "notar", "notartermin", "beratungsgespräch",
      "kundengespräch", "mitarbeitergespräch", "personalgespräch", "standesamt", "trauung")
    // de – school, culture, sport, social
    put(0.35, "elternabend", "elterngespräch", "elternsprechtag", "schulfest", "einschulung", "zeugnisausgabe", "klassenfahrt", "probe", "chorprobe", "bandprobe",
      "auftritt", "aufführung", "theater", "musical", "oper", "ausstellung", "vernissage", "lesung", "festival", "tagung", "seminar", "workshop", "schulung",
      "fortbildung", "vortrag", "präsentation", "prüfung", "klausur", "fahrstunde", "führerscheinprüfung", "turnier", "wettkampf", "punktspiel", "heimspiel",
      "auswärtsspiel", "marathon", "radtour", "wanderung", "ausflug", "grillen", "grillfest", "brunch", "stammtisch", "spieleabend", "geburtstagsfeier",
      "jubiläum", "taufe", "kommunion", "konfirmation", "beerdigung", "trauerfeier", "polterabend", "junggesellenabschied", "kaffee trinken", "essen gehen")
    // de – work, travel, deliveries
    put(0.35, "jour fixe", "stand-up", "standup", "kickoff", "kick-off", "abstimmungstermin", "telko", "telefonkonferenz", "videokonferenz", "teams-meeting",
      "zoom-call", "google meet", "webex", "rückruf um", "check-in", "abflug", "ankunft", "landung", "boarding", "zugfahrt", "fähre", "mietwagen",
      "übernachtung", "anreise", "abreise", "lieferfenster", "zustellung am", "abholung", "tischreservierung", "reservierungsbestätigung",
      "buchungsbestätigung", "terminbestätigung", "terminerinnerung", "terminänderung", "neuer termin", "verschoben auf", "verlegt auf", "eintrittskarte", "einlass",
      "ganztägig", "ganztags", "freihalten", "sprint review", "retrospektive")
    // en
    put(0.45, "doctor's appointment", "check-up", "checkup", "consultation", "client meeting", "booking confirmation", "your appointment", "appointment reminder")
    put(0.35, "vaccination", "therapy", "therapist", "physio", "haircut", "hairdresser", "barber", "mechanic", "service appointment", "inspection", "plumber",
      "electrician", "technician", "viewing", "open house", "parent-teacher", "school play", "recital", "rehearsal", "gig", "exhibition", "festival",
      "convention", "seminar", "workshop", "training session", "lecture", "presentation", "exam", "driving lesson", "tournament", "hike", "picnic",
      "barbecue", "bbq", "drinks", "get together", "get-together", "reunion", "anniversary", "baptism", "funeral", "memorial", "bachelor party",
      "baby shower", "bridal shower", "court hearing", "notary", "lawyer", "stand-up", "standup", "kickoff", "kick-off", "one-on-one", "1:1",
      "teams meeting", "google meet", "webex", "departure", "arrival", "boarding", "ferry", "rental car", "check-in", "delivery window", "pickup",
      "pick-up", "table for", "rescheduled", "postponed", "moved to", "tickets for", "doors open", "all-day", "all day", "save the date",
      "sprint review", "retrospective", "demo day")
    // fr
    put(0.35, "consultation", "kiné", "kinésithérapeute", "vaccin", "coiffeur", "contrôle technique", "visite", "réunion de parents", "répétition",
      "spectacle", "exposition", "festival", "séminaire", "atelier", "conférence", "examen", "match", "tournoi", "randonnée", "pique-nique",
      "apéro", "barbecue", "baptême", "enterrement", "obsèques", "notaire", "avocat", "visioconférence", "départ", "arrivée", "embarquement", "livraison",
      "retrait", "réservation", "confirmation de réservation", "reporté", "décalé")
    // es
    put(0.35, "consulta", "fisio", "fisioterapia", "vacuna", "peluquería", "peluquero", "taller", "revisión", "visita", "reunión de padres", "ensayo",
      "espectáculo", "exposición", "festival", "seminario", "taller", "curso", "conferencia", "examen", "partido", "torneo", "excursión", "senderismo",
      "barbacoa", "picnic", "bautizo", "funeral", "entierro", "notario", "abogado", "videollamada", "salida", "llegada", "embarque", "entrega", "recogida",
      "reserva", "confirmación de reserva", "aplazado", "pospuesto")
    // it
    put(0.35, "visita medica", "fisioterapia", "vaccino", "parrucchiere", "barbiere", "officina", "revisione", "colloquio", "ricevimento genitori",
      "spettacolo", "mostra", "festival", "seminario", "corso", "conferenza", "esame", "partita", "torneo", "gita", "escursione", "grigliata", "picnic",
      "battesimo", "funerale", "notaio", "avvocato", "videochiamata", "partenza", "arrivo", "imbarco", "consegna", "ritiro", "prenotazione",
      "conferma di prenotazione", "rinviato", "spostato")
    // pt
    put(0.35, "consulta médica", "fisioterapia", "vacina", "cabeleireiro", "barbeiro", "oficina", "revisão", "visita", "reunião de pais", "ensaio",
      "espetáculo", "exposição", "festival", "seminário", "curso", "conferência", "exame", "jogo", "torneio", "passeio", "caminhada", "churrasco",
      "piquenique", "batizado", "funeral", "notário", "advogado", "videochamada", "partida", "chegada", "embarque", "entrega", "recolha", "reserva",
      "confirmação da reserva", "adiado")
    // pl
    put(0.35, "wizyta lekarska", "fizjoterapia", "szczepienie", "fryzjer", "warsztat", "przegląd", "wywiadówka", "zebranie rodziców", "próba",
      "przedstawienie", "wystawa", "festiwal", "seminarium", "warsztaty", "szkolenie", "konferencja", "egzamin", "mecz", "turniej", "wycieczka", "grill",
      "piknik", "chrzciny", "pogrzeb", "notariusz", "adwokat", "wideorozmowa", "odlot", "przylot", "dostawa", "odbiór", "rezerwacja",
      "potwierdzenie rezerwacji", "przełożone")
  }

  /** To-do vocabulary beyond the core words in [RuleEngine]. */
  val TASK: Map<String, Double> = buildMap {
    fun put(weight: Double, vararg words: String) = words.forEach { put(it, weight) }
    // de
    put(0.4, "zahlungserinnerung", "mahnung", "rechnung bezahlen", "überweisung", "antrag", "formular", "ausfüllen", "unterlagen", "nachweis", "bescheinigung",
      "verlängern", "erneuern", "kündigung", "anrufen", "melde dich", "gib bescheid", "rückmeldung bis", "bis spätestens", "spätestens", "abgabe",
      "abgabetermin", "einsenden", "hochladen", "ausdrucken", "zurückschicken", "rücksendung", "retoure", "einkaufsliste", "vorbereiten", "hausaufgaben",
      "reparieren", "termin vereinbaren", "termin ausmachen")
    // en
    put(0.4, "invoice", "payment due", "overdue", "fill out", "fill in", "application form", "documents", "renewal", "expires", "return by", "drop off",
      "homework", "assignment", "prepare", "let me know", "get back to me", "reply by", "respond by", "rsvp", "make an appointment", "schedule a")
    // fr
    put(0.4, "facture", "relance", "formulaire", "remplir", "dossier", "renouveler", "résilier", "rappelle-moi", "réponds-moi", "avant le", "à rendre", "devoirs")
    // es
    put(0.4, "factura", "recordatorio de pago", "formulario", "rellenar", "documentos", "renovar", "dar de baja", "avísame", "contéstame", "antes del", "deberes")
    // it
    put(0.4, "fattura", "sollecito", "modulo", "compilare", "documenti", "rinnovare", "disdire", "fammi sapere", "rispondimi", "entro il", "compiti")
    // pt
    put(0.4, "fatura", "lembrete de pagamento", "formulário", "preencher", "documentos", "renovar", "cancelar", "avisa-me", "responde-me", "até dia", "trabalho de casa")
    // pl
    put(0.4, "faktura", "przypomnienie o płatności", "formularz", "wypełnić", "dokumenty", "odnowić", "wypowiedzenie", "daj znać", "odpisz", "do dnia", "zadanie domowe")
  }

  /** Advertising, account and system messages: lower the relevance (substrings, so only unambiguous phrases). */
  val NOISE: List<String> = listOf(
    "black friday", "cyber monday", "sonderangebot", "versandkostenfrei", "jetzt kaufen", "jetzt bestellen", "nur heute", "gratis testen", "% rabatt",
    "werbung", "gewinnspiel", "neue anmeldung", "anmeldung erkannt", "passwort zurücksetzen", "sicherheitswarnung", "kontostand", "zahlung erhalten",
    "zahlung eingegangen", "abbuchung", "free shipping", "buy now", "order now", "limited time", "sign-in attempt", "new login", "reset your password",
    "security alert", "payment received", "your balance", "livraison gratuite", "achetez maintenant", "envío gratis", "compra ahora", "spedizione gratuita",
    "acquista ora", "portes grátis", "compre agora", "darmowa dostawa", "kup teraz",
    // payment confirmations (PayPal, banking apps): a date in them is the booking date, no appointment
    "zahlung vom", "kartenzahlung", "lastschrift", "geld gesendet", "geld erhalten", "you sent", "you've sent",
  )

  /**
   * Strong signals of a real appointment even without event words: meeting links, calendar files, booking or
   * confirmation numbers and flight or platform details.
   */
  private val STRONG = rx(
    """zoom\.us/j/|teams\.microsoft\.com/l/meetup-join|meet\.google\.com/|webex\.com/(?:meet|join)|begin:vevent|\.ics\b|""" +
      """\b(?:buchungs|reservierungs|auftrags|booking|reservation|confirmation|réservation|reserva|prenotazione|rezerwacji)\s*-?\s*(?:nr|nummer|number|code|no|n°|nº|numero|número|numer)\b|""" +
      """\b(?:gate|gleis|platform|terminal)\s+[a-z]?\d{1,3}\b""",
  )

  // Case-sensitive airline code ("LH 123"), so "flight to 123" is not taken for one.
  private val FLIGHT = rx("""(?i:\b(?:flug|flight|vol|vuelo|volo|voo|lot))\s+[A-Z]{2}\s?\d{2,4}\b""", ignoreCase = false)

  fun strongSignal(text: String): Boolean = STRONG.containsMatchIn(text) || FLIGHT.containsMatchIn(text)
}
