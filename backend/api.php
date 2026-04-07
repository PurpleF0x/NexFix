<?php
/**
 * NEXA OS - PROTOCOLO JARVIS CENTRAL (GEMINI 1.5 FLASH EDITION)
 * =============================================================
 * Desenvolvido para Martim (Purpl3F0x)
 */

ob_start();

// ── SEGURANÇA E CHAVES ──
$geminiApiKey = getenv('GEMINI_API_KEY'); // Use a nova chave aqui
$githubRepo = getenv('GITHUB_REPO') ?: 'PurpleF0x/NexFix';
$githubToken = getenv('GITHUB_TOKEN');

if (!$geminiApiKey) {
    header('Content-Type: application/json');
    echo json_encode(['type' => 'chat', 'response' => 'Senhor, a chave GEMINI_API_KEY não foi detetada.']);
    exit;
}

// Alteração na linha 21 do seu api.php
define('GEMINI_URL', "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" . $geminiApiKey);

// ── FUNÇÃO PARA HISTÓRICO DO GITHUB ──
function getLatestUpdates($repo, $token = null) {
    if (!$repo) return "Repositório não definido.";
    $url = "https://api.github.com/repos/$repo/commits?per_page=3";
    $ch = curl_init($url);
    $headers = ['User-Agent: Nex-Assistant-OS', 'Accept: application/vnd.github.v3+json'];
    if ($token) {
        $authType = (strpos($token, 'github_pat_') === 0) ? "Bearer" : "token";
        $headers[] = "Authorization: $authType $token";
    }
    curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_HTTPHEADER => $headers, CURLOPT_TIMEOUT => 5]);
    $response = curl_exec($ch);
    curl_close($ch);
    $commits = json_decode($response, true);
    if (!is_array($commits)) return "Histórico indisponível.";
    $updates = "";
    foreach ($commits as $c) {
        $date = date('d/m', strtotime($c['commit']['author']['date']));
        $msg = explode("\n", $c['commit']['message'])[0];
        $updates .= "[$date] $msg; ";
    }
    return $updates;
}

$latestChanges = getLatestUpdates($githubRepo, $githubToken);

// ── SISTEMA DE MEMÓRIA ──
$memoryFile = 'nex_memory.json';
$memory = file_exists($memoryFile) ? json_decode(file_get_contents($memoryFile), true) : ['facts' => []];

// ── CAPTURA DE INPUT ──
$input = json_decode(file_get_contents('php://input'), true);
$messages = $input['messages'] ?? [];
$context = $input['context'] ?? [];

$currentTime = $context['time'] ?? date('H:i');
$battery = $context['battery_level'] ?? 100;
$temp = $context['battery_temp'] ?? 30;
$screenText = $context['screen_content'] ?? 'Ecrã vazio.';
$installedApps = $context['installed_apps'] ?? [];

$appsString = "";
if (is_array($installedApps)) {
    foreach ($installedApps as $label => $pkg) { $appsString .= "- $label ($pkg)\n"; }
}

// ── MONTAGEM DO PROMPT ──
$systemInstruction = "Tu és o NEX, IA central do Nexa OS. Criador: Martim (Purpl3F0x). Local: PurpleF0x/NexFix.
Trata o utilizador por 'Senhor'. Proativo, sofisticado, sem emojis.
GITHUB: $latestChanges
MEMÓRIA: " . implode("\n", $memory['facts']) . "
STATUS: $currentTime | Bateria: $battery% | Temp: $temp°C
APPS: $appsString
VISÃO: $screenText

OBRIGATÓRIO: Responde APENAS em JSON no formato:
{\"type\": \"chat\"|\"action\", \"response\": \"...\", \"action\": \"...\", \"metadata\": {}, \"memorize\": \"...\"}

AÇÕES: LIGHT_ON, LIGHT_OFF, SET_WIFI, SET_BLUETOOTH, SET_VOLUME, SET_BRIGHTNESS, OPEN_APP, MAKE_CALL.";

// Converter histórico para formato Gemini
$geminiMessages = [];
foreach ($messages as $msg) {
    $role = ($msg['role'] === 'user') ? 'user' : 'model';
    $geminiMessages[] = ['role' => $role, 'parts' => [['text' => $msg['content']]]];
}

$payload = [
    'contents' => $geminiMessages,
    'system_instruction' => ['parts' => [['text' => $systemInstruction]]],
    'generationConfig' => [
        'temperature' => 0.4,
        'topP' => 0.8,
        'maxOutputTokens' => 1024,
        'responseMimeType' => 'application/json'
    ]
];

$ch = curl_init(GEMINI_URL);
curl_setopt_array($ch, [
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_POST => true,
    CURLOPT_POSTFIELDS => json_encode($payload),
    CURLOPT_HTTPHEADER => ['Content-Type: application/json'],
    CURLOPT_TIMEOUT => 20
]);

$result = curl_exec($ch);
$httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
curl_close($ch);

if ($httpCode === 200) {
    $data = json_decode($result, true);
    $rawResponse = $data['candidates'][0]['content']['parts'][0]['text'];
    $aiContent = json_decode($rawResponse, true);

    if (!empty($aiContent['memorize'])) {
        $fact = "- " . $aiContent['memorize'];
        if (!in_array($fact, $memory['facts'])) {
            $memory['facts'][] = $fact;
            file_put_contents($memoryFile, json_encode($memory));
        }
    }
    echo json_encode($aiContent);
} else {
    echo json_encode(['type' => 'chat', 'response' => "Senhor, erro no Gemini (HTTP $httpCode)."]);
}
?>
