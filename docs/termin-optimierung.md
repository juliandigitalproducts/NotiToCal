# Terminerkennung – Messung und Optimierung

Branch `termin-optimierung`. Gemessen wird der Offline-Detektor (Regeln), der bei der Engine „Regeln" allein entscheidet,
bei KI-Engines als Vorfilter läuft und bei KI-Fehlern als Fallback dient.

## Wo die Erkennung sitzt

| Baustein | Datei | Aufgabe |
|---|---|---|
| `DateParser` | `app/src/main/java/de/juliandigitalproducts/notitocal/analysis/DateParser.kt` | Datum/Uhrzeit/Zeitspannen aus Text (7 Sprachen), relative Angaben, Zeitzonen-Kürzel, Grüße maskieren |
| `RuleEngine` | `analysis/RuleEngine.kt` (+ Vokabular in `analysis/Lexicon.kt`) | Punktesystem Termin/Aufgabe/Rauschen, Absagen, Ort, Titel, `analyze()` und `relevance()` (Vorfilter) |
| `TextNormalizer` | `analysis/TextNormalizer.kt` | Umlaute/Encoding reparieren, „naechsten" → „nächsten" |
| `Analyzer` | `analysis/Analyzer.kt` | Engine-Auswahl, Vorfilter, `decide()` (Schwellen, Vergangenheit, Standarddauer) |
| `Prompt` | `analysis/Llm.kt` | System-Prompt (Cloud/Self-Hosted) und Kompakt-Prompt (On-Device) |
| `Hybrid` | `analysis/Hybrid.kt` | On-Device-Antwort mit den Regeln zusammenführen (Datum/Ort aus den Regeln) |

## Testaufbau

- Fixtures: `app/src/test/resources/termin/faelle.json` – 78 erfundene Nachrichten (60 Termine, 3 Absagen, 15 Negativbeispiele),
  WhatsApp/Signal/SMS/Mail/Slack-Stil, Deutsch (55) und Englisch (23), Tippfehler, Umgangssprache, Emojis.
  Festes „jetzt": **Do 26.11.2026 14:30 Europe/Berlin**.
- Test: `app/src/test/java/de/juliandigitalproducts/notitocal/TerminErkennungTest.kt` – echter Detektor
  (`RuleEngine.analyze` + `Analyzer.decide`, Standard-Einstellungen), keine Netzwerk-/KI-Aufrufe. Bericht auf der Konsole und
  in `app/build/reports/termin-erkennung.md`.
- „Vollständig korrekt" = Termin ja/nein richtig **und** Start, ggf. Ende, ganztägig, Titelwort, ggf. Ort stimmen.
- Ausführen: `./gradlew --offline :app:testDebugUnitTest --tests '*TerminErkennungTest' -i`

## VORHER (Stand b8683f0)

Gesamt vollständig korrekt: 51/78 (65 %) · Termin ja/nein richtig: 66/78 (85 %)

| Kategorie | korrekt | Termin ja/nein richtig |
|---|---|---|
| relativ | 12/14 (86 %) | 13/14 (93 %) |
| absolut | 8/8 (100 %) | 8/8 (100 %) |
| dauer | 0/6 (0 %) | 4/6 (67 %) |
| ganztags | 1/5 (20 %) | 1/5 (20 %) |
| ort | 3/4 (75 %) | 4/4 (100 %) |
| absage_verschiebung | 3/6 (50 %) | 6/6 (100 %) |
| mehrfach_wiederholung | 2/3 (67 %) | 3/3 (100 %) |
| messenger_stil | 4/9 (44 %) | 8/9 (89 %) |
| englisch | 3/4 (75 %) | 3/4 (75 %) |
| edge | 2/4 (50 %) | 3/4 (75 %) |
| negativ | 13/15 (87 %) | 13/15 (87 %) |
| Sprache de | 36/55 (65 %) | 48/55 (87 %) |
| Sprache en | 15/23 (65 %) | 18/23 (78 %) |

Vorfilter (Schwelle 0.2) lässt Termine durch: 60/60 (100 %)

<details><summary>Einzelergebnisse vorher</summary>

