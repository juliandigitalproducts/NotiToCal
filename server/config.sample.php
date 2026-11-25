<?php
// Copy this file to config.php and adjust it. config.php is never served (see .htaccess).
return [
    // Database: MySQL or MariaDB (recommended). SQLite works for quick tests – keep the file OUTSIDE the web root,
    // e.g. 'sqlite:' . dirname(__DIR__) . '/notitocal-data/notitocal.sqlite' (the .htaccess rule only helps on Apache).
    'db_dsn'  => 'mysql:host=localhost;dbname=notitocal;charset=utf8mb4',
    'db_user' => 'notitocal',
    'db_pass' => 'change-me',

    // Token the app sends as "Authorization: Bearer <token>". Use a long random string (e.g. `openssl rand -hex 32`).
    // The server refuses to work while a token is shorter than 24 characters or still starts with "change-me".
    'api_token' => 'change-me-to-a-long-random-token',

    // Separate token for the calendar subscription URL (api.php?action=ics&token=...), so it can be shared read-only.
    // The feed stays disabled until this is a real token (same rules as api_token).
    'ics_token' => 'change-me-too',

    // Any OpenAI-compatible chat completion endpoint: OpenAI, Mistral, Groq, OpenRouter, or a local Ollama / vLLM / LM Studio.
    'llm_base_url' => 'http://localhost:11434/v1',
    'llm_api_key'  => '',
    'llm_model'    => 'qwen2.5:7b',
    'llm_timeout'  => 60,
    // Set to false if your model server rejects response_format=json_object.
    'llm_json_mode' => true,
    // Room for the answer. Thinking models (Qwen 3, Gemma 4) need more than plain ones.
    'llm_max_tokens' => 2048,
    // Ollama is called through its native API (thinking off) when the URL uses port 11434; force with true/false.
    // 'llm_ollama_native' => true,
    // llama.cpp, vLLM, LM Studio with Qwen-3-style models: switch thinking off. Leave false for cloud APIs (they reject it).
    'llm_disable_thinking' => false,
    // Store sender and message text that the app sends along (app setting "Send message text to webhook and server").
    // Off: only the entry itself (title, time, place …) is kept, the original message never lands in the database.
    'store_message_text' => false,
];
