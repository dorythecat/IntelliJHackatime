package io.github.dorythecat.intellijhackatime

import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginManagerCore.getPlugin
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.jetbrains.cef.remote.thrift.annotation.Nullable
import java.awt.KeyboardFocusManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.math.BigDecimal
import java.math.MathContext
import java.util.*
import java.util.concurrent.TimeUnit


var VERSION: String = ""
var IDE_NAME: String = ""
var IDE_VERSION: String = ""
var READY = false
var DEBUG = false
var METRICS = true
var STATUS_BAR = true
var DEBUG_CHECKED = false
val FREQUENCY = BigDecimal(2 * 60) // max secs between heartbeats for continuous coding

var isBuilding = false
var lastFile: String = ""
var lastTime: BigDecimal = BigDecimal(0)
val heartbeatsQueue: Queue<Heartbeat> = LinkedList<Heartbeat>()

var todayText: String = " WakaTime loading..."
var todayTextTime: BigDecimal = BigDecimal(0)

val log = com.intellij.openapi.diagnostic.Logger.getInstance("Hackatime")
val dependencies = Dependencies()

fun getCurrentTimestamp(): BigDecimal {
    return BigDecimal((System.currentTimeMillis() / 1000.0).toString()).setScale(4).round(MathContext.DECIMAL32)
}

fun isAppActive(): Boolean {
    return KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow != null
}

@Nullable
fun getFile(document: Document): VirtualFile? { return FileDocumentManager.getInstance().getFile(document) }

fun getProject(document: Document): Project? {
    val editors: Array<Editor?> = EditorFactory.getInstance().getEditors(document)
    return if (editors.isNotEmpty()) editors[0]?.project else null
}

fun isProjectInitialized(project: Project?): Boolean { return project?.isInitialized ?: true }

fun getLineStats(document: Document): LineStats? {
    try {
        val text = document.text
        val totalLines = document.lineCount
        val totalChars = text.length
        val totalWords = text.trim { it <= ' ' }.split("\\s+".toRegex()).size
        return LineStats(totalLines, totalWords, totalChars)
    } catch (e: Exception) {
        log.error(e)
        return null
    }
}

fun checkDebug() {
    if (DEBUG_CHECKED) return
    DEBUG_CHECKED = true
    if (!DEBUG) return
    ApplicationManager.getApplication().invokeLater {
        Messages.showWarningDialog(
            "Your IDE may respond slower. Disable debug mode from Tools -> WakaTime Settings.",
            "WakaTime Debug Mode Enabled"
        )
    }
}

fun shouldLogFile(file: VirtualFile?): Boolean {
    if (file == null || file.url.startsWith("mock://")) return false
    return !(file.path == "atlassian-ide-plugin.xml" || file.path.contains("/.idea/workspace.xml"))
}

fun getLanguage(file: VirtualFile): String { return file.fileType.name }

private fun obfuscateKey(cmds: Array<String>): Array<String> {
    val newCmds = ArrayList<String>()
    var lastCmd = ""
    for (cmd: String in cmds) {
        if (lastCmd === "--key") newCmds.add(obfuscateKey(arrayOf(cmd)))
        else newCmds.add(cmd)
        lastCmd = cmd
    }
    return newCmds.toTypedArray<String>()
}