| id | Nachricht | Ergebnis | Start | Ende | Titel | Ort | OK |
|---|---|---|---|---|---|---|---|
| rel-01 | Treffen wir uns morgen um 19 Uhr im Brauhaus? | EVENT/ASK | 2026-11-27T19:00 | 2026-11-27T20:00 | Treffen wir uns im Brauhaus | Brauhaus | ✅ |
| rel-02 | übermorgen 10:30 Elterngespräch in der Schule | EVENT/ASK | 2026-11-28T10:30 | 2026-11-28T11:30 | Elterngespräch in der Schule | Schule | ✅ |
| rel-03 | Nächsten Dienstag um 18 Uhr Probe im Gemeindehaus 🎻 | EVENT/ASK | 2026-12-01T18:00 | 2026-12-01T19:00 | Probe im Gemeindehaus 🎻 | Gemeindehaus | ✅ |
| rel-04 | Kontrolltermin in 2 Wochen um 8:15, Praxis Dr. Sommer | EVENT/ASK | 2026-12-10T08:15 | 2026-12-10T09:15 | Kontrolltermin, Praxis Dr |  | ✅ |
| rel-05 | morgen abend kino? so um 8 🍿 | EVENT/ASK | 2026-11-27T20:00 | 2026-11-27T21:00 | Abend kino |  | ✅ |
| rel-06 | Heute um halb 6 Zoom-Call mit dem Team | EVENT/ASK | 2026-11-26T17:30 | 2026-11-26T18:30 | Zoom-Call mit dem Team |  | ✅ |
| rel-07 | Dinner at Luigi's tomorrow at 7:30pm? | EVENT/ASK | 2026-11-27T19:30 | 2026-11-27T20:30 | Dinner at Luigi's | Luigi's | ✅ |
| rel-08 | Let's meet next Monday at 10am for the kickoff | EVENT/ASK | 2026-11-30T10:00 | 2026-11-30T11:00 | Let's meet for the kickoff |  | ✅ |
| rel-09 | in 3 Tagen um 14 Uhr Abgabe-Besprechung | EVENT/ASK | 2026-11-29T14:00 | 2026-11-29T15:00 | Abgabe-Besprechung |  | ✅ |
| rel-10 | Am Wochenende Grillen bei uns? Samstag ab 16 Uhr | EVENT/ASK | 2026-11-28T16:00 | 2026-11-28T17:00 | Grillen bei uns |  | ✅ |
| rel-11 | Termin beim Zahnartz am Mitwoch um 9 | EVENT/ASK | 2026-11-27T09:00 | 2026-11-27T10:00 | Termin beim Zahnartz am Mitwoch | Zahnartz | ❌ Start 2026-11-27T09:00 statt 2026-12-02T09:00 |
| rel-12 | Team offsite in two weeks, Thursday 9am-5pm | EVENT/IGNORE | 2026-12-10T09:00 | 2026-12-10T17:00 | Team offsite |  | ❌ kein Termin (EVENT/BELOW_MIN) |
| rel-13 | Heute Abend Stammtisch ab 20 Uhr im Ochsen | EVENT/ASK | 2026-11-26T20:00 | 2026-11-26T21:00 | Stammtisch im Ochsen | Ochsen | ✅ |
| rel-14 | Morgen früh um 7 Abfahrt am Hbf! | EVENT/ASK | 2026-11-27T07:00 | 2026-11-27T08:00 | Früh Abfahrt am Hbf | Hbf | ✅ |
| abs-01 | Ihr Termin bei Dr. med. Kaya am 03.12.2026 um 11:20 Uhr. Bitte Versichertenkarte mitbringen. | EVENT/ASK | 2026-12-03T11:20 | 2026-12-03T12:20 | Ihr Termin bei Dr | Dr | ✅ |
| abs-02 | Einladung zur Mitgliederversammlung am Freitag, den 11. Dezember 2026, um 19:00 Uhr im Vereinsheim | EVENT/ASK | 2026-12-11T19:00 | 2026-12-11T20:00 | Einladung zur Mitgliederversammlung | Vereinsheim | ✅ |
| abs-03 | Your appointment with Dr. Patel is confirmed for December 8 at 2:45 PM. | EVENT/ASK | 2026-12-08T14:45 | 2026-12-08T15:45 | Your appointment with Dr |  | ✅ |
| abs-04 | Konzert am 17.1. um 20 Uhr in der Philharmonie, Karten hab ich! | EVENT/ASK | 2027-01-17T20:00 | 2027-01-17T21:00 | Konzert | Philharmonie | ✅ |
| abs-05 | Interview on 14/12 at 11:00, Building C reception | EVENT/ASK | 2026-12-14T11:00 | 2026-12-14T12:00 | Interview, Building C reception |  | ✅ |
| abs-06 | Weihnachtsfeier am 18.12. ab 18:30 im Restaurant Adler | EVENT/ASK | 2026-12-18T18:30 | 2026-12-18T19:30 | Weihnachtsfeier | Restaurant Adler | ✅ |
| abs-07 | Hausversammlung am 2.12 um 19h im Keller | EVENT/ASK | 2026-12-02T19:00 | 2026-12-02T20:00 | Hausversammlung im Keller | Keller | ✅ |
| abs-08 | Parent-teacher conference Dec 3rd, 4:15pm | EVENT/ASK | 2026-12-03T16:15 | 2026-12-03T17:15 | Parent-teacher conference |  | ✅ |
| dau-01 | Workshop am 9.12. von 9 bis 12:30 Uhr | EVENT/ASK | 2026-12-09T12:30 | 2026-12-09T13:30 | Workshop |  | ❌ Start 2026-12-09T12:30 statt 2026-12-09T09:00; Ende 2026-12-09T13:30 statt 2026-12-09T12:30 |
| dau-02 | Meeting morgen 14 Uhr, Dauer ca. 90 Minuten | EVENT/ASK | 2026-11-27T14:00 | 2026-11-27T15:00 | Meeting, Dauer ca |  | ❌ Ende 2026-11-27T15:00 statt 2026-11-27T15:30 |
| dau-03 | Call tomorrow at 4pm for 30 min | EVENT/ASK | 2026-11-27T16:00 | 2026-11-27T17:00 | Call for 30 min |  | ❌ Ende 2026-11-27T17:00 statt 2026-11-27T16:30 |
| dau-04 | Massagetermin am Samstag um 11 Uhr (2 Std.) | EVENT/ASK | 2026-11-28T11:00 | 2026-11-28T12:00 | Massagetermin (2 Std.) |  | ❌ Ende 2026-11-28T12:00 statt 2026-11-28T13:00 |
| dau-05 | Team sync Monday 9:30–10:15 | EVENT/IGNORE | 2026-11-30T09:30 | 2026-11-30T10:15 | Team sync |  | ❌ kein Termin (EVENT/BELOW_MIN) |
| dau-06 | Zwischen 14 und 16 Uhr kommt morgen der Techniker | EVENT/IGNORE | 2026-11-27T14:00 | 2026-11-27T16:00 | Kommt der Techniker |  | ❌ kein Termin (EVENT/BELOW_MIN) |
| gan-01 | Urlaub vom 21.12. bis 3.1. 🌴 | NONE/IGNORE |  |  |  |  | ❌ kein Termin (NONE/NOTHING) |
| gan-02 | Familienfeier bei Oma am 24.12. | EVENT/ASK | 2026-12-24 |  | Familienfeier bei Oma | Oma | ✅ |
| gan-03 | Company retreat Dec 10-12, Lake Constance | NONE/IGNORE |  |  |  |  | ❌ kein Termin (NONE/NOTHING) |
| gan-04 | Messe in Köln vom 2. bis 4. Dezember | EVENT/IGNORE | 2026-12-02 | 2026-12-04 | Messe in Köln vom 2 | Köln | ❌ kein Termin (EVENT/BELOW_MIN) |
| gan-05 | Don't forget: Lena's birthday is on Saturday 🎂 | TASK/ASK |  |  | Lena's birthday is 🎂 |  | ❌ kein Termin (TASK/ASK_MODE) |
| ort-01 | Treffpunkt: Haupteingang Stadtpark, Sonntag 10 Uhr, Laufgruppe 🏃 | EVENT/ASK | 2026-11-29T10:00 | 2026-11-29T11:00 | Treffpunkt: Haupteingang Stadtpark,, Laufgruppe 🏃 | Haupteingang Stadtpark, Sonntag 10 Uhr, Laufgruppe 🏃 | ❌ Ort 'Haupteingang Stadtpark, Sonntag 10 Uhr, Laufgruppe 🏃' |
| ort-02 | Besichtigung der Wohnung morgen 17:00, Lindenstraße 12, 3. OG | EVENT/ASK | 2026-11-27T17:00 | 2026-11-27T18:00 | Besichtigung der Wohnung, Lindenstraße 12, 3 | Lindenstraße 12 | ✅ |
| ort-03 | Meet me at Central Station, platform 4, Friday 8:10am | EVENT/ASK | 2026-11-27T08:10 | 2026-11-27T09:10 | Meet me at Central Station, platform 4 | Central Station | ✅ |
| ort-04 | Teams-Meeting Dienstag 11 Uhr: https://teams.example.com/l/meetup-join/abc123 | EVENT/ASK | 2026-12-01T11:00 | 2026-12-01T12:00 | Teams-Meeting: https://teams.example.com/l/meetup-join/abc123 |  | ✅ |
| abg-01 | Das Training morgen fällt leider aus 😕 | NONE/IGNORE |  |  |  |  | ✅ |
| abg-02 | Termin am Montag um 10 ist abgesagt, melde mich wegen neuem Termin | EVENT/IGNORE | 2026-11-30T10:00 | 2026-11-30T11:00 | Termin ist abgesagt, melde mich wegen neuem Termin |  | ✅ |
| abg-03 | Sorry, have to cancel our lunch tomorrow | TASK/IGNORE |  |  | Sorry, have to cancel our lunch |  | ✅ |
| abg-04 | Wir müssen den Zahnarzttermin verschieben: statt Dienstag jetzt Mittwoch um 15 Uhr | EVENT/ASK | 2026-12-01T15:00 | 2026-12-01T16:00 | Wir müssen den Zahnarzttermin verschieben: statt jetzt Mittwoch |  | ❌ Start 2026-12-01T15:00 statt 2026-12-02T15:00 |
| abg-05 | The meeting has been moved to Thursday 2pm | EVENT/ASK | 2026-11-26T14:00 | 2026-11-26T15:00 | The meeting has been moved to |  | ❌ Start 2026-11-26T14:00 statt 2026-12-03T14:00 |
| abg-06 | Kino ist verschoben vom Freitag auf Samstag, 20 Uhr | EVENT/ASK | 2026-11-27T20:00 | 2026-11-27T21:00 | Kino ist verschoben vom auf Samstag |  | ❌ Start 2026-11-27T20:00 statt 2026-11-28T20:00 |
| meh-01 | Dienstag 18 Uhr Training, Donnerstag 19:30 Chorprobe | EVENT/ASK | 2026-12-01T18:00 | 2026-12-01T19:00 | Training, Donnerstag 19:30 Chorprobe |  | ✅ |
| meh-02 | Ab nächster Woche immer montags 19 Uhr Yoga im Studio Prana | EVENT/ASK | 2026-11-26T19:00 | 2026-11-26T20:00 | Ab nächster Woche immer montags Yoga im Studio Prana | Studio Prana | ❌ Start 2026-11-26T19:00 statt 2026-11-30T19:00 |
| meh-03 | Weekly standup every Wednesday at 9:15 starting next week | EVENT/ASK | 2026-12-02T09:15 | 2026-12-02T10:15 | Weekly standup every starting |  | ✅ |
| msg-01 | yo bin morgen ab 3 im park, kommst rum? | NONE/IGNORE |  |  |  |  | ❌ kein Termin (NONE/NOTHING) |
| msg-02 | Schatz denk dran, Elternabend heute 19:30!! | EVENT/ASK | 2026-11-26T19:30 | 2026-11-26T20:30 | Schatz denk dran, Elternabend |  | ✅ |
| msg-03 | dentist tmrw 10am ugh 😩 | EVENT/ASK | 2026-11-27T10:00 | 2026-11-27T11:00 | Dentist ugh 😩 |  | ✅ |
| msg-04 | Sehr geehrter Herr Beispiel, hiermit bestätigen wir Ihren Beratungstermin am Mittwoch, 02.12.2026, 16:00 Uhr in unserer Filiale Marktplatz 3. Mit freundlichen Grüßen Ihre Musterbank Tel. 0711 000000 | EVENT/ASK | 2026-12-02T16:00 | 2026-12-02T17:00 | Sehr geehrter Herr Beispiel, hiermit bestätigen wir Ihren Beratungs… (Ihre Musterbank Tel) | in unserer Filiale Marktplatz 3 | ❌ Titel 'Sehr geehrter Herr Beispiel, hiermit bestätigen wir Ihren Beratungs… (Ihre Musterbank Tel)' |
| msg-05 | treffen uns naechsten mittwoch um 1 beim italiener | EVENT/ASK | 2026-12-02T13:00 | 2026-12-02T14:00 | Treffen uns beim italiener |  | ✅ |
| msg-06 | Wäre Do 3.12. um 14:00 ok für die Besprechung? | EVENT/ASK | 2026-12-03T14:00 | 2026-12-03T15:00 | Ok für die Besprechung |  | ✅ |
| msg-07 | Sat 5th Dec, brunch @ 11 at Mama's 🥞 | EVENT/ASK | 2026-12-05 |  | Sat, brunch @ 11 at Mama's 🥞 | Mama's | ❌ Start 2026-12-05 statt 2026-12-05T11:00; ganztägig true |
| msg-08 | Mo 14 Uhr Teammeeting | EVENT/ASK | 2026-11-27T14:00 | 2026-11-27T15:00 | Mo Teammeeting |  | ❌ Start 2026-11-27T14:00 statt 2026-11-30T14:00 |
| msg-09 | Fr. 18:00 Abschiedsessen für Tom im Lokal Krone | EVENT/ASK | 2026-11-26T18:00 | 2026-11-26T19:00 | Fr | Lokal Krone | ❌ Start 2026-11-26T18:00 statt 2026-11-27T18:00; Titel 'Fr' |
| eng-01 | Hey! Are you free on Sunday afternoon for coffee? | EVENT/ASK | 2026-11-29T15:00 | 2026-11-29T16:00 | Are you free afternoon for coffee |  | ✅ |
| eng-02 | Reminder: yoga class tonight 6:30pm @ Studio Bloom | TASK/IGNORE |  |  | Reminder: yoga class @ Studio Bloom |  | ❌ kein Termin (TASK/BELOW_MIN) |
| eng-03 | Flight BA 917 LHR→MUC departs 01 Dec 2026 06:40 | EVENT/ASK | 2026-12-01T06:40 | 2026-12-01T07:40 | Flight BA 917 LHR→MUC departs |  | ✅ |
| eng-04 | Lunch with Sarah Friday 12pm | EVENT/ASK | 2026-11-27T12:00 | 2026-11-27T13:00 | Lunch with Sarah |  | ✅ |
| edge-01 | Morgen! Hast du heute um 16 Uhr Zeit für einen Kaffee? | EVENT/ASK | 2026-11-26T16:00 | 2026-11-26T17:00 | Hast du Zeit für einen Kaffee |  | ✅ |
| edge-02 | Heute in einer Woche ist die Abschlussprüfung, 9 Uhr Raum 204 | EVENT/IGNORE | 2026-12-03T09:00 | 2026-12-03T10:00 | Ist die Abschlussprüfung, Raum 204 |  | ❌ kein Termin (EVENT/BELOW_MIN) |
| edge-03 | Silvester feiern wir bei Jonas, ab 20 Uhr 🎆 | EVENT/ASK | 2026-11-26T20:00 | 2026-11-26T21:00 | Silvester feiern wir bei Jonas, 🎆 | Jonas | ❌ Start 2026-11-26T20:00 statt 2026-12-31T20:00 |
| edge-04 | Termin morgen um 12 Uhr mittags beim Notar, Ausweis nicht vergessen | EVENT/ASK | 2026-11-27T12:00 | 2026-11-27T13:00 | Termin mittags beim Notar, Ausweis nicht vergessen | Notar | ✅ |
| neg-01 | Pizza Margherita 8,50 €, Lieferung in 30-40 Minuten | NONE/IGNORE |  |  |  |  | ✅ |
| neg-02 | Ruf mich mal an: 0176 00000000 | NONE/IGNORE |  |  |  |  | ✅ |
| neg-03 | Haha das war vor 3 Tagen schon 😂 | NONE/IGNORE |  |  |  |  | ✅ |
| neg-04 | Ofen auf 180 Grad vorheizen, dann 25 Min backen und 10 Min ruhen lassen | NONE/IGNORE |  |  |  |  | ✅ |
| neg-05 | Es ist schon 23:40 und ich bin immer noch wach 🙈 | NONE/IGNORE |  |  |  |  | ✅ |
| neg-06 | Mein Zug hatte heute 20 Minuten Verspätung, unfassbar | NONE/IGNORE |  |  |  |  | ✅ |
| neg-07 | lol I woke up at 6 today, never again | EVENT/IGNORE | 2026-11-26T18:00 | 2026-11-26T19:00 | Lol I woke up, never again |  | ✅ |
| neg-08 | Your order #4471-2219 total $54.99 has shipped. | NONE/IGNORE |  |  |  |  | ✅ |
| neg-09 | Danke für den schönen Abend am 21.11.! 🥰 | NONE/IGNORE |  |  |  |  | ✅ |
| neg-10 | Angebot: 2 für 1 bis Sonntag, nur im Markt am Bahnhof! | NONE/IGNORE |  |  |  |  | ✅ |
| neg-11 | Your verification code is 739201. It expires in 10 minutes. | NONE/IGNORE |  |  |  |  | ✅ |
| neg-12 | Weißt du noch, letzten Freitag um 9 im Biergarten? 😂 | EVENT/ASK | 2026-11-27T09:00 | 2026-11-27T10:00 | Weißt du noch, letzten im Biergarten | Biergarten | ❌ falscher Termin 2026-11-27T09:00 'Weißt du noch, letzten im Biergarten' |
| neg-13 | Bis morgen! 😘 | TASK/IGNORE |  |  | Bis morgen! 😘 |  | ✅ |
| neg-14 | Das Spiel endete 2:1, unglaublich ⚽ | NONE/IGNORE |  |  |  |  | ✅ |
| neg-15 | Hier die Fotos vom Grillen am 14.11. um 16 Uhr 📸 | EVENT/ASK | 2027-11-14T16:00 | 2027-11-14T17:00 | Hier die Fotos vom Grillen |  | ❌ falscher Termin 2027-11-14T16:00 'Hier die Fotos vom Grillen' |

