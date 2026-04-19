package com.nex.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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

val UniverseDeep    = Color(0xFF010208)
val NexPurpleLight  = Color(0xFFA78BFA)
val EvaEyeGold      = Color(0xFFFFD700)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private val BACKEND_URL = "https://purple-uwq6.onrender.com/api.php"
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private var conversationHistory = JSONArray()
    private val client = OkHttpClient()
    private lateinit var actionHandler: ActionHandler
    private var lastInteractionTime: Long = 0

    private val statusState       = mutableStateOf("EVA OS: ADMIN_MODE")
    private val lastResponseState = mutableStateOf("Sincronização completa. Aguardando comando, Senhor.")
    private val isListeningState  = mutableStateOf(false)
    private val textInputState    = mutableStateOf("")
    private val showSplash        = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()
        actionHandler = ActionHandler(this)
        ContextCompat.startForegroundService(this, Intent(this, NexService::class.java))
        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()

        setContent {
            NexTheme {
                if (showSplash.value) {
                    EvaSplashScreen(onFinish = { showSplash.value = false })
                } else {
                    UniverseUI()
                }
            }
        }
    }

    @Composable
    fun EvaSplashScreen(onFinish: () -> Unit) {
        val anim = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            anim.animateTo(1f, tween(3000, easing = LinearOutSlowInEasing))
            kotlinx.coroutines.delay(800)
            onFinish()
        }

        Box(modifier = Modifier.fillMaxSize().background(UniverseDeep), contentAlignment = Alignment.Center) {
            val p = anim.value
            
            if (p > 0.1f) {
                val logoAlpha = ((p - 0.1f) / 0.5f).coerceIn(0f, 1f)
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = null,
                    modifier = Modifier.size(200.dp).alpha(logoAlpha).scale(0.85f + (0.15f * logoAlpha)),
                    contentScale = ContentScale.Inside
                )
            }

            if (p > 0.6f) {
                val textAlpha = ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)
                Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("EVA OS", color = Color.White.copy(alpha = textAlpha), fontSize = 32.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = 12.sp)
                    Text("ADMIN PROTOCOL v3.5", color = NexPurpleLight.copy(alpha = textAlpha * 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                }
            }
        }
    }

    @Composable
    fun UniverseUI() {
        Box(modifier = Modifier.fillMaxSize().background(UniverseDeep)) {
            StarField()
            Column(modifier = Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("MAIN_OS: EVA_ADMIN", color = NexPurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(statusState.value, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                }
                NexCoreOrb(isListening = isListeningState.value)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), color = Color.White.copy(alpha = 0.04f), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, NexPurpleLight.copy(alpha = 0.15f))) {
                        Text(lastResponseState.value, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(16.dp))
                    }
                    OutlinedTextField(
                        value = textInputState.value,
                        onValueChange = { textInputState.value = it },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),
                        placeholder = { Text("Introduzir diretriz manual...", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NexPurpleLight, unfocusedBorderColor = NexPurpleLight.copy(alpha = 0.2f), focusedTextColor = Color.White),
                        trailingIcon = {
                            if (textInputState.value.isNotEmpty()) {
                                IconButton(onClick = { 
                                    val t = textInputState.value
                                    textInputState.value = ""
                                    sendToBackend(t)
                                }) { Icon(Icons.AutoMirrored.Filled.Send, null, tint = NexPurpleLight) }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    IconButton(onClick = { if (isListeningState.value) speechRecognizer.stopListening() else startListening() }, modifier = Modifier.size(68.dp).background(NexPurpleLight.copy(alpha = 0.1f), CircleShape).border(1.dp, NexPurpleLight.copy(alpha = 0.3f), CircleShape)) {
                        Icon(Icons.Default.Mic, null, tint = if(isListeningState.value) Color.Cyan else NexPurpleLight, modifier = Modifier.size(34.dp))
                    }
                }
            }
        }
    }

    @Composable
    fun NexCoreOrb(isListening: Boolean) {
        val infiniteTransition = rememberInfiniteTransition()
        val rotation by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(25000, easing = LinearEasing)))
        val pulse by infiniteTransition.animateFloat(1f, if (isListening) 1.12f else 1.05f, infiniteRepeatable(tween(2000), RepeatMode.Reverse))

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(320.dp).scale(pulse)) {
            Box(modifier = Modifier.size(270.dp).blur(80.dp).background(Brush.radialGradient(listOf(NexPurpleLight.copy(alpha = 0.25f), Color.Transparent)), CircleShape))
            Canvas(modifier = Modifier.size(280.dp).rotate(rotation)) {
                drawCircle(Brush.sweepGradient(listOf(Color.Transparent, NexPurpleLight, Color.Transparent)), style = Stroke(width = 30f))
            }
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(150.dp).alpha(if(isListening) 1f else 0.85f),
                contentScale = ContentScale.Inside
            )
        }
    }

    @Composable
    fun StarField() {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rng = java.util.Random(1337)
            repeat(80) {
                drawCircle(Color.White, radius = (1..3).random().toFloat(), center = Offset(rng.nextInt(size.width.toInt()).toFloat(), rng.nextInt(size.height.toInt()).toFloat()), alpha = rng.nextFloat() * 0.4f)
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
            override fun onError(error: Int) { isListeningState.value = false }
            override fun onReadyForSpeech(params: Bundle?) { isListeningState.value = true }
            override fun onEndOfSpeech() { isListeningState.value = false }
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
        }
        speechRecognizer.startListening(intent)
    }

    private fun sendToBackend(userText: String) {
        runOnUiThread { statusState.value = "ANALYZING..." }
        
        try {
            // Adicionar mensagem do utilizador ao histórico
            val userMsg = JSONObject().apply { 
                put("role", "user")
                put("content", userText)
            }
            conversationHistory.put(userMsg)

            // Criar o payload final com o histórico completo
            val payload = JSONObject().apply {
                put("messages", conversationHistory)
                put("context", JSONObject().apply {
                    put("time", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
                    put("battery_level", 100) // Placeholder
                })
            }

            val body = payload.toString().toRequestBody("application/json".toMediaType())

            client.newCall(Request.Builder().url(BACKEND_URL).post(body).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { 
                    runOnUiThread { 
                        statusState.value = "OFFLINE"
                        lastResponseState.value = "Senhor, perdi a conexão com o núcleo."
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    val rawRes = response.body?.string() ?: ""
                    val code = response.code
                    
                    try {
                        if (!response.isSuccessful) {
                            runOnUiThread {
                                lastResponseState.value = "Erro de Servidor (HTTP $code). Verifique o backend."
                                statusState.value = "ERRO_HTTP"
                            }
                            return
                        }

                        val json = JSONObject(rawRes)
                        val responseText = json.optString("response", "Erro: O servidor não enviou 'response'.")
                        
                        runOnUiThread {
                            statusState.value = "EVA_ONLINE"
                            lastResponseState.value = responseText
                            
                            val assistantMsg = JSONObject().apply { 
                                put("role", "assistant")
                                put("content", responseText)
                            }
                            conversationHistory.put(assistantMsg)
                            
                            if (conversationHistory.length() > 10) {
                                val newHistory = JSONArray()
                                for (i in (conversationHistory.length() - 10) until conversationHistory.length()) {
                                    newHistory.put(conversationHistory.get(i))
                                }
                                conversationHistory = newHistory
                            }

                            tts.speak(responseText, TextToSpeech.QUEUE_FLUSH, null, "res")
                        }
                    } catch (e: Exception) {
                        runOnUiThread { 
                            lastResponseState.value = "Erro de Dados: Resposta Inválida. Verifique o console do PHP."
                            statusState.value = "ERRO_PARSE"
                            // Log detalhado para o Logcat se precisares
                            android.util.Log.e("EVA_ERROR", "Raw response: $rawRes", e)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            runOnUiThread { lastResponseState.value = "Erro interno: ${e.message}" }
        }
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts.language = Locale("pt", "PT") }
    override fun onDestroy() { super.onDestroy(); tts.shutdown(); speechRecognizer.destroy() }

    private fun checkPermissions() {
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions, 101)
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }
}
