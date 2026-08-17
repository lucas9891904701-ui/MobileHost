package com.mobilehost.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Terminal interno leve do MobileHost (visual estilo Termux), mas sem
 * nenhuma dependência do app Termux. "ls"/"cd"/"pwd"/"clear" são resolvidos
 * localmente; "python3 arquivo.py" roda no interpretador Python embutido
 * (ver TerminalEngine). Antes de rodar qualquer script ou parar um
 * processo, o terminal pede confirmação ("permitir"/"negar").
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var tvOutput: TextView
    private lateinit var scroll: ScrollView
    private lateinit var etCommand: EditText
    private lateinit var tvPrompt: TextView

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        tvOutput = findViewById(R.id.tvTerminalOutput)
        scroll = findViewById(R.id.scrollTerminal)
        etCommand = findViewById(R.id.etCommand)
        tvPrompt = findViewById(R.id.tvPrompt)

        printLine("MobileHost Terminal — python3 embutido, sem Termux.")
        printLine("Comandos: ls, cd, pwd, clear, python3 <arquivo.py>, processos, logs [pid], parar [pid]")
        updatePrompt()

        etCommand.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                submitCommand()
                true
            } else {
                false
            }
        }
    }

    private fun updatePrompt() {
        tvPrompt.text = "${TerminalEngine.pwd(this)} \$"
    }

    private fun printLine(text: String) {
        tvOutput.append(if (tvOutput.text.isEmpty()) text else "\n$text")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun printOutput(text: String) {
        runOnUiThread {
            tvOutput.append(text)
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun submitCommand() {
        val raw = etCommand.text.toString().trim()
        etCommand.setText("")
        if (raw.isEmpty()) return
        printLine("${TerminalEngine.pwd(this)} \$ $raw")

        val parts = raw.split(Regex("\\s+"))
        when (parts[0]) {
            "ls" -> printLine(TerminalEngine.ls(this))
            "pwd" -> printLine(TerminalEngine.pwd(this))
            "cd" -> {
                printLine(TerminalEngine.cd(this, parts.getOrNull(1)))
                updatePrompt()
            }
            "clear" -> tvOutput.text = ""
            "processos" -> printLine(formatJobs())
            "logs" -> printLine(formatLogs(parts.getOrNull(1)?.toIntOrNull()))
            "python3" -> {
                val script = parts.getOrNull(1)
                if (script == null) {
                    printLine("uso: python3 <arquivo.py> [args...]")
                } else {
                    confirmAndRun(raw) { runPython(script, parts.drop(2)) }
                }
            }
            "parar" -> confirmAndRun(raw) { doStop(parts.getOrNull(1)?.toIntOrNull()) }
            else -> printLine("comando não suportado: ${parts[0]}")
        }
    }

    /** Mostra o diálogo "permitir"/"negar" antes de rodar comandos que
     *  executam código ou encerram processos. */
    private fun confirmAndRun(cmdLine: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Executar comando?")
            .setMessage(cmdLine)
            .setPositiveButton("Permitir") { _, _ -> action() }
            .setNegativeButton("Negar") { _, _ -> printLine("[negado pelo usuário] $cmdLine") }
            .setCancelable(false)
            .show()
    }

    private fun runPython(script: String, args: List<String>) {
        val job = TerminalEngine.runPython(
            context = this,
            scriptPath = script,
            args = args,
            onOutput = { text -> printOutput(text) },
            onFinished = { finishedJob ->
                runOnUiThread {
                    printLine("[pid ${finishedJob.id}] status: ${finishedJob.status} (${finishedJob.exitMessage})")
                }
            }
        )
        printLine("[pid ${job.id}] iniciado: python3 $script ${args.joinToString(" ")}".trim())
    }

    private fun doStop(id: Int?) {
        val job = TerminalEngine.stopJob(id)
        if (job == null) {
            printLine("nenhum processo em execução para parar.")
        } else {
            printLine("[pid ${job.id}] pedido de parada enviado.")
        }
    }

    private fun formatJobs(): String {
        val jobs = TerminalEngine.listJobs()
        if (jobs.isEmpty()) return "(nenhum processo iniciado nesta sessão)"
        return jobs.joinToString("\n") { j ->
            val started = timeFmt.format(j.startedAt)
            "pid ${j.id}  python3 ${j.script}  status=${j.status}  início=$started"
        }
    }

    private fun formatLogs(id: Int?): String {
        val job = if (id != null) TerminalEngine.jobById(id) else TerminalEngine.lastJob()
        if (job == null) return "nenhum log encontrado" + (id?.let { " para pid $it" } ?: "")
        val text = job.output.toString()
        return "--- logs pid ${job.id} (${job.status}) ---\n" +
            (if (text.isEmpty()) "(sem saída)" else text)
    }
}
