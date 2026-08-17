package com.mobilehost.app

import android.content.Context
import android.os.Environment
import java.io.File

object HostPaths {
    /**
     * A pasta da host fica em armazenamento compartilhado (/sdcard/MobileHost/host)
     * e não na pasta interna do app, porque o Termux não consegue acessar a pasta
     * privada de outro app (isolamento do Android). É por isso que o app precisa
     * da permissão "Acesso a todos os arquivos".
     */
    fun hostDir(context: Context): File {
        val base = File(Environment.getExternalStorageDirectory(), "MobileHost")
        val dir = File(base, "host")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