</details>

## NACHHER (Stand dc1b48f)

| Kategorie | korrekt vorher | korrekt nachher | Termin ja/nein vorher | Termin ja/nein nachher |
|---|---|---|---|---|
| **gesamt** | **51/78 (65 %)** | **78/78 (100 %)** | 66/78 (85 %) | 78/78 (100 %) |
| relativ | 12/14 (86 %) | 14/14 (100 %) | 13/14 (93 %) | 14/14 (100 %) |
| absolut | 8/8 (100 %) | 8/8 (100 %) | 8/8 (100 %) | 8/8 (100 %) |
| dauer | 0/6 (0 %) | 6/6 (100 %) | 4/6 (67 %) | 6/6 (100 %) |
| ganztags | 1/5 (20 %) | 5/5 (100 %) | 1/5 (20 %) | 5/5 (100 %) |
| ort | 3/4 (75 %) | 4/4 (100 %) | 4/4 (100 %) | 4/4 (100 %) |
| absage_verschiebung | 3/6 (50 %) | 6/6 (100 %) | 6/6 (100 %) | 6/6 (100 %) |
| mehrfach_wiederholung | 2/3 (67 %) | 3/3 (100 %) | 3/3 (100 %) | 3/3 (100 %) |
| messenger_stil | 4/9 (44 %) | 9/9 (100 %) | 8/9 (89 %) | 9/9 (100 %) |
| englisch | 3/4 (75 %) | 4/4 (100 %) | 3/4 (75 %) | 4/4 (100 %) |
| edge | 2/4 (50 %) | 4/4 (100 %) | 3/4 (75 %) | 4/4 (100 %) |
| negativ | 13/15 (87 %) | 15/15 (100 %) | 13/15 (87 %) | 15/15 (100 %) |
| Sprache de | 36/55 (65 %) | 55/55 (100 %) | 48/55 (87 %) | 55/55 (100 %) |
| Sprache en | 15/23 (65 %) | 23/23 (100 %) | 18/23 (78 %) | 23/23 (100 %) |

