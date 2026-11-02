package com.ratka.client

import android.content.Context
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var statusText: TextView? = null

    private val wsUrl = "wss://sanya-tech.cc.cd/socket.io/?EIO=4&transport=websocket"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        statusText = TextView(this).apply { text = "Ожидание подключения..." }
        val btnConnect = Button(this).apply {
            text = "Подключиться"
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
                    put("device", android.os.Build.MODEL)
                }
                webSocket.send(hello.toString())
                sendBatteryInfo()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("RATKA", "Получено: $text")
                try {
                    val json = JSONObject(text)
                    if (json.optString("cmd") == "battery") {
                        sendBatteryInfo()
                    }
                } catch (e: Exception) {
                    Log.e("RATKA", "Ошибка парсинга", e)
                }
            }
        })
    }

    private fun sendBatteryInfo() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val json = JSONObject().apply {
            put("type", "battery")
            put("level", level)
        }
        webSocket?.send(json.toString())
    }
}
