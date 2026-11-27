<?php
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products
/**
 * NotiToCal self-hosted bridge.
 *
 *   GET  api.php?action=ping                  -> {"ok":true,...}                     (Bearer token)
 *   POST api.php?action=complete              -> {"content":"..."}                   (Bearer token)  LLM proxy
 *   POST api.php?action=item                  -> {"ok":true,"id":1}                  (Bearer token)  store or update (same uid) an entry
 *   GET  api.php?action=items[&since=ISO]     -> {"items":[...]}                     (Bearer token)
 *   POST api.php?action=done&id=1             -> {"ok":true}                         (Bearer token)  mark task done
 *   GET  api.php?action=ics&token=ICS_TOKEN   -> text/calendar (VEVENT + VTODO)      subscribe in any calendar app
 */
declare(strict_types=1);

// No stack traces to clients (they can contain the DSN or SQL); details go to the server log only (C11).
ini_set('display_errors', '0');
set_exception_handler(function (Throwable $e): void {
    error_log('notitocal: ' . $e);
    fail(500, 'server error');
});

$configFile = __DIR__ . '/config.php';
if (!is_file($configFile)) {
    fail(500, 'config.php missing: copy config.sample.php to config.php');
}
$config = require $configFile;
$action = $_GET['action'] ?? 'ping';

if ($action === 'ics') {
    // An empty or sample token would make the feed public: the feed stays off until a real token is set.
    if (!strongToken($config['ics_token'] ?? '')) fail(403, 'calendar feed disabled: set a long random ics_token in config.php');
    if (!hash_equals((string)$config['ics_token'], (string)($_GET['token'] ?? ''))) fail(403, 'invalid token');
    ics(db($config));
    exit;
}

authorize($config);

switch ($action) {
    case 'ping':
        $pdo = db($config);
        $count = (int)$pdo->query('SELECT COUNT(*) FROM notitocal_items')->fetchColumn();
        json(['ok' => true, 'service' => 'notitocal', 'items' => $count, 'llm' => $config['llm_model'] ?? null]);
    case 'complete':
        complete($config, body());
    case 'item':
        json(['ok' => true, 'id' => store(db($config), body(), !empty($config['store_message_text']))]);
    case 'items':
        listItems(db($config), $_GET['since'] ?? null);
    case 'done':
        $st = db($config)->prepare('UPDATE notitocal_items SET done = 1 WHERE id = ?');
        $st->execute([(int)($_GET['id'] ?? 0)]);
        json(['ok' => $st->rowCount() > 0]);
    default:
        fail(404, 'unknown action');
}

// ---------------------------------------------------------------------------------------------------------------

