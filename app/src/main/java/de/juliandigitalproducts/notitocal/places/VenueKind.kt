// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.places

import de.juliandigitalproducts.notitocal.analysis.rx

/**
 * Kinds of places people name instead of an address ("beim Italiener", "zum Zahnarzt", "at the gym").
 * [words] find the kind in a message, [nameHints] in a place's name ("Pizzeria Capone"), [osm] in OpenStreetMap tags.
 */
enum class VenueKind(
  words: String,
  nameHints: String,
  val osm: List<Pair<String, String>>,
  /** Words such places carry in their name, for a name search (Photon) near home. */
  val nameTerms: List<String> = emptyList(),
) {
  ITALIAN(
    """italiener\w*|italienisch\w*|italien|italiano|italiana|włosk\w*|pizzeria|pizza|italian|trattoria|ristorante|osteria""",
    """pizz|trattoria|ristorante|osteria|italia|capone|napoli|roma|toscana|venezia|milano|bella|mamma|luigi|vapiano""",
    listOf("cuisine" to "italian|pizza"), listOf("pizzeria", "ristorante", "trattoria", "osteria"),
  ),
  GREEK("""griechen?\w*|griechisch\w*|greek|taverna""", """taverna|akropolis|olymp|athen|kreta|mykonos|santorini|zorbas|hellas|delphi|rhodos""", listOf("cuisine" to "greek"), listOf("taverna", "griechisches restaurant")),
  ASIAN(
    """chinesen?\w*|chinesisch\w*|chinese|asiaten?\w*|asiatisch\w*|asian|vietnamese\w*|thai\w*|japaner\w*|japanisch\w*|sushi|japanese|wok""",
    """china|asia|wok|sushi|thai|saigon|hanoi|pho|tokyo|bambus|lotus|panda|dragon|drache|mandarin""",
    listOf("cuisine" to "chinese|asian|vietnamese|thai|japanese|sushi|korean"), listOf("sushi", "asia", "wok"),
  ),
  INDIAN("""inder\w*|indisch\w*|indian|curry""", """india|indien|taj|maharaja|bombay|delhi|curry|ganesha""", listOf("cuisine" to "indian"), listOf("indisches restaurant", "india")),
  TURKISH("""türken?\w*|türkisch\w*|turkish|döner\w*|doner|kebab\w*|dönerbude""", """döner|doner|kebab|kebap|istanbul|ankara|anatolia|bosporus""", listOf("cuisine" to "turkish|kebab"), listOf("döner", "kebab")),
  MEXICAN("""mexikaner\w*|mexikanisch\w*|mexican|tacos?|burritos?""", """mexic|taco|burrito|cantina|azteca|amigo""", listOf("cuisine" to "mexican"), listOf("mexican", "taco")),
  BURGER("""burger\w*""", """burger""", listOf("cuisine" to "burger"), listOf("burger")),
  CAFE("""café|cafe|kaffee\w*|coffee|eisdiele|eiscafé""", """café|cafe|coffee|kaffee|espresso|barista|starbucks|eiscaf""", listOf("amenity" to "cafe|ice_cream"), listOf("café", "coffee")),
  BAKERY("""bäcker\w*|baecker\w*|bakery""", """bäcker|baecker|backhaus|backstube|bakery|brot""", listOf("shop" to "bakery"), listOf("bäckerei", "bakery")),
  DENTIST("""zahnarzt\w*|zahnärzt\w*|kieferorthopäd\w*|dentist""", """zahn|dental|dentist|kieferorthop""", listOf("amenity" to "dentist", "healthcare" to "dentist"), listOf("zahnarzt", "dental")),
  DOCTOR("""hausarzt\w*|hausärzt\w*|arztpraxis|doktor|doctor'?s?|gp""", """praxis|arzt|ärzt|medizin|clinic|klinik""", listOf("amenity" to "doctors|clinic", "healthcare" to "doctor|clinic"), listOf("arztpraxis", "praxis")),
  HAIRDRESSER("""friseur\w*|frisör\w*|hairdresser|barber\w*|haircut""", """friseur|frisör|hair|haar|barber|coiffeur|salon""", listOf("shop" to "hairdresser|barber"), listOf("friseur", "hair")),
  GYM("""fitnessstudio|gym|fitness\w*|mucki\w*""", """fitness|gym|mcfit|clever fit|john reed|crunch""", listOf("leisure" to "fitness_centre|sports_centre"), listOf("fitness")),
  CINEMA("""kino\w*|cinema|movies""", """kino|cinema|cineplex|cinemaxx|uci|filmpalast""", listOf("amenity" to "cinema"), listOf("kino", "cinema")),
  SUPERMARKET("""supermarkt\w*|supermarket|edeka|rewe|aldi|lidl|netto|penny|kaufland""", """edeka|rewe|aldi|lidl|netto|penny|kaufland|spar|billa|hofer|markt""", listOf("shop" to "supermarket"), listOf("supermarkt")),
  HOME("", "", emptyList()),
  WORK("", "", emptyList());

  private val wordRegex = words.takeIf { it.isNotEmpty() }?.let { rx("""(?<![\p{L}])(?:$it)(?![\p{L}])""") }
  private val nameRegex = nameHints.takeIf { it.isNotEmpty() }?.let { rx("""(?:$it)""") }

  /** The OSM key a name search is narrowed to (restaurants and cafés are amenities, bakeries shops …). */
  val photonKey: String? get() = osm.firstOrNull()?.first?.let { if (it == "cuisine") "amenity" else it }

  /** Whether OpenStreetMap can search this kind (home and work are only known from the location history). */
  val searchable: Boolean get() = osm.isNotEmpty()

  companion object {
    /** The kind of place a message or location talks about, e.g. "Treffen beim Italiener" → [ITALIAN]. */
    fun inText(text: String?): VenueKind? =
      text?.takeIf { it.isNotBlank() }?.let { t -> entries.firstOrNull { it.wordRegex?.containsMatchIn(t) == true } }

    /** The word that named the kind ("Italiener" in "beim Italiener"), to look for it in past appointments. */
    fun wordIn(text: String?, kind: VenueKind): String? = text?.let { kind.wordRegex?.find(it)?.value }

    /** The kind a place's name suggests ("Pizzeria Capone" → [ITALIAN]); null when it does not tell. */
    fun ofName(name: String?): VenueKind? =
      name?.takeIf { it.isNotBlank() }?.let { n -> entries.firstOrNull { it.wordRegex?.containsMatchIn(n) == true || it.nameRegex?.containsMatchIn(n) == true } }

    /** The kind OpenStreetMap tags describe (amenity, cuisine, shop, leisure, healthcare). */
    fun ofTags(tags: Map<String, String>): VenueKind? = entries.firstOrNull { kind ->
      kind.osm.any { (key, values) ->
        val v = tags[key] ?: return@any false
        val wanted = values.split('|')
        v.split(';').any { it.trim().lowercase() in wanted }
      }
    }

    /** Timeline semantic types ("TYPE_HOME", "INFERRED_WORK") → home / work. */
    fun ofSemanticType(type: String?): VenueKind? = when {
      type == null -> null
      "HOME" in type.uppercase() -> HOME
      "WORK" in type.uppercase() -> WORK
      else -> null
    }
  }
}
