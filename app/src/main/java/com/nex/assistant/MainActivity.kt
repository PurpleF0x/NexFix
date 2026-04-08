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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
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

val UniverseDeep    = Color(0xFF020617)
val UniversePurple  = Color(0xFF1E1B4B) // Azul Noite Profundo
val NexPurpleLight  = Color(0xFFA78BFA)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private val BACKEND_URL = "https://purple-uwq6.onrender.com/api.php"
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private val conversationHistory = JSONArray()
    private val client = OkHttpClient()
    private lateinit var actionHandler: ActionHandler

    private val statusState       = mutableStateOf("SISTEMA ONLINE")
    private val lastResponseState = mutableStateOf("Pronto para o comando, Senhor Martim.")
    private val isListeningState  = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()
        actionHandler = ActionHandler(this)
        ContextCompat.startForegroundService(this, Intent(this, NexService::class.java))
        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()

        setContent {
            NexTheme {
                UniverseUI()
            }
        }
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                statusState.value = "A PROCESSAR..."
                sendToBackend(text)
            }
            override fun onError(error: Int) { 
                statusState.value = when(error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "NÃO OUVI"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SILÊNCIO..."
                    SpeechRecognizer.ERROR_NETWORK -> "ERRO CONEXÃO"
                    else -> "REPETIR COMANDO"
                }
                isListeningState.value = false 
            }
            override fun onReadyForSpeech(params: Bundle?) { 
                statusState.value = "A OUVIR..."
                isListeningState.value = true 
            }
            override fun onEndOfSpeech() { isListeningState.value = false }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        tts.stop()
        isListeningState.value = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Como posso ajudar?")
            // Melhora a sensibilidade e tempo de espera
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        }
        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            statusState.value = "ERRO MIC"
        }
    }

    private fun sendToBackend(userText: String) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val batteryStatus = try { registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch(e: Exception) { null }
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        
        val contextObj = JSONObject().apply {
            put("time", sdf.format(Date()))
            put("battery_level", level)
            put("screen_content", NexAccessibilityService.lastScreenContent.take(500)) 
        }

        conversationHistory.put(JSONObject().apply { put("role", "user"); put("content", userText) })
        val body = JSONObject().apply { 
            put("messages", conversationHistory)
            put("context", contextObj)
        }.toString().toRequestBody("application/json".toMediaType())

        client.newCall(Request.Builder().url(BACKEND_URL).post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { runOnUiThread { statusState.value = "OFFLINE" } }
            override fun onResponse(call: Call, response: Response) {
                val res = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    runOnUiThread { statusState.value = "ERRO SERVIDOR" }
                    return
                }
                try {
                    val json = JSONObject(res)
                    val responseText = json.optString("response", "Sem resposta do núcleo.")
                    val action = json.optString("action", "")
                    
                    runOnUiThread {
                        lastResponseState.value = responseText
                        statusState.value = "SISTEMA ONLINE"
                        conversationHistory.put(JSONObject().apply { put("role", "assistant"); put("content", responseText) })
                        
                        if (action.isNotEmpty()) {
                            val metadata = mutableMapOf<String, String>()
                            json.optJSONObject("metadata")?.let { meta ->
                                meta.keys().forEach { key -> metadata[key] = meta.getString(key) }
                            }
                            actionHandler.execute(action, metadata)
                        }
                        tts.speak(responseText, TextToSpeech.QUEUE_FLUSH, null, "res")
                    }
                } catch (e: Exception) { 
                    runOnUiThread { 
                        statusState.value = "DADOS INVÁLIDOS"
                        // Log para depuração se necessário: android.util.Log.e("NEX", "Res: $res")
                    } 
                }
            }
        })
    }

    @Composable
    fun UniverseUI() {
        Box(modifier = Modifier.fillMaxSize().background(UniverseDeep)) {
            StarField()
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("NEXA_CORE V2.0", color = NexPurpleLight.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(statusState.value, color = NexPurpleLight, fontSize = 14.sp)
                }

                NexCoreOrb(isListening = isListeningState.value)

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(lastResponseState.value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(bottom = 20.dp))
                    IconButton(onClick = { if (isListeningState.value) speechRecognizer.stopListening() else startListening() }, modifier = Modifier.size(64.dp).background(NexPurpleLight.copy(alpha = 0.1f), CircleShape)) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = NexPurpleLight)
                    }
                }
            }
        }
    }

    @Composable
    fun StarField() {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rng = java.util.Random()
            repeat(80) {
                drawCircle(
                    color = Color.White,
                    radius = (1..2).random().toFloat(),
                    center = androidx.compose.ui.geometry.Offset(
                        rng.nextInt(size.width.toInt()).toFloat(),
                        rng.nextInt(size.height.toInt()).toFloat()
                    ),
                    alpha = 0.05f + rng.nextFloat() * (0.3f - 0.05f)
                )
            }
        }
    }

    @Composable
    fun NexCoreOrb(isListening: Boolean) {
        val infiniteTransition = rememberInfiniteTransition(label = "orb")
        
        // Rotação do disco principal (mais lento e majestoso)
        val rotationAccretion by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(animation = tween(25000, easing = LinearEasing)), label = "rotationAccretion"
        )
        
        // Rotação inversa para a camada de poeira estelar
        val rotationDust by infiniteTransition.animateFloat(
            initialValue = 360f, targetValue = 0f,
            animationSpec = infiniteRepeatable(animation = tween(40000, easing = LinearEasing)), label = "rotationDust"
        )

        // Pulsação orgânica
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f, targetValue = if (isListening) 1.12f else 1.02f,
            animationSpec = infiniteRepeatable(animation = tween(2000), repeatMode = RepeatMode.Reverse), label = "scale"
        )
        
        val photonAlpha by infiniteTransition.animateFloat(
            initialValue = 0.6f, targetValue = 1.0f,
            animationSpec = infiniteRepeatable(animation = tween(1500), repeatMode = RepeatMode.Reverse), label = "photonAlpha"
        )

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(300.dp).scale(scale)) {
            
            // 1. Brilho de Fundo (Nebulosa/Glow Violeta)
            Box(modifier = Modifier.size(280.dp).blur(70.dp).background(
                Brush.radialGradient(listOf(NexPurpleLight.copy(alpha = 0.25f), Color.Transparent)), CircleShape)
            )

            // 2. Disco de Acreção (O "Swirl" de matéria)
            Canvas(modifier = Modifier.size(240.dp).rotate(rotationAccretion).blur(2.dp)) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            NexPurpleLight.copy(alpha = 0.7f),
                            UniversePurple,
                            NexPurpleLight.copy(alpha = 0.3f),
                            Color.Transparent
                        )
                    ),
                    style = Stroke(width = 45f)
                )
            }

            // 2.1 Camada Interna (Poeira Estelar com rotação inversa)
            Canvas(modifier = Modifier.size(190.dp).rotate(rotationDust).blur(1.dp)) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.2f),
                            NexPurpleLight.copy(alpha = 0.4f),
                            Color.Transparent
                        )
                    ),
                    style = Stroke(width = 10f)
                )
            }

            // 3. Photon Ring (Anel de luz branca intensa na borda)
            Box(modifier = Modifier.size(102.dp).border(2.dp, Color.White.copy(alpha = 0.8f * photonAlpha), CircleShape).blur(1.dp))
            Box(modifier = Modifier.size(105.dp).border(5.dp, NexPurpleLight.copy(alpha = 0.4f), CircleShape).blur(4.dp))

            // 4. O Horizonte de Eventos (O Buraco Negro)
            Box(modifier = Modifier.size(100.dp).background(Color.Black, CircleShape))
            
            // 5. Sombra interna para dar volume
            Box(modifier = Modifier.size(100.dp).border(8.dp, 
                Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))), CircleShape)
            )
        }
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts.language = Locale("pt", "PT") }
    override fun onDestroy() { super.onDestroy(); tts.shutdown(); speechRecognizer.destroy() }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 101)
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }
}
