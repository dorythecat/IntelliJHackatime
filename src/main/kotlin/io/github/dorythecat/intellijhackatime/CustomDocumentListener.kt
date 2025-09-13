package io.github.dorythecat.intellijhackatime

import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile


class CustomDocumentListener : BulkAwareDocumentListener.Simple {
    override fun documentChangedNonBulk(documentEvent: DocumentEvent) {
        try {
            if (!isAppActive()) return
            val document = documentEvent.document
            val file: VirtualFile = getFile(document) ?: return
            val project: Project = getProject(document) ?: return
            if (!isProjectInitialized(project)) return
            val lineStats: LineStats = getLineStats(document) ?: return
            appendHeartbeat(file, project, false, lineStats)
        } catch (e: Exception) { log.error(e) }
    }
}