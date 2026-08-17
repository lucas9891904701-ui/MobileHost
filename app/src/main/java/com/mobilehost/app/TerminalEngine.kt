package com.mobilehost.app

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Objeto exposto ao interpretador Python embutido (Chaquopy) no lugar de
 * sys.stdout/sys.stderr. Cada vez que o script chama print()/escreve na
 * saída, o Python acaba chamando write() aqui — e repassamos o texto em
 * tempo real para o terminal.
 *
 * Também é o ponto usado para "parar" um script: como o Python roda dentro
 * do mesmo processo do app (não é um processo do SO separado, então não dá
 * pra usar um kill de verdade), quando o usuário pede para parar nós apenas
 * marcamos um flag; na próxima vez que o script tentar imprimir algo,
 * lançamos uma exceção aqui dentro, que o Python propaga como se o próprio
 * script tivesse falhado — interrompendo a execução na prática.
 */
class PyStreamRedirector(
    private val shouldStop: () -> Boolean,
    private val onWrite: (String) -> Unit
) {
    @Suppress("unused")
    fun write(text: String) {
        if (shouldStop()) throw RuntimeException("Execução interrompida pelo usuário")
        if (text.isNotEmpty()) onWrite(text)
    }

    @Suppress("unused")
    fun flush() {
        // cada write já é repassado imediatamente, nada para descarregar aqui
    }
}

enum class JobStatus { RODANDO, FINALIZADO, ERRO, PARADO }

class TerminalJob(val id: Int, val script: String, val args: List<String>) {
    @Volatile var status: JobStatus = JobStatus.RODANDO
    @Volatile var stopRequested: Boolean = false
    @Volatile var exitMessage: String = ""
    val startedAt: Long = System.currentTimeMillis()
    var finishedAt: Long = 0L
    var thread: Thread? = null
    val output = StringBuilder()

    @Synchronized
    fun appendOutput(text: String) {
        output.append(text)
        if (output.length > 50_000) output.delete(0, output.length - 50_000)
    }
}

/**
 * Terminal leve embutido no MobileHost, independente do Termux.
 *
 * Comandos "ls", "cd", "pwd" e "clear" são resolvidos localmente (I/O de
 * arquivo comum, sem processo nenhum). "python3 arquivo.py" roda um
 * interpretador CPython real embutido no próprio app via Chaquopy — uma
 * distribuição oficial do Python para Android, ARM64 nativo, sem depender
 * de Termux, root, Node ou Java externo. Isso mantém o APK pequeno porque
 * só o runtime Python (para arm64-v8a) é empacotado, nenhum outro runtime.
 */
object TerminalEngine {

    private val jobs = LinkedHashMap<Int, TerminalJob>()
    private val nextId = AtomicInteger(1)

    private var _currentDir: File? = null

    fun currentDir(context: Context): File {
        val dir = _currentDir
        if (dir != null && dir.exists()) return dir
        val fallback = AppState.projectPath.value?.let { File(it) }?.takeIf { it.exists() }
            ?: HostPaths.hostDir(context)
        _currentDir = fallback
        return fallback
    }

    fun pwd(context: Context): String = currentDir(context).absolutePath

    fun ls(context: Context): String {
        val dir = currentDir(context)
        val files = dir.listFiles() ?: return "(não foi possível listar $dir)"
        if (files.isEmpty()) return "(pasta vazia)"
        return files.sortedBy { it.name.lowercase() }.joinToString("\n") { f ->
            if (f.isDirectory) "${f.name}/" else f.name
        }
    }

    /** Resolve "cd", "cd ..", "cd nome" ou "cd /caminho/absoluto". */
    fun cd(context: Context, target: String?): String {
        val base = currentDir(context)
        val dest = when {
            target.isNullOrBlank() -> AppState.projectPath.value?.let { File(it) } ?: HostPaths.hostDir(context)
            target.startsWith("/") -> File(target)
            else -> File(base, target)
        }
        val resolved = try { dest.canonicalFile } catch (e: Exception) { dest }
        if (!resolved.exists() || !resolved.isDirectory) {
            return "cd: pasta não encontrada: $target"
        }
        _currentDir = resolved
        return resolved.absolutePath
    }