function json(array $data, int $code = 200): never {
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function fail(int $code, string $message): never {
    json(['ok' => false, 'error' => $message], $code);
}

function body(): array {
    $raw = file_get_contents('php://input') ?: '';
    if (strlen($raw) > 12 * 1024 * 1024) fail(413, 'payload too large');
    $data = json_decode($raw, true);
    if (!is_array($data)) fail(400, 'invalid JSON');
    return $data;
}

/** At least 24 characters and not one of the placeholders from config.sample.php. */
function strongToken(mixed $token): bool {
    $t = (string)$token;
    return strlen($t) >= 24 && !str_starts_with($t, 'change-me');
}

function authorize(array $config): void {
    if (!strongToken($config['api_token'] ?? '')) fail(500, 'set a long random api_token in config.php');
    $header = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if (!$header && function_exists('getallheaders')) {
        foreach (getallheaders() as $k => $v) if (strcasecmp($k, 'Authorization') === 0) $header = $v;
    }
    $token = preg_replace('/^Bearer\s+/i', '', trim($header));
    if (empty($config['api_token']) || !hash_equals((string)$config['api_token'], (string)$token)) fail(401, 'unauthorized');
}

function db(array $config): PDO {
    static $pdo = null;
    if ($pdo) return $pdo;
    $pdo = new PDO($config['db_dsn'], $config['db_user'] ?? null, $config['db_pass'] ?? null, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
    if ($pdo->getAttribute(PDO::ATTR_DRIVER_NAME) === 'sqlite') {
        $pdo->exec('CREATE TABLE IF NOT EXISTS notitocal_items (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, title TEXT NOT NULL,
            description TEXT, start_at TEXT, end_at TEXT, all_day INTEGER NOT NULL DEFAULT 0, due_at TEXT, location TEXT,
            priority INTEGER NOT NULL DEFAULT 0, confidence REAL NOT NULL DEFAULT 0, engine TEXT, timezone TEXT,
            source_type TEXT, source_app TEXT, source_pkg TEXT, sender TEXT, message TEXT, done INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)');
    } else {
        // Create the table only when it is missing, not on every request (C11).
        try {
            $pdo->query('SELECT 1 FROM notitocal_items LIMIT 1');
        } catch (PDOException) {
            $pdo->exec(file_get_contents(__DIR__ . '/schema.sql'));
        }
    }
    return $pdo;
}

/** $keepText: store the sender and message text the app sent along (config store_message_text, off by default). */
function store(PDO $pdo, array $d, bool $keepText = false): int {
    $kind = strtoupper((string)($d['kind'] ?? ''));
    if (!in_array($kind, ['EVENT', 'TASK', 'NOTE'], true)) fail(422, 'kind must be EVENT, TASK or NOTE');
    $title = trim((string)($d['title'] ?? ''));
    if ($title === '') fail(422, 'title missing');
    $src = is_array($d['source'] ?? null) ? $d['source'] : [];
    // Validated when stored, so one bad row can never break the calendar feed later.
    $tz = (string)($d['timezone'] ?? '');
    if ($tz !== '' && !in_array($tz, DateTimeZone::listIdentifiers(), true)) fail(422, 'unknown timezone');
    foreach (['start', 'end', 'due'] as $f) {
        if (isset($d[$f]) && $d[$f] !== null && !validDateValue((string)$d[$f])) fail(422, $f . ' must be YYYY-MM-DD or YYYY-MM-DDTHH:MM');
    }
    $values = [
        $kind, mb_substr($title, 0, 255), $d['description'] ?? null,
        $d['start'] ?? null, $d['end'] ?? null, !empty($d['all_day']) ? 1 : 0, $d['due'] ?? null,
        isset($d['location']) ? mb_substr((string)$d['location'], 0, 255) : null, (int)($d['priority'] ?? 0),
        (float)($d['confidence'] ?? 0), $d['engine'] ?? null, $d['timezone'] ?? null,
        $src['type'] ?? null, $src['app'] ?? null, $src['package'] ?? null,
        $keepText ? ($src['sender'] ?? null) : null, $keepText ? ($src['text'] ?? null) : null,
    ];
    // The app sends a stable uid per entry (1.5.17+): a second request with the same uid updates the item, so a
    // moved appointment changes in the calendar feed instead of appearing twice. Older apps send no uid.
    $uid = (string)($d['uid'] ?? '');
    if (!preg_match('/^[A-Za-z0-9-]{8,64}$/', $uid)) $uid = bin2hex(random_bytes(16));
    $find = $pdo->prepare('SELECT id FROM notitocal_items WHERE uid = ?');
    $find->execute([$uid]);
    $existing = $find->fetchColumn();
    if ($existing !== false) {
        $st = $pdo->prepare('UPDATE notitocal_items SET kind = ?, title = ?, description = ?, start_at = ?, end_at = ?,
            all_day = ?, due_at = ?, location = ?, priority = ?, confidence = ?, engine = ?, timezone = ?,
            source_type = ?, source_app = ?, source_pkg = ?, sender = ?, message = ? WHERE id = ?');
        $st->execute([...$values, (int)$existing]);
        return (int)$existing;
    }
    $st = $pdo->prepare('INSERT INTO notitocal_items
        (uid, kind, title, description, start_at, end_at, all_day, due_at, location, priority, confidence, engine, timezone,
         source_type, source_app, source_pkg, sender, message)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)');
    $st->execute([$uid, ...$values]);
    return (int)$pdo->lastInsertId();
}

function validDateValue(string $v): bool {
    if (!preg_match('/^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}(:\d{2})?)?$/', $v)) return false;
    try { new DateTime($v, new DateTimeZone('UTC')); return true; } catch (Exception) { return false; }
}

function listItems(PDO $pdo, ?string $since): never {
    if ($since) {
        $st = $pdo->prepare('SELECT * FROM notitocal_items WHERE created_at >= ? ORDER BY created_at DESC LIMIT 500');
        $st->execute([$since]);
    } else {
        $st = $pdo->query('SELECT * FROM notitocal_items ORDER BY created_at DESC LIMIT 500');
    }
    json(['items' => $st->fetchAll()]);
}

/**
 * Thinking models (Qwen 3, Gemma 4, DeepSeek R1) write reasoning before the answer: drop closed blocks, everything up to
 * a stray closing tag and an unclosed block (out of tokens while thinking = no answer). Same as Thinking.strip in the app.
 */
function stripThinking(string $t): string {
    $t = preg_replace('#<(think|thinking|reasoning)>.*?</\1>#is', '', $t) ?? $t;
    if (preg_match_all('#</(think|thinking|reasoning)>#i', $t, $m, PREG_OFFSET_CAPTURE)) {
        $last = end($m[0]);
        $t = substr($t, $last[1] + strlen($last[0]));
    }
    if (preg_match('#<(think|thinking|reasoning)>#i', $t, $m, PREG_OFFSET_CAPTURE)) $t = substr($t, 0, $m[0][1]);
    return trim($t);
}

/** Forwards the app's prompt to an OpenAI-compatible model; the phone never needs the LLM key. */
function complete(array $config, array $d): never {
    $user = (string)($d['user'] ?? '');
    $system = (string)($d['system'] ?? '');
    $image = (string)($d['image_base64'] ?? '');
    $mime = (string)($d['image_mime'] ?? 'image/jpeg');
    $wantJson = !empty($config['llm_json_mode']) && ($d['json'] ?? true) !== false;  // json=false: free-text answers
    $maxTokens = (int)($config['llm_max_tokens'] ?? 2048);
    $base = rtrim((string)$config['llm_base_url'], '/');

    // Ollama: only its native API switches thinking off ("think": false) and limits the length ("num_predict").
    $ollama = $config['llm_ollama_native'] ?? str_contains($base, ':11434');
    if ($ollama) {
        $userMsg = ['role' => 'user', 'content' => $user];
        if ($image !== '') $userMsg['images'] = [$image];
        $payload = [
            'model' => $config['llm_model'], 'stream' => false, 'think' => false,
            'options' => ['temperature' => 0.1, 'num_predict' => $maxTokens],
            'messages' => [['role' => 'system', 'content' => $system], $userMsg],
        ];
        if ($wantJson) $payload['format'] = 'json';
        $data = llmPost(preg_replace('#/v1$#', '', $base) . '/api/chat', $payload, $config);
        answer((string)($data['message']['content'] ?? ''));
    }

    $content = $user;
    if ($image !== '') {
        $content = [
            ['type' => 'text', 'text' => $user],
            ['type' => 'image_url', 'image_url' => ['url' => 'data:' . $mime . ';base64,' . $image]],
        ];
    }
    $payload = [
        'model' => $config['llm_model'],
        'temperature' => 0.1,
        'max_tokens' => $maxTokens,
        'messages' => [
            ['role' => 'system', 'content' => $system],
            ['role' => 'user', 'content' => $content],
        ],
    ];
    if ($wantJson) $payload['response_format'] = ['type' => 'json_object'];
    // llama.cpp / vLLM / LM Studio: Qwen-3-style chat templates. Cloud APIs reject unknown fields, so opt-in.
    if (!empty($config['llm_disable_thinking'])) $payload['chat_template_kwargs'] = ['enable_thinking' => false];
    $data = llmPost($base . '/chat/completions', $payload, $config);
    answer((string)($data['choices'][0]['message']['content'] ?? ''));
}

/** Sends the answer without reasoning; an empty answer is an error, not an empty success. */
function answer(string $raw): never {
    $text = stripThinking($raw);
    if ($text === '') fail(502, 'empty answer from the model (a thinking model may have used up llm_max_tokens)');
    json(['content' => $text]);
}

function llmPost(string $url, array $payload, array $config): array {
    $ch = curl_init($url);
    $headers = ['Content-Type: application/json'];
    if (!empty($config['llm_api_key'])) $headers[] = 'Authorization: Bearer ' . $config['llm_api_key'];
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => json_encode($payload, JSON_UNESCAPED_UNICODE),
        CURLOPT_HTTPHEADER => $headers,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => (int)($config['llm_timeout'] ?? 60),
    ]);
    $res = curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
    if ($res === false || $code >= 300) fail(502, 'LLM error ' . $code . ': ' . substr((string)($res ?: curl_error($ch)), 0, 300));
    $data = json_decode((string)$res, true);
    if (!is_array($data)) fail(502, 'LLM answer is not JSON');
    return $data;
}

/** RFC 5545 line folding: at most 75 octets per line, without splitting a UTF-8 character. */
function icsFold(string $line): string {
    if (strlen($line) <= 75) return $line;
    $out = '';
    $len = 0;
    foreach (preg_split('//u', $line, -1, PREG_SPLIT_NO_EMPTY) ?: [$line] as $ch) {
        $b = strlen($ch);
        if ($len + $b > 75) { $out .= "\r\n "; $len = 1; }
        $out .= $ch;
        $len += $b;
    }
    return $out;
}

/** Escapes a TEXT value. A lone CR counts as a line break too, so message text can never start a new property line. */
function icsText(?string $s): string {
    return str_replace(["\\", ";", ",", "\r\n", "\n", "\r"], ["\\\\", "\;", "\\,", "\\n", "\\n", "\\n"], (string)$s);
}

function icsDate(string $value, string $tz, bool $allDay): string {
    try { $zone = new DateTimeZone($tz ?: 'UTC'); } catch (Exception) { $zone = new DateTimeZone('UTC'); }
    if ($allDay || !str_contains($value, 'T')) return ';VALUE=DATE:' . (new DateTime(substr($value, 0, 10), $zone))->format('Ymd');
    $dt = new DateTime($value, $zone);
    $dt->setTimezone(new DateTimeZone('UTC'));
    return ':' . $dt->format('Ymd\THis\Z');
}

/** Calendar feed with every stored event and open task. */
function ics(PDO $pdo): void {
    header('Content-Type: text/calendar; charset=utf-8');
    header('Content-Disposition: inline; filename="notitocal.ics"');
    $out = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//NotiToCal//Server//EN", "X-WR-CALNAME:NotiToCal", "CALSCALE:GREGORIAN"];
    $stamp = gmdate('Ymd\THis\Z');
    foreach ($pdo->query("SELECT * FROM notitocal_items WHERE kind IN ('EVENT','TASK') ORDER BY created_at DESC LIMIT 2000") as $r) {
      // One broken row (e.g. stored before validation existed) is skipped instead of failing the whole feed.
      try {
        $tz = $r['timezone'] ?: 'UTC';
        if ($r['kind'] === 'EVENT' && $r['start_at']) {
            $out[] = 'BEGIN:VEVENT';
            $out[] = 'UID:' . $r['uid'] . '@notitocal';
            $out[] = 'DTSTAMP:' . $stamp;
            $out[] = 'SUMMARY:' . icsText($r['title']);
            $allDay = (bool)$r['all_day'] || !str_contains($r['start_at'], 'T');
            $out[] = 'DTSTART' . icsDate($r['start_at'], $tz, $allDay);
            if ($r['end_at']) $out[] = 'DTEND' . icsDate($r['end_at'], $tz, $allDay);
            if ($r['location']) $out[] = 'LOCATION:' . icsText($r['location']);
            if ($r['description']) $out[] = 'DESCRIPTION:' . icsText($r['description']);
            $out[] = 'END:VEVENT';
        } elseif ($r['kind'] === 'TASK') {
            $out[] = 'BEGIN:VTODO';
            $out[] = 'UID:' . $r['uid'] . '@notitocal';
            $out[] = 'DTSTAMP:' . $stamp;
            $out[] = 'SUMMARY:' . icsText($r['title']);
            $out[] = 'STATUS:' . ($r['done'] ? 'COMPLETED' : 'NEEDS-ACTION');
            if ($r['due_at']) $out[] = 'DUE' . icsDate($r['due_at'], $tz, !str_contains($r['due_at'], 'T'));
            if ($r['description']) $out[] = 'DESCRIPTION:' . icsText($r['description']);
            $out[] = 'END:VTODO';
        }
      } catch (Exception $e) {
        error_log('notitocal: ics row ' . ($r['id'] ?? '?') . ' skipped: ' . $e->getMessage());
        // Drop the partly written block.
        while ($out && end($out) !== 'END:VEVENT' && end($out) !== 'END:VTODO' && end($out) !== 'CALSCALE:GREGORIAN') array_pop($out);
      }
    }
    $out[] = 'END:VCALENDAR';
    echo implode("\r\n", array_map('icsFold', $out)) . "\r\n";
}
