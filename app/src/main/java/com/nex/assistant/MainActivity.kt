package com.nex.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nex.assistant.ui.theme.NexTheme
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val NexBlack       = Color(0xFF0A0A0F)
val NexPurple      = Color(0xFF7C3AED)
val NexPurpleLight = Color(0xFFA78BFA)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private val BACKEND_URL = "https://purple-uwq6.onrender.com/api.php"
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private val conversationHistory = JSONArray()
    private val client = OkHttpClient()
    private lateinit var actionHandler: ActionHandler

    private val statusState       = mutableStateOf("PRONTO")
    private val lastResponseState = mutableStateOf("Olá, sou o Nex.")
    private val isListeningState  = mutableStateOf(false)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()
        
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraId = cameraManager.cameraIdList.firstOrNull()
        actionHandler = ActionHandler(this)
        
        ContextCompat.startForegroundService(this, Intent(this, NexService::class.java))
        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()

        if (intent.getBooleanExtra("triggered_by_voice", false)) {
            statusState.value = "A OUVIR..."
            isListeningState.value = true
            handler.postDelayed({ startListening() }, 500)
        }

        setContent {
            NexTheme {
                AssistantOverlay()
            }
        }
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                statusState.value = "A PENSAR..."
                sendToBackend(text)
            }
            override fun onError(error: Int) { 
                statusState.value = "PRONTO"
                isListeningState.value = false 
            }
            override fun onReadyForSpeech(params: Bundle?) { 
                statusState.value = "A OUVIR..."
                isListeningState.value = true 
            }
            override fun onEndOfSpeech() { 
                statusState.value = "A PROCESSAR..."
                isListeningState.value = false 
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        tts.stop()
        statusState.value = "A OUVIR..."
        isListeningState.value = true
        tts.speak("Sim?", TextToSpeech.QUEUE_FLUSH, null, "manual_wake")
        
        handler.postDelayed({
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-PT")
            }
            speechRecognizer.startListening(intent)
        }, 600)
    }

    private fun stopListening() { speechRecognizer.stopListening() }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        
        // Android 13+ exige permissão explícita para notificações
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        // Android 14+ exige permissão de microfone específica para serviços de primeiro plano
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        }

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)

        // 2. Pedir Permissão: Alterar Definições de Sistema (Volume/Brilho)
        if (!Settings.System.canWrite(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        // 3. Pedir Permissão: Sobreposição de Ecrã (Orb HUD)
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun sendToBackend(userText: String) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        
        // Obter estado da bateria e memória
        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val isCharging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
        val temperature = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        
        val actManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val availableRam = memInfo.availMem / (1024 * 1024)

        // --- LISTAR APPS INSTALADAS ---
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        val pkgManager = packageManager
        val appsList = pkgManager.queryIntentActivities(mainIntent, 0)
        val installedApps = JSONObject()
        appsList.forEach { app ->
            val label = app.loadLabel(pkgManager).toString()
            val packageName = app.activityInfo.packageName
            installedApps.put(label, packageName)
        }

        val contextObject = JSONObject().apply {
            put("time", sdf.format(Date()))
            put("device", Build.MODEL)
            put("battery_level", level)
            put("battery_temp", temperature)
            put("is_charging", isCharging)
            put("ram_free_mb", availableRam)
            put("installed_apps", installedApps)
            put("screen_content", NexAccessibilityService.lastScreenContent)
        }

        conversationHistory.put(JSONObject().apply { put("role", "user"); put("content", userText) })
        val body = JSONObject().apply { 
            put("messages", conversationHistory)
            put("context", contextObject)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(BACKEND_URL).post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { statusState.value = "ERRO" }
            }
            override fun onResponse(call: Call, response: Response) {
                var res = response.body?.string() ?: ""
                try {
                    // Tentar encontrar o início e fim do JSON caso a IA tenha enviado texto extra
                    val startIndex = res.indexOf("{")
                    val endIndex = res.lastIndexOf("}")
                    if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                        res = res.substring(startIndex, endIndex + 1)
                    }

                    val json = JSONObject(res)
                    val type = json.optString("type", "chat")
                    val responseText = json.optString("response", "")
                    val action = if (json.has("action")) json.getString("action") else null
                    
                    val metadata = mutableMapOf<String, String>()
                    json.optJSONObject("metadata")?.let { metaJson ->
                        metaJson.keys().forEach { key ->
                            metadata[key] = metaJson.getString(key)
                        }
                    }

                    runOnUiThread {
                        lastResponseState.value = responseText
                        statusState.value = "PRONTO"
                        
                        conversationHistory.put(JSONObject().apply { 
                            put("role", "assistant")
                            put("content", responseText) 
                        })

                        if (type == "action" && action != null) {
                            actionHandler.execute(action, metadata)
                        }
                        
                        if (responseText.isNotEmpty()) {
                            // --- VOLUME ALTO (CANAL DE MÚSICA) ---
                            val params = Bundle()
                            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
                            tts.speak(responseText, TextToSpeech.QUEUE_FLUSH, params, "res")
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { 
                        lastResponseState.value = "Erro no Protocolo JSON: $res"
                        statusState.value = "ERRO" 
                    }
                }
            }
        })
    }

    @Composable
    fun AssistantOverlay() {
        Box(modifier = Modifier.fillMaxSize().background(Color.Transparent).clickable { finish() }, contentAlignment = Alignment.BottomCenter) {
            
            // Efeito HUD de Dados (JARVIS Style)
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("SYS_CORE: ACTIVE", color = NexPurpleLight.copy(alpha = 0.3f), fontSize = 10.sp)
                Text("NET_LINK: STABLE", color = NexPurpleLight.copy(alpha = 0.3f), fontSize = 10.sp)
                Text("MODEL: " + Build.MODEL, color = NexPurpleLight.copy(alpha = 0.3f), fontSize = 10.sp)
            }

            if (isListeningState.value) {
                ListeningBorderEffect()
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    .background(NexBlack.copy(alpha = 0.90f))
                    .clickable(enabled = false) {}
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(modifier = Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.3f)))
                Spacer(Modifier.height(20.dp))
                
                Text(statusState.value, color = NexPurpleLight, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
                Spacer(Modifier.height(12.dp))
                
                Text(lastResponseState.value, color = Color.White, fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.height(32.dp))
                
                NexOrb(isListening = isListeningState.value, onPressStart = { startListening() }, onPressEnd = { stopListening() })
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    @Composable
    fun ListeningBorderEffect() {
        val infiniteTransition = rememberInfiniteTransition(label = "border")
        val offset by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1000f,
            animationSpec = infiniteRepeatable(animation = tween(2000, easing = LinearEasing), repeatMode = RepeatMode.Restart), label = "offset"
        )

        Canvas(modifier = Modifier.fillMaxSize().blur(20.dp)) {
            val brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, NexPurple, Color.Black, NexPurple, Color.Transparent),
                start = androidx.compose.ui.geometry.Offset(offset, 0f),
                end = androidx.compose.ui.geometry.Offset(offset + 500f, 1000f)
            )
            drawRect(brush = brush, alpha = 0.6f)
        }
    }

    @Composable
    fun NexOrb(isListening: Boolean, onPressStart: () -> Unit, onPressEnd: () -> Unit) {
        val infiniteTransition = rememberInfiniteTransition(label = "orb")
        val pulse by infiniteTransition.animateFloat(
            initialValue = 1f, targetValue = if (isListening) 1.2f else 1.05f,
            animationSpec = infiniteRepeatable(animation = tween(1000), repeatMode = RepeatMode.Reverse), label = "pulse"
        )
        Box(
            modifier = Modifier.size(80.dp).scale(pulse).clip(CircleShape).background(NexPurple)
                .pointerInput(Unit) { detectTapGestures(onPress = { onPressStart(); try { awaitRelease() } finally { onPressEnd() } }) },
            contentAlignment = Alignment.Center
        ) {
            // Se tiveres uma imagem chamada 'logo_nex' na pasta drawable, ela aparece aqui
            // Caso contrário, mostra o ícone de microfone como fallback
            val imageResId = resources.getIdentifier("logo_nex", "drawable", packageName)
            if (imageResId != 0) {
                Image(
                    painter = painterResource(id = imageResId),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("pt", "PT")
        }
    }

    override fun onDestroy() { super.onDestroy(); tts.shutdown(); speechRecognizer.destroy() }
}
