#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Julian Digital Products
"""Generates the Telegram bridge scripts shipped in app/src/main/assets from one template.

  python3 tools/bridge/generate.py      (run from the repository root)

template.py holds the code, strings.json the console and API texts for each language. The output is
assets/bridge_scripts/notitocal_telegram_bridge_<lang>.py (7 languages) and assets/notitocal_telegram_bridge.py
(German, the fallback the app uses when no language is chosen). Edit the template or the strings, never the output.
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent.parent / "app/src/main/assets"
LANGS = ["de", "en", "es", "fr", "it", "pl", "pt"]


def render(lang, script_name, strings):
    s = strings[lang]
    missing = set(strings["en"]) - set(s)
    if missing:
        raise SystemExit(f"{lang}: missing {sorted(missing)}")
    out = (HERE / "template.py").read_text(encoding="utf-8")
    out = out.replace("{{MESSAGES}}", json.dumps({k: v for k, v in s.items() if not k.startswith("DOC_")}, ensure_ascii=False, indent=4))
    out = out.replace("{{SCRIPT}}", script_name)
    for k, v in s.items():
        if k.startswith("DOC_"):
            out = out.replace("{{" + k + "}}", v)
    if "{{" in out.split("M = ")[0]:
        raise SystemExit(f"{lang}: unreplaced placeholder")
    return out


def main():
    strings = json.loads((HERE / "strings.json").read_text(encoding="utf-8"))
    for lang in LANGS:
        name = f"notitocal_telegram_bridge_{lang}.py"
        (ASSETS / "bridge_scripts" / name).write_text(render(lang, name, strings), encoding="utf-8")
    (ASSETS / "notitocal_telegram_bridge.py").write_text(render("de", "notitocal_telegram_bridge.py", strings), encoding="utf-8")
    print(f"{len(LANGS) + 1} bridge scripts written")


if __name__ == "__main__":
    main()
