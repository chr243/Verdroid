package com.verdroid.app.data.localllm

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the Vulkan probe in its own process (`:gpuprobe`), so a GPU driver crash (seen on some Mali/Adreno drivers)
 * only kills that process. The app gets the probe JSON, or a "crashed"/"timed out" error.
 */
class GpuProbeService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        val reply = msg.replyTo
        Thread {
            val r = try { if (LlamaNative.available) LlamaNative.nativeGpuProbe() else "{\"ok\":false,\"error\":\"${LlamaNative.unavailableReason}\"}" }
                catch (e: Throwable) { "{\"ok\":false,\"error\":\"${(e.message ?: e.javaClass.simpleName).replace("\"", "'")}\"}" }
            runCatching { reply?.send(Message.obtain().apply { data = Bundle().apply { putString("json", r) } }) }
        }.start()
        true
    })
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onUnbind(intent: Intent?): Boolean {
        // Throwaway process: exit so the next probe starts clean (fresh Vulkan instance).
        Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 200)
        return false
    }

    companion object {
        private const val TAG = "VerdroidGpuProbe"

        /** Probe JSON, or an error JSON when the probe process died or took longer than [timeoutMs]. */
        suspend fun probe(context: Context, timeoutMs: Long = 60_000): String {
            val result = CompletableDeferred<String>()
            val handler = Handler(Looper.getMainLooper()) { m -> result.complete(m.data.getString("json") ?: "{\"ok\":false,\"error\":\"empty probe reply\"}"); true }
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val m = Messenger(service)
                    runCatching { service?.linkToDeath({ result.complete("{\"ok\":false,\"error\":\"GPU probe crashed (graphics driver problem)\",\"crashed\":true}") }, 0) }
                    runCatching { m.send(Message.obtain().apply { replyTo = Messenger(handler) }) }
                        .onFailure { result.complete("{\"ok\":false,\"error\":\"GPU probe unreachable\"}") }
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    result.complete("{\"ok\":false,\"error\":\"GPU probe crashed (graphics driver problem)\",\"crashed\":true}")
                }
            }
            val bound = withContext(Dispatchers.Main) {
                context.bindService(Intent(context, GpuProbeService::class.java), conn, Context.BIND_AUTO_CREATE)
            }
            if (!bound) return "{\"ok\":false,\"error\":\"Couldn't start the GPU probe\"}"
            val r = withTimeoutOrNull(timeoutMs) { result.await() } ?: "{\"ok\":false,\"error\":\"GPU probe timed out\"}"
            withContext(Dispatchers.Main) { runCatching { context.unbindService(conn) } }
            Log.i(TAG, "probe: $r")
            return r
        }
    }
}
