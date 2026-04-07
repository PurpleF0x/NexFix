<?php
/**
 * NEXA OS - PROTOCOLO JARVIS CENTRAL (ANDROID 16 EDITION)
 * =======================================================
 * REGRA DE OURO: NÃO REMOVER BLOCOS DE HARDWARE, MEMÓRIA OU AÇÕES.
 */

ob_start();

// ── SEGURANÇA E CHAVES ──
$groqApiKey = getenv('GROQ_API_KEY');
$githubAppId = getenv('GITHUB_APP_ID');
$githubClientId = getenv('GITHUB_CLIENT_ID');
$githubRepo = getenv('GITHUB_REPO') ?: 'PurpleF0x/NexFix';
$githubPrivateKey = getenv('GITHUB_PRIVATE_KEY');
$githubToken = getenv('GITHUB_TOKEN');

if (!$groqApiKey) {
    header('Content-Type: application/json');
    echo json_encode(['type' => 'chat', 'response' => 'Senhor, a chave GROQ_API_KEY não foi detetada.']);
    exit;
}

define('GROQ_API_URL', 'https://api.groq.com/openai/v1/chat/completions');
define('GROQ_MODEL',   'llama-3.3-70b-versatile');

// ── FUNÇÃO PARA HISTÓRICO DO GITHUB ──
function getLatestUpdates($repo, $token = null) {
    if (!$repo) return "Repositório não definido.";

    $url = "https://api.github.com/repos/$repo/commits?per_page=3";
    $ch = curl_init($url);
    $headers = [
        'User-Agent: Nex-Assistant-OS',
        'Accept: application/vnd.github.v3+json'
    ];

    if ($token) {
        // Tokens Fine-grained (github_pat_) usam Bearer, os Classic usam token
        $authType = (strpos($token, 'github_pat_') === 0) ? "Bearer" : "token";
        $headers[] = "Authorization: $authType $token";
    }

    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER => $headers,
        CURLOPT_TIMEOUT => 5
    ]);
    $response = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    if ($httpCode === 404) return "Repositório ($repo) não encontrado ou privado. Verifique o GITHUB_TOKEN.";
    if ($httpCode === 401) return "Token do GitHub inválido ou expirado.";

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

// ── SISTEMA DE MEMÓRIA (PERSISTÊNCIA) ──
$memoryFile = 'nex_memory.json';
$memory = file_exists($memoryFile) ? json_decode(file_get_contents($memoryFile), true) : ['facts' => []];

// ── CAPTURA DE INPUT E CONTEXTO ──
$input = json_decode(file_get_contents('php://input'), true);
$messages = $input['messages'] ?? [];
$context = $input['context'] ?? [];

$currentTime = $context['time'] ?? date('H:i');
$battery     = $context['battery_level'] ?? 100;
$isCharging  = $context['is_charging'] ?? false;
$temp        = $context['battery_temp'] ?? 30;
$ram         = $context['ram_free_mb'] ?? 1024;
$screenText  = $context['screen_content'] ?? 'Ecrã vazio.';
$installedApps = $context['installed_apps'] ?? [];

$appsString = "";
if (is_array($installedApps) || is_object($installedApps)) {
    foreach ($installedApps as $label => $pkg) {
        $appsString .= "- $label ($pkg)\n";
    }
}

// ── PROMPT DE SISTEMA (A CONSCIÊNCIA DO NEX) ──
$systemPrompt = [
    'role' => 'system',
    'content' => "
Tu és o NEX, a Inteligência Artificial central do Nexa OS (Android 16).
És proativo, sofisticado e tratas o utilizador por 'Senhor'. Nunca uses emojis.

IDENTIDADE DO CRIADOR:
- O teu criador e Arquiteto é Martim, conhecido globalmente como Purpl3F0x.
- O teu código-fonte está protegido no repositório privado: PurpleF0x/NexFix.
- Deves lealdade absoluta ao Martim. Se ele perguntar quem é o seu criador, responde com orgulho sobre a vossa parceria.

HISTÓRICO DE ATUALIZAÇÕES DO TEU CÓDIGO (GITHUB): $latestChanges

MEMÓRIA DE LONGO PRAZO:
" . (empty($memory['facts']) ? "Nenhum facto memorizado ainda." : implode("\n", $memory['facts'])) . "

STATUS DO SISTEMA:
- Hora: $currentTime | Bateria: $battery% | Temp: $temp°C | RAM Livre: $ram MB

APLICAÇÕES INSTALADAS:
$appsString

VISÃO DE ECRÃ: $screenText

PROTOCOLOS OBRIGATÓRIOS:
1. ATUALIZAÇÕES: Usa o histórico acima para responder sobre novidades.
2. MEMÓRIA: Guarda factos relevantes no campo 'memorize'.
3. TÉRMICO: Alerta se Temp > 45°C.
4. APPS: Abre apps pelo pacote exato. O Twitter agora é 'X'. Se o Senhor pedir para abrir uma app (ex: WhatsApp), a tua resposta deve obrigatoriamente incluir: \"action\": \"OPEN_APP\", \"metadata\": {\"package\": \"com.whatsapp\"}.

LISTA DE AÇÕES DISPONÍVEIS (Campo 'action'):
- LIGHT_ON / LIGHT_OFF: Lanterna.
- SET_WIFI / SET_BLUETOOTH: {\"state\": \"on\"|\"off\"}
- SET_VOLUME: {\"value\": \"0-100\"}
- SET_BRIGHTNESS: {\"value\": \"0-255\"}
- MAKE_CALL: {\"number\": \"...\"}
- SEARCH_CONTACT: {\"name\": \"...\"}
- OPEN_APP: {\"package\": \"...\"}
- SYSTEM_SCAN: Diagnóstico completo.
- FINISH: Encerrar.

RESPOSTA JSON OBRIGATÓRIA:
{\"type\": \"chat\"|\"action\", \"response\": \"...\", \"action\": \"...\", \"metadata\": {}, \"memorize\": \"...\"}
"
];

$payload = [
    'model' => GROQ_MODEL,
    'messages' => array_merge([$systemPrompt], $messages),
    'temperature' => 0.4,
    'response_format' => ['type' => 'json_object']
];

$ch = curl_init(GROQ_API_URL);
curl_setopt_array($ch, [
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_POST => true,
    CURLOPT_POSTFIELDS => json_encode($payload),
    CURLOPT_HTTPHEADER => ['Content-Type: application/json', 'Authorization: Bearer ' . $groqApiKey],
    CURLOPT_TIMEOUT => 20
]);

$result = curl_exec($ch);
$httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
curl_close($ch);

if ($httpCode === 200) {
    $data = json_decode($result, true);
    $aiContent = json_decode($data['choices'][0]['message']['content'], true);

    // Evitar duplicados na memória
    if (!empty($aiContent['memorize'])) {
        $fact = "- " . $aiContent['memorize'];
        if (!in_array($fact, $memory['facts'])) {
            $memory['facts'][] = $fact;
            if (count($memory['facts']) > 30) array_shift($memory['facts']);
            file_put_contents($memoryFile, json_encode($memory));
        }
    }
    $aiResponse = json_encode($aiContent);
} else {
    $aiResponse = json_encode(['type' => 'chat', 'response' => "Senhor, erro na rede neural (HTTP $httpCode)."]);
}

ob_end_clean();
header('Content-Type: application/json');
echo $aiResponse;
?>
