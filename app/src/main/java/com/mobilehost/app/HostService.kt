package com.mobilehost.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Foreground Service responsável por manter a "host" rodando em segundo
 * plano, controlar o processo do projeto (Node/Python/Jar) e expor a
 * API local de controle.
 */
class HostService : Service() {

    companion object {
        const val ACTION_START = "com.mobilehost.app.START"
        const val ACTION_STOP = "com.mobilehost.app.STOP"
        const val ACTION_RESTART = "com.mobilehost.app.RESTART"
        const val CHANNEL_ID = "mobilehost_channel"
        const val NOTIF_ID = 1
        const val API_PORT = 8080
    }

    private var process: Process? = null
    private var httpServer: LocalHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startHost()
            ACTION_STOP -> stopHost()
            ACTION_RESTART -> {
                startForeground(NOTIF_ID, buildNotification("Reiniciando"))
                stopProcessOnly()
                startProcess()
            }
        }
        return START_STICKY
    }

    private fun startHost() {
        startForeground(NOTIF_ID, buildNotification("Online"))
        if (httpServer == null) {
            httpServer = LocalHttpServer(API_PORT) { path -> handleApi(path) }
            httpServer?.start()
        }
        AppState.isOnline.postValue(true)
        AppState.startTimeMillis.postValue(System.currentTimeMillis())
        startProcess()
    }

    private fun stopHost() {
        stopProcessOnly()
        httpServer?.stop()
        httpServer = null
        AppState.isOnline.postValue(false)
        stopForeground(true)
        stopSelf()
    }

    private fun stopProcessOnly() {
        try { process?.destroy() } catch (e: Exception) { }
        process = null
        AppState.appendLog("[MobileHost] Processo parado.")
    }

    private fun startProcess() {
        val pathStr = AppState.projectPath.value ?: return
        val dir = File(pathStr)
        val type = AppState.projectType.value ?: ProjectType.UNKNOWN

        val cmd: List<String> = when (type) {
            ProjectType.NODE -> listOf("node", nodeEntry(dir))
            ProjectType.PYTHON -> listOf("python3", scriptEntry(dir, "main.py", "app.py"))
            ProjectType.JAR -> {
                val jarName = dir.listFiles { f -> f.name.endsWith(".jar") }?.firstOrNull()?.name ?: "server.jar"
                listOf("java", "-jar", jarName, "nogui")
            }
            else -> {
                AppState.appendLog("[MobileHost] Nenhum projeto reconhecido para executar.")
                return
            }
        }

        AppState.appendLog("[MobileHost] Iniciando: ${cmd.joinToString(" ")}")
        try {
            val pb = ProcessBuilder(cmd)
            pb.directory(dir)
            pb.redirectErrorStream(true)
            process = pb.start()
            readOutput(process!!)
        } catch (e: Exception) {
            AppState.appendLog("[MobileHost] Erro ao iniciar processo: ${e.message}")
            AppState.appendLog("[MobileHost] O runtime (node/python3/java) precisa estar instalado no aparelho " +
                "e acessível no PATH do app (ex.: via Termux).")
        }
    }

    private fun nodeEntry(dir: File): String {
        val pkg = File(dir, "package.json")
        if (pkg.exists()) {
            val text = pkg.readText()
            val match = Regex("\"main\"\\s*:\\s*\"([^\"]+)\"").find(text)
            if (match != null) return match.groupValues[1]
        }
        return "index.js"
    }

    private fun scriptEntry(dir: File, vararg candidates: String): String {
        for (c in candidates) if (File(dir, c).exists()) return c
        return candidates.first()
    }

    private fun readOutput(p: Process) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    AppState.appendLog(line ?: "")
                }
            } catch (e: Exception) {
                // processo encerrado
            }
        }.start()
    }

    private fun handleApi(path: String): String {
        val type = AppState.projectType.value?.name ?: "NONE"
        return when {
            path.startsWith("/status") -> {
                val online = AppState.isOnline.value ?: false
                "{\"online\":$online,\"type\":\"$type\"}"
            }
            path.startsWith("/start") -> { startProcess(); "{\"result\":\"started\"}" }
            path.startsWith("/stop") -> { stopProcessOnly(); "{\"result\":\"stopped\"}" }
            path.startsWith("/restart") -> { stopProcessOnly(); startProcess(); "{\"result\":\"restarted\"}" }
            else -> "{\"error\":\"not found\"}"
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MobileHost", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MobileHost")
            .setContentText("Status: $status")
            .setSmallIcon(android.R.drawable.presence_online)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopProcessOnly()
        httpServer?.stop()
        super.onDestroy()
    }
}
