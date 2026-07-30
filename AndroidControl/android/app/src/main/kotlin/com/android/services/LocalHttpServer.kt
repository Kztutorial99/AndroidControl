package com.android.services

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * Lightweight HTTP server untuk captive-portal redirect.
 *
 * Mendukung 3 mode:
 *  - Port 80  (no-root): semua HTTP request dari hotspot client langsung masuk
 *  - Port 8080 + iptables (root): port 80/443 di-redirect ke sini via iptables
 *  - Port 8080 only: client buka manual http://192.168.43.1:8080
 *
 * Captive portal detection OS:
 *  Android  → connectivitycheck.gstatic.com/generate_204  (expect 204 → kita 302 → popup muncul)
 *  iOS      → captive.apple.com/hotspot-detect.html
 *  Windows  → www.msftconnecttest.com/connecttest.txt
 *  Firefox  → detectportal.firefox.com/success.txt
 *
 * Semua request → HTTP 302 ke portal Vercel.
 * Khusus captive-check: tambahkan header X-NetworkLogin-URL supaya OS tampilkan popup.
 */
class LocalHttpServer(
    private val portalUrl: String,
    val port: Int = 8080
) {
    companion object {
        private const val TAG = "LocalHttpServer"

        private val CAPTIVE_HOSTS = setOf(
            "connectivitycheck.gstatic.com",
            "connectivitycheck.android.com",
            "clients1.google.com",
            "clients3.google.com",
            "captive.apple.com",
            "www.apple.com",
            "gsp1.apple.com",
            "www.msftconnecttest.com",
            "www.msftncsi.com",
            "dns.msftncsi.com",
            "detectportal.firefox.com",
            "networkcheck.kde.org",
        )
    }

    private var serverSocket: ServerSocket? = null
    @Volatile var running = false
    private val executor = Executors.newCachedThreadPool()

    fun start() {
        if (running) return
        running = true
        executor.submit {
            try {
                serverSocket = ServerSocket(port).also {
                    it.reuseAddress = true
                    it.soTimeout = 1000
                }
                Log.i(TAG, "HTTP server listening on port $port → $portalUrl")
                while (running) {
                    try {
                        val client = serverSocket!!.accept()
                        executor.submit { handleClient(client) }
                    } catch (_: SocketTimeoutException) {
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start on port $port: ${e.message}")
            } finally {
                Log.i(TAG, "HTTP server stopped (port $port)")
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val input  = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val output = socket.getOutputStream()

            // Baca request line
            val requestLine = input.readLine() ?: return
            val parts = requestLine.trim().split(" ")
            val method = parts.getOrElse(0) { "GET" }
            val path   = parts.getOrElse(1) { "/" }

            // Baca headers
            val headers = mutableMapOf<String, String>()
            var line = input.readLine()
            while (!line.isNullOrBlank()) {
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                line = input.readLine()
            }

            val clientIp = socket.inetAddress?.hostAddress ?: "unknown"
            val host     = headers["host"]?.substringBefore(":") ?: ""

            Log.d(TAG, "[$clientIp] $method $path  Host:$host")

            // CONNECT method (HTTPS tunnel) → langsung tolak supaya browser
            // fallback ke HTTP dan terkena captive portal check
            if (method.equals("CONNECT", ignoreCase = true)) {
                output.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            // Build redirect target — sertakan IP client sebagai param
            val target = buildString {
                append(portalUrl)
                append(if (portalUrl.contains('?')) '&' else '?')
                append("ip=").append(clientIp)
            }

            val isCaptiveCheck = CAPTIVE_HOSTS.any { host.endsWith(it, ignoreCase = true) }

            // HTTP 302 redirect → portal Vercel
            val response = buildString {
                append("HTTP/1.1 302 Found\r\n")
                append("Location: $target\r\n")
                append("Content-Length: 0\r\n")
                append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
                append("Pragma: no-cache\r\n")
                append("Connection: close\r\n")
                // Header khusus captive portal — beritahu OS ada login page
                if (isCaptiveCheck) {
                    append("X-NetworkLogin-URL: $target\r\n")
                    append("X-CaptivePortal: true\r\n")
                }
                append("\r\n")
            }

            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()

            Log.i(TAG, "→ 302 [$clientIp]${if (isCaptiveCheck) " [CAPTIVE-CHECK]" else ""} → $target")

        } catch (e: Exception) {
            Log.d(TAG, "Client error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
