<?php
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products
// End-to-end test of the server: api.php in PHP's built-in web server with a temporary SQLite database.
// Stores entries like the app does (target "Own server"), updates one by uid and reads the ICS calendar feed.
// Run: php server/tests/e2e.php   (needs php-cli with pdo_sqlite; nothing is kept afterwards)
declare(strict_types=1);

// Command line only: the folder may be served by the web server (docker-compose mounts server/).
if (PHP_SAPI !== 'cli') { http_response_code(404); exit; }

$root = sys_get_temp_dir() . '/notitocal-e2e-' . bin2hex(random_bytes(4));
$web = "$root/web";
mkdir($web, 0700, true);
foreach (['api.php', 'schema.sql'] as $f) copy(__DIR__ . "/../$f", "$web/$f");
$apiToken = bin2hex(random_bytes(16));
$icsToken = bin2hex(random_bytes(16));
file_put_contents("$web/config.php", '<?php return ' . var_export([
    'db_dsn' => "sqlite:$root/notitocal.sqlite",
    'api_token' => $apiToken,
    'ics_token' => $icsToken,
    'store_message_text' => false,
], true) . ';');

$probe = stream_socket_server('tcp://127.0.0.1:0');
$port = (int)substr(strrchr(stream_socket_get_name($probe, false), ':'), 1);
fclose($probe);
$server = proc_open([PHP_BINARY, '-S', "127.0.0.1:$port", '-t', $web], [1 => ['file', '/dev/null', 'w'], 2 => ['file', "$root/server.log", 'w']], $pipes);
$base = "http://127.0.0.1:$port/api.php";

$failures = 0;
$checks = 0;
function check(bool $ok, string $what): void {
    global $failures, $checks;
    $checks++;
    if (!$ok) { $failures++; fwrite(STDERR, "FAIL: $what\n"); } else { echo "ok   $what\n"; }
}

/** @return array{0:int,1:string} */
function call(string $method, string $url, ?array $json = null, ?string $token = null): array {
    $headers = [];
    if ($token !== null) $headers[] = "Authorization: Bearer $token";
    if ($json !== null) $headers[] = 'Content-Type: application/json';
    $ctx = stream_context_create(['http' => [
        'method' => $method, 'header' => implode("\r\n", $headers), 'ignore_errors' => true, 'timeout' => 10,
        'content' => $json !== null ? json_encode($json, JSON_UNESCAPED_UNICODE) : '',
    ]]);
    $body = @file_get_contents($url, false, $ctx);
    $code = 0;
    foreach ($http_response_header ?? [] as $h) if (preg_match('#^HTTP/\S+\s+(\d{3})#', $h, $m)) $code = (int)$m[1];
    return [$code, (string)$body];
}

