package com.mobilehost.app

import java.io.File

/**
 * Detecta o tipo de projeto dentro da pasta extraída, procurando por
 * arquivos indicadores (package.json, requirements.txt, *.jar).
 */
object ProjectDetector {
    fun detect(root: File): Pair<ProjectType, File?> {
        var jar: File? = null
        for (f in root.walkTopDown().maxDepth(3)) {
            if (f.isFile) {
                when {
                    f.name == "package.json" -> return ProjectType.NODE to f.parentFile
                    f.name == "requirements.txt" -> return ProjectType.PYTHON to f.parentFile
                    f.name.endsWith(".jar") && jar == null -> jar = f
                }
            }
        }
        if (jar != null) return ProjectType.JAR to jar!!.parentFile
        return ProjectType.UNKNOWN to null
    }
}
