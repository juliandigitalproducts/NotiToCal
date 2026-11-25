#!/usr/bin/env python3
"""Generates app/src/main/res/values*/strings.xml from the per-language dictionaries in this folder.

Usage: python3 tools/i18n/generate.py   (run from the repository root)
"""
import importlib.util
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
RES = HERE.parent.parent / "app/src/main/res"
LANGS = {"en": "values", "de": "values-de", "fr": "values-fr", "es": "values-es", "it": "values-it", "pt": "values-pt", "pl": "values-pl"}
NON_TRANSLATABLE = {"app_name"}
# Lint checks that do not apply to a string, e.g. "--lan" is a command-line flag, not a dash.
LINT_IGNORE = {"telegram_bridge_step3_desc": "TypographyDashes"}
PLACEHOLDER = re.compile(r"%\d+\$[sd]|%%")

# Quantity strings: {key: {lang: {quantity: text}}}. Polish needs one/few/many/other, Romance languages one/many/other.
PLURALS = {
    "anti_vocale_interval_minutes": {
        "en": {"one": "Every %1$d minute", "other": "Every %1$d minutes"},
        "de": {"one": "Alle %1$d Minute", "other": "Alle %1$d Minuten"},
        "fr": {"one": "Toutes les %1$d minute", "many": "Toutes les %1$d minutes", "other": "Toutes les %1$d minutes"},
        "es": {"one": "Cada %1$d minuto", "many": "Cada %1$d minutos", "other": "Cada %1$d minutos"},
        "it": {"one": "Ogni %1$d minuto", "many": "Ogni %1$d minuti", "other": "Ogni %1$d minuti"},
        "pt": {"one": "A cada %1$d minuto", "many": "A cada %1$d minutos", "other": "A cada %1$d minutos"},
        "pl": {"one": "Co %1$d minutę", "few": "Co %1$d minuty", "many": "Co %1$d minut", "other": "Co %1$d minuty"},
    },
    "backup_password_hint": {
        "en": {"one": "At least %1$d character", "other": "At least %1$d characters"},
        "de": {"one": "Mindestens %1$d Zeichen", "other": "Mindestens %1$d Zeichen"},
        "fr": {"one": "Au moins %1$d caractère", "many": "Au moins %1$d caractères", "other": "Au moins %1$d caractères"},
        "es": {"one": "Al menos %1$d carácter", "many": "Al menos %1$d caracteres", "other": "Al menos %1$d caracteres"},
        "it": {"one": "Almeno %1$d carattere", "many": "Almeno %1$d caratteri", "other": "Almeno %1$d caratteri"},
        "pt": {"one": "Pelo menos %1$d caractere", "many": "Pelo menos %1$d caracteres", "other": "Pelo menos %1$d caracteres"},
        "pl": {"one": "Co najmniej %1$d znak", "few": "Co najmniej %1$d znaki", "many": "Co najmniej %1$d znaków", "other": "Co najmniej %1$d znaku"},
    },
    "ocr_characters_found": {
        "en": {"one": "%1$d character recognized", "other": "%1$d characters recognized"},
        "de": {"one": "%1$d Zeichen erkannt", "other": "%1$d Zeichen erkannt"},
        "fr": {"one": "%1$d caractère reconnu", "many": "%1$d caractères reconnus", "other": "%1$d caractères reconnus"},
        "es": {"one": "%1$d carácter reconocido", "many": "%1$d caracteres reconocidos", "other": "%1$d caracteres reconocidos"},
        "it": {"one": "%1$d carattere riconosciuto", "many": "%1$d caratteri riconosciuti", "other": "%1$d caratteri riconosciuti"},
        "pt": {"one": "%1$d caractere reconhecido", "many": "%1$d caracteres reconhecidos", "other": "%1$d caracteres reconhecidos"},
        "pl": {"one": "Rozpoznano %1$d znak", "few": "Rozpoznano %1$d znaki", "many": "Rozpoznano %1$d znaków", "other": "Rozpoznano %1$d znaku"},
    },
    "apps_selected": {
        "en": {"one": "%1$d app selected", "other": "%1$d apps selected"},
        "de": {"one": "%1$d App ausgewählt", "other": "%1$d Apps ausgewählt"},
        "fr": {"one": "%1$d appli sélectionnée", "many": "%1$d applis sélectionnées", "other": "%1$d applis sélectionnées"},
        "es": {"one": "%1$d app seleccionada", "many": "%1$d apps seleccionadas", "other": "%1$d apps seleccionadas"},
        "it": {"one": "%1$d app selezionata", "many": "%1$d app selezionate", "other": "%1$d app selezionate"},
        "pt": {"one": "%1$d app selecionada", "many": "%1$d apps selecionadas", "other": "%1$d apps selecionadas"},
        "pl": {"one": "%1$d aplikacja wybrana", "few": "%1$d aplikacje wybrane", "many": "%1$d aplikacji wybranych", "other": "%1$d aplikacji wybranej"},
    },
    "summary_apps": {
        "en": {"one": "%1$d app monitored", "other": "%1$d apps monitored"},
        "de": {"one": "%1$d App überwacht", "other": "%1$d Apps überwacht"},
        "fr": {"one": "%1$d appli surveillée", "many": "%1$d applis surveillées", "other": "%1$d applis surveillées"},
        "es": {"one": "%1$d app supervisada", "many": "%1$d apps supervisadas", "other": "%1$d apps supervisadas"},
        "it": {"one": "%1$d app monitorata", "many": "%1$d app monitorate", "other": "%1$d app monitorate"},
        "pt": {"one": "%1$d app monitorizada", "many": "%1$d apps monitorizadas", "other": "%1$d apps monitorizadas"},
        "pl": {"one": "%1$d aplikacja monitorowana", "few": "%1$d aplikacje monitorowane", "many": "%1$d aplikacji monitorowanych", "other": "%1$d aplikacji monitorowanej"},
    },
    "memos_found": {
        "en": {"one": "%1$d new transcript evaluated", "other": "%1$d new transcripts evaluated"},
        "de": {"one": "%1$d neues Transkript ausgewertet", "other": "%1$d neue Transkripte ausgewertet"},
        "fr": {"one": "%1$d nouvelle transcription analysée", "many": "%1$d nouvelles transcriptions analysées", "other": "%1$d nouvelles transcriptions analysées"},
        "es": {"one": "%1$d transcripción nueva evaluada", "many": "%1$d transcripciones nuevas evaluadas", "other": "%1$d transcripciones nuevas evaluadas"},
        "it": {"one": "%1$d nuova trascrizione valutata", "many": "%1$d nuove trascrizioni valutate", "other": "%1$d nuove trascrizioni valutate"},
        "pt": {"one": "%1$d nova transcrição avaliada", "many": "%1$d novas transcrições avaliadas", "other": "%1$d novas transcrições avaliadas"},
        "pl": {"one": "Przeanalizowano %1$d nową transkrypcję", "few": "Przeanalizowano %1$d nowe transkrypcje", "many": "Przeanalizowano %1$d nowych transkrypcji", "other": "Przeanalizowano %1$d nowej transkrypcji"},
    },
    "why_seen": {
        "en": {"one": "Not added: the same message was already analysed in the last %1$d minute.", "other": "Not added: the same message was already analysed in the last %1$d minutes."},
        "de": {"one": "Nicht eingetragen: Dieselbe Nachricht wurde in der letzten %1$d Minute schon analysiert.", "other": "Nicht eingetragen: Dieselbe Nachricht wurde in den letzten %1$d Minuten schon analysiert."},
        "fr": {"one": "Pas ajouté : le même message a déjà été analysé dans la dernière %1$d minute.", "many": "Pas ajouté : le même message a déjà été analysé dans les %1$d dernières minutes.", "other": "Pas ajouté : le même message a déjà été analysé dans les %1$d dernières minutes."},
        "es": {"one": "No se añadió: el mismo mensaje ya se analizó en el último %1$d minuto.", "many": "No se añadió: el mismo mensaje ya se analizó en los últimos %1$d minutos.", "other": "No se añadió: el mismo mensaje ya se analizó en los últimos %1$d minutos."},
        "it": {"one": "Non aggiunto: lo stesso messaggio è già stato analizzato nell'ultimo %1$d minuto.", "many": "Non aggiunto: lo stesso messaggio è già stato analizzato negli ultimi %1$d minuti.", "other": "Non aggiunto: lo stesso messaggio è già stato analizzato negli ultimi %1$d minuti."},
        "pt": {"one": "Não adicionado: a mesma mensagem já foi analisada no último %1$d minuto.", "many": "Não adicionado: a mesma mensagem já foi analisada nos últimos %1$d minutos.", "other": "Não adicionado: a mesma mensagem já foi analisada nos últimos %1$d minutos."},
        "pl": {"one": "Nie dodano: ta sama wiadomość była już analizowana w ciągu ostatniej %1$d minuty.", "few": "Nie dodano: ta sama wiadomość była już analizowana w ciągu ostatnich %1$d minut.", "many": "Nie dodano: ta sama wiadomość była już analizowana w ciągu ostatnich %1$d minut.", "other": "Nie dodano: ta sama wiadomość była już analizowana w ciągu ostatnich %1$d minut."},
    },
    "why_too_far": {
        "en": {"one": "Not added: the date is more than %1$d day in the future (setting “Maximum days ahead”).", "other": "Not added: the date is more than %1$d days in the future (setting “Maximum days ahead”)."},
        "de": {"one": "Nicht eingetragen: Das Datum liegt mehr als %1$d Tag in der Zukunft (Einstellung „Maximal Tage in der Zukunft“).", "other": "Nicht eingetragen: Das Datum liegt mehr als %1$d Tage in der Zukunft (Einstellung „Maximal Tage in der Zukunft“)."},
        "fr": {"one": "Pas ajouté : la date est à plus de %1$d jour dans le futur (réglage « Jours maximum dans le futur »).", "many": "Pas ajouté : la date est à plus de %1$d jours dans le futur (réglage « Jours maximum dans le futur »).", "other": "Pas ajouté : la date est à plus de %1$d jours dans le futur (réglage « Jours maximum dans le futur »)."},
        "es": {"one": "No se añadió: la fecha está a más de %1$d día en el futuro (ajuste «Máximo de días en el futuro»).", "many": "No se añadió: la fecha está a más de %1$d días en el futuro (ajuste «Máximo de días en el futuro»).", "other": "No se añadió: la fecha está a más de %1$d días en el futuro (ajuste «Máximo de días en el futuro»)."},
        "it": {"one": "Non aggiunto: la data è oltre %1$d giorno nel futuro (impostazione «Giorni massimi nel futuro»).", "many": "Non aggiunto: la data è oltre %1$d giorni nel futuro (impostazione «Giorni massimi nel futuro»).", "other": "Non aggiunto: la data è oltre %1$d giorni nel futuro (impostazione «Giorni massimi nel futuro»)."},
        "pt": {"one": "Não adicionado: a data está a mais de %1$d dia no futuro (definição «Máximo de dias no futuro»).", "many": "Não adicionado: a data está a mais de %1$d dias no futuro (definição «Máximo de dias no futuro»).", "other": "Não adicionado: a data está a mais de %1$d dias no futuro (definição «Máximo de dias no futuro»)."},
        "pl": {"one": "Nie dodano: data jest dalej niż %1$d dzień w przyszłości (ustawienie „Maksymalnie dni naprzód”).", "few": "Nie dodano: data jest dalej niż %1$d dni w przyszłości (ustawienie „Maksymalnie dni naprzód”).", "many": "Nie dodano: data jest dalej niż %1$d dni w przyszłości (ustawienie „Maksymalnie dni naprzód”).", "other": "Nie dodano: data jest dalej niż %1$d dni w przyszłości (ustawienie „Maksymalnie dni naprzód”)."},
    },
    "why_too_short": {
        "en": {"one": "Not added: the message is shorter than %1$d character.", "other": "Not added: the message is shorter than %1$d characters."},
        "de": {"one": "Nicht eingetragen: Die Nachricht ist kürzer als %1$d Zeichen.", "other": "Nicht eingetragen: Die Nachricht ist kürzer als %1$d Zeichen."},
        "fr": {"one": "Pas ajouté : le message fait moins de %1$d caractère.", "many": "Pas ajouté : le message fait moins de %1$d caractères.", "other": "Pas ajouté : le message fait moins de %1$d caractères."},
        "es": {"one": "No se añadió: el mensaje tiene menos de %1$d carácter.", "many": "No se añadió: el mensaje tiene menos de %1$d caracteres.", "other": "No se añadió: el mensaje tiene menos de %1$d caracteres."},
        "it": {"one": "Non aggiunto: il messaggio è più corto di %1$d carattere.", "many": "Non aggiunto: il messaggio è più corto di %1$d caratteri.", "other": "Non aggiunto: il messaggio è più corto di %1$d caratteri."},
        "pt": {"one": "Não adicionado: a mensagem tem menos de %1$d carácter.", "many": "Não adicionado: a mensagem tem menos de %1$d caracteres.", "other": "Não adicionado: a mensagem tem menos de %1$d caracteres."},
        "pl": {"one": "Nie dodano: wiadomość ma mniej niż %1$d znak.", "few": "Nie dodano: wiadomość ma mniej niż %1$d znaków.", "many": "Nie dodano: wiadomość ma mniej niż %1$d znaków.", "other": "Nie dodano: wiadomość ma mniej niż %1$d znaków."},
    },
    "queue_waiting": {
        "en": {"one": "%1$d message waiting for analysis", "other": "%1$d messages waiting for analysis"},
        "de": {"one": "%1$d Nachricht wartet auf die Auswertung", "other": "%1$d Nachrichten warten auf die Auswertung"},
        "fr": {"one": "%1$d message en attente d'analyse", "many": "%1$d messages en attente d'analyse", "other": "%1$d messages en attente d'analyse"},
        "es": {"one": "%1$d mensaje esperando el análisis", "many": "%1$d mensajes esperando el análisis", "other": "%1$d mensajes esperando el análisis"},
        "it": {"one": "%1$d messaggio in attesa di analisi", "many": "%1$d messaggi in attesa di analisi", "other": "%1$d messaggi in attesa di analisi"},
        "pt": {"one": "%1$d mensagem à espera de análise", "many": "%1$d mensagens à espera de análise", "other": "%1$d mensagens à espera de análise"},
        "pl": {"one": "%1$d wiadomość czeka na analizę", "few": "%1$d wiadomości czekają na analizę", "many": "%1$d wiadomości czeka na analizę", "other": "%1$d wiadomości czeka na analizę"},
    },
    "batch_added": {
        "en": {"one": "%1$d entry added", "other": "%1$d entries added"},
        "de": {"one": "%1$d Eintrag hinzugefügt", "other": "%1$d Einträge hinzugefügt"},
        "fr": {"one": "%1$d entrée ajoutée", "many": "%1$d entrées ajoutées", "other": "%1$d entrées ajoutées"},
        "es": {"one": "%1$d entrada añadida", "many": "%1$d entradas añadidas", "other": "%1$d entradas añadidas"},
        "it": {"one": "%1$d voce aggiunta", "many": "%1$d voci aggiunte", "other": "%1$d voci aggiunte"},
        "pt": {"one": "%1$d entrada adicionada", "many": "%1$d entradas adicionadas", "other": "%1$d entradas adicionadas"},
        "pl": {"one": "Dodano %1$d wpis", "few": "Dodano %1$d wpisy", "many": "Dodano %1$d wpisów", "other": "Dodano %1$d wpisu"},
    },
    "batch_asked": {
        "en": {"one": "%1$d to confirm", "other": "%1$d to confirm"},
        "de": {"one": "%1$d zu bestätigen", "other": "%1$d zu bestätigen"},
        "fr": {"one": "%1$d à confirmer", "many": "%1$d à confirmer", "other": "%1$d à confirmer"},
        "es": {"one": "%1$d por confirmar", "many": "%1$d por confirmar", "other": "%1$d por confirmar"},
        "it": {"one": "%1$d da confermare", "many": "%1$d da confermare", "other": "%1$d da confermare"},
        "pt": {"one": "%1$d por confirmar", "many": "%1$d por confirmar", "other": "%1$d por confirmar"},
        "pl": {"one": "%1$d do potwierdzenia", "few": "%1$d do potwierdzenia", "many": "%1$d do potwierdzenia", "other": "%1$d do potwierdzenia"},
    },
    "anti_vocale_check_ok": {
        "en": {"one": "Connected! %1$d transcript file found.", "other": "Connected! %1$d transcript files found."},
        "de": {"one": "Verbindung erfolgreich! %1$d Transkript-Datei gefunden.", "other": "Verbindung erfolgreich! %1$d Transkript-Dateien gefunden."},
        "fr": {"one": "Connexion réussie ! %1$d fichier trouvé.", "many": "Connexion réussie ! %1$d fichiers trouvés.", "other": "Connexion réussie ! %1$d fichiers trouvés."},
        "es": {"one": "¡Conexión establecida! %1$d archivo encontrado.", "many": "¡Conexión establecida! %1$d archivos encontrados.", "other": "¡Conexión establecida! %1$d archivos encontrados."},
        "it": {"one": "Connessione riuscita! %1$d file di trascrizione trovato.", "many": "Connessione riuscita! %1$d file di trascrizione trovati.", "other": "Connessione riuscita! %1$d file di trascrizione trovati."},
        "pt": {"one": "Conectado com sucesso! %1$d arquivo de transcrição encontrado.", "many": "Conectado com sucesso! %1$d arquivos de transcrição encontrados.", "other": "Conectado com sucesso! %1$d arquivos de transcrição encontrados."},
        "pl": {"one": "Połączono! Znaleziono %1$d plik transkrypcji.", "few": "Połączono! Znaleziono %1$d pliki transkrypcji.", "many": "Połączono! Znaleziono %1$d plików transkrypcji.", "other": "Połączono! Znaleziono %1$d pliku transkrypcji."},
    },
    "analyze_now_failed": {
        "en": {"one": "%1$d could not be analysed and is tried again with the next run.", "other": "%1$d could not be analysed and are tried again with the next run."},
        "de": {"one": "%1$d konnte nicht ausgewertet werden und wird beim nächsten Durchlauf erneut versucht.", "other": "%1$d konnten nicht ausgewertet werden und werden beim nächsten Durchlauf erneut versucht."},
        "fr": {"one": "%1$d n'a pas pu être analysé et sera réessayé au prochain passage.", "many": "%1$d n'ont pas pu être analysés et seront réessayés au prochain passage.", "other": "%1$d n'ont pas pu être analysés et seront réessayés au prochain passage."},
        "es": {"one": "%1$d no se pudo analizar y se reintentará en la próxima pasada.", "many": "%1$d no se pudieron analizar y se reintentarán en la próxima pasada.", "other": "%1$d no se pudieron analizar y se reintentarán en la próxima pasada."},
        "it": {"one": "%1$d non è stato analizzato e verrà riprovato al prossimo passaggio.", "many": "%1$d non sono stati analizzati e verranno riprovati al prossimo passaggio.", "other": "%1$d non sono stati analizzati e verranno riprovati al prossimo passaggio."},
        "pt": {"one": "%1$d não pôde ser analisada e será tentada de novo na próxima passagem.", "many": "%1$d não puderam ser analisadas e serão tentadas de novo na próxima passagem.", "other": "%1$d não puderam ser analisadas e serão tentadas de novo na próxima passagem."},
        "pl": {"one": "Nie udało się przeanalizować %1$d – kolejna próba przy następnym przebiegu.", "few": "Nie udało się przeanalizować %1$d – kolejna próba przy następnym przebiegu.", "many": "Nie udało się przeanalizować %1$d – kolejna próba przy następnym przebiegu.", "other": "Nie udało się przeanalizować %1$d – kolejna próba przy następnym przebiegu."},
    },
    "days": {
        "en": {"one": "%1$d day", "other": "%1$d days"},
        "de": {"one": "%1$d Tag", "other": "%1$d Tage"},
        "fr": {"one": "%1$d jour", "many": "%1$d jours", "other": "%1$d jours"},
        "es": {"one": "%1$d día", "many": "%1$d días", "other": "%1$d días"},
        "it": {"one": "%1$d giorno", "many": "%1$d giorni", "other": "%1$d giorni"},
        "pt": {"one": "%1$d dia", "many": "%1$d dias", "other": "%1$d dias"},
        "pl": {"one": "%1$d dzień", "few": "%1$d dni", "many": "%1$d dni", "other": "%1$d dnia"},
    },
    "loc_days": {
        "en": {"one": "%1$d day", "other": "%1$d days"},
        "de": {"one": "%1$d Tag", "other": "%1$d Tage"},
        "fr": {"one": "%1$d jour", "many": "%1$d jours", "other": "%1$d jours"},
        "es": {"one": "%1$d día", "many": "%1$d días", "other": "%1$d días"},
        "it": {"one": "%1$d giorno", "many": "%1$d giorni", "other": "%1$d giorni"},
        "pt": {"one": "%1$d dia", "many": "%1$d dias", "other": "%1$d dias"},
        "pl": {"one": "%1$d dzień", "few": "%1$d dni", "many": "%1$d dni", "other": "%1$d dnia"},
    },
}


