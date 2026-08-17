package com.mobilehost.app

import androidx.lifecycle.MutableLiveData

enum class ProjectType { NONE, NODE, PYTHON, JAR, UNKNOWN }

/**
 * Estado compartilhado entre a Activity e o Service (mesmo processo).
 * Evita a necessidade de bind/AIDL para uma app simples.
 */
object AppState {
    val isOnline = MutableLiveData(false)
    val projectType = MutableLiveData(ProjectType.NONE)
    val projectPath = MutableLiveData<String?>(null)
    val consoleText = MutableLiveData("")
    val startTimeMillis = MutableLiveData(0L)

    private val log = StringBuilder()

    @Synchronized
    fun appendLog(line: String) {
        log.append(line).append("\n")
        if (log.length > 20000) log.delete(0, log.length - 20000)
        consoleText.postValue(log.toString())
    }

    fun clearLog() {
        log.setLength(0)
        consoleText.postValue("")
    }
}
