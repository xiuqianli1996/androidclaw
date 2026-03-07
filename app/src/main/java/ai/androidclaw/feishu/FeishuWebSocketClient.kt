package ai.androidclaw.feishu

import android.util.Log
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import com.lark.oapi.ws.Client
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class FeishuWebSocketClient(
    private val config: FeishuConfig,
    private val messageListener: FeishuMessageListener
) {

    private var wsClient: Client? = null
    private var disconnectMethod: Method? = null
    private val connected = AtomicBoolean(false)

    companion object {
        private const val TAG = "FeishuWSClient"
    }

    interface FeishuMessageListener {
        fun onConnected()
        fun onDisconnected()
        fun onMessage(event: P2MessageReceiveV1)
        fun onError(error: String)
    }

    fun connect() {
        if (connected.get()) {
            Log.d(TAG, "Already connected")
            return
        }

        try {
            val dispatcher = EventDispatcher
                .newBuilder(config.verificationToken, config.encryptKey)
                .onP2MessageReceiveV1(object : ImService.P2MessageReceiveV1Handler() {
                    override fun handle(event: P2MessageReceiveV1) {
                        messageListener.onMessage(event)
                    }
                })
                .build()

            wsClient = Client.Builder(config.appId, config.appSecret)
                .eventHandler(dispatcher)
                .autoReconnect(true)
                .build()
            disconnectMethod = Client::class.java.getDeclaredMethod("disconnect").apply {
                isAccessible = true
            }

            connected.set(true)
            messageListener.onConnected()
            Log.d(TAG, "WebSocket client started via lark oapi")

            thread(start = true, name = "feishu-ws-client") {
                try {
                    wsClient?.start()
                } catch (e: Exception) {
                    if (connected.getAndSet(false)) {
                        Log.e(TAG, "WebSocket client exited", e)
                        messageListener.onError(e.message ?: "WebSocket client exited")
                        messageListener.onDisconnected()
                    }
                }
            }
        } catch (e: Exception) {
            connected.set(false)
            Log.e(TAG, "Failed to start WebSocket client", e)
            messageListener.onError(e.message ?: "Failed to start WebSocket client")
        }
    }

    fun disconnect() {
        if (!connected.get()) {
            return
        }

        try {
            wsClient?.let { client ->
                disconnectMethod?.invoke(client)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error while disconnecting WebSocket", e)
        } finally {
            wsClient = null
            disconnectMethod = null
            if (connected.getAndSet(false)) {
                messageListener.onDisconnected()
            }
            Log.d(TAG, "WebSocket client stopped")
        }
    }

    fun release() {
        disconnect()
    }
}