fun updateStatusBarText() {
    // rate limit, to prevent from fetching Today's stats too frequently

    val now: BigDecimal = getCurrentTimestamp()
    if (todayTextTime.add(BigDecimal(60)) > now) return
    todayTextTime = getCurrentTimestamp()

    ApplicationManager.getApplication().executeOnPooledThread {
        val cmds = ArrayList<String>()
        cmds.add(dependencies.getCLILocation())
        cmds.add("--today")

        val apiKey = ConfigFile.getApiKey()
        if (apiKey != "") {
            cmds.add("--key")
            cmds.add(apiKey)
        }

        log.debug("Executing CLI: " + obfuscateKey(cmds.toTypedArray<String>()).contentToString())

        try {
            val proc = Runtime.getRuntime().exec(cmds.toTypedArray<String?>())
            val stdout = BufferedReader(InputStreamReader(proc.inputStream))
            val stderr = BufferedReader(InputStreamReader(proc.errorStream))
            proc.waitFor()
            val output = ArrayList<String?>()
            var s: String?
            while ((stdout.readLine().also { s = it }) != null) output.add(s)
            while ((stderr.readLine().also { s = it }) != null) output.add(s)
            log.debug("Command finished with return value: " + proc.exitValue())
            todayText = " " + java.lang.String.join("", output)
            todayTextTime = getCurrentTimestamp()
        } catch (interruptedException: InterruptedException) {
            log.error(interruptedException)
        } catch (e: java.lang.Exception) {
            log.error(e)
            if (dependencies.isWindows() && e.toString().contains("Access is denied")) {
                try {
                    Messages.showWarningDialog(
                        "Microsoft Defender is blocking WakaTime. Please allow " +
                                dependencies.getCLILocation() +
                                " to run so WakaTime can upload code stats to your dashboard.",
                        "Error"
                    )
                } catch (e: java.lang.Exception) { log.error(e) }
            }
        }
    }
}

fun enoughTimePassed(currentTime: BigDecimal?): Boolean { return lastTime.add(FREQUENCY) < currentTime }

@Nullable
fun getCurrentProject(): Project? {
    var project: Project? = null
    try { project = ProjectManager.getInstance().defaultProject } catch (e: java.lang.Exception) { log.error(e) }
    return project
}

@Nullable
fun getCurrentFile(project: Project): VirtualFile? {
    val editor: Editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null;
    val document: Document = editor.document;
    return getFile(document);
}

@Nullable
fun getCurrentDocument(project: Project): Document? {
    val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
    return editor.document
}

fun setBuildTimeout() {
    AppExecutorUtil.getAppScheduledExecutorService().schedule({
        @Override
        fun run() {
            if (!isBuilding) return
            val project: Project = getCurrentProject() ?: return
            if (!isProjectInitialized(project)) return
            val file: VirtualFile = getCurrentFile(project) ?: return
            val document: Document = getCurrentDocument(project) ?: return
            val lineStats: LineStats = getLineStats(document) ?: return
            appendHeartbeat(file, project, false, lineStats)
        } }, 10, TimeUnit.SECONDS)
}

fun appendHeartbeat(file: VirtualFile, project: Project, isWrite: Boolean, lineStats: LineStats) {
    checkDebug()
    if (!lineStats.isOK) return
    if (!shouldLogFile(file)) return
    if (file.path.contains("/var/cache/")) return
    if (READY) {
        updateStatusBarText()
        WindowManager.getInstance().getStatusBar(project).updateWidget("Hackatime")
    }

    val time: BigDecimal = getCurrentTimestamp()
    if (!isWrite && file.path.equals(lastFile) && !enoughTimePassed(time)) return

    lastFile = file.path
    lastTime = time

    val projectName: String = project.name
    val language: String = getLanguage(file)

    ApplicationManager.getApplication().executeOnPooledThread {
        val h: Heartbeat = Heartbeat()
        h.entity = file.path
        h.timestamp = time
        h.isWrite = isWrite
        h.isUnsavedFile = !file.exists()
        h.project = projectName
        h.language = language
        h.isBuilding = isBuilding
        h.lineCount = lineStats.lineCount
        h.lineNumber = lineStats.lineNumber
        h.cursorPosition = lineStats.cursorPosition

        heartbeatsQueue.add(h)

        if (isBuilding) setBuildTimeout()
    };
}

