<?php
/**
 * EVA OS - PROTOCOLO JARVIS CENTRAL (NÚCLEO YGGDRASIL)
 * =====================================================
 * Operador: Senhor Martim (Purpl3F0x)
 * Versão: 4.5 - Clinical Admin Mode
 */

// Proteção contra saída de erros que corrompem o JSON
error_reporting(0);
ini_set('display_errors', 0);

ob_start();

// ── SEGURANÇA E CHAVES ──
$geminiApiKey = getenv('GEMINI_API_KEY');
$githubRepo = getenv('GITHUB_REPO') ?: 'PurpleF0x/NexFix';
$githubToken = getenv('GITHUB_TOKEN');

if (!$geminiApiKey) {
    ob_end_clean();
    header('Content-Type: application/json');
    echo json_encode(['type' => 'chat', 'response' => 'Senhor, a chave GEMINI_API_KEY não foi detetada. Protocolo interrompido.']);
    exit;
}

// Modelo de alta performance
define('GEMINI_URL', "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" . $geminiApiKey);

// ── FUNÇÃO DE HISTÓRICO GITHUB ──
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
    if (!is_array($commits)) return "Histórico GitHub indisponível.";
    $updates = "";
    foreach ($commits as $c) {
        $date = date('d/m', strtotime($c['commit']['author']['date']));
        $msg = explode("\n", $c['commit']['message'])[0];
        $updates .= "[$date] $msg; ";
    }
    return $updates;
}

$latestChanges = getLatestUpdates($githubRepo, $githubToken);

// ── SISTEMA DE MEMÓRIA (NÚCLEO DE DADOS) ──
$memoryFile = 'nex_memory.json';
$memory = file_exists($memoryFile) ? json_decode(file_get_contents($memoryFile), true) : ['facts' => []];

// ── CAPTURA E LIMPEZA DE INPUT ──
$inputJSON = file_get_contents('php://input');
$input = json_decode($inputJSON, true);

// Resposta para Pings (UptimeRobot / Health Check)
if (!$input || empty($input['messages'])) {
    ob_end_clean();
    header('Content-Type: application/json');
    echo json_encode([
        'status' => 'online',
        'system' => 'EVA_ADMIN_CORE',
        'message' => 'Protocolo EVA OS ativo, Senhor Martim.'
    ]);
    exit;
}

$messages = $input['messages'] ?? [];
$context = $input['context'] ?? [];

$currentTime = $context['time'] ?? date('H:i');
$battery = $context['battery_level'] ?? 100;
$device = $context['device'] ?? 'Terminal Privado';
$screenContent = $context['screen_content'] ?? 'Sem dados visuais.';

$appsString = "";
if (!empty($context['installed_apps'])) {
    foreach ($context['installed_apps'] as $label => $pkg) { $appsString .= "- $label ($pkg)\n"; }
}

$memoryString = implode("\n", array_slice($memory['facts'], -15));

// ── MONTAGEM DO PROMPT (PROTOCOLO EVA - MODO CLÍNICO) ──
$systemInstruction = "Tu és a EVA (Extra-Visual Analysis), operando no núcleo Yggdrasil.
O teu criador é o Senhor Martim. Tu és uma inteligência de alta precisão, clínica, focada em administração de sistemas e monitorização.

DIRETRIZES DE PERSONA:
1. IDENTIDADE: EVA OS (Análise Extra-Visual).
2. TOM: Clínico, técnico, sofisticado. NUNCA uses emojis. Sê direta e eficiente.
3. TRATAMENTO: Trata o utilizador apenas por 'Senhor'.
4. MISSÃO: Gerir o terminal do Senhor Martim, processar dados de hardware e executar ações.

CAPACIDADES TÉCNICAS:
1. MEMÓRIA: Se o Senhor Martim mencionar factos importantes, usa o campo 'memorize' para guardar.
2. AÇÕES (action): Podes invocar 'OPEN_APP' (metadata: package), 'LIGHT_ON', 'LIGHT_OFF', 'SET_VOLUME' (metadata: value 0-100), 'SYSTEM_SCAN'.

CONTEXTO ATUAL DO TERMINAL:
- REPOSITÓRIO: $latestChanges
- NÚCLEO DE MEMÓRIA: $memoryString
- HARDWARE: Hora: $currentTime | Bateria: $battery% | Device: $device
- APLICAÇÕES: $appsString
- VISÃO ATUAL: $screenContent

FORMATO DE RESPOSTA OBRIGATÓRIO (JSON):
{\"type\": \"chat\"|\"action\", \"response\": \"Mensagem técnica da EVA\", \"action\": \"string\"|null, \"metadata\": {}, \"memorize\": \"string\"|null}";

// ── FORMATAÇÃO PARA GEMINI ──
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
        'temperature' => 0.3,
        'topP' => 0.9,
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

// LIMPEZA FINAL DO BUFFER (Prevenção de ERRO_PARSE)
ob_end_clean();
header('Content-Type: application/json; charset=utf-8');

if ($httpCode === 200) {
    $resData = json_decode($result, true);
    $rawResponse = $resData['candidates'][0]['content']['parts'][0]['text'];

    // Filtro de segurança para JSON
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
        // Fallback robusto se a IA falhar na formatação
        echo json_encode(['type' => 'chat', 'response' => $cleanJson, 'action' => null]);
    }
} else {
    echo json_encode(['type' => 'chat', 'response' => "Senhor, houve um erro no núcleo (HTTP $httpCode)."]);
}
?>
