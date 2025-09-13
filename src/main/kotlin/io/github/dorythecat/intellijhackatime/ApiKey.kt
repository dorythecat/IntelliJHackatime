package io.github.dorythecat.intellijhackatime

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Desktop
import java.awt.GridLayout
import java.io.IOException
import java.net.URISyntaxException
import java.util.*
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener


class ApiKey(project: Project?) : DialogWrapper(project, true) {
    private val panel: JPanel
    private val label: JLabel
    private val input: JTextField
    private val link: LinkPane

    init {
        title = "WakaTime API Key"
        setOKButtonText("Save")
        panel = JPanel()
        panel.setLayout(GridLayout(0, 1))
        label = JLabel("Enter your WakaTime API key:", JLabel.CENTER)
        panel.add(label)
        input = JTextField(36)
        panel.add(input)
        link = LinkPane("https://wakatime.com/api-key")
        panel.add(link)

        init()
    }

    override fun createCenterPanel(): JComponent { return panel }

    override fun doValidate(): ValidationInfo? {
        val apiKey = input.text
        try {
            UUID.fromString(apiKey.replaceFirst("^waka_".toRegex(), ""))
        } catch (_: Exception) { return ValidationInfo("Invalid api key.") }
        return null
    }

    public override fun doOKAction() {
        ConfigFile.setApiKey(input.text)
        super.doOKAction()
    }

    override fun doCancelAction() {
        cancelApiKey = true
        super.doCancelAction()
    }

    fun promptForApiKey(): String {
        input.text = ConfigFile.getApiKey()
        this.show()
        return input.text
    }
}

internal class LinkPane(private val url: String?) : JTextPane() {
    init {
        this.isEditable = false
        this.addHyperlinkListener(UrlHyperlinkListener())
        this.contentType = "text/html"
        this.setBackground(JBColor(Color(0x000000), Color(0x000000)))
        this.setText(url)
    }

    private inner class UrlHyperlinkListener : HyperlinkListener {
        override fun hyperlinkUpdate(event: HyperlinkEvent) {
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                try {
                    Desktop.getDesktop().browse(event.url.toURI())
                } catch (e: IOException) {
                    throw RuntimeException("Can't open URL", e)
                } catch (e: URISyntaxException) {
                    throw RuntimeException("Can't open URL", e)
                }
            }
        }
    }

    override fun setText(text: String?) {
        super.setText("<html><body style=\"text-align:center;\"><a href=\"$url\">$text</a></body></html>")
    }
}