Vorfilter lässt weiterhin alle 60 Termine durch (60/60 vorher und nachher).

**Holdout-Prüfung** (14 neue Nachrichten, nach dem Tuning geschrieben und nicht zum Tuning benutzt, nicht eingecheckt):
vorher 10/14, nachher 13/14. Der verbleibende Fehler („Kickoff call next Wed 10-11am“, am/pm bei Stundenbereichen)
war auch vorher falsch und ist danach in `4d89777` behoben (seitdem kein sauberer Holdout mehr).

Alle Unit-Tests: `./gradlew --offline :app:testDebugUnitTest --rerun` → 324 Tests, 0 Fehler (vorher 309; neu:
`TerminErkennungTest`, `TerminParserGuardsTest` mit 13 Schutztests, `TerminPromptTest`).
Harte Schwellen in `TerminErkennungTest`: gesamt ≥ 95 %, Negativbeispiele 100 %.

<details><summary>Einzelergebnisse nachher</summary>

| id |Nachricht | Ergebnis | Start | Ende | Titel | Ort | OK |
|---|---|---|---|---|---|---|---|
| rel-01 | Treffen wir uns morgen um 19 Uhr im Brauhaus? | EVENT/ASK | 2026-11-27T19:00 | 2026-11-27T20:00 | Treffen wir uns im Brauhaus | Brauhaus | ✅ |
| rel-02 | übermorgen 10:30 Elterngespräch in der Schule | EVENT/ASK | 2026-11-28T10:30 | 2026-11-28T11:30 | Elterngespräch in der Schule | Schule | ✅ |
| rel-03 | Nächsten Dienstag um 18 Uhr Probe im Gemeindehaus 🎻 | EVENT/ASK | 2026-12-01T18:00 | 2026-12-01T19:00 | Probe im Gemeindehaus 🎻 | Gemeindehaus | ✅ |
| rel-04 | Kontrolltermin in 2 Wochen um 8:15, Praxis Dr. Sommer | EVENT/ASK | 2026-12-10T08:15 | 2026-12-10T09:15 | Kontrolltermin, Praxis Dr. Sommer |  | ✅ |
| rel-05 | morgen abend kino? so um 8 🍿 | EVENT/ASK | 2026-11-27T20:00 | 2026-11-27T21:00 | Abend kino |  | ✅ |
| rel-06 | Heute um halb 6 Zoom-Call mit dem Team | EVENT/ASK | 2026-11-26T17:30 | 2026-11-26T18:30 | Zoom-Call mit dem Team |  | ✅ |
| rel-07 | Dinner at Luigi's tomorrow at 7:30pm? | EVENT/ASK | 2026-11-27T19:30 | 2026-11-27T20:30 | Dinner at Luigi's | Luigi's | ✅ |
| rel-08 | Let's meet next Monday at 10am for the kickoff | EVENT/ASK | 2026-11-30T10:00 | 2026-11-30T11:00 | Let's meet for the kickoff |  | ✅ |
| rel-09 | in 3 Tagen um 14 Uhr Abgabe-Besprechung | EVENT/ASK | 2026-11-29T14:00 | 2026-11-29T15:00 | Abgabe-Besprechung |  | ✅ |
| rel-10 | Am Wochenende Grillen bei uns? Samstag ab 16 Uhr | EVENT/ASK | 2026-11-28T16:00 | 2026-11-28T17:00 | Grillen bei uns |  | ✅ |
| rel-11 | Termin beim Zahnartz am Mitwoch um 9 | EVENT/ASK | 2026-12-02T09:00 | 2026-12-02T10:00 | Termin beim Zahnartz | Zahnartz | ✅ |
| rel-12 | Team offsite in two weeks, Thursday 9am-5pm | EVENT/ASK | 2026-12-10T09:00 | 2026-12-10T17:00 | Team offsite |  | ✅ |
| rel-13 | Heute Abend Stammtisch ab 20 Uhr im Ochsen | EVENT/ASK | 2026-11-26T20:00 | 2026-11-26T21:00 | Stammtisch im Ochsen | Ochsen | ✅ |
| rel-14 | Morgen früh um 7 Abfahrt am Hbf! | EVENT/ASK | 2026-11-27T07:00 | 2026-11-27T08:00 | Früh Abfahrt am Hbf | Hbf | ✅ |
| abs-01 | Ihr Termin bei Dr. med. Kaya am 03.12.2026 um 11:20 Uhr. Bitte Versichertenkarte mitbringen. | EVENT/ASK | 2026-12-03T11:20 | 2026-12-03T12:20 | Ihr Termin bei Dr. med. Kaya |  | ✅ |
| abs-02 | Einladung zur Mitgliederversammlung am Freitag, den 11. Dezember 2026, um 19:00 Uhr im Vereinsheim | EVENT/ASK | 2026-12-11T19:00 | 2026-12-11T20:00 | Einladung zur Mitgliederversammlung | Vereinsheim | ✅ |
| abs-03 | Your appointment with Dr. Patel is confirmed for December 8 at 2:45 PM. | EVENT/ASK | 2026-12-08T14:45 | 2026-12-08T15:45 | Your appointment with Dr. Patel |  | ✅ |
| abs-04 | Konzert am 17.1. um 20 Uhr in der Philharmonie, Karten hab ich! | EVENT/ASK | 2027-01-17T20:00 | 2027-01-17T21:00 | Konzert | Philharmonie | ✅ |
| abs-05 | Interview on 14/12 at 11:00, Building C reception | EVENT/ASK | 2026-12-14T11:00 | 2026-12-14T12:00 | Interview, Building C reception |  | ✅ |
| abs-06 | Weihnachtsfeier am 18.12. ab 18:30 im Restaurant Adler | EVENT/ASK | 2026-12-18T18:30 | 2026-12-18T19:30 | Weihnachtsfeier | Restaurant Adler | ✅ |
| abs-07 | Hausversammlung am 2.12 um 19h im Keller | EVENT/ASK | 2026-12-02T19:00 | 2026-12-02T20:00 | Hausversammlung im Keller | Keller | ✅ |
| abs-08 | Parent-teacher conference Dec 3rd, 4:15pm | EVENT/ASK | 2026-12-03T16:15 | 2026-12-03T17:15 | Parent-teacher conference |  | ✅ |
| dau-01 | Workshop am 9.12. von 9 bis 12:30 Uhr | EVENT/ASK | 2026-12-09T09:00 | 2026-12-09T12:30 | Workshop |  | ✅ |
| dau-02 | Meeting morgen 14 Uhr, Dauer ca. 90 Minuten | EVENT/ASK | 2026-11-27T14:00 | 2026-11-27T15:30 | Meeting |  | ✅ |
| dau-03 | Call tomorrow at 4pm for 30 min | EVENT/ASK | 2026-11-27T16:00 | 2026-11-27T16:30 | Call |  | ✅ |
| dau-04 | Massagetermin am Samstag um 11 Uhr (2 Std.) | EVENT/ASK | 2026-11-28T11:00 | 2026-11-28T13:00 | Massagetermin |  | ✅ |
| dau-05 | Team sync Monday 9:30–10:15 | EVENT/ASK | 2026-11-30T09:30 | 2026-11-30T10:15 | Team sync |  | ✅ |
| dau-06 | Zwischen 14 und 16 Uhr kommt morgen der Techniker | EVENT/ASK | 2026-11-27T14:00 | 2026-11-27T16:00 | Kommt der Techniker |  | ✅ |
| gan-01 | Urlaub vom 21.12. bis 3.1. 🌴 | EVENT/ASK | 2026-12-21 | 2027-01-03 | Urlaub |  | ✅ |
| gan-02 | Familienfeier bei Oma am 24.12. | EVENT/ASK | 2026-12-24 |  | Familienfeier bei Oma | Oma | ✅ |
| gan-03 | Company retreat Dec 10-12, Lake Constance | EVENT/ASK | 2026-12-10 | 2026-12-12 | Company retreat, Lake Constance |  | ✅ |
| gan-04 | Messe in Köln vom 2. bis 4. Dezember | EVENT/ASK | 2026-12-02 | 2026-12-04 | Messe in Köln | Köln | ✅ |
| gan-05 | Don't forget: Lena's birthday is on Saturday 🎂 | EVENT/ASK | 2026-11-28 |  | Lena's birthday |  | ✅ |
| ort-01 | Treffpunkt: Haupteingang Stadtpark, Sonntag 10 Uhr, Laufgruppe 🏃 | EVENT/ASK | 2026-11-29T10:00 | 2026-11-29T11:00 | Treffpunkt: Haupteingang Stadtpark, Laufgruppe 🏃 | Haupteingang Stadtpark | ✅ |
| ort-02 | Besichtigung der Wohnung morgen 17:00, Lindenstraße 12, 3. OG | EVENT/ASK | 2026-11-27T17:00 | 2026-11-27T18:00 | Besichtigung der Wohnung, Lindenstraße 12, 3 | Lindenstraße 12 | ✅ |
| ort-03 | Meet me at Central Station, platform 4, Friday 8:10am | EVENT/ASK | 2026-11-27T08:10 | 2026-11-27T09:10 | Meet me at Central Station, platform 4 | Central Station | ✅ |
| ort-04 | Teams-Meeting Dienstag 11 Uhr: https://teams.example.com/l/meetup-join/abc123 | EVENT/ASK | 2026-12-01T11:00 | 2026-12-01T12:00 | Teams-Meeting: https://teams.example.com/l/meetup-join/abc123 |  | ✅ |
| abg-01 | Das Training morgen fällt leider aus 😕 | NONE/IGNORE |  |  |  |  | ✅ |
| abg-02 | Termin am Montag um 10 ist abgesagt, melde mich wegen neuem Termin | EVENT/IGNORE | 2026-11-30T10:00 | 2026-11-30T11:00 | Termin ist abgesagt, melde mich wegen neuem Termin |  | ✅ |
| abg-03 | Sorry, have to cancel our lunch tomorrow | TASK/IGNORE |  |  | Sorry, have to cancel our lunch |  | ✅ |
| abg-04 | Wir müssen den Zahnarzttermin verschieben: statt Dienstag jetzt Mittwoch um 15 Uhr | EVENT/ASK | 2026-12-02T15:00 | 2026-12-02T16:00 | Wir müssen den Zahnarzttermin verschieben: statt Dienstag jetzt |  | ✅ |
| abg-05 | The meeting has been moved to Thursday 2pm | EVENT/ASK | 2026-12-03T14:00 | 2026-12-03T15:00 | The meeting has been moved to |  | ✅ |
| abg-06 | Kino ist verschoben vom Freitag auf Samstag, 20 Uhr | EVENT/ASK | 2026-11-28T20:00 | 2026-11-28T21:00 | Kino ist verschoben vom Freitag |  | ✅ |
| meh-01 | Dienstag 18 Uhr Training, Donnerstag 19:30 Chorprobe | EVENT/ASK | 2026-12-01T18:00 | 2026-12-01T19:00 | Training, Donnerstag 19:30 Chorprobe |  | ✅ |
| meh-02 | Ab nächster Woche immer montags 19 Uhr Yoga im Studio Prana | EVENT/ASK | 2026-11-30T19:00 | 2026-11-30T20:00 | Immer Yoga im Studio Prana | Studio Prana | ✅ |
| meh-03 | Weekly standup every Wednesday at 9:15 starting next week | EVENT/ASK | 2026-12-02T09:15 | 2026-12-02T10:15 | Weekly standup every starting |  | ✅ |
| msg-01 | yo bin morgen ab 3 im park, kommst rum? | EVENT/ASK | 2026-11-27T15:00 | 2026-11-27T16:00 | Yo bin im park, kommst rum |  | ✅ |
| msg-02 | Schatz denk dran, Elternabend heute 19:30!! | EVENT/ASK | 2026-11-26T19:30 | 2026-11-26T20:30 | Schatz denk dran, Elternabend |  | ✅ |
| msg-03 | dentist tmrw 10am ugh 😩 | EVENT/ASK | 2026-11-27T10:00 | 2026-11-27T11:00 | Dentist ugh 😩 |  | ✅ |
| msg-04 | Sehr geehrter Herr Beispiel, hiermit bestätigen wir Ihren Beratungstermin am Mittwoch, 02.12.2026, 16:00 Uhr in unserer Filiale Marktplatz 3. Mit freundlichen Grüßen Ihre Musterbank Tel. 0711 000000 | EVENT/ASK | 2026-12-02T16:00 | 2026-12-02T17:00 | Beratungstermin, in unserer Filiale Marktplatz 3 (Musterbank) | in unserer Filiale Marktplatz 3 | ✅ |
| msg-05 | treffen uns naechsten mittwoch um 1 beim italiener | EVENT/ASK | 2026-12-02T13:00 | 2026-12-02T14:00 | Treffen uns beim italiener |  | ✅ |
| msg-06 | Wäre Do 3.12. um 14:00 ok für die Besprechung? | EVENT/ASK | 2026-12-03T14:00 | 2026-12-03T15:00 | Ok für die Besprechung |  | ✅ |
| msg-07 | Sat 5th Dec, brunch @ 11 at Mama's 🥞 | EVENT/ASK | 2026-12-05T11:00 | 2026-12-05T12:00 | Sat, brunch at Mama's 🥞 | Mama's | ✅ |
| msg-08 | Mo 14 Uhr Teammeeting | EVENT/ASK | 2026-11-30T14:00 | 2026-11-30T15:00 | Teammeeting |  | ✅ |
| msg-09 | Fr. 18:00 Abschiedsessen für Tom im Lokal Krone | EVENT/ASK | 2026-11-27T18:00 | 2026-11-27T19:00 | Abschiedsessen für Tom im Lokal Krone | Lokal Krone | ✅ |
| eng-01 | Hey! Are you free on Sunday afternoon for coffee? | EVENT/ASK | 2026-11-29T15:00 | 2026-11-29T16:00 | Are you free afternoon for coffee |  | ✅ |
| eng-02 | Reminder: yoga class tonight 6:30pm @ Studio Bloom | EVENT/ASK | 2026-11-26T18:30 | 2026-11-26T19:30 | Reminder: yoga class @ Studio Bloom | Studio Bloom | ✅ |
| eng-03 | Flight BA 917 LHR→MUC departs 01 Dec 2026 06:40 | EVENT/ASK | 2026-12-01T06:40 | 2026-12-01T07:40 | Flight BA 917 LHR→MUC departs |  | ✅ |
| eng-04 | Lunch with Sarah Friday 12pm | EVENT/ASK | 2026-11-27T12:00 | 2026-11-27T13:00 | Lunch with Sarah |  | ✅ |
| edge-01 | Morgen! Hast du heute um 16 Uhr Zeit für einen Kaffee? | EVENT/ASK | 2026-11-26T16:00 | 2026-11-26T17:00 | Hast du Zeit für einen Kaffee |  | ✅ |
| edge-02 | Heute in einer Woche ist die Abschlussprüfung, 9 Uhr Raum 204 | EVENT/ASK | 2026-12-03T09:00 | 2026-12-03T10:00 | Ist die Abschlussprüfung, Raum 204 |  | ✅ |
| edge-03 | Silvester feiern wir bei Jonas, ab 20 Uhr 🎆 | EVENT/ASK | 2026-12-31T20:00 | 2026-12-31T21:00 | Silvester feiern wir bei Jonas, 🎆 | Jonas | ✅ |
| edge-04 | Termin morgen um 12 Uhr mittags beim Notar, Ausweis nicht vergessen | EVENT/ASK | 2026-11-27T12:00 | 2026-11-27T13:00 | Termin mittags beim Notar, Ausweis nicht vergessen | Notar | ✅ |
| neg-01 | Pizza Margherita 8,50 €, Lieferung in 30-40 Minuten | NONE/IGNORE |  |  |  |  | ✅ |
| neg-02 | Ruf mich mal an: 0176 00000000 | NONE/IGNORE |  |  |  |  | ✅ |
| neg-03 | Haha das war vor 3 Tagen schon 😂 | NONE/IGNORE |  |  |  |  | ✅ |
| neg-04 | Ofen auf 180 Grad vorheizen, dann 25 Min backen und 10 Min ruhen lassen | NONE/IGNORE |  |  |  |  | ✅ |
| neg-05 | Es ist schon 23:40 und ich bin immer noch wach 🙈 | NONE/IGNORE |  |  |  |  | ✅ |
| neg-06 | Mein Zug hatte heute 20 Minuten Verspätung, unfassbar | NONE/IGNORE |  |  |  |  | ✅ |
| neg-07 | lol I woke up at 6 today, never again | EVENT/IGNORE | 2026-11-26T18:00 | 2026-11-26T19:00 | Lol I woke up, never again |  | ✅ |
| neg-08 | Your order #4471-2219 total $54.99 has shipped. | NONE/IGNORE |  |  |  |  | ✅ |
| neg-09 | Danke für den schönen Abend am 21.11.! 🥰 | NONE/IGNORE |  |  |  |  | ✅ |
| neg-10 | Angebot: 2 für 1 bis Sonntag, nur im Markt am Bahnhof! | NONE/IGNORE |  |  |  |  | ✅ |
| neg-11 | Your verification code is 739201. It expires in 10 minutes. | NONE/IGNORE |  |  |  |  | ✅ |
| neg-12 | Weißt du noch, letzten Freitag um 9 im Biergarten? 😂 | EVENT/IGNORE | 2026-11-20T09:00 | 2026-11-20T10:00 | Weißt du noch, im Biergarten | Biergarten | ✅ |
| neg-13 | Bis morgen! 😘 | TASK/IGNORE |  |  | Bis morgen! 😘 |  | ✅ |
| neg-14 | Das Spiel endete 2:1, unglaublich ⚽ | NONE/IGNORE |  |  |  |  | ✅ |
| neg-15 | Hier die Fotos vom Grillen am 14.11. um 16 Uhr 📸 | EVENT/IGNORE | 2026-11-14T16:00 | 2026-11-14T17:00 | Hier die Fotos vom Grillen |  | ✅ |

