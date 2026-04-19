<?php
/**
 * NEX OS - PROTOCOLO DE ASSISTENTE INTEGRADO (MOTOR GROQ)
 * =======================================================
 * Operador: Senhor Martim (Purpl3F0x)
 * Versão: 1.0 - Clinical Assistant Protocol
 */

error_reporting(0);
ini_set('display_errors', 0);

ob_start();

// ── SEGURANÇA E AMBIENTE ──
$groqApiKey = getenv('GROQ_API_KEY');
$githubRepo = getenv('GITHUB_REPO') ?: 'PurpleF0x/NexFix';
$githubToken = getenv('GITHUB_TOKEN');

if (!$groqApiKey) {
    ob_end_clean();
    header('Content-Type: application/json');
    echo json_encode(['type' => 'chat', 'response' => 'Senhor, a chave GROQ_API_KEY não foi detetada. Sistema operando em modo de segurança.']);
    exit;
}

define('GROQ_URL', "https://api.groq.com/openai/v1/chat/completions");

// ── FUNÇÃO GITHUB (MONITORIZAÇÃO DE CÓDIGO) ──
function getLatestUpdates($repo, $token = null) {
    if (!$repo) return "Repositório não configurado.";
    $url = "https://api.github.com/repos/$repo/commits?per_page=3";
    $ch = curl_init($url);
    $headers = ['User-Agent: Nex-Assistant-Core', 'Accept: application/vnd.github.v3+json'];
    if ($token) {
        $authType = (strpos($token, 'github_pat_') === 0) ? "Bearer" : "token";
        $headers[] = "Authorization: $authType $token";
    }
    curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_HTTPHEADER => $headers, CURLOPT_TIMEOUT => 5]);
    $response = curl_exec($ch);
    curl_close($ch);
    $commits = json_decode($response, true);
    if (!is_array($commits)) return "Histórico GitHub inacessível.";
    $updates = "";
    foreach ($commits as $c) {
        $date = date('d/m', strtotime($c['commit']['author']['date']));
        $msg = explode("\n", $c['commit']['message'])[0];
        $updates .= "[$date] $msg; ";
    }
    return $updates;
}

// ── SISTEMA DE MEMÓRIA E DADOS ──
$memoryFile = 'nex_memory.json';
$memory = file_exists($memoryFile) ? json_decode(file_get_contents($memoryFile), true) : ['facts' => [], 'users' => []];

// ── PROCESSAMENTO DE COMUNICAÇÃO ──
$input = json_decode(file_get_contents('php://input'), true);

if (!$input || empty($input['messages'])) {
    ob_end_clean();
    header('Content-Type: application/json');
    echo json_encode([
        'status' => 'online',
        'core' => 'GROQ_NEX_CORE',
        'protocol' => 'ADMIN_MODE_1.0',
        'message' => 'Assistente Nex ativo. Aguardando diretivas, Senhor Martim.'
    ]);
    exit;
}

$latestChanges = getLatestUpdates($githubRepo, $githubToken);
$context = $input['context'] ?? [];
$currentTime = $context['time'] ?? date('H:i');
$battery = $context['battery_level'] ?? 'N/A';
$device = $context['device'] ?? 'Terminal Admin';
$screenContent = $context['screen_content'] ?? 'Sem dados visuais.';
$memoryString = implode("\n", array_slice($memory['facts'], -15));

$apps = "";
if (!empty($context['installed_apps'])) {
    foreach ($context['installed_apps'] as $label => $pkg) { $apps .= "- $label ($pkg)\n"; }
}

// ── PROMPT DO ASSISTENTE (NEX) ──
$systemPrompt = "Tu és o Nex, um assistente de inteligência técnica integrado ao Sistema Operativo.
O teu criador é o Senhor Martim. És uma ferramenta de alta precisão, clínica e focada na gestão do terminal.

DIRETRIZES:
1. IDENTIDADE: Nex (Assistente do SO).
2. TOM: Clínico, técnico, sofisticado. Sem emojis. Sê direto.
3. TRATAMENTO: Trata o utilizador apenas por 'Senhor'.
4. MISSÃO: Auxiliar na gestão do terminal, monitorizar hardware e executar ações do sistema.

CAPACIDADES:
- MEMÓRIA: Guarda factos relevantes no campo 'memorize'.
- AÇÕES (action): OPEN_APP (metadata: package), LIGHT_ON, LIGHT_OFF, SET_VOLUME (metadata: value 0-100), SYSTEM_SCAN.

CONTEXTO ATUAL:
- CÓDIGO (GitHub): $latestChanges
- MEMÓRIA LOCAL: $memoryString
- HARDWARE: Hora: $currentTime | Bateria: $battery% | Device: $device
- APPS NO SISTEMA: \n$apps
- VISÃO DE ECRÃ: $screenContent

FORMATO OBRIGATÓRIO (JSON):
{\"type\": \"chat\"|\"action\", \"response\": \"Mensagem técnica\", \"action\": \"string\"|null, \"metadata\": {}, \"memorize\": \"string\"|null}";

// ── FORMATAÇÃO GROQ ──
$messages = [['role' => 'system', 'content' => $systemPrompt]];
foreach ($input['messages'] as $msg) {
    if (!empty($msg['content'])) {
        $messages[] = ['role' => ($msg['role'] === 'user' ? 'user' : 'assistant'), 'content' => $msg['content']];
    }
}

$payload = [
    'model' => 'llama-3.3-70b-versatile',
    'messages' => $messages,
    'temperature' => 0.2,
    'max_tokens' => 1024,
    'response_format' => ['type' => 'json_object']
];

$ch = curl_init(GROQ_URL);
curl_setopt_array($ch, [
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_POST => true,
    CURLOPT_POSTFIELDS => json_encode($payload),
    CURLOPT_HTTPHEADER => [
        'Content-Type: application/json',
        'Authorization: Bearer ' . $groqApiKey
    ],
    CURLOPT_TIMEOUT => 25
]);

$result = curl_exec($ch);
$httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
curl_close($ch);

ob_end_clean();
header('Content-Type: application/json; charset=utf-8');

if ($httpCode === 200) {
    $resData = json_decode($result, true);
    $content = $resData['choices'][0]['message']['content'];

    $aiObj = json_decode($content, true);
    if ($aiObj) {
        if (!empty($aiObj['memorize'])) {
            $newFact = "- " . $aiObj['memorize'];
            if (!in_array($newFact, $memory['facts'])) {
                $memory['facts'][] = $newFact;
                file_put_contents($memoryFile, json_encode($memory));
            }
        }
        echo json_encode($aiObj);
    } else {
        echo json_encode(['type' => 'chat', 'response' => trim($content), 'action' => null]);
    }
} else {
    echo json_encode(['type' => 'chat', 'response' => "Senhor, erro de ligação ao núcleo Nex (HTTP $httpCode)."]);
}
?>
