package io.github.dorythecat.intellijhackatime

import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.vfs.VirtualFile


class CustomVisibleAreaListener : VisibleAreaListener {
    override fun visibleAreaChanged(visibleAreaEvent: VisibleAreaEvent) {
        try {
            if (!didChange(visibleAreaEvent)) return
            if (!isAppActive()) return
            val document = visibleAreaEvent.editor.document
            val file: VirtualFile = getFile(document) ?: return
            val project = visibleAreaEvent.editor.project ?: return
            if (!isProjectInitialized(project)) return
            val lineStats: LineStats = getLineStats(document, visibleAreaEvent.editor) ?: return
            appendHeartbeat(file, project, false, lineStats)
        } catch (e: Exception) { log.error(e) }
    }

    private fun didChange(visibleAreaEvent: VisibleAreaEvent): Boolean {
        val oldRect = visibleAreaEvent.oldRectangle ?: return true
        val newRect = visibleAreaEvent.newRectangle
        return newRect.x != oldRect.x || newRect.y != oldRect.y
    }
}