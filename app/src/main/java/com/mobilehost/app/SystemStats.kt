package com.mobilehost.app

import android.app.ActivityManager
import android.content.Context
import java.io.RandomAccessFile

object SystemStats {

    fun ramInfo(context: Context): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val usedMb = (info.totalMem - info.availMem) / (1024 * 1024)
        val totalMb = info.totalMem / (1024 * 1024)
        return usedMb to totalMb
    }

    private var lastIdle = 0L
    private var lastTotal = 0L

    /**
     * Uso de CPU aproximado via /proc/stat. Em versões recentes do Android
     * o acesso pode ser bloqueado; nesse caso retorna null (mostrar "N/D").
     */
    fun cpuUsagePercent(): Int? {
        return try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val load = reader.readLine()
            reader.close()
            val toks = load.split(" ").filter { it.isNotBlank() }
            val user = toks[1].toLong(); val nice = toks[2].toLong()
            val system = toks[3].toLong(); val idle = toks[4].toLong()
            val total = user + nice + system + idle
            if (lastTotal == 0L) {
                lastTotal = total; lastIdle = idle
                return null
            }
            val diffIdle = idle - lastIdle
            val diffTotal = total - lastTotal
            lastTotal = total; lastIdle = idle
            if (diffTotal <= 0) return 0
            (100 * (diffTotal - diffIdle) / diffTotal).toInt()
        } catch (e: Exception) {
            null
        }
    }

    fun uptimeString(startMillis: Long): String {
        if (startMillis == 0L) return "00:00:00"
        val diff = (System.currentTimeMillis() - startMillis) / 1000
        val h = diff / 3600; val m = (diff % 3600) / 60; val s = diff % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }
}
