package com.mobilehost.app

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ZipUtils {

    fun extract(zipFile: File, destDir: File, onProgress: (Int) -> Unit) {
        if (!destDir.exists()) destDir.mkdirs()
        val total = countEntries(zipFile)
        var done = 0
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                // Proteção contra Zip Slip
                if (!outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                    throw SecurityException("Entrada de ZIP inválida: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                done++
                if (total > 0) onProgress((done * 100) / total)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        onProgress(100)
    }

    private fun countEntries(zipFile: File): Int {
        var count = 0
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            while (zis.nextEntry != null) {
                count++
                zis.closeEntry()
            }
        }
        return count
    }
}
