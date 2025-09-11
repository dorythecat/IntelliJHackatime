package io.github.dorythecat.intellijhackatime

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class Configure : AnAction() {
    override fun actionPerformed(p0: AnActionEvent) {
        Messages.showMessageDialog("This is a test", "TEST MESSAGE", Messages.getInformationIcon())
    }
}