</details>

## Änderungen

**DateParser** (`787ed82`, `4d89777`)
- Dauer („Dauer ca. 90 Minuten“, „for 30 min“, „(2 Std.)“, „für 1,5 h“) → `ParsedWhen.durationMinutes`, wird als Ende genutzt,
  wenn kein Endzeitpunkt genannt ist (Regeln und On-Device-Hybrid). „2h“ in einer Dauer ist keine Uhrzeit mehr.
- Zeitspannen: „von 9 bis 12:30 Uhr“, „10-11am“, „11-1pm“; Tagesbereich „Dec 10-12“.
- Verschiebungen: Bei einem Verschiebe-Wort wird der alte Tag („statt Dienstag“, „vom Freitag auf …“, „from Monday to …“) ausgeblendet.
- Wochentag = heute und Uhrzeit vorbei → gleiche Uhrzeit nächste Woche. „letzten Freitag“/„last Monday“ → Vergangenheit.
- Deutsche Kurzformen Mo–So nur direkt vor Uhrzeit/Datum (case-sensitiv, „So“ nur als „So.“ oder vor Datum);
  Pluralformen („montags“, „mondays“) und häufige Tippfehler („Mitwoch“, „Donerstag“); „nächster Woche“.
- Feste Feiertage: Silvester, Heiligabend, 1./2. Weihnachtstag, Neujahrstag.
- „@ 11“ als Uhrzeit, „morgen ab 3“ (nur mit Datum, nicht „ab 18 Jahren“).
- **Datum ohne Jahr**: ein Tag der letzten 60 Tage bleibt in der Vergangenheit (vorher: nächstes Jahr). Ältere Tage wie bisher kommendes Jahr.

