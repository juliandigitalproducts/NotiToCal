# NotiToCal server (PHP + MySQL / MariaDB)

A small bridge you host yourself. The app can use it in two ways:

1. **AI engine "Own server"** – the app sends the prompt, the server forwards it to any OpenAI-compatible model
   (OpenAI, Mistral, Groq, OpenRouter or a local Ollama / vLLM). The model key stays on your server.
2. **Target "Own server"** – every appointment / task is stored in MySQL or MariaDB and offered as a calendar
   subscription (ICS with events and to-dos) that any calendar app can subscribe to.

## Install on a web host
1. Upload `api.php`, `index.php`, `schema.sql`, `.htaccess` and `config.sample.php` into a folder, e.g. `https://example.com/notitocal/`.
2. Create a MySQL/MariaDB database and user. Import `schema.sql` (optional, the table is also created automatically).
3. Copy `config.sample.php` to `config.php` and fill in database, `api_token`, `ics_token` and the LLM settings.
   Generate tokens with `openssl rand -hex 32`. The server refuses requests while `api_token` is shorter than 24
   characters or still the sample value, and the calendar feed stays off until `ics_token` is set the same way.
   Errors are logged on the server only; clients get a short message without details.
4. In the app: *Settings › AI engine › Own server* and/or *Calendars and task apps › Own server*:
   URL `https://example.com/notitocal/`, token = `api_token`. Tap **Test connection**.

Requirements: PHP 8.1+ with `pdo_mysql` and `curl`. Always use HTTPS on the internet.

### Thinking models (Qwen 3, Gemma 4, DeepSeek R1)
Their reasoning is removed from the answer; an answer that is only reasoning is reported as an error instead of an
empty result. With Ollama (URL on port 11434, or `llm_ollama_native => true`) the server uses Ollama's own API and
switches thinking off. For llama.cpp, vLLM or LM Studio set `llm_disable_thinking => true`. `llm_max_tokens`
(default 2048) leaves room for the answer.

## Docker
```bash
cp config.sample.php config.php   # set db_dsn to mysql:host=db;dbname=notitocal;charset=utf8mb4 and db_pass
export NOTITOCAL_DB_PASSWORD=...  # same as db_pass; there is no default database password
docker compose up -d              # http://<host>:8080/
```

## API
All requests except `ics` need `Authorization: Bearer <api_token>`.

| Request | Purpose |
|---|---|
| `GET api.php?action=ping` | health check, item count |
| `POST api.php?action=complete` `{"system","user","image_base64"?,"image_mime"?,"json"?}` | LLM proxy → `{"content": "..."}` |
| `POST api.php?action=item` (detection JSON from the app) | store, or update the item with the same `uid` → `{"ok":true,"id":1}` |
| `GET api.php?action=items[&since=YYYY-MM-DD]` | list stored items |
| `POST api.php?action=done&id=1` | mark a task as done |
| `GET api.php?action=ics&token=<ics_token>` | calendar subscription (VEVENT + VTODO) |

The `item` payload is the same JSON the webhook target sends (`action, uid, kind, title, description, start, end,
all_day, due, location, priority, confidence, engine, timezone, source{type, app, package, sender, text}, ics`), so n8n
or Home Assistant flows can share one format. `uid` stays the same for one entry; when the app learns that an
appointment was moved or got its end time, it sends the entry again with `"action":"update"` and the same `uid`
(webhooks and automation broadcasts get the same fields). `source.sender` and `source.text` are only sent when the app setting
"Send message text to webhook and server" is on, and the server keeps them only with `'store_message_text' => true` in
`config.php` (off by default).

## License
The server is licensed under the GNU Affero General Public License v3.0 or later (`AGPL-3.0-or-later`); full text in
[LICENSE](LICENSE). Versions up to and including v1.0.7 were released under MIT. If you run a modified version for
other users over a network, AGPL section 13 requires you to offer them its source code. Additional terms (names, logos,
graphics) are in [../LICENSE-EXCEPTIONS.md](../LICENSE-EXCEPTIONS.md). The Android app itself is `GPL-3.0-or-later`.
