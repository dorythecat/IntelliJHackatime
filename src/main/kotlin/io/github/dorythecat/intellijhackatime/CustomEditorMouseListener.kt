package io.github.dorythecat.intellijhackatime

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.vfs.VirtualFile


class CustomEditorMouseListener : EditorMouseListener {
    override fun mousePressed(editorMouseEvent: EditorMouseEvent) {
        try {
            if (!isAppActive()) return
            val document = editorMouseEvent.editor.document
            val file: VirtualFile = getFile(document) ?: return
            val project = editorMouseEvent.editor.project ?: return
            if (!isProjectInitialized(project)) return
            ApplicationManager.getApplication().invokeLater(object : Runnable {
                override fun run() {
                    val lineStats: LineStats = getLineStats(document, editorMouseEvent.editor) ?: return
                    appendHeartbeat(file, project, false, lineStats)
                }
            })
        } catch (e: Exception) { log.error(e) }
    }

    override fun mouseClicked(event: EditorMouseEvent) {}

    override fun mouseReleased(event: EditorMouseEvent) {}

    override fun mouseEntered(event: EditorMouseEvent) {}

    override fun mouseExited(event: EditorMouseEvent) {}
}