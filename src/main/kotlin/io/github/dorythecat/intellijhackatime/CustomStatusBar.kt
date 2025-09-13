package io.github.dorythecat.intellijhackatime

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader.getIcon
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget.Multiframe
import com.intellij.openapi.wm.StatusBarWidget.MultipleTextValuesPresentation
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import org.jetbrains.annotations.Contract
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import javax.swing.Icon


class CustomStatusBar : StatusBarWidgetFactory {
    override fun getId(): String { return "Hackatime" }

    @Nls
    override fun getDisplayName(): String { return "Hackatime" }

    override fun isAvailable(project: Project): Boolean { return true }

    override fun createWidget(project: Project): StatusBarWidget { return WakaTimeStatusBarWidget(project) }

    override fun disposeWidget(widget: StatusBarWidget) {}

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean { return true }

    inner class WakaTimeStatusBarWidget @Contract(pure = true) constructor(val project: Project) : StatusBarWidget {
        val statusBar: StatusBar = WindowManager.getInstance().getStatusBar(project)

        override fun ID(): String { return "Hackatime" }

        override fun getPresentation(): StatusBarWidget.WidgetPresentation { return StatusBarPresenter(this) }

        override fun install(statusBar: StatusBar) {}

        override fun dispose() {}

        private inner class StatusBarPresenter(private val widget: WakaTimeStatusBarWidget) :
            MultipleTextValuesPresentation, Multiframe {
            @Deprecated("implement {@link #getPopup()}")
            override fun getPopupStep(): ListPopup? {
                openDashboardWebsite()
                updateStatusBarText()
                widget.statusBar.updateWidget("Hackatime")
                return null
            }

            override fun getSelectedValue(): String? { return getStatusBarText() }

            override fun getIcon(): Icon? {
                val theme = if (!JBColor.isBright()) "dark" else "light"
                return getIcon("status-bar-icon-$theme-theme.svg", IntelliJHackatime::class.java)
            }

            override fun getTooltipText(): String? { return null }

            override fun copy(): StatusBarWidget { return WakaTimeStatusBarWidget(this.widget.project) }

            @NonNls
            override fun ID(): String { return "Hackatime" }

            override fun install(statusBar: StatusBar) {}

            override fun dispose() { Disposer.dispose(widget) }
        }
    }
}