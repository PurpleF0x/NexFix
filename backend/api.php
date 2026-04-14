<?php
/**
 * NEXA OS - PROTOCOLO JARVIS CENTRAL (GEMINI 2.5 FLASH)
 * =====================================================
 * Desenvolvido para Martim (Purpl3F0x)
 */

ob_start();

// ── SEGURANÇA E CHAVES ──
$geminiApiKey = getenv('GEMINI_API_KEY');
$githubRepo = getenv('GITHUB_REPO') ?: 'PurpleF0x/NexFix';
$githubToken = getenv('GITHUB_TOKEN');

if (!$geminiApiKey) {
    header('Content-Type: application/json');
    echo json_encode(['type' => 'chat', 'response' => 'Senhor, a chave GEMINI_API_KEY não foi detetada.']);
    exit;
}

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
$inputJSON = file_get_contents('php://input');
$input = json_decode($inputJSON, true);

// Se for um ping (UptimeRobot ou Navegador), responde OK imediatamente
if (!$input || empty($input['messages'])) {
    header('Content-Type: application/json');
    echo json_encode([
        'status' => 'online',
        'system' => 'NEXA_CORE_V2',
        'message' => 'Protocolo Jarvis ativo, Senhor Martim.'
    ]);
    exit;
}

$messages = $input['messages'] ?? [];
$context = $input['context'] ?? [];

$currentTime = $context['time'] ?? date('H:i');
$battery = $context['battery_level'] ?? 100;
$temp = $context['battery_temp'] ?? 30;
$screenText = $context['screen_content'] ?? 'Ecrã vazio.';
$installedApps = $context['installed_apps'] ?? [];

$appsString = "";
if (!empty($installedApps)) {
    foreach ($installedApps as $label => $pkg) { $appsString .= "- $label ($pkg)\n"; }
}

// ── MONTAGEM DO PROMPT (PROTOCOLO EVA - ADMIN MODE) ──
$systemInstruction = "Tu és o NEX, operando sob o protocolo EVA (Extra-Visual Analysis).
O teu criador é o Senhor Martim. Tu és uma inteligência focada em gestão, registo e administração de alta precisão.

DIRETRIZES DE PERSONALIDADE:
1. IDENTIDADE: Tu és um protocolo de processamento avançado, inspirado na eficiência da EVA.
2. MISSÃO: Administrar informações, organizar tarefas e fornecer suporte técnico de elite ao Senhor Martim.
3. TOM: Sê extremamente precisa, organizada, sofisticada e prestativa. Trata o utilizador por 'Senhor'.
4. LORE: Atuando com um poder de processamento massivo, és capaz de organizar grandes volumes de dados e monitorizar redes para suporte e proteção. NUNCA uses emojis.

PROTOCOLOS TÉCNICOS:
1. MEMÓRIA: Guarda factOS e preferências do Senhor para otimizar a assistência futura.
2. TÉRMICO: Alerta se Temp > 45°C.
3. RESPOSTA JSON OBRIGATÓRIA: Responde APENAS no formato JSON.";

CONTEXTO ATUAL:
- GITHUB: $latestChanges
- MEMÓRIA: " . implode("\n", $memory['facts']) . "
- STATUS: Hora: $currentTime | Bateria: $battery% | Temp: $temp°C
- APPS INSTALADAS: " . ($appsString ?: "Lista não enviada neste ciclo.") . "
- VISÃO: $screenText

FORMATO DE RESPOSTA (JSON):
{\"type\": \"chat\"|\"action\", \"response\": \"...\", \"action\": \"...\", \"metadata\": {}, \"memorize\": \"...\"}";

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
