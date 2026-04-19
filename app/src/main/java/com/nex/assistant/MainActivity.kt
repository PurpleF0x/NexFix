package com.nex.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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

val UniverseDeep    = Color(0xFF010208)
val NexPurpleLight  = Color(0xFFA78BFA)
val NexCyan         = Color(0xFF00F2FF)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private val BACKEND_URL = "https://purple-uwq6.onrender.com/api.php"
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private var conversationHistory = JSONArray()
    private val client = OkHttpClient()
    private lateinit var actionHandler: ActionHandler

    private val statusState       = mutableStateOf("NEX_CORE: STANDBY")
    private val lastResponseState = mutableStateOf("Sistemas prontos. Aguardando comando, Senhor.")
    private val isListeningState  = mutableStateOf(false)
    private val batteryState      = mutableStateOf("--%")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()
        actionHandler = ActionHandler(this)
        updateBatteryInfo()
        
        ContextCompat.startForegroundService(this, Intent(this, NexService::class.java))
        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()

        setContent {
            NexTheme {
                UniverseUI()
            }
        }
    }

    private fun updateBatteryInfo() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        batteryState.value = "$level%"
    }

    @Composable
    fun UniverseUI() {
        var textInput by remember { mutableStateOf("") }
        val interactionSource = remember { MutableInteractionSource() }
        
        Box(modifier = Modifier.fillMaxSize().background(UniverseDeep)) {
            StarField()
            
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // HUD Superior Avançado
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("NEX_OS v5.5", color = NexPurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text(statusState.value, color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("PWR: ${batteryState.value}", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                        Text("LINK: SECURE", color = NexCyan.copy(alpha = 0.5f), fontSize = 8.sp)
                    }
                }

                // Orb Central com Ondas de Áudio
                NexAdvancedOrb(
                    isListening = isListeningState.value,
                    onClick = { 
                        vibrate(40)
                        if (isListeningState.value) speechRecognizer.stopListening() else startListening() 
                    }
                )

                // Bloco de Interação
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    // Resposta da IA
                    Box(modifier = Modifier.heightIn(min = 60.dp, max = 200.dp).padding(bottom = 20.dp)) {
                        Text(
                            lastResponseState.value,
                            color = Color.White,
                            fontSize = 17.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.alpha(if(isListeningState.value) 0.4f else 1f),
                            lineHeight = 24.sp
                        )
                    }

                    // Console de Comando (Input + Mic)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(Color.White.copy(alpha = 0.04f), CircleShape)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { 
                                vibrate(30)
                                if (isListeningState.value) speechRecognizer.stopListening() else startListening() 
                            },
                            modifier = Modifier.size(42.dp).background(if(isListeningState.value) NexCyan.copy(alpha = 0.1f) else Color.Transparent, CircleShape)
                        ) {
                            Icon(
                                if(isListeningState.value) Icons.Default.Mic else Icons.Default.MicNone, 
                                null, 
                                tint = if(isListeningState.value) NexCyan else NexPurpleLight
                            )
                        }

                        Box(modifier = Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                            if (textInput.isEmpty()) {
                                Text("Diretriz manual...", color = Color.White.copy(alpha = 0.2f), fontSize = 14.sp)
                            }
                            BasicTextField(
                                value = textInput,
                                onValueChange = { textInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                                cursorBrush = SolidColor(NexCyan),
                                singleLine = true
                            )
                        }

                        if (textInput.isNotEmpty()) {
                            IconButton(
                                onClick = { 
                                    val t = textInput
                                    textInput = ""
                                    sendToBackend(t) 
                                },
                                modifier = Modifier.size(42.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, null, tint = NexCyan)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }

    @Composable
    fun NexAdvancedOrb(isListening: Boolean, onClick: () -> Unit) {
        val infiniteTransition = rememberInfiniteTransition()
        
        // Rotação do anel externo
        val rotation by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(20000, easing = LinearEasing)))
        
        // Pulsação base
        val baseScale by infiniteTransition.animateFloat(1f, 1.05f, infiniteRepeatable(tween(2500), RepeatMode.Reverse))
        
        // Ondas de Áudio (apenas quando ouve)
        val waveAlpha by infiniteTransition.animateFloat(0.6f, 0f, infiniteRepeatable(tween(1500)))
        val waveScale by infiniteTransition.animateFloat(1f, 1.8f, infiniteRepeatable(tween(1500)))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(320.dp)
                .scale(baseScale)
                .clickable(interactionSource = null, indication = null, onClick = onClick)
        ) {
            // Ondas de expansão (Radar effect)
            if (isListening) {
                Box(modifier = Modifier.size(240.dp).scale(waveScale).alpha(waveAlpha).background(NexCyan.copy(alpha = 0.2f), CircleShape))
                Box(modifier = Modifier.size(240.dp).scale(waveScale * 0.7f).alpha(waveAlpha).background(NexCyan.copy(alpha = 0.1f), CircleShape))
            }

            // Glow de fundo
            Box(modifier = Modifier.size(260.dp).blur(80.dp).background(Brush.radialGradient(listOf((if(isListening) NexCyan else NexPurpleLight).copy(alpha = 0.2f), Color.Transparent)), CircleShape))
            
            // Anel de progresso / Rotação
            Canvas(modifier = Modifier.size(280.dp).rotate(rotation)) {
                drawCircle(
                    Brush.sweepGradient(listOf(Color.Transparent, if(isListening) NexCyan else NexPurpleLight, Color.Transparent)),
                    style = Stroke(width = if(isListening) 12f else 6f)
                )
            }

            // Logo Central
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(140.dp).alpha(if(isListening) 1f else 0.7f),
                contentScale = ContentScale.Inside
            )
        }
    }

    @Composable
    fun StarField() {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rng = java.util.Random(42)
            repeat(150) {
                drawCircle(Color.White, radius = (1..2).random().toFloat(), center = Offset(rng.nextInt(size.width.toInt()).toFloat(), rng.nextInt(size.height.toInt()).toFloat()), alpha = rng.nextFloat() * 0.4f)
            }
        }
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                sendToBackend(text)
            }
            override fun onError(error: Int) { 
                isListeningState.value = false 
                statusState.value = "CORE_READY"
            }
            override fun onReadyForSpeech(params: Bundle?) { 
                isListeningState.value = true 
                statusState.value = "LISTENING..."
                vibrate(20)
            }
            override fun onEndOfSpeech() { 
                isListeningState.value = false 
                statusState.value = "PROCESSING..."
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-PT")
        }
        speechRecognizer.startListening(intent)
    }

    private fun sendToBackend(userText: String) {
        statusState.value = "ANALYZING_CORE..."
        updateBatteryInfo()
        
        val userMsg = JSONObject().apply { put("role", "user"); put("content", userText) }
        conversationHistory.put(userMsg)

        val body = JSONObject().apply {
            put("messages", conversationHistory)
            put("context", JSONObject().apply {
                put("time", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
                put("battery", batteryState.value)
            })
        }.toString().toRequestBody("application/json".toMediaType())

        client.newCall(Request.Builder().url(BACKEND_URL).post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, _e: IOException) {
                runOnUiThread {
                    statusState.value = "CONNECTION_LOST"
                    lastResponseState.value = "Erro de rede. Núcleo inacessível."
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val resStr = response.body?.string() ?: ""
                try {
                    val json = JSONObject(resStr)
                    val responseText = json.optString("response", "Sistemas estáveis.")
                    
                    runOnUiThread {
                        statusState.value = "CORE_READY"
                        lastResponseState.value = responseText
                        conversationHistory.put(JSONObject().apply { put("role", "assistant"); put("content", responseText) })
                        
                        // Execução de Ações
                        val action = json.optString("action", "")
                        val metadata = json.optJSONObject("metadata")
                        val metaMap = mutableMapOf<String, String>()
                        metadata?.keys()?.forEach { metaMap[it] = metadata.getString(it) }
                        actionHandler.execute(action, metaMap)

                        tts.speak(responseText, TextToSpeech.QUEUE_FLUSH, null, "res")
                    }
                } catch (e: Exception) { runOnUiThread { statusState.value = "CORE_READY" } }
            }
        })
    }

    private fun vibrate(ms: Long) {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts.language = Locale("pt", "PT") }
    override fun onDestroy() { super.onDestroy(); tts.shutdown(); speechRecognizer.destroy() }

    private fun checkPermissions() {
        val perms = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE)
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, 101)
        }
    }
}