try {
    // wait for the web server
    for ($i = 0; $i < 50 && @fsockopen('127.0.0.1', $port) === false; $i++) usleep(100000);

    [$c] = call('GET', "$base?action=ping");
    check($c === 401, 'ping without token is refused');
    [$c, $b] = call('GET', "$base?action=ping", null, $apiToken);
    check($c === 200 && json_decode($b, true)['ok'] === true, 'ping with token');
    [$c] = call('GET', "$base?action=ics&token=wrong");
    check($c === 403, 'feed with a wrong token is refused');

    // An entry as the app sends it (Targets.payload) with "Send message text" on: the server keeps no text by default.
    $event = [
        'action' => 'create', 'uid' => '0f6c2b1e-7a7d-4a63-9d55-2d3c5b4c1a01', 'kind' => 'EVENT',
        'title' => 'Treffen im Café (Anna)', 'description' => "Zeile 1\rX-INJECTED:1\nZeile 2",
        'start' => '2026-11-21T15:00', 'end' => '2026-11-21T16:00', 'all_day' => false, 'location' => 'Café Lotte, Köln',
        'priority' => 0, 'confidence' => 0.9, 'engine' => 'rules', 'timezone' => 'Europe/Berlin',
        'source' => ['type' => 'NOTIFICATION', 'app' => 'WhatsApp', 'package' => 'com.whatsapp', 'sender' => 'Anna', 'text' => 'geheimer Nachrichtentext'],
    ];
    [$c, $b] = call('POST', "$base?action=item", $event, $apiToken);
    $id = json_decode($b, true)['id'] ?? null;
    check($c === 200 && is_int($id), 'event stored');
    // Moved by one hour: same uid, same item.
    [$c, $b] = call('POST', "$base?action=item", ['action' => 'update', 'start' => '2026-11-21T16:00', 'end' => '2026-11-21T17:00'] + $event, $apiToken);
    check($c === 200 && (json_decode($b, true)['id'] ?? null) === $id, 'update with the same uid changes the same item');
    [$c] = call('POST', "$base?action=item", ['uid' => '0f6c2b1e-7a7d-4a63-9d55-2d3c5b4c1a02', 'kind' => 'EVENT', 'title' => 'Betriebsausflug', 'start' => '2026-12-11', 'all_day' => true, 'timezone' => 'Europe/Berlin'], $apiToken);
    check($c === 200, 'all-day event stored');
    [$c, $b] = call('POST', "$base?action=item", ['uid' => '0f6c2b1e-7a7d-4a63-9d55-2d3c5b4c1a03', 'kind' => 'TASK', 'title' => 'Rechnung bezahlen', 'due' => '2026-11-27', 'timezone' => 'Europe/Berlin'], $apiToken);
    $taskId = json_decode($b, true)['id'] ?? 0;
    check($c === 200, 'task stored');

    // validation
    [$c] = call('POST', "$base?action=item", ['kind' => 'PARTY', 'title' => 'x'], $apiToken);
    check($c === 422, 'unknown kind rejected');
    [$c] = call('POST', "$base?action=item", ['kind' => 'EVENT', 'title' => 'x', 'start' => '21.11.2026'], $apiToken);
    check($c === 422, 'bad date rejected');
    [$c] = call('POST', "$base?action=item", ['kind' => 'EVENT', 'title' => 'x', 'start' => '2026-11-21T10:00', 'timezone' => 'Mars/Olympus'], $apiToken);
    check($c === 422, 'unknown timezone rejected');

    [$c, $b] = call('GET', "$base?action=items", null, $apiToken);
    $items = json_decode($b, true)['items'] ?? json_decode($b, true);
    $stored = array_values(array_filter((array)$items, fn($r) => is_array($r) && ($r['id'] ?? null) == $id))[0] ?? [];
    check($c === 200 && $stored !== [] && $stored['sender'] === null && $stored['message'] === null, 'sender and message text not stored (store_message_text off)');

    [$c] = call('POST', "$base?action=done&id=$taskId", [], $apiToken);
    check($c === 200, 'task marked done');

    [$c, $ics] = call('GET', "$base?action=ics&token=$icsToken");
    check($c === 200, 'feed with the ics token');
    check(str_starts_with($ics, "BEGIN:VCALENDAR\r\n") && str_ends_with($ics, "END:VCALENDAR\r\n"), 'feed is a calendar with CRLF lines');
    $lines = explode("\r\n", rtrim($ics, "\r\n"));
    check(!str_contains(str_replace("\r\n", '', $ics), "\r") && !str_contains(str_replace("\r\n", '', $ics), "\n"), 'no bare CR/LF inside the data');
    check(count(array_filter($lines, fn($l) => strlen($l) > 75)) === 0, 'lines folded to at most 75 octets');
    $unfolded = str_replace("\r\n ", '', $ics);
    $u = explode("\r\n", $unfolded);
    check(count(array_keys($u, 'BEGIN:VEVENT')) === 2, 'two events in the feed (the moved one only once)');
    check(in_array('DTSTART:20261121T150000Z', $u, true) && in_array('DTEND:20261121T160000Z', $u, true), 'moved event at 16:00 Berlin = 15:00 UTC');
    check(in_array('DTSTART;VALUE=DATE:20261211', $u, true), 'all-day event as DATE');
    check(in_array('SUMMARY:Treffen im Café (Anna)', $u, true) && in_array('LOCATION:Café Lotte\, Köln', $u, true), 'summary and escaped location');
    check(!array_filter($u, fn($l) => str_starts_with($l, 'X-INJECTED')), 'a lone CR in the description cannot start a property');
    check(in_array('DESCRIPTION:Zeile 1\nX-INJECTED:1\nZeile 2', $u, true), 'description escaped');
    check(!str_contains($ics, 'geheimer Nachrichtentext'), 'message text not in the feed');
    check(in_array('BEGIN:VTODO', $u, true) && in_array('STATUS:COMPLETED', $u, true) && in_array('DUE;VALUE=DATE:20261127', $u, true), 'done task in the feed');
} finally {
    proc_terminate($server);
    proc_close($server);
    array_map('unlink', glob("$web/*") ?: []);
    @rmdir($web);
    array_map('unlink', glob("$root/*") ?: []);
    @rmdir($root);
}

echo "\n$checks checks, $failures failed\n";
exit($failures === 0 ? 0 : 1);
