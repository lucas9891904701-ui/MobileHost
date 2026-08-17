package com.mobilehost.app

import android.content.Context
import java.io.File

object HostPaths {
    fun hostDir(context: Context): File {
        val dir = File(context.filesDir, "host")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
