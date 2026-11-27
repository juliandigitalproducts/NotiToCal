// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.analysis

/** Example texts for the style previews in the user's language. The prompt itself stays English. */
object StyleSamples {
  data class Sample(val title: String, val description: String, val location: String?, val whenLabel: String)

  /** Labels used inside the formats ({w_appointment}, …); English is the fallback. */
  fun words(language: String): Map<String, String> = WORDS[language] ?: WORDS.getValue("en")

  val supportedLanguages: Set<String> get() = WORDS.keys

  fun sample(language: String, id: String): Sample? = SAMPLES[language]?.get(id)

  private val WORDS = mapOf(
    "en" to mapOf("w_appointment" to "Appointment", "w_subject" to "Subject", "w_where" to "Where", "w_notes" to "Notes", "w_focus" to "Focus block", "w_next_step" to "Next step", "w_do_next" to "Do next", "w_note" to "Note"),
    "de" to mapOf("w_appointment" to "Termin", "w_subject" to "Betreff", "w_where" to "Ort", "w_notes" to "Notizen", "w_focus" to "Fokuszeit", "w_next_step" to "Nächster Schritt", "w_do_next" to "Als Nächstes", "w_note" to "Notiz"),
    "fr" to mapOf("w_appointment" to "Rendez-vous", "w_subject" to "Objet", "w_where" to "Lieu", "w_notes" to "Notes", "w_focus" to "Plage de concentration", "w_next_step" to "Prochaine étape", "w_do_next" to "À faire ensuite", "w_note" to "Note"),
    "es" to mapOf("w_appointment" to "Cita", "w_subject" to "Asunto", "w_where" to "Lugar", "w_notes" to "Notas", "w_focus" to "Bloque de concentración", "w_next_step" to "Siguiente paso", "w_do_next" to "Hacer después", "w_note" to "Nota"),
    "it" to mapOf("w_appointment" to "Appuntamento", "w_subject" to "Oggetto", "w_where" to "Luogo", "w_notes" to "Note", "w_focus" to "Blocco di concentrazione", "w_next_step" to "Prossimo passo", "w_do_next" to "Da fare dopo", "w_note" to "Nota"),
    "pt" to mapOf("w_appointment" to "Compromisso", "w_subject" to "Assunto", "w_where" to "Local", "w_notes" to "Notas", "w_focus" to "Bloco de foco", "w_next_step" to "Próximo passo", "w_do_next" to "Fazer a seguir", "w_note" to "Nota"),
    "pl" to mapOf("w_appointment" to "Termin", "w_subject" to "Temat", "w_where" to "Miejsce", "w_notes" to "Notatki", "w_focus" to "Blok skupienia", "w_next_step" to "Następny krok", "w_do_next" to "Do zrobienia", "w_note" to "Notatka"),
  )

  private fun s(title: String, description: String, location: String?, whenLabel: String) = Sample(title, description, location, whenLabel)

