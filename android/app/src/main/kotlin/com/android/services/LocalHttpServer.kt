package com.android.services

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Lightweight HTTP server yang menjalankan captive-portal redirect.
 * Tidak butuh library tambahan — pakai ServerSocket bawaan Java.
 *
 * Flow:
 *  1. Server listen di port 8080 (tanpa root, port < 1024 butuh root).
 *  2. Client hotspot buka browser → request masuk ke sini.
 *  3. Server return HTTP 302 ke portalUrl (Vercel).
 *  4. Browser client otomatis buka halaman login portal.
 *
 * Captive-portal detection (Android/iOS/Windows) mengirim HTTP ke domain
 * spesifik. Tanpa DNS intercept kita tidak bisa auto-hijack, tapi:
 *  - Hotspot operator bisa share URL portal secara manual.
 *  - Di beberapa ROM (MIUI, One UI) DNS tethering bisa di-override.
 *  - Semua request HTTP ke IP hotspot (192.168.43.1:8080) AKAN di-redirect.
 */
class LocalHttpServer(
    private val portalUrl: String,
    val port: Int = 8080
) {
    companion object {
        private const val TAG = "LocalHttpServer"
    }

    private var serverSocket: ServerSocket? = null
    @Volatile var running = false
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    fun start() {
        if (running) return
        running = true
        executor.submit {
            try {
                serverSocket = ServerSocket(port).also {
                    it.reuseAddress = true
                    it.soTimeout = 1000   // cek running flag tiap 1 detik
                }
                Log.i(TAG, "HTTP server started on port $port → $portalUrl")
                while (running) {
                    try {
                        val client = serverSocket!!.accept()
                        executor.submit { handleClient(client) }
                    } catch (_: SocketTimeoutException) {
                        // normal — loop kembali untuk cek running
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server failed to start: ${e.message}")
            } finally {
                Log.i(TAG, "HTTP server stopped")
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val out    = socket.getOutputStream()

            // Baca request line (GET /path HTTP/1.1)
            val requestLine = reader.readLine() ?: return
            val clientIp = socket.inetAddress?.hostAddress ?: "unknown"
            val path = requestLine.split(" ").getOrElse(1) { "/" }
            Log.d(TAG, "[$clientIp] $requestLine")

            // Tambahkan IP client sebagai query param agar portal bisa log-nya
            val target = if (portalUrl.contains("?")) "$portalUrl&ip=$clientIp"
                         else "$portalUrl?ip=$clientIp"

            // Captive portal check URLs — kembalikan 302 redirect
            val response = buildString {
                append("HTTP/1.1 302 Found\r\n")
                append("Location: $target\r\n")
                append("Content-Length: 0\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(response.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Exception) {
            Log.d(TAG, "Client error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}