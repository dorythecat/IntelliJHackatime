package io.github.dorythecat.intellijhackatime

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class Configure : AnAction() {
    override fun actionPerformed(p0: AnActionEvent) {
        val apiKey = Messages.showInputDialog("Enter your API key", "API key", Messages.getQuestionIcon())
        Messages.showMessageDialog("Your API Key is $apiKey", "TEST MESSAGE", Messages.getInformationIcon())
    }
}