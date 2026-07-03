package com.android.services

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Lightweight HTTP server untuk captive-portal redirect.
 *
 * Setelah iptables redirect port 80 → 8080:
 *  1. Client hotspot buka browser (atau OS melakukan captive portal check)
 *  2. Request masuk ke server ini di port 8080
 *  3. Server baca Host header untuk deteksi captive portal check URL
 *  4. Untuk captive portal check → return 302 ke portalUrl (trigger popup)
 *  5. Untuk semua request lain → return 302 ke portalUrl
 *
 * Captive portal check domains:
 *  Android : connectivitycheck.gstatic.com/generate_204
 *  iOS     : captive.apple.com/hotspot-detect.html
 *  Windows : www.msftconnecttest.com/connecttest.txt
 */
class LocalHttpServer(
    private val portalUrl: String,
    val port: Int = 8080
) {
    companion object {
        private const val TAG = "LocalHttpServer"

        // OS captive-portal detection hosts — semua di-redirect ke portal
        private val CAPTIVE_HOSTS = setOf(
            "connectivitycheck.gstatic.com",
            "connectivitycheck.android.com",
            "clients1.google.com",
            "clients3.google.com",
            "captive.apple.com",
            "www.apple.com",
            "www.msftconnecttest.com",
            "www.msftncsi.com",
            "detectportal.firefox.com",
            "networkcheck.kde.org",
        )
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
                    it.soTimeout = 1000
                }
                Log.i(TAG, "HTTP server started on port $port → $portalUrl")
                while (running) {
                    try {
                        val client = serverSocket!!.accept()
                        executor.submit { handleClient(client) }
                    } catch (_: SocketTimeoutException) {
                        // normal — loop check running flag
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server failed to start on port $port: ${e.message}")
            } finally {
                Log.i(TAG, "HTTP server stopped")
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

            // Baca request line + headers
            val requestLine = input.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            var line = input.readLine()
            while (!line.isNullOrBlank()) {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
                line = input.readLine()
            }

            val clientIp = socket.inetAddress?.hostAddress ?: "unknown"
            val host     = headers["host"]?.substringBefore(":") ?: ""
            val path     = requestLine.split(" ").getOrElse(1) { "/" }

            Log.d(TAG, "[$clientIp] $requestLine host=$host")

            // Tambah clientIp sebagai query param supaya portal bisa log
            val target = buildString {
                append(portalUrl)
                append(if (portalUrl.contains("?")) "&" else "?")
                append("ip=${clientIp}")
            }

            // Captive portal check: kalau host adalah salah satu OS check domain
            // → return 302 (OS akan tampilkan popup "Sign in to network")
            // Kalau bukan → return 302 juga (direct redirect ke portal)
            val isCaptiveCheck = CAPTIVE_HOSTS.any { host.contains(it, ignoreCase = true) }

            val response = buildString {
                append("HTTP/1.1 302 Found\r\n")
                append("Location: $target\r\n")
                append("Content-Length: 0\r\n")
                append("Cache-Control: no-store, no-cache\r\n")
                append("Connection: close\r\n")
                if (isCaptiveCheck) {
                    // Beritahu OS ini adalah captive portal
                    append("X-NetworkLogin-URL: $target\r\n")
                }
                append("\r\n")
            }

            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()

            Log.d(TAG, "→ 302 → $target${if (isCaptiveCheck) " [captive-check]" else ""}")
        } catch (e: Exception) {
            Log.d(TAG, "Client error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
