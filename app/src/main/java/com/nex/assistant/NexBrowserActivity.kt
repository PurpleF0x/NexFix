package com.nex.assistant

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class NexBrowserActivity : ComponentActivity() {

    private val client = OkHttpClient()
    private val BACKEND_URL = "https://purple-uwq6.onrender.com/api.php" // Substituir pela sua URL real

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NexBrowserUI()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun NexBrowserUI() {
        var url by remember { mutableStateOf("https://www.google.com") }
        var webView: WebView? by remember { mutableStateOf(null) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Nex Browser", color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { webView?.reload() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A2E))
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF0F0F1B))) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            @SuppressLint("SetJavaScriptEnabled")
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    url?.let { TabManager.currentUrl = it }
                                    extractPageContent(view)
                                }
                            }
                            addJavascriptInterface(NexWebInterface(), "NexInterface")
                            loadUrl(url)
                            webView = this
                        }
                    },
                    update = { webView = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    private fun extractPageContent(view: WebView?) {
        view?.evaluateJavascript(
            "(function() { return document.body.innerText; })();"
        ) { content ->
            // O conteúdo vem entre aspas, vamos limpar
            val cleanContent = content?.trim()?.removeSurrounding("\"")
            if (!cleanContent.isNullOrBlank() && cleanContent != "null") {
                TabManager.currentTabText = cleanContent
                sendContentToNex(cleanContent)
                
                // Dispara análise proativa no serviço
                val proactiveIntent = Intent(this@NexBrowserActivity, NexService::class.java).apply {
                    action = "PROACTIVE_SYNC"
                }
                startService(proactiveIntent)
            }
        }
    }

    private fun sendContentToNex(text: String) {
        // Comprimir texto para economizar tokens (limite simples de caracteres)
        val compressedText = if (text.length > 2000) text.substring(0, 2000) + "..." else text
        
        val json = JSONObject().apply {
            put("type", "browser_sync")
            put("content", compressedText)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(BACKEND_URL).post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Silencioso em background
            }

            override fun onResponse(call: Call, response: Response) {
                // O Nex agora "sabe" o que você está a ler
            }
        })
    }

    class NexWebInterface {
        @JavascriptInterface
        fun postMessage(@Suppress("UNUSED_PARAMETER") message: String) {
            // Futuras integrações de mensagens do site para a IA
            android.util.Log.d("NexBrowser", "Message from web: $message")
        }
    }
}
