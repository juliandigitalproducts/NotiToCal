# Privacy policy – NotiToCal

*Last updated: 25 November 2026*

NotiToCal is an open-source app by juliandigitalproducts. It has **no user accounts, no analytics of its own, no ads and
no server of its own**. The one component that contacts a third party on its own is Google ML Kit, which the app uses to
read text in pictures (see [Google ML Kit](#google-ml-kit-text-recognition-on-the-phone)).

## Controller
Responsible for this app and this privacy policy is juliandigitalproducts. Name, postal address and contact details are
in the imprint: https://juliandigitalproducts.de/impressum

The app has no server of its own: everything described below happens on your phone or between your phone and the
services you choose. juliandigitalproducts receives no data from the app.

## What the app processes
- **Notifications** of the apps you select (title, sender name, message text) to detect appointments and tasks. This
  includes WhatsApp, WhatsApp Business, SMS/RCS and Telegram, each only while it is in your app list and its source is
  switched on.
- **Pictures in notifications** (e.g. a photo in a WhatsApp message), if "Analyse pictures from notifications" is on: the
  text is recognised on the phone. The picture itself only goes to your AI provider if you also switch on "Also send
  these pictures to the AI" (off by default).
- **E-mail (IMAP)**, only for accounts you add: the newest messages of the chosen folder are read read-only (sender,
  subject, the beginning of the text). Nothing is changed, moved or marked as read.
- **Telegram bot**, if you add a bot token: messages sent to your bot, fetched from Telegram's Bot API.
- **Telegram User-API**, if you run the bridge script yourself (in Termux or on your computer): the bridge logs in to your
  Telegram account with Telethon and passes messages of the chats you allow to the app. The bridge runs on your own
  device, requires a secret token for every request and stores its Telegram session only there.
- **Calendar files (.ics)** in a folder you choose, and text or pictures you share with the app or paste from the
  clipboard.
- **Screenshots** in the folders you choose (only if you enable it), and images or text you share with the app. A
  screenshot and the text shared with it are analysed together as one message.
- **Voice messages**: NotiToCal does not transcribe audio. The separate app Anti-Vocale writes transcripts into a folder
  you choose; NotiToCal reads these text files and evaluates them like a message.
- **Camera** only when you take a photo with the quick-capture widget. Photos are deleted after they were analysed.
  NotiToCal has no microphone access.
- **Duplicate finder**: reads the events of the calendars you choose to find duplicates. Merged and removed events are
  kept locally for 30 days so you can undo a merge.
- **Location history** (setting "Location history", off by default): if you switch on regular sampling, the app records
  your approximate position about once per chosen interval (default: hourly), also in the background. This needs the
  location permission with "Allow all the time". It mostly reuses a position another app already requested and
  otherwise makes one low-power request. The visited places are stored **only on the phone** and are used to resolve
  texts like "at the Italian place" to the place you usually go to. They are never sent in full: at most the one matching
  place goes into an appointment (and from there to the targets you configured). You can delete the history at any time;
  it is only included in a settings backup if you choose so, and then encrypted.
- **Google Maps Timeline import** (optional): a Timeline or Takeout export file you pick is read on the phone; only the
  visits are taken into the local location history, raw GPS points are skipped. Nothing is uploaded.
- Results and, if enabled, the original text are stored **locally** in the app's history (default: 30 days).

## Your responsibility with a remote AI
Privacy is only guaranteed with the **offline rules or the on-device AI**: then everything stays on your phone.

If you choose a remote AI (a cloud provider or your own AI server), the contents of your messages – including messages
from other people – are sent to that service, and NotiToCal can no longer guarantee their protection. You are then
responsible for complying with data protection law and for obtaining any consent needed from everyone whose messages are
processed. The app asks you to confirm this when you switch to a remote AI and shows a reminder while it is selected.

## Where data goes
Apart from Google ML Kit (see below), data leaves your phone only to services you configure yourself, plus the
address lookup and model downloads described below:
- an AI provider (OpenAI, Anthropic, Google Gemini, xAI, Mistral, Groq, OpenRouter, DeepSeek) or an AI server in your
  network (Ollama, LM Studio, oMLX, llama.cpp),
- your own NotiToCal server, a webhook, Todoist, Notion or a CalDAV server,
- automation apps (Tasker, MacroDroid, Automate, or the one app you choose) via an Android broadcast, if you enable that
  target,
- your e-mail server (IMAP), Telegram (bot token) and your Telegram bridge, if you set them up.

**Address lookup** (setting "Find places automatically", on by default, can be switched off; the sources Photon and
OpenStreetMap can also be switched off individually). The online lookup only runs if you selected a remote AI (a cloud
provider or your own AI server) as engine. With the offline rules or the on-device AI it is off unless you switch on
"Online place search with local engine" (off by default); places are then only searched in your own appointment
history on the phone. When the online lookup is active and a detected appointment has an incomplete place ("Café
Central", "Hauptstraße"), only that place text is sent to Photon (photon.komoot.io, operated by komoot) and/or
Nominatim (nominatim.openstreetmap.org, operated by the OpenStreetMap Foundation) to find the full address. If you saved
a home location, Photon also receives it rounded to about 1 km, to prefer nearby results. No message text, sender or
title is sent. While the phone is offline the lookup is postponed and done automatically once a connection is available.
Your own appointment history is searched on the phone first.

**Venue search** (part of the address lookup, only with the source OpenStreetMap switched on and an effort other than
"fast"): when an appointment names a kind of place instead of an address ("at the Italian place", "at the dentist") and
your location history has no match, the app asks the Overpass API (overpass-api.de, a public OpenStreetMap server run by
volunteers) for venues of that kind near your home location. Overpass receives the kind of place (e.g. restaurant,
cuisine italian), the street name if the message mentions one, a search radius and your home position rounded to about
1 km. Your location history itself is never sent; results are matched against it on the phone. Photon may receive the
same rounded position and the name of your home town for the same purpose.

When the meaning of a message is unclear (for example "Kunde 14 Uhr" and later "Kunde 15 Uhr" on the same day), the
duplicate check asks the on-device AI whether the appointment was moved. A remote AI is only asked if you selected one
as engine; without an AI both appointments are kept and you confirm the new one.

The AI check of the duplicate finder sends the title, time, location and the beginning of the description of the
events in question to your AI provider, only when you start it or switch on its automatic mode.

Sender and chat names are only sent to an AI service if you switch on "Send sender names to the AI" (off by default).
Webhooks and your own server only receive the original message text and sender if you switch that on (off by default).
Plain `http://` is only used for addresses in your local network; everything else needs HTTPS.

With the offline rules or the on-device AI, nothing is sent anywhere (unless you switch on "Online place search with
local engine", see the address lookup above). The pre-filter and the keyword lists limit which
messages are sent to an AI service. Model downloads are fetched from Hugging Face (huggingface.co) when you start them;
Hugging Face then sees your IP address and, for gated models such as Gemma, the access token you entered.

## Google ML Kit (text recognition on the phone)
NotiToCal uses Google ML Kit Text Recognition to read text in screenshots, photos and pictures in notifications. The
recognition itself runs entirely on the phone; the pictures and the recognised text are **not** sent to Google.
According to Google, ML Kit sends usage and performance metrics to Google (for example device model, Android and app
version, which ML Kit API was used and how long it took) through Google's logging service (Firelog). NotiToCal cannot
switch this off and does not receive these data. Google's description:
https://developers.google.com/ml-kit/terms (ML Kit Terms) and Google's privacy policy
https://policies.google.com/privacy. If you do not want this, do not use picture analysis (screenshots, quick capture,
pictures in notifications).

## Storage and security
API keys, tokens and passwords are encrypted with a key in the Android Keystore. Android backups and device transfer
are disabled for all app data. A settings backup you export yourself contains API keys only if you choose so, and then
encrypted with a password you set. Uninstalling the app deletes everything.

## Bug report
The diagnostic log is kept in memory only and is lost when the app closes. A bug report is only created and shared when
you tap the button. Passwords, tokens, API keys and phone numbers are removed from it.

## Permissions
Notification access (read messages), notifications (ask/confirm), calendar (create events, read events for the
duplicate finder), task providers
(Tasks.org/OpenTasks/jtx Board), photos (screenshots you choose to have analysed and photos you take or share for analysis, optional), camera
(quick-capture widget, optional), location (to save your home location for the address lookup and, only if you switch
on the location history, in the background with "Allow all the time"; optional),
internet (AI, address lookup and targets you configure), keep the processor awake while a message is analysed, list of installed apps
(choose which apps to monitor).

## Contact
- Controller and contact details: https://juliandigitalproducts.de/impressum
- Website: https://notitocal.com/
- Source code: https://git.juliandigitalproducts.de/jdaniels/NotiToCal (GitHub: soon)
