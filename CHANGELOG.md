## 1.0.8

### Deutsch
- Lizenzwechsel: Ab dieser Version steht NotiToCal unter GPL-3.0-or-later (der Server unter `server/` unter AGPL-3.0-or-later), mit Zusatzbedingungen nach § 7 in `LICENSE-EXCEPTIONS.md`. Versionen bis einschließlich 1.0.7 bleiben MIT.
- Lizenzhinweis in der App (Datenschutz-Einstellungen → Open-Source-Lizenzen) und in allen 7 Sprachen angepasst.

### English
- License change: starting with this version NotiToCal is licensed under GPL-3.0-or-later (the server in `server/` under AGPL-3.0-or-later), with additional terms under section 7 in `LICENSE-EXCEPTIONS.md`. Versions up to and including 1.0.7 remain MIT.
- License notice in the app (privacy settings → open source licenses) updated in all 7 languages.

## 1.0.7

### Deutsch
- Bessere Terminerkennung: Dauer und Zeitspannen („von 9 bis 12:30“, „10-11am“), Verschiebungen („statt Dienstag jetzt Mittwoch“), Wochentage, Feiertage wie Silvester und sauberere Titel und Orte.
- Datum ohne Jahr in der jüngsten Vergangenheit wird nicht mehr zu einem Termin im nächsten Jahr.
- Der KI-Prompt kennt den Kalender der nächsten 14 Tage.
- Neue Sicherheitsprüfung vor der Veröffentlichung (Secret-Scan).

### English
- Better appointment detection: durations and time ranges ("from 9 to 12:30", "10-11am"), reschedules ("Wednesday instead of Tuesday"), weekdays, holidays such as New Year's Eve, and cleaner titles and places.
- A date without a year in the recent past no longer becomes an appointment next year.
- The AI prompt knows the calendar of the next 14 days.
- New security check before publishing (secret scan).

## 1.0.0

### Deutsch
Erste Veröffentlichung von NotiToCal.
- Erkennt Termine und Aufgaben in Benachrichtigungen frei wählbarer Apps, in Screenshots, in Sprachnachricht-Transkripten und in geteiltem Text.
- Analyse mit Offline-Regeln für 7 Sprachen, einer KI auf dem Gerät, KI-Diensten, einer KI im eigenen Netz oder einem eigenen Server.
- Ziele: Handy-Kalender, Aufgaben-Apps, CalDAV, Notion, eigener Server, Webhook, Tasker/MacroDroid und Teilen an jede App.
- Orte wie „beim Italiener“ werden über frühere Termine, den Standortverlauf auf dem Gerät und OpenStreetMap zu echten Adressen.
- Geführte Einrichtung mit Konfigurations-Import, Präsentation „So arbeitet NotiToCal“, verschlüsselte Sicherung.

### English
First release of NotiToCal.
- Detects appointments and to-dos in notifications of the apps you choose, in screenshots, voice message transcripts and shared text.
- Analysis with offline rules for 7 languages, an on-device AI, AI services, an AI in your own network or your own server.
- Targets: phone calendar, task apps, CalDAV, Notion, your own server, webhook, Tasker/MacroDroid and sharing to any app.
- Places like "at the Italian" become real addresses through earlier appointments, the on-device location history and OpenStreetMap.
- Guided setup with configuration import, the presentation "How NotiToCal works" and an encrypted backup.