**RuleEngine / Lexicon** (`016acf9`)
- Wortschatz (Urlaub, Messe, Versammlung, Techniker, Yoga, Offsite, Retreat, Sync, Class, „kommst rum“ …), Komposita auf
  -prüfung/-klausur/-versammlung/-urlaub, +0,15 für Tagesbereiche, englische Geburtstage („Lena's birthday“).
- Titel: Satztrennung an Zeilenumbrüchen, nicht nach Abkürzungen (Dr., med., Fr. …); überlappende Datumsbereiche zusammengefasst
  und an Satzgrenzen abgeschnitten; Bestätigungsfloskeln, „,,“, lose „)“ entfernt.
- Ort: „Treffpunkt: X, Sonntag 10 Uhr“ endet vor dem Datumsteil, „@ Studio Bloom“, „Dr“ ist kein Ort;
  Absender aus der Grußformel bleibt auf einer Zeile („Ihre Musterbank“ statt „Ihre Musterbank Tel“).

**KI-Prompt** (`dc1b48f`) – bewertet nur per Prompt-Analyse, keine Modellaufrufe
- Analyse vorher: keine Anweisung für Verschiebungen (Modell konnte den alten Tag nehmen), keine für Dauer → Ende,
  `end` konnte laut Schema kein Datum sein (mehrtägige Ganztagstermine nicht darstellbar), keine Regel für Wochentag = heute,
  Rückblicke, mehrere/wiederkehrende Termine; Wochentage musste das Modell selbst ausrechnen (bekannte Fehlerquelle, siehe `Hybrid`).
