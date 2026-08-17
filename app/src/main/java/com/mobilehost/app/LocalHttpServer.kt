package com.mobilehost.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * Servidor HTTP local minimalista (sem bibliotecas externas) para expor
 * uma API simples de controle da host: /status, /start, /stop, /restart.
 */
class LocalHttpServer(private val port: Int, private val onRequest: (String) -> String) {

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    Thread { handle(client) }.start()
                }
            } catch (e: Exception) {
                // socket fechado ao parar
            }
        }.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) { }
    }

    private fun handle(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrElse(1) { "/" }
            val body = onRequest(path)
            val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n$body"
            client.getOutputStream().write(response.toByteArray())
            client.getOutputStream().flush()
        } catch (e: Exception) {
            // ignora conexões malformadas
        } finally {
            try { client.close() } catch (e: Exception) { }
        }
    }
}