    fun listJobs(): List<TerminalJob> = synchronized(jobs) { jobs.values.toList() }

    fun jobById(id: Int): TerminalJob? = synchronized(jobs) { jobs[id] }

    fun lastJob(): TerminalJob? = synchronized(jobs) { jobs.values.lastOrNull() }

    /**
     * Executa "python3 arquivo.py [args...]" em uma thread separada,
     * chamando onOutput() em tempo real para cada trecho de saída e
     * onFinished() quando o script termina (com sucesso, erro ou parada).
     */
    fun runPython(
        context: Context,
        scriptPath: String,
        args: List<String>,
        onOutput: (String) -> Unit,
        onFinished: (TerminalJob) -> Unit
    ): TerminalJob {
        val dir = currentDir(context)
        val scriptFile = if (scriptPath.startsWith("/")) File(scriptPath) else File(dir, scriptPath)
        val job = TerminalJob(nextId.getAndIncrement(), scriptPath, args)
        synchronized(jobs) { jobs[job.id] = job }

        val appContext = context.applicationContext
        val thread = Thread {
            if (!scriptFile.exists()) {
                job.status = JobStatus.ERRO
                job.exitMessage = "arquivo não encontrado: ${scriptFile.absolutePath}"
                onOutput("[erro] ${job.exitMessage}\n")
                job.finishedAt = System.currentTimeMillis()
                onFinished(job)
                return@Thread
            }
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
                val py = Python.getInstance()
                val sys = py.getModule("sys")
                val redirector = PyStreamRedirector({ job.stopRequested }) { text ->
                    job.appendOutput(text)
                    onOutput(text)
                }
                sys.put("stdout", redirector)
                sys.put("stderr", redirector)
                sys.put("argv", listOf(scriptFile.absolutePath) + args)
                sys.get("path")?.callAttr("insert", 0, scriptFile.parentFile?.absolutePath ?: dir.absolutePath)

                val runpy = py.getModule("runpy")
                runpy.callAttr("run_path", scriptFile.absolutePath, null, "__main__")

                job.status = JobStatus.FINALIZADO
                job.exitMessage = "concluído"
            } catch (e: PyException) {
                job.status = if (job.stopRequested) JobStatus.PARADO else JobStatus.ERRO
                job.exitMessage = e.message ?: "erro Python desconhecido"
                onOutput("\n[erro] ${job.exitMessage}\n")
            } catch (e: Exception) {
                job.status = if (job.stopRequested) JobStatus.PARADO else JobStatus.ERRO
                job.exitMessage = e.message ?: e.javaClass.simpleName
                onOutput("\n[erro] ${job.exitMessage}\n")
            } finally {
                job.finishedAt = System.currentTimeMillis()
                onFinished(job)
            }
        }
        thread.isDaemon = true
        thread.name = "mobilehost-py-${job.id}"
        job.thread = thread
        thread.start()
        return job
    }

    /**
     * Pede para parar um job (o mais recente em execução, se nenhum id for
     * informado). Como o script roda embutido no processo do app, isso é
     * uma parada cooperativa: funciona de forma confiável em scripts que
     * imprimem algo periodicamente; scripts totalmente silenciosos e presos
     * num laço infinito só param quando voltarem a chamar print().
     */
    fun stopJob(id: Int?): TerminalJob? {
        val job = synchronized(jobs) {
            if (id != null) jobs[id] else jobs.values.lastOrNull { it.status == JobStatus.RODANDO }
        } ?: return null
        job.stopRequested = true
        job.thread?.interrupt()
        return job
    }
}
