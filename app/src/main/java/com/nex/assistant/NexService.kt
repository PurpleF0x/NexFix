package com.nex.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class NexService : Service(), TextToSpeech.OnInitListener {
    
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var actionHandler: ActionHandler
    private val client = OkHttpClient()
    private val conversationHistory = JSONArray()
    private val BACKEND_URL = "https://purple-uwq6.onrender.com/api.php"

    private var isListening = false
    private var isWaitingForCommand = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastBatteryAlertLevel = -1

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (level != lastBatteryAlertLevel && (level == 20 || level == 10 || level == 5) && !isCharging) {
                lastBatteryAlertLevel = level
                speakHigh("Senhor, os sistemas de energia estão em $level por cento.", TextToSpeech.QUEUE_FLUSH, "battery_alert")
            }
            if (isCharging) lastBatteryAlertLevel = -1
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, buildNotification(), type)
        } else {
            startForeground(1, buildNotification())
        }

        actionHandler = ActionHandler(this)
        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase() ?: ""
                
                if (!isWaitingForCommand) {
                    val triggers = listOf("nex", "nexe", "ei", "hey", "olá", "assistente")
                    if (text.split(" ").any { it in triggers }) {
                        isWaitingForCommand = true
                        vibrate(70)
                        speakHigh("Sim, Senhor?", TextToSpeech.QUEUE_FLUSH, "wake")

                        // --- PROTOCOLO DE PROJEÇÃO (RESTAURADO) ---
                        val launchIntent = Intent(this@NexService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            putExtra("triggered_by_voice", true)
                        }
                        
                        // Tenta enviar o intent para acordar a UI mesmo com o ecrã bloqueado
                        val pendingIntent = PendingIntent.getActivity(this@NexService, 0, launchIntent, 
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        try { pendingIntent.send() } catch (e: Exception) { startActivity(launchIntent) }

                        handler.postDelayed({ startListening() }, 1000)
                    } else {
                        handler.postDelayed({ startListening() }, 500)
                    }
                } else {
                    isWaitingForCommand = false
                    sendToBackend(text)
                }
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                isWaitingForCommand = false
                // Aumentamos drasticamente o delay em caso de erro para poupar a API (429)
                val delay = when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 5000L
                    SpeechRecognizer.ERROR_NO_MATCH -> 2000L
                    else -> 3000L
                }
                handler.postDelayed({ startListening() }, delay)
            }

            override fun onReadyForSpeech(params: Bundle?) { isListening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        startListening()
    }

    private fun sendToBackend(userText: String) {
        // 1. Filtragem por Tamanho (Ignora ruídos curtos)
        if (userText.length < 3) {
            handler.post { startListening() }
            return
        }

        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        
        // 2. Envio Inteligente de Apps (Poupa Tokens)
        val keywords = listOf("abre", "lança", "executa", "aplicação", "app", "whatsapp", "calculadora", "google", "youtube")
        val needsApps = userText.split(" ").any { it in keywords }
        
        val installedApps = JSONObject()
        if (needsApps || conversationHistory.length() <= 1) {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val appsList = packageManager.queryIntentActivities(mainIntent, 0)
            appsList.forEach { installedApps.put(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
        }

        val contextObj = JSONObject().apply {
            put("time", sdf.format(Date()))
            put("battery_level", level)
            if (installedApps.length() > 0) put("installed_apps", installedApps)
            put("device", Build.MODEL)
        }

        // Adicionar ao histórico e limitar para evitar 429
        conversationHistory.put(JSONObject().apply { put("role", "user"); put("content", userText) })
        if (conversationHistory.length() > 8) {
            val oldHistory = JSONArray()
            for (i in (conversationHistory.length() - 8) until conversationHistory.length()) {
                oldHistory.put(conversationHistory.get(i))
            }
            // Limpa e repovoa para manter leve
            while(conversationHistory.length() > 0) conversationHistory.remove(0)
            for (i in 0 until oldHistory.length()) conversationHistory.put(oldHistory.get(i))
        }

        val body = JSONObject().apply { 
            put("messages", conversationHistory)
            put("context", contextObj)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(BACKEND_URL).post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { handler.post { startListening() } }
            override fun onResponse(call: Call, response: Response) {
                val resStr = response.body?.string() ?: ""
                try {
                    val json = JSONObject(resStr)
                    val responseText = json.optString("response", "Senhor, houve um erro na rede.")
                    
                    // Adicionar resposta da IA ao histórico
                    handler.post {
                        conversationHistory.put(JSONObject().apply { put("role", "assistant"); put("content", responseText) })
                        
                        val action = if (json.isNull("action")) null else json.getString("action")
                        val metadata = json.optJSONObject("metadata")
                        val metadataMap = mutableMapOf<String, String>()
                        metadata?.keys()?.forEach { metadataMap[it] = metadata.getString(it) }

                        actionHandler.execute(action, metadataMap)
                        speakHigh(responseText, TextToSpeech.QUEUE_FLUSH, "res")
                    }
                } catch (e: Exception) { handler.post { startListening() } }
            }
        })
    }

    private fun startListening() {
        if (isListening || isWaitingForCommand || tts.isSpeaking) return
        
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.isMusicActive) {
            handler.postDelayed({ startListening() }, 5000)
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-PT")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        
        try {
            speechRecognizer.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            isListening = false
            handler.postDelayed({ startListening() }, 2000)
        }
    }

    private fun speakHigh(text: String, mode: Int, id: String) {
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        tts.speak(text, mode, params, id)
    }

    private fun vibrate(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else { v.vibrate(ms) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("pt", "PT")
            tts.setPitch(0.9f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id == "res" || id == "wake") handler.post { startListening() } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { handler.post { startListening() } }
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
        speechRecognizer.destroy()
        tts.shutdown()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "nex_channel")
            .setContentTitle("Nex OS")
            .setContentText("Escuta ativa • Diga 'Nex'")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("nex_channel", "Nex Assistant", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
}
