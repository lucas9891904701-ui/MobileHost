package com.mobilehost.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class FilesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)

        val tv = findViewById<TextView>(R.id.tvFiles)
        val dir = HostPaths.hostDir(this)
        val sb = StringBuilder()
        listFiles(dir, sb, 0)
        tv.text = if (sb.isEmpty()) "Nenhum arquivo na pasta da host." else sb.toString()
    }

    private fun listFiles(dir: File, sb: StringBuilder, depth: Int) {
        val files = dir.listFiles() ?: return
        for (f in files.sortedBy { it.name }) {
            sb.append("  ".repeat(depth))
            sb.append(if (f.isDirectory) "📁 " else "📄 ")
            sb.append(f.name)
            if (f.isFile) sb.append(" (${f.length() / 1024} KB)")
            sb.append("\n")
            if (f.isDirectory && depth < 4) listFiles(f, sb, depth + 1)
        }
    }
}