- Neu: Kalenderzeile der nächsten 14 Tage, Datumsregeln passend zum Offline-Parser (`Prompt.DATE_RULES`), `end` auch als Datum.
  Der Kompakt-Prompt für On-Device-Modelle ist unverändert (dort korrigieren ohnehin die Regeln die Daten).

## Verhaltensänderungen / Risiken vor dem Merge

1. **Datum ohne Jahr in den letzten 60 Tagen** wird jetzt als vergangen gelesen und deshalb ignoriert. Wer z. B. am 26.11.
   „Termin am 15.10.“ schreibt und den Oktober nächsten Jahres meint, bekommt keinen Vorschlag mehr (vorher: 15.10.2027).
2. **Wochentag = heute mit vergangener Uhrzeit** springt auf nächste Woche („Donnerstag 10 Uhr“ am Donnerstag 14:30).
   Nachträglich verschickte Mitteilungen über einen heutigen Termin landen damit eine Woche später statt als „vergangen“ verworfen.
3. **Verschiebe-Logik** blendet den alten Tag nur bei Verschiebe-Wörtern aus („verschoben“, „statt“, „moved“ …). Eine Nachricht wie
   „Statt Dienstag geht auch Mittwoch oder Donnerstag“ nimmt den ersten neuen Tag.