  private val SAMPLES: Map<String, Map<String, Sample>> = mapOf(
    "de" to mapOf(
      "event_clean" to s("Zahnarzt", "Kontrolle bei Dr. Keller.", "Hauptstraße 12", "Di 12. Mai · 09:30"),
      "event_pin" to s("Team-Meeting", "Wochenplanung.", "Raum 4", "Mo 11. Mai · 10:00"),
      "event_brief" to s("Schulaufführung", "Zehn Minuten früher da sein.", "Aula", "Fr 15. Mai · 18:00"),
      "event_formal" to s("Budgetbesprechung", "Q2-Übersicht mitbringen.", "Büro", "Mi 13. Mai · 14:00"),
      "event_focus" to s("Konzentriertes Arbeiten", "Keine Meetings in diesem Block.", null, "Do 14. Mai · 08:00"),
      "task_check" to s("Stromrechnung bezahlen", "Vor Freitag fällig.", null, "Fällig Fr 15. Mai"),
      "task_todo" to s("Bericht senden", "Unterschriebenes PDF anhängen.", null, "Fällig Do 14. Mai"),
      "task_next" to s("Schule anrufen", "Nach dem Ausflug fragen.", null, "Fällig heute"),
      "task_note" to s("Milch kaufen", "Hafermilch, zwei Packungen.", null, "Fällig morgen"),
      "task_plain" to s("Reisepass verlängern", "Erst das Ablaufdatum prüfen.", null, "Fällig 1. Juni"),
    ),
    "fr" to mapOf(
      "event_clean" to s("Dentiste", "Contrôle chez le Dr Keller.", "12 rue Principale", "mar. 12 mai · 09:30"),
      "event_pin" to s("Réunion d'équipe", "Planification de la semaine.", "Salle 4", "lun. 11 mai · 10:00"),
      "event_brief" to s("Spectacle de l'école", "Arriver dix minutes en avance.", "Salle", "ven. 15 mai · 18:00"),
      "event_formal" to s("Revue du budget", "Apporter le tableau du T2.", "Bureau", "mer. 13 mai · 14:00"),
      "event_focus" to s("Travail concentré", "Aucune réunion pendant ce bloc.", null, "jeu. 14 mai · 08:00"),
      "task_check" to s("Payer la facture d'électricité", "À régler avant vendredi.", null, "Échéance ven. 15 mai"),
      "task_todo" to s("Envoyer le rapport", "Joindre le PDF signé.", null, "Échéance jeu. 14 mai"),
      "task_next" to s("Appeler l'école", "Se renseigner sur la sortie.", null, "Échéance aujourd'hui"),
      "task_note" to s("Acheter du lait", "Lait d'avoine, deux briques.", null, "Échéance demain"),
      "task_plain" to s("Renouveler le passeport", "Vérifier d'abord la date d'expiration.", null, "Échéance 1er juin"),
    ),
    "es" to mapOf(
      "event_clean" to s("Dentista", "Revisión con la Dra. Keller.", "Calle Mayor 12", "mar 12 may · 09:30"),
      "event_pin" to s("Reunión de equipo", "Planificación semanal.", "Sala 4", "lun 11 may · 10:00"),
      "event_brief" to s("Función escolar", "Llegar diez minutos antes.", "Salón", "vie 15 may · 18:00"),
      "event_formal" to s("Revisión del presupuesto", "Traer la hoja del T2.", "Oficina", "mié 13 may · 14:00"),
      "event_focus" to s("Trabajo concentrado", "Sin reuniones en este bloque.", null, "jue 14 may · 08:00"),
      "task_check" to s("Pagar la factura de la luz", "Vence antes del viernes.", null, "Vence vie 15 may"),
      "task_todo" to s("Enviar el informe", "Adjuntar el PDF firmado.", null, "Vence jue 14 may"),
      "task_next" to s("Llamar al colegio", "Preguntar por la excursión.", null, "Vence hoy"),
      "task_note" to s("Comprar leche", "Leche de avena, dos cartones.", null, "Vence mañana"),
      "task_plain" to s("Renovar el pasaporte", "Comprobar antes la fecha de caducidad.", null, "Vence 1 jun"),
    ),
    "it" to mapOf(
      "event_clean" to s("Dentista", "Controllo dal dott. Keller.", "Via Principale 12", "mar 12 mag · 09:30"),
      "event_pin" to s("Riunione di team", "Pianificazione settimanale.", "Sala 4", "lun 11 mag · 10:00"),
      "event_brief" to s("Recita scolastica", "Arrivare dieci minuti prima.", "Aula magna", "ven 15 mag · 18:00"),
      "event_formal" to s("Revisione del budget", "Portare il prospetto del Q2.", "Ufficio", "mer 13 mag · 14:00"),
      "event_focus" to s("Lavoro concentrato", "Nessuna riunione in questo blocco.", null, "gio 14 mag · 08:00"),
      "task_check" to s("Pagare la bolletta della luce", "Scade prima di venerdì.", null, "Scadenza ven 15 mag"),
      "task_todo" to s("Inviare il resoconto", "Allegare il PDF firmato.", null, "Scadenza gio 14 mag"),
      "task_next" to s("Chiamare la scuola", "Chiedere della gita.", null, "Scadenza oggi"),
      "task_note" to s("Comprare il latte", "Latte d'avena, due confezioni.", null, "Scadenza domani"),
      "task_plain" to s("Rinnovare il passaporto", "Controllare prima la data di scadenza.", null, "Scadenza 1 giu"),
    ),
    "pt" to mapOf(
      "event_clean" to s("Dentista", "Consulta de rotina com a Dra. Keller.", "Rua Principal 12", "ter 12 mai · 09:30"),
      "event_pin" to s("Reunião de equipa", "Planeamento semanal.", "Sala 4", "seg 11 mai · 10:00"),
      "event_brief" to s("Peça da escola", "Chegar dez minutos mais cedo.", "Auditório", "sex 15 mai · 18:00"),
      "event_formal" to s("Revisão do orçamento", "Levar a folha do T2.", "Escritório", "qua 13 mai · 14:00"),
      "event_focus" to s("Trabalho concentrado", "Sem reuniões neste bloco.", null, "qui 14 mai · 08:00"),
      "task_check" to s("Pagar a conta da luz", "Vence antes de sexta.", null, "Vence sex 15 mai"),
      "task_todo" to s("Enviar o relatório", "Anexar o PDF assinado.", null, "Vence qui 14 mai"),
      "task_next" to s("Ligar para a escola", "Perguntar pela visita de estudo.", null, "Vence hoje"),
      "task_note" to s("Comprar leite", "Leite de aveia, dois pacotes.", null, "Vence amanhã"),
      "task_plain" to s("Renovar o passaporte", "Verificar primeiro a data de validade.", null, "Vence 1 jun"),
    ),
    "pl" to mapOf(
      "event_clean" to s("Dentysta", "Kontrola u dr Keller.", "ul. Główna 12", "wt 12 maj · 09:30"),
      "event_pin" to s("Spotkanie zespołu", "Planowanie tygodnia.", "Sala 4", "pon 11 maj · 10:00"),
      "event_brief" to s("Przedstawienie szkolne", "Przyjść dziesięć minut wcześniej.", "Aula", "pt 15 maj · 18:00"),
      "event_formal" to s("Przegląd budżetu", "Zabrać zestawienie za II kwartał.", "Biuro", "śr 13 maj · 14:00"),
      "event_focus" to s("Praca w skupieniu", "Bez spotkań w tym bloku.", null, "czw 14 maj · 08:00"),
      "task_check" to s("Zapłacić rachunek za prąd", "Termin przed piątkiem.", null, "Termin pt 15 maj"),
      "task_todo" to s("Wysłać raport", "Dołączyć podpisany PDF.", null, "Termin czw 14 maj"),
      "task_next" to s("Zadzwonić do szkoły", "Zapytać o wycieczkę.", null, "Termin dziś"),
      "task_note" to s("Kupić mleko", "Mleko owsiane, dwa kartony.", null, "Termin jutro"),
      "task_plain" to s("Odnowić paszport", "Najpierw sprawdzić datę ważności.", null, "Termin 1 cze"),
    ),
  )
}
