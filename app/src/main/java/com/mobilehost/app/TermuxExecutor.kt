package com.mobilehost.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.RandomAccessFile

/**
 * Executa comandos DENTRO do Termux usando o Intent oficial RUN_COMMAND.
 *
 * Por que isso é necessário: o Android impede que o MobileHost chame
 * diretamente os binários instalados pelo Termux (python3, node, java),
 * pois cada app roda isolado em sua própria pasta de dados — daí o erro
 * "Permission denied". O RUN_COMMAND é a forma oficial e sem root de pedir
 * para o próprio Termux rodar um comando por nós.
 *
 * A saída do comando é redirecionada para um arquivo de log dentro da
 * pasta do projeto, e o PID é salvo em outro arquivo para permitir parar
 * o processo depois.
 */
object TermuxExecutor {

    private const val TERMUX_PKG = "com.termux"
    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    /** Nome da permissão exigida pelo Termux, exposto como constante para
     *  evitar strings soltas/duplicadas em outras classes (MainActivity). */
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PKG, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun hasRunCommandPermission(context: Context): Boolean {
        return context.checkSelfPermission(RUN_COMMAND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** true somente se o Termux estiver instalado E a permissão já concedida. */
    fun isReady(context: Context): Boolean =
        isTermuxInstalled(context) && hasRunCommandPermission(context)

    fun logFile(dir: File): File = File(dir, ".mobilehost_console.log")
    private fun pidFile(dir: File): File = File(dir, ".mobilehost.pid")

    /**
     * Pede ao Termux para iniciar o comando, com saída redirecionada para o log.
     * Retorna false (sem lançar exceção) se a permissão RUN_COMMAND não estiver
     * concedida ou se o Android recusar o envio do Intent por falta dela — o que
     * pode acontecer mesmo após a checagem prévia, já que a permissão pode ser
     * revogada pelo usuário/sistema entre a checagem e o envio do comando.
     */
    fun start(context: Context, dir: File, command: List<String>): Boolean {
        if (!hasRunCommandPermission(context)) return false
        val log = logFile(dir)
        val pid = pidFile(dir)
        log.delete()
        val cmdLine = command.joinToString(" ") { arg -> if (arg.contains(" ")) "'$arg'" else arg }
        // "exec" troca o processo do bash pelo comando final, mantendo o mesmo PID,
        // o que permite matar o processo certo depois usando o pidfile.
        val script = "cd '${dir.absolutePath}'; echo \$\$ > '${pid.absolutePath}'; " +
            "exec $cmdLine >> '${log.absolutePath}' 2>&1"
        return runInTermux(context, BASH, arrayOf("-c", script), dir.absolutePath)
    }

    /** Pede ao Termux para matar o processo salvo no pidfile. */
    fun stop(context: Context, dir: File): Boolean {
        if (!hasRunCommandPermission(context)) return false
        val pid = pidFile(dir)
        val script = "if [ -f '${pid.absolutePath}' ]; then kill \$(cat '${pid.absolutePath}') 2>/dev/null; " +
            "rm -f '${pid.absolutePath}'; fi"
        return runInTermux(context, BASH, arrayOf("-c", script), dir.absolutePath)
    }

    private fun runInTermux(context: Context, path: String, args: Array<String>, workDir: String): Boolean {
        val intent = Intent(RUN_COMMAND_ACTION)
        intent.setComponent(ComponentName(TERMUX_PKG, RUN_COMMAND_SERVICE))
        intent.putExtra("com.termux.RUN_COMMAND_PATH", path)
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", workDir)
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        return try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: SecurityException) {
            // O próprio Termux (ou o Android) recusou o comando por falta da
            // permissão RUN_COMMAND. Não deixamos a exceção propagar — quem
            // chamou trata o retorno false e mostra uma mensagem clara.
            false
        }
    }

    /** Lê somente as linhas novas do log desde a última posição lida (polling leve). */
    fun readNewLines(dir: File, lastPos: Long): Pair<String, Long> {
        val log = logFile(dir)
        if (!log.exists()) return "" to lastPos
        val raf = RandomAccessFile(log, "r")
        val len = raf.length()
        if (len <= lastPos) {
            raf.close()
            return "" to lastPos
        }
        raf.seek(lastPos)
        val buffer = ByteArray((len - lastPos).toInt())
        raf.readFully(buffer)
        raf.close()
        return String(buffer) to len
    }
}