4. **Neue Wörter** (z. B. „class“, „sync“, „Messe“, „Yoga“, „Urlaub“) heben die Punkte; zusammen mit Datum/Uhrzeit kann das mehr
   Vorschläge erzeugen. Alle 15 Negativbeispiele und alle bestehenden Negativ-Samples bleiben ohne Termin.
5. **Titel** ändern sich bei Mails mit Zeilenumbrüchen und Abkürzungen (meist kürzer/sauberer). Bestehende Tests sind grün,
   aber sichtbare Titel in der App können anders aussehen als bisher.
6. **Prompt** ist ca. 25 Zeilen länger (mehr Tokens pro Cloud-Anfrage). Wirkung auf echte Modelle ist nicht gemessen.

## Offen

- KI-Pfad nur per Prompt-Analyse bewertet; eine Messung mit echten Modellen (Cloud/On-Device) gegen `faelle.json` steht aus.
- Mehrere Termine in einer Nachricht: weiterhin nur der erste (die App legt pro Nachricht einen an); Wiederholungsregeln (RRULE)
  werden nicht erzeugt, nur der erste Termin.
- Vage Tageszeiten („Sunday afternoon“ → 15:00) setzen die Regeln weiterhin fest, der Prompt verlangt dagegen ganztägig – bewusst
  unverändert, aber uneinheitlich.
- Bewegliche Feiertage (Ostern, Pfingsten, Advent) fehlen; Tippfehler nur für Wochentage, als feste Liste.
- „nächsten Freitag“ an einem Donnerstag bleibt der morgige Freitag (bestehende Konvention, im Deutschen mehrdeutig).
- Die 78 Fixtures sind selbst geschrieben und zum Tuning benutzt; die 100 % sind deshalb optimistisch. Der Holdout (13/14) ist die ehrlichere Zahl.