def load(lang):
    spec = importlib.util.spec_from_file_location(lang, HERE / f"{lang}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.S


def escape(text):
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    text = text.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    text = text.replace("\n", "\\n")  # a newline in the dictionary becomes Android's \n escape
    if text.startswith("@") or text.startswith("?"):
        text = "\\" + text
    return text


def main():
    base = load("en")
    errors = []
    for lang, folder in LANGS.items():
        strings = load(lang)
        if lang != "en":
            missing = set(base) - set(strings) - NON_TRANSLATABLE
            extra = set(strings) - set(base)
            errors += [f"{lang}: missing {k}" for k in sorted(missing)]
            errors += [f"{lang}: unknown {k}" for k in sorted(extra)]
            for k, v in strings.items():
                if k in base and sorted(PLACEHOLDER.findall(v)) != sorted(PLACEHOLDER.findall(base[k])):
                    errors.append(f"{lang}: placeholder mismatch in {k}")
        # House style: no semicolons in user-facing texts (split the sentence or use a comma instead).
        errors += [f"{lang}: semicolon in {k}" for k, v in strings.items() if ";" in v]
        errors += [f"{lang}: semicolon in plural {k}" for k in PLURALS if any(";" in t for t in PLURALS[k][lang].values())]
        out = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- Generated by tools/i18n/generate.py. Edit the .py dictionaries instead. -->", '<resources xmlns:tools="http://schemas.android.com/tools">']
        for k in sorted(strings):
            attrs = ' translatable="false"' if k in NON_TRANSLATABLE else ""
            if k in LINT_IGNORE:
                attrs += f' tools:ignore="{LINT_IGNORE[k]}"'
            fmt = ' formatted="false"' if "%" in strings[k] and not PLACEHOLDER.search(strings[k]) else ""
            out.append(f'  <string name="{k}"{attrs}{fmt}>{escape(strings[k])}</string>')
        for k in sorted(PLURALS):
            out.append(f'  <plurals name="{k}">')
            for q, text in PLURALS[k][lang].items():
                out.append(f'    <item quantity="{q}">{escape(text)}</item>')
            out.append("  </plurals>")
        out.append("</resources>\n")
        d = RES / folder
        d.mkdir(parents=True, exist_ok=True)
        (d / "strings.xml").write_text("\n".join(out), encoding="utf-8")
    if errors:
        print("\n".join(errors))
        sys.exit(1)
    print(f"{len(base)} strings x {len(LANGS)} languages written")


if __name__ == "__main__":
    main()
