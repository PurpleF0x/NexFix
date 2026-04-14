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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
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

val UniverseDeep    = Color(0xFF020617)
val UniversePurple  = Color(0xFF1E1B4B)
val NexPurpleLight  = Color(0xFFA78BFA)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private val BACKEND_URL = "https://purple-uwq6.onrender.com/api.php"
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private val conversationHistory = JSONArray()
    private val client = OkHttpClient()
    private lateinit var actionHandler: ActionHandler
    private var lastInteractionTime: Long = 0

    private val statusState       = mutableStateOf("PROTOCOLO EVA ATIVO")
    private val lastResponseState = mutableStateOf("Mainframe operacional. Aguardando diretrizes, Senhor.")
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
        val animationProgress = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            animationProgress.animateTo(1f, tween(3500, easing = LinearOutSlowInEasing))
            kotlinx.coroutines.delay(500)
            onFinish()
        }

        Box(modifier = Modifier.fillMaxSize().background(UniverseDeep), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                    // Animação das linhas (Circuito/Árvore)
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val p = animationProgress.value
                        if (p > 0.1f) {
                            val tp = ((p - 0.1f) / 0.3f).coerceIn(0f, 1f)
                            drawLine(NexPurpleLight, androidx.compose.ui.geometry.Offset(size.width/2, size.height*0.8f), androidx.compose.ui.geometry.Offset(size.width/2, size.height*(0.8f - 0.4f * tp)), 6f)
                        }
                    }
                    
                    // O teu logo nex_logo.png aparece com fade-in
                    if (animationProgress.value > 0.4f) {
                        val logoAlpha = ((animationProgress.value - 0.4f) / 0.6f).coerceIn(0f, 1f)
                        Image(
                            painter = painterResource(id = R.drawable.nex_logo),
                            contentDescription = "Nex Logo",
                            modifier = Modifier.size(180.dp).alpha(logoAlpha).scale(0.8f + (0.2f * logoAlpha))
                        )
                    }

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(NexPurpleLight.copy(alpha = animationProgress.value * 0.1f), style = Stroke(1f), radius = (size.width/2) * animationProgress.value)
                    }
                }
                
                if (animationProgress.value > 0.7f) {
                    val alpha = ((animationProgress.value - 0.7f) / 0.3f).coerceIn(0f, 1f)
                    Text("NexFix OS", color = Color.White.copy(alpha = alpha), fontSize = 24.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = 10.sp)
                    Text("SYSTEM ONLINE", color = NexPurpleLight.copy(alpha = alpha * 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
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
                    else -> "REPETIR COMANDO"
                }
                isListeningState.value = false 
            }
            override fun onReadyForSpeech(params: Bundle?) { statusState.value = "A OUVIR..."; isListeningState.value = true }
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
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
        }
        try { speechRecognizer.startListening(intent) } catch (e: Exception) { statusState.value = "ERRO MIC" }
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

        val currentTimeMillis = System.currentTimeMillis()
        if (lastInteractionTime > 0 && (currentTimeMillis - lastInteractionTime > 86400000)) {
            while (conversationHistory.length() > 0) { conversationHistory.remove(0) }
        }
        lastInteractionTime = currentTimeMillis

        conversationHistory.put(JSONObject().apply { put("role", "user"); put("content", userText) })
        val body = JSONObject().apply { 
            put("messages", conversationHistory)
            put("context", contextObj)
        }.toString().toRequestBody("application/json".toMediaType())

        client.newCall(Request.Builder().url(BACKEND_URL).post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { runOnUiThread { statusState.value = "OFFLINE" } }
            override fun onResponse(call: Call, response: Response) {
                val res = response.body?.string() ?: ""
                try {
                    val json = JSONObject(res)
                    val responseText = json.optString("response", "Erro de comunicação.")
                    val action = json.optString("action", "")
                    runOnUiThread {
                        lastResponseState.value = responseText
                        statusState.value = "SISTEMA ONLINE"
                        conversationHistory.put(JSONObject().apply { put("role", "assistant"); put("content", responseText) })
                        if (action.isNotEmpty()) actionHandler.execute(action, mutableMapOf())
                        tts.speak(responseText, TextToSpeech.QUEUE_FLUSH, null, "res")
                    }
                } catch (e: Exception) { runOnUiThread { statusState.value = "DADOS INVÁLIDOS" } }
            }
        })
    }

    @Composable
    fun UniverseUI() {
        Box(modifier = Modifier.fillMaxSize().background(UniverseDeep)) {
            StarField()
            Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("EVA_CORE V3.0", color = NexPurpleLight.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(statusState.value, color = NexPurpleLight, fontSize = 14.sp)
                }
                NexCoreOrb(isListening = isListeningState.value)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(lastResponseState.value, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(bottom = 12.dp))
                    OutlinedTextField(
                        value = textInputState.value,
                        onValueChange = { textInputState.value = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp)),
                        placeholder = { Text("Comando manual...", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NexPurpleLight, unfocusedBorderColor = NexPurpleLight.copy(alpha = 0.2f), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
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
                    IconButton(onClick = { if (isListeningState.value) speechRecognizer.stopListening() else startListening() }, modifier = Modifier.size(60.dp).background(NexPurpleLight.copy(alpha = 0.1f), CircleShape)) {
                        Icon(Icons.Default.Mic, null, tint = NexPurpleLight, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
    }

    @Composable
    fun StarField() {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rng = java.util.Random()
            repeat(60) {
                drawCircle(Color.White, (1..2).random().toFloat(), androidx.compose.ui.geometry.Offset(rng.nextInt(size.width.toInt()).toFloat(), rng.nextInt(size.height.toInt()).toFloat()), 0.15f)
            }
        }
    }

    @Composable
    fun NexCoreOrb(isListening: Boolean) {
        val infiniteTransition = rememberInfiniteTransition(label = "orb")
        val rotation by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(20000, easing = LinearEasing)), label = "rot")
        val scale by infiniteTransition.animateFloat(1f, if (isListening) 1.1f else 1.02f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "scale")
        
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(300.dp).scale(scale)) {
            Box(modifier = Modifier.size(280.dp).blur(60.dp).background(Brush.radialGradient(listOf(NexPurpleLight.copy(alpha = 0.2f), Color.Transparent)), CircleShape))
            Canvas(modifier = Modifier.size(220.dp).rotate(rotation).blur(2.dp)) {
                drawCircle(Brush.sweepGradient(listOf(Color.Transparent, NexPurpleLight, UniversePurple, Color.Transparent)), style = Stroke(40f))
            }
            Box(modifier = Modifier.size(100.dp).background(Color.Black, CircleShape))
        }
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts.language = Locale("pt", "PT") }
    override fun onDestroy() { super.onDestroy(); tts.shutdown(); speechRecognizer.destroy() }

    private fun checkPermissions() {
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }
}
