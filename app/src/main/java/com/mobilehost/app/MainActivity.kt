package com.mobilehost.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatusDot: TextView
    private lateinit var tvStatusText: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvCpu: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvIpPort: TextView
    private lateinit var tvProjectInfo: TextView
    private lateinit var progressBar: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) receiveHost(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatusDot = findViewById(R.id.tvStatusDot)
        tvStatusText = findViewById(R.id.tvStatusText)
        tvRam = findViewById(R.id.tvRam)
        tvCpu = findViewById(R.id.tvCpu)
        tvUptime = findViewById(R.id.tvUptime)
        tvIpPort = findViewById(R.id.tvIpPort)
        tvProjectInfo = findViewById(R.id.tvProjectInfo)
        progressBar = findViewById(R.id.progressBar)

        findViewById<Button>(R.id.btnReceive).setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startHost() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopHost() }
        findViewById<Button>(R.id.btnRestart).setOnClickListener { restartHost() }
        findViewById<Button>(R.id.btnConsole).setOnClickListener {
            startActivity(Intent(this, ConsoleActivity::class.java))
        }
        findViewById<Button>(R.id.btnFiles).setOnClickListener {
            startActivity(Intent(this, FilesActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        requestStorageAccessIfNeeded()
        if (!TermuxExecutor.hasRunCommandPermission(this)) {
            ActivityCompat.requestPermissions(this, arrayOf("com.termux.permission.RUN_COMMAND"), 3)
        }

        AppState.isOnline.observe(this) { online ->
            tvStatusDot.setTextColor(getColor(if (online) R.color.online else R.color.offline))
            tvStatusText.text = if (online) "Online" else "Offline"
        }
        AppState.projectType.observe(this) { updateProjectInfo() }
        AppState.projectPath.observe(this) { updateProjectInfo() }

        tvIpPort.text = "API: ${getLocalIp()}:${HostService.API_PORT}"
    }

    override fun onResume() {
        super.onResume()
        handler.post(statsRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statsRunnable)
    }

    private val statsRunnable = object : Runnable {
        override fun run() {
            val (used, total) = SystemStats.ramInfo(this@MainActivity)
            tvRam.text = "RAM: ${used}MB / ${total}MB"
            val cpu = SystemStats.cpuUsagePercent()
            tvCpu.text = "CPU: ${cpu?.let { "$it%" } ?: "N/D"}"
            tvUptime.text = "Tempo online: ${SystemStats.uptimeString(AppState.startTimeMillis.value ?: 0L)}"
            handler.postDelayed(this, 2000)
        }
    }

    private fun updateProjectInfo() {
        val type = AppState.projectType.value ?: ProjectType.NONE
        val path = AppState.projectPath.value
        tvProjectInfo.text = when (type) {
            ProjectType.NONE -> "Nenhum projeto carregado."
            ProjectType.NODE -> "Projeto Node.js detectado em: $path"
            ProjectType.PYTHON -> "Projeto Python detectado em: $path"
            ProjectType.JAR -> "Servidor Java/Minecraft detectado em: $path"
            ProjectType.UNKNOWN -> "ZIP extraído, mas nenhum projeto reconhecido."
        }
    }

    private fun receiveHost(uri: Uri) {
        progressBar.visibility = ProgressBar.VISIBLE
        progressBar.progress = 0
        scope.launch {
            try {
                val hostDir = HostPaths.hostDir(this@MainActivity)
                val zipFile = File(hostDir, "upload.zip")
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(zipFile).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                val extractDir = File(hostDir, "project")
                extractDir.deleteRecursively()
                withContext(Dispatchers.IO) {
                    ZipUtils.extract(zipFile, extractDir) { pct ->
                        handler.post { progressBar.progress = pct }
                    }
                }
                zipFile.delete()

                val (type, path) = ProjectDetector.detect(extractDir)
                AppState.projectType.postValue(type)
                AppState.projectPath.postValue((path ?: extractDir).absolutePath)

                progressBar.visibility = ProgressBar.GONE
                Toast.makeText(this@MainActivity, "HOST PRONTA ✅", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                progressBar.visibility = ProgressBar.GONE
                Toast.makeText(this@MainActivity, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestStorageAccessIfNeeded() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
        }
    }

    private fun startHost() {
        if ((AppState.projectType.value ?: ProjectType.NONE) == ProjectType.NONE) {
            Toast.makeText(this, "Envie um ZIP primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!TermuxExecutor.isTermuxInstalled(this)) {
            Toast.makeText(this, "Termux não encontrado. Instale o Termux para executar hosts.", Toast.LENGTH_LONG).show()
        } else if (!TermuxExecutor.hasRunCommandPermission(this)) {
            Toast.makeText(this, "Permissão RUN_COMMAND do Termux não concedida.", Toast.LENGTH_LONG).show()
        }
        val intent = Intent(this, HostService::class.java).setAction(HostService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun stopHost() {
        startService(Intent(this, HostService::class.java).setAction(HostService.ACTION_STOP))
    }

    private fun restartHost() {
        val intent = Intent(this, HostService::class.java).setAction(HostService.ACTION_RESTART)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun getLocalIp(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        return addr.hostAddress ?: "?"
                    }
                }
            }
        } catch (e: Exception) {
            // sem rede disponível
        }
        return "127.0.0.1"
    }
}
