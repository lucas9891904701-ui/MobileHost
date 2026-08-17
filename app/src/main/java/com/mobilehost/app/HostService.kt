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

    // Id do job em execução no TerminalEngine (Python interno via Chaquopy).
    // A saída chega em tempo real pelo callback onOutput, sem precisar de
    // polling de arquivo de log.
    private var currentJobId: Int? = null

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
        val jobId = currentJobId
        if (jobId != null) {
            val job = TerminalEngine.stopJob(jobId)
            if (job != null) {
                AppState.appendLog("[MobileHost] Pedido de parada enviado ao processo Python interno (pid $jobId).")
            }
            currentJobId = null
        }
    }

    private fun startProcess() {
        val pathStr = AppState.projectPath.value ?: return
        val dir = File(pathStr)

        val type = AppState.projectType.value ?: ProjectType.UNKNOWN
        if (type != ProjectType.PYTHON) {
            AppState.appendLog("[MobileHost] ❌ Este tipo de projeto (${type.name}) não é suportado pelo runtime " +
                "interno, que executa somente Python.")
            return
        }
        val script = scriptEntry(dir, "main.py", "app.py")
        val scriptFile = File(dir, script)

        AppState.appendLog("[MobileHost] Executando com o Python interno: python3 $script")
        val job = TerminalEngine.runPython(
            context = this,
            scriptPath = scriptFile.absolutePath,
            args = emptyList(),
            onOutput = { text -> AppState.appendLog(text.trimEnd('\n')) },
            onFinished = { finished ->
                AppState.appendLog("[MobileHost] Processo finalizado (status: ${finished.status}, ${finished.exitMessage})")
            }
        )
        currentJobId = job.id
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
        httpServer?.stop()
        super.onDestroy()
    }
}
