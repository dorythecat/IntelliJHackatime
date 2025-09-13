package io.github.dorythecat.intellijhackatime

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.vfs.VirtualFile


class CustomCaretListener : CaretListener {
    override fun caretPositionChanged(event: CaretEvent) {
        try {
            if (!isAppActive()) return
            val editor = event.editor
            val document = editor.document
            val file: VirtualFile = getFile(document) ?: return
            val project = editor.project ?: return
            if (!isProjectInitialized(project)) return
            ApplicationManager.getApplication().invokeLater {
                val lineStats: LineStats = getLineStats(document, editor) ?: return@invokeLater
                appendHeartbeat(file, project, false, lineStats)
            }
        } catch (e: Exception) { log.error(e) }
    }
}