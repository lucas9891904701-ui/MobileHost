package com.mobilehost.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

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

    private var httpServer: LocalHttpServer? = null

    // Polling do console: como o comando roda dentro do Termux (outro app),
    // lemos periodicamente o arquivo de log que ele vai preenchendo.
    private var pollThread: Thread? = null
    @Volatile private var polling = false
    private var logPos = 0L

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
        val pathStr = AppState.projectPath.value
        if (pathStr != null && TermuxExecutor.isReady(this)) {
            val sent = TermuxExecutor.stop(this, File(pathStr))
            if (sent) {
                AppState.appendLog("[MobileHost] Comando de parada enviado ao Termux.")
            } else {
                AppState.appendLog("[MobileHost] ❌ Não foi possível enviar o comando de parada: " +
                    "permissão RUN_COMMAND do Termux não concedida.")
            }
        }
        stopPolling()
    }

    private fun startProcess() {
        val pathStr = AppState.projectPath.value ?: return
        val dir = File(pathStr)

        if (!TermuxExecutor.isTermuxInstalled(this)) {
            AppState.appendLog("[MobileHost] ❌ Termux não está instalado. Instale o Termux (F-Droid) para executar hosts.")
            return
        }
        if (!TermuxExecutor.hasRunCommandPermission(this)) {
            AppState.appendLog("[MobileHost] ❌ Permissão com.termux.permission.RUN_COMMAND não concedida ao MobileHost. " +
                "Volte à tela inicial e toque em Iniciar para que o app solicite a permissão do Termux.")
            return
        }

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

        AppState.appendLog("[MobileHost] Enviando para o Termux: ${cmd.joinToString(" ")}")
        val sent = TermuxExecutor.start(this, dir, cmd)
        if (!sent) {
            AppState.appendLog("[MobileHost] ❌ O Termux recusou o comando: permissão RUN_COMMAND " +
                "não concedida ao MobileHost. Abra o app e conceda a permissão quando solicitado.")
            return
        }
        startPolling(dir)
    }

    private fun startPolling(dir: File) {
        stopPolling()
        logPos = 0L
        polling = true
        pollThread = Thread {
            while (polling) {
                try {
                    val (text, newPos) = TermuxExecutor.readNewLines(dir, logPos)
                    logPos = newPos
                    if (text.isNotEmpty()) AppState.appendLog(text.trimEnd('\n'))
                } catch (e: Exception) {
                    // arquivo de log ainda não existe ou está sendo escrito; ignora e tenta de novo
                }
                Thread.sleep(1500)
            }
        }
        pollThread?.start()
    }

    private fun stopPolling() {
        polling = false
        pollThread = null
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
        stopPolling()
        httpServer?.stop()
        super.onDestroy()
    }
}
