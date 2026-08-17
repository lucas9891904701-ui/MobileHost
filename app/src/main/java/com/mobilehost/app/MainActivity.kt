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

    // Quando o usuário toca em "Iniciar" sem a permissão RUN_COMMAND concedida,
    // pedimos a permissão em tempo de execução e guardamos essa intenção aqui;
    // se o usuário conceder, retomamos o start automaticamente.
    private var pendingHostStart = false
    private val REQ_RUN_COMMAND = 3

    // Marca o instante em que o diálogo de permissão foi solicitado. Como
    // com.termux.permission.RUN_COMMAND é uma permissão custom (não pertence a
    // nenhum grupo padrão do Android), em várias ROMs Android 13 o sistema NÃO
    // exibe diálogo algum e devolve DENIED instantaneamente. Medimos o tempo até
    // a resposta para diferenciar "usuário tocou em Negar" de "o SO nem mostrou
    // a janela" e, nesse segundo caso, abrimos a tela oficial onde essa
    // permissão pode ser concedida manualmente.
    private var runCommandRequestedAtMillis = 0L
    private val NO_DIALOG_THRESHOLD_MS = 500L

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
            return
        }
        if (!TermuxExecutor.hasRunCommandPermission(this)) {
            // Solicita a permissão em tempo de execução ANTES de tentar rodar
            // qualquer comando. O resultado é tratado em onRequestPermissionsResult.
            pendingHostStart = true
            runCommandRequestedAtMillis = System.currentTimeMillis()
            ActivityCompat.requestPermissions(this, arrayOf(TermuxExecutor.RUN_COMMAND_PERMISSION), REQ_RUN_COMMAND)
            return
        }
        launchHostService()
    }

    private fun launchHostService() {
        val intent = Intent(this, HostService::class.java).setAction(HostService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    /** Abre a tela oficial de "Permissões" do MobileHost (App Info), onde o
     *  Android lista com.termux.permission.RUN_COMMAND em "Permissões
     *  adicionais" — mecanismo documentado pelo próprio Termux para conceder
     *  RUN_COMMAND quando nenhum diálogo do sistema é exibido. */
    private fun openAppPermissionSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RUN_COMMAND) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            val elapsed = System.currentTimeMillis() - runCommandRequestedAtMillis
            if (granted) {
                Toast.makeText(this, "Permissão do Termux concedida.", Toast.LENGTH_SHORT).show()
                if (pendingHostStart) {
                    pendingHostStart = false
                    launchHostService()
                }
            } else {
                pendingHostStart = false
                if (elapsed < NO_DIALOG_THRESHOLD_MS) {
                    // A resposta chegou rápido demais para ter sido um toque real
                    // do usuário: o Android não exibiu diálogo para essa permissão
                    // custom. Vamos direto para a tela oficial onde ela é concedida.
                    Toast.makeText(
                        this,
                        "Seu Android não exibe diálogo para a permissão RUN_COMMAND do Termux. Abrindo Permissões do " +
                            "MobileHost — ative \"RUN_COMMAND\" em Permissões adicionais.",
                        Toast.LENGTH_LONG
                    ).show()
                    openAppPermissionSettings()
                } else {
                    Toast.makeText(
                        this,
                        "Permissão com.termux.permission.RUN_COMMAND negada. Sem ela o MobileHost não consegue executar " +
                            "comandos no Termux. Toque em Iniciar novamente para tentar de novo, ou conceda-a manualmente " +
                            "em Ajustes > Apps > MobileHost > Permissões > Permissões adicionais.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
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
