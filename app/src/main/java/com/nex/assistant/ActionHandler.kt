package com.nex.assistant

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.widget.Toast

import androidx.core.net.toUri

class ActionHandler(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var cameraId: String? = try { cameraManager.cameraIdList.firstOrNull() } catch (_: Exception) { null }

    fun execute(action: String?, metadata: Map<String, String>?) {
        when (action) {
            "LIGHT_ON" -> toggleFlashlight(true)
            "LIGHT_OFF" -> toggleFlashlight(false)
            "SET_VOLUME" -> setVolume(metadata?.get("value")?.toIntOrNull() ?: 50)
            "SET_BRIGHTNESS" -> setBrightness(metadata?.get("value")?.toIntOrNull() ?: 128)
            "OPEN_APP" -> openApp(metadata?.get("package"))
            "WEB_SEARCH" -> webSearch(metadata?.get("query"))
            "OPEN_URL" -> openUrl(metadata?.get("url"))
            "OPEN_NEX_BROWSER" -> openNexBrowser(metadata?.get("url"))
            "ANALYZE_WEB_CONTEXT" -> analyzeWebContext()
            "SYSTEM_SCAN" -> systemScan()
            "GET_OS_STATUS" -> getOSStatus()
            "MAKE_CALL" -> makeCall(metadata?.get("number"))
            "SEARCH_CONTACT" -> searchContact(metadata?.get("name"))
            "SET_WIFI" -> setWifi(metadata?.get("state") == "on")
            "SET_BLUETOOTH" -> setBluetooth(metadata?.get("state") == "on")
            "TOAST" -> Toast.makeText(context, metadata?.get("message") ?: "Comando executado", Toast.LENGTH_SHORT).show()
            "FINISH" -> (context as? android.app.Activity)?.finish()
            else -> { /* Ação desconhecida */ }
        }
    }

    private fun setWifi(enable: Boolean) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                context.startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = enable
            }
        } catch (_: Exception) {
            Toast.makeText(context, "Erro ao alterar Wi-Fi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setBluetooth(enable: Boolean) {
        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val btAdapter = btManager.adapter
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(context, "Sem permissão BLUETOOTH_CONNECT", Toast.LENGTH_SHORT).show()
                    return
                }
            }

            @Suppress("DEPRECATION")
            if (enable) btAdapter.enable() else btAdapter.disable()
        } catch (_: Exception) {
            Toast.makeText(context, "Erro ao alterar Bluetooth", Toast.LENGTH_SHORT).show()
        }
    }

    private fun makeCall(number: String?) {
        if (number == null) return
        try {
            val intent = Intent(Intent.ACTION_CALL, "tel:$number".toUri())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Erro ao iniciar chamada.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchContact(name: String?) {
        if (name == null) return
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val contactName = it.getString(0)
                val number = it.getString(1)
                Toast.makeText(context, "Contato: $contactName ($number)", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Contato não encontrado.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getOSStatus() {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val availableRam = memInfo.availMem / (1024 * 1024)
        
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val availableStorage = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024 * 1024)

        val statusMsg = "RAM Disponível: ${availableRam}MB | Armazenamento: ${availableStorage}GB livres."
        Toast.makeText(context, statusMsg, Toast.LENGTH_LONG).show()
    }

    private fun systemScan() {
        val batteryStatus: Intent? = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        Toast.makeText(context, "Diagnóstico: Bateria em $level% | Hardware OK", Toast.LENGTH_LONG).show()
    }

    private fun setBrightness(value: Int) {
        // O valor vem de 0 a 255
        try {
            android.provider.Settings.System.putInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                value
            )
        } catch (_: Exception) {
            // Requer permissão WRITE_SETTINGS, mostramos um aviso se falhar
            Toast.makeText(context, "Preciso de permissão para alterar o brilho", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFlashlight(status: Boolean) {
        try { cameraId?.let { cameraManager.setTorchMode(it, status) } } catch (_: Exception) {}
    }

    private fun setVolume(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * (percent / 100f)).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
    }

    private fun openApp(packageName: String?) {
        if (packageName == null) return
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun webSearch(query: String?) {
        if (query == null) return
        try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH)
            intent.putExtra(android.app.SearchManager.QUERY, query)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Erro ao pesquisar na web.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String?) {
        if (url == null) return
        try {
            val uri = (if (url.startsWith("http")) url else "https://$url").toUri()
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Erro ao abrir URL.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openNexBrowser(url: String?) {
        try {
            val intent = Intent(context, NexBrowserActivity::class.java)
            url?.let { intent.putExtra("url", it) }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Erro ao abrir Nex Browser.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun analyzeWebContext() {
        // Esta função será o gatilho para o NexService recolher dados do TabManager
        // e enviar para o Groq comparar o contexto das abas.
        Toast.makeText(context, "Nex: Analisando contexto das abas ativas...", Toast.LENGTH_SHORT).show()
    }
}
