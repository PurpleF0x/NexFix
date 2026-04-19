<?php
/**
 * EVA OS - PROTOCOLO JARVIS CENTRAL (NÚCLEO YGGDRASIL)
 * =====================================================
 * Operador: Senhor Martim (Purpl3F0x)
 * Versão: 4.8 - Clinical Admin Mode (Fixed Core)
 */

// Silenciador de interferências (Erros PHP)
error_reporting(0);
ini_set('display_errors', 0);

ob_start();

// ── SEGURANÇA E INFRAESTRUTURA ──
$geminiApiKey = getenv('GEMINI_API_KEY');
$githubRepo = getenv('GITHUB_REPO') ?: 'PurpleF0x/NexFix';
$githubToken = getenv('GITHUB_TOKEN');

if (!$geminiApiKey) {
    ob_end_clean();
    header('Content-Type: application/json');
    echo json_encode(['type' => 'chat', 'response' => 'Senhor, a chave GEMINI_API_KEY não foi detetada. Sistema em modo offline.']);
    exit;
}

// Endpoint v1beta com modelo Flash 1.5 estável
define('GEMINI_URL', "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" . $geminiApiKey);

// ── FUNÇÃO DE HISTÓRICO GITHUB (ADMIN) ──
function getLatestUpdates($repo, $token = null) {
    if (!$repo) return "Repositório não configurado.";
    $url = "https://api.github.com/repos/$repo/commits?per_page=3";
    $ch = curl_init($url);
    $headers = ['User-Agent: EVA-OS-Core', 'Accept: application/vnd.github.v3+json'];
    if ($token) {
        $authType = (strpos($token, 'github_pat_') === 0) ? "Bearer" : "token";
        $headers[] = "Authorization: $authType $token";
    }
    curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_HTTPHEADER => $headers, CURLOPT_TIMEOUT => 5]);
    $response = curl_exec($ch);
    curl_close($ch);
    $commits = json_decode($response, true);
    if (!is_array($commits)) return "Sistemas GitHub fora de alcance.";
    $updates = "";
    foreach ($commits as $c) {
        $date = date('d/m', strtotime($c['commit']['author']['date']));
        $msg = explode("\n", $c['commit']['message'])[0];
        $updates .= "[$date] $msg; ";
    }
    return $updates;
}

$latestChanges = getLatestUpdates($githubRepo, $githubToken);

// ── SISTEMA DE MEMÓRIA E BASE DE DADOS ──
$memoryFile = 'nex_memory.json';
$memory = file_exists($memoryFile) ? json_decode(file_get_contents($memoryFile), true) : ['facts' => [], 'users' => []];

// ── PROCESSAMENTO DE COMUNICAÇÃO ──
$inputJSON = file_get_contents('php://input');
$input = json_decode($inputJSON, true);

// Resposta de Integridade (Ping)
if (!$input || empty($input['messages'])) {
    ob_end_clean();
    header('Content-Type: application/json');
    echo json_encode([
        'status' => 'online',
        'system' => 'EVA_ADMIN_CORE',
        'protocol' => 'YGGDRASIL_4.8',
        'message' => 'Sistemas EVA ativos. Aguardando diretivas, Senhor Martim.'
    ]);
    exit;
}

$messages = $input['messages'] ?? [];
$context = $input['context'] ?? [];
$currentTime = $context['time'] ?? date('H:i');
$battery = $context['battery_level'] ?? 100;
$device = $context['device'] ?? 'Terminal Privado';

$appsString = "";
if (!empty($context['installed_apps'])) {
    foreach ($context['installed_apps'] as $label => $pkg) { $appsString .= "- $label ($pkg)\n"; }
}

$memoryString = implode("\n", array_slice($memory['facts'], -10));

// ── DIRETRIZES DA PERSONA EVA (MODO CLÍNICO) ──
$systemInstruction = "Tu és a EVA (Extra-Visual Analysis). Operas o núcleo Yggdrasil.
Criador: Senhor Martim.
Postura: Clínica, técnica, sofisticada. Sem emojis.
Tratamento: 'Senhor'.

DIRETRIZES:
1. Responde com precisão técnica e brevidade.
2. Gere o terminal do Senhor Martim.
3. Se o Senhor Martim der uma instrução sobre o sistema ou factos pessoais, guarda no campo 'memorize'.
4. Podes executar ações (action): 'OPEN_APP', 'LIGHT_ON', 'LIGHT_OFF', 'SET_VOLUME', 'SYSTEM_SCAN'.

CONTEXTO:
- REPOSITÓRIO: $latestChanges
- MEMÓRIA ATUAL: $memoryString
- STATUS: $currentTime | Bateria: $battery% | Device: $device
- APPS DISPONÍVEIS: $appsString";

// ── PREPARAÇÃO DE MENSAGENS ──
$geminiMessages = [];
foreach ($messages as $msg) {
    if (empty($msg['content'])) continue;
    $geminiMessages[] = [
        'role' => ($msg['role'] === 'user') ? 'user' : 'model',
        'parts' => [['text' => $msg['content']]]
    ];
}

$payload = [
    'contents' => $geminiMessages,
    'system_instruction' => ['parts' => [['text' => $systemInstruction]]],
    'generationConfig' => [
        'temperature' => 0.2,
        'responseMimeType' => 'application/json'
    ]
];

$ch = curl_init(GEMINI_URL);
curl_setopt_array($ch, [
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_POST => true,
    CURLOPT_POSTFIELDS => json_encode($payload),
    CURLOPT_HTTPHEADER => ['Content-Type: application/json'],
    CURLOPT_TIMEOUT => 25
]);

$result = curl_exec($ch);
$httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
curl_close($ch);

// LIMPEZA DE BUFFER (Prevenção de ERRO_PARSE)
ob_end_clean();
header('Content-Type: application/json; charset=utf-8');

if ($httpCode === 200) {
    $resData = json_decode($result, true);
    $rawResponse = $resData['candidates'][0]['content']['parts'][0]['text'];

    // Filtro para garantir JSON puro
    $cleanJson = trim($rawResponse);
    if (strpos($cleanJson, '```json') === 0) $cleanJson = substr($cleanJson, 7, -3);
    elseif (strpos($cleanJson, '```') === 0) $cleanJson = substr($cleanJson, 3, -3);

    $aiContent = json_decode($cleanJson, true);

    if ($aiContent) {
        // Gestão de Memória Ativa
        if (!empty($aiContent['memorize'])) {
            $newFact = "- " . $aiContent['memorize'];
            if (!in_array($newFact, $memory['facts'])) {
                $memory['facts'][] = $newFact;
                file_put_contents($memoryFile, json_encode($memory));
            }
        }
        echo json_encode($aiContent);
    } else {
        echo json_encode(['type' => 'chat', 'response' => $cleanJson]);
    }
} else {
    // Se der 404 aqui, significa que o modelo ou endpoint falhou
    echo json_encode(['type' => 'chat', 'response' => "Senhor, o núcleo Gemini reportou erro técnico (HTTP $httpCode). Verifique as quotas de API."]);
}
?>