class IntelliJHackatime : ProjectActivity {
    override suspend fun execute(project: Project) {
        try {
            // support older IDE versions with deprecated PluginManager
            VERSION = PluginManager.getPlugin(PluginId.getId("io.github.dorythecat.IntelliJHackatime"))!!.version
        } catch (_: Exception) {
            // use PluginManagerCore if PluginManager deprecated
            VERSION = getPlugin(PluginId.getId("io.github.dorythecat.IntelliJHackatime"))!!.version
        }
        IDE_NAME = com.intellij.openapi.application.ApplicationInfo.getInstance().versionName
        IDE_VERSION = com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion
        log.info("Hackatime v$VERSION running on $IDE_NAME v$IDE_VERSION")

        setupConfigs()
        setLoggingLevel()
        setupStatusBar()
        checkCli()
        setupEventListeners()
    }

    fun setupConfigs() {
        val debug = ConfigFile.get("settings", "debug", false)
        DEBUG = debug != null && debug.trim { it <= ' ' } == "true"
        val metrics = ConfigFile.get("settings", "metrics", false)
        METRICS = metrics != null && metrics.trim { it <= ' ' } == "true"
    }

    fun setLoggingLevel() {
        if (DEBUG) {
            log.info("Debug logging enabled.")
            System.setProperty("idea.log.debug.categories", "Hackatime")
        } else System.setProperty("idea.log.debug.categories", "")
    }

    fun setupStatusBar() {
        val statusBarVal = ConfigFile.get("settings", "status_bar_enabled", false)
        STATUS_BAR = statusBarVal == null || statusBarVal.trim { it <= ' ' } != "false"
        if (READY) {
            try {
                updateStatusBarText()
                val project: Project = getCurrentProject() ?: return
                val statusbar: StatusBar = WindowManager.getInstance().getStatusBar(project) ?: return
                statusbar.updateWidget("WakaTime")
            } catch (e: java.lang.Exception) { log.error("Failed to update status bar widget: " + e.message) }
        }
    }

    private fun checkCli() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!dependencies.isCLIInstalled()) {
                log.info("Downloading and installing wakatime-cli...")
                dependencies.installCLI()
                READY = true
                log.info("Finished downloading and installing wakatime-cli.")
            } else if (dependencies.isCLIOld()) {
                if (System.getenv("WAKATIME_CLI_LOCATION") != null &&
                    !System.getenv("WAKATIME_CLI_LOCATION").trim { it <= ' ' }.isEmpty()) {
                    if (File(System.getenv("WAKATIME_CLI_LOCATION")).exists())
                        log.warn("\$WAKATIME_CLI_LOCATION is out of date, please update it.")
                } else {
                    log.info("Upgrading wakatime-cli ...")
                    dependencies.installCLI()
                    READY = true
                    log.info("Finished upgrading wakatime-cli.")
                }
            } else {
                READY = true
                log.info("wakatime-cli is up to date.")
            }
            dependencies.createSymlink(
                dependencies.combinePaths(
                    dependencies.getResourcesLocation(),
                    "wakatime-cli"
                ), dependencies.getCLILocation()
            )
            log.debug("wakatime-cli location: " + dependencies.getCLILocation())
        }
    }

    private fun setupEventListeners() {
        ApplicationManager.getApplication().invokeLater {
            val disposable: Disposable = Disposer.newDisposable("WakaTimeListener")
            ApplicationManager.getApplication().messageBus.connect()

            // edit document
            EditorFactory.getInstance().eventMulticaster
                .addDocumentListener(CustomDocumentListener(), disposable)

            // mouse press
            EditorFactory.getInstance().eventMulticaster
                .addEditorMouseListener(CustomEditorMouseListener(), disposable)

            // scroll document
            EditorFactory.getInstance().eventMulticaster
                .addVisibleAreaListener(CustomVisibleAreaListener(), disposable)

            // caret moved
            EditorFactory.getInstance().eventMulticaster
                .addCaretListener(CustomCaretListener(), disposable)
        }
    }
}

private fun ArrayList<String>.add(e: Array<String>) {
    for (s in e) this.add(s)
}