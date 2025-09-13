package io.github.dorythecat.intellijhackatime

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginManagerCore.getPlugin
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.editor.*
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
import kotlinx.io.IOException
import java.awt.KeyboardFocusManager
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.math.MathContext
import java.util.*
import java.util.concurrent.ScheduledFuture
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
val lineStatsCache: MutableMap<String, LineStats> = HashMap<String, LineStats>()
var cancelApiKey = false

var todayText: String = " WakaTime loading..."
var todayTextTime: BigDecimal = BigDecimal(0)

val log = com.intellij.openapi.diagnostic.Logger.getInstance("Hackatime")
val dependencies = Dependencies()

const val queueTimeoutSeconds: Long = 30
val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
var scheduledFixture: ScheduledFuture<*>? = null

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

fun saveLineStats(@Nullable file: VirtualFile, lineStats: LineStats) {
    if (lineStats.isOK) lineStatsCache[file.path] = lineStats
}

fun saveLineStats(document: Document, lineStats: LineStats) {
    val file: VirtualFile = getFile(document) ?: return
    saveLineStats(file, lineStats)
}

fun getLineStats(document: Document, editor: Editor): LineStats? {
    for (caret: Caret in editor.caretModel.allCarets) {
        val lineStats = LineStats()
        lineStats.lineCount = document.lineCount
        val position: LogicalPosition = caret.logicalPosition
        lineStats.lineNumber = position.line + 1
        lineStats.cursorPosition = position.column + 1
        if (lineStats.isOK) {
            saveLineStats(document, lineStats)
            return lineStats
        }
    }
    return getLineStats(document)
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
        if (!isBuilding) return@schedule
        val project: Project = getCurrentProject() ?: return@schedule
        if (!isProjectInitialized(project)) return@schedule
        val file: VirtualFile = getCurrentFile(project) ?: return@schedule
        val document: Document = getCurrentDocument(project) ?: return@schedule
        val lineStats: LineStats = getLineStats(document) ?: return@schedule
            appendHeartbeat(file, project, false, lineStats)
    }, 10, TimeUnit.SECONDS)
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

fun openDashboardWebsite() { BrowserUtil.browse(ConfigFile.getDashboardUrl()); }

fun getStatusBarText(): String? { return if (!STATUS_BAR || !READY) "" else todayText }

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
        setupQueueProcessor()
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

    private fun pluginString(): String? {
        return if (IDE_NAME == "") null else "$IDE_NAME/$IDE_VERSION $IDE_NAME-wakatime/$VERSION"
    }

    fun checkApiKey() {
        if (cancelApiKey) return
        ApplicationManager.getApplication().invokeLater(object : Runnable {
            override fun run() {
                // prompt for apiKey if it does not already exist
                val project = getCurrentProject() ?: return
                if (ConfigFile.getApiKey() == "" && !ConfigFile.usingVaultCmd()) {
                    val app: Application = ApplicationManager.getApplication()
                    if (app.isUnitTestMode || !app.isDispatchThread) return
                    try {
                        val apiKey: ApiKey = ApiKey(project)
                        apiKey.promptForApiKey()
                    } catch (e: java.lang.Exception) {
                        log.error(e)
                    } catch (_: Throwable) {
                        log.warn("Unable to prompt for api key because UI not ready.")
                    }
                }
            }
        })
    }

    private fun getBuiltinProxy(): String {
        val proxy = ConfigFile.get("settings", "proxy", false)
        return if (proxy == null || proxy.trim { it <= ' ' } == "") "builtin" else proxy
    }

    private fun buildCliCommand(heartbeat: Heartbeat, extraHeartbeats: ArrayList<Heartbeat>): ArrayList<String> {
        val cmds: ArrayList<String> = ArrayList<String>()
        cmds.add(dependencies.getCLILocation())
        cmds.add("--plugin")
        pluginString() ?: return ArrayList<String>()
        cmds.add(pluginString()!!)
        cmds.add("--entity")
        cmds.add(heartbeat.entity!!)
        cmds.add("--time")
        cmds.add(heartbeat.timestamp!!.toPlainString())
        val apiKey: String = ConfigFile.getApiKey()
        if (apiKey != "") {
            cmds.add("--key")
            cmds.add(apiKey)
        }
        if (heartbeat.lineCount != null) {
            cmds.add("--lines-in-file")
            cmds.add(heartbeat.lineCount.toString())
        }
        if (heartbeat.lineNumber != null) {
            cmds.add("--lineno")
            cmds.add(heartbeat.lineNumber.toString())
        }
        if (heartbeat.cursorPosition != null) {
            cmds.add("--cursorpos")
            cmds.add(heartbeat.cursorPosition.toString())
        }
        if (heartbeat.project != null) {
            cmds.add("--alternate-project")
            cmds.add(heartbeat.project!!)
        }
        if (heartbeat.language != null) {
            cmds.add("--alternate-language")
            cmds.add(heartbeat.language!!)
        }
        if (heartbeat.isWrite == true) cmds.add("--write")
        if (heartbeat.isUnsavedFile == true) cmds.add("--is-unsaved-entity")
        if (heartbeat.isBuilding == true) {
            cmds.add("--category")
            cmds.add("building")
        }
        if (METRICS) cmds.add("--metrics")

        val proxy: String = getBuiltinProxy()
        log.info("built-in proxy will be used: $proxy")
        cmds.add("--proxy")
        cmds.add(proxy)

        if (extraHeartbeats.isNotEmpty()) cmds.add("--extra-heartbeats")
        return cmds
    }

    private fun jsonEscape(s: String?): String? {
        if (s == null) return null
        val escaped = StringBuffer()
        val len = s.length
        for (i in 0..<len) {
            when (val c = s.get(i)) {
                '\\' -> escaped.append("\\\\")
                '"' -> escaped.append("\\\"")
                '\b' -> escaped.append("\\b")
                '\u000c' -> escaped.append("\\f")
                '\n' -> escaped.append("\\n")
                '\r' -> escaped.append("\\r")
                '\t' -> escaped.append("\\t")
                else -> {
                    val isUnicode = (c <= '\u001F') || (c in '\u007F'..'\u009F') || (c in '\u2000'..'\u20FF')
                    if (isUnicode) {
                        escaped.append("\\u")
                        val hex = Integer.toHexString(c.code)
                        var k = 0
                        while (k < 4 - hex.length) {
                            escaped.append('0')
                            k++
                        }
                        escaped.append(hex.uppercase(Locale.getDefault()))
                    } else escaped.append(c)
                }
            }
        }
        return escaped.toString()
    }

    private fun toJSON(extraHeartbeats: ArrayList<Heartbeat>): String {
        val json = StringBuffer();
        json.append("[");
        var first = true;
        for (heartbeat: Heartbeat in extraHeartbeats) {
            val h = StringBuffer();
            h.append("{\"entity\":\"");
            h.append(jsonEscape(heartbeat.entity));
            h.append("\",\"timestamp\":");
            h.append(heartbeat.timestamp!!.toPlainString());
            h.append(",\"is_write\":");
            h.append(heartbeat.isWrite.toString());
            if (heartbeat.lineCount != null) {
                h.append(",\"lines\":");
                h.append(heartbeat.lineCount);
            }
            if (heartbeat.lineNumber != null) {
                h.append(",\"lineno\":");
                h.append(heartbeat.lineNumber);
            }
            if (heartbeat.cursorPosition != null) {
                h.append(",\"cursorpos\":");
                h.append(heartbeat.cursorPosition);
            }
            if (heartbeat.isUnsavedFile == true) h.append(",\"is_unsaved_entity\":true");
            if (heartbeat.isBuilding == true) h.append(",\"category\":\"building\"");
            if (heartbeat.project != null) {
                h.append(",\"alternate_project\":\"");
                h.append(jsonEscape(heartbeat.project));
                h.append("\"");
            }
            if (heartbeat.language != null) {
                h.append(",\"language\":\"");
                h.append(jsonEscape(heartbeat.language));
                h.append("\"");
            }
            h.append("}");
            if (!first) json.append(",");
            json.append(h);
            first = false;
        }
        json.append("]");
        return json.toString();
    }

    private fun sendHeartbeat(heartbeat: Heartbeat, extraHeartbeats: ArrayList<Heartbeat>) {
        val cmds = buildCliCommand(heartbeat, extraHeartbeats)
        if (cmds.isEmpty()) return
        log.debug("Executing CLI: " + Arrays.toString(obfuscateKey(cmds.toTypedArray<String>())))
        try {
            val proc: Process = Runtime.getRuntime().exec(cmds.toTypedArray<String?>())
            if (extraHeartbeats.isNotEmpty()) {
                val json: String = toJSON(extraHeartbeats)
                log.debug(json)
                try {
                    val stdin = BufferedWriter(OutputStreamWriter(proc.outputStream))
                    stdin.write(json)
                    stdin.write("\n")
                    try {
                        stdin.flush()
                        stdin.close()
                    } catch (_: IOException) { /* ignored because wakatime-cli closes pipe after receiving \n */ }
                } catch (e: IOException) { log.error(e); }
            }
            if (DEBUG) {
                val stdout = BufferedReader(InputStreamReader(proc.inputStream))
                val stderr = BufferedReader(InputStreamReader(proc.errorStream))
                proc.waitFor()
                var s: String
                while (stdout.readLine().also { s = it } != null) log.debug(s)
                while (stderr.readLine().also { s = it } != null) log.debug(s)
                log.debug("Command finished with return value: " + proc.exitValue())
            }
        } catch (e: Exception) {
            log.error(e);
            if (dependencies.isWindows() && e.toString().contains("Access is denied")) {
                try {
                    Messages.showWarningDialog(
                        "Microsoft Defender is blocking WakaTime. Please allow " +
                                dependencies.getCLILocation() +
                                " to run so WakaTime can upload code stats to your dashboard.", "Error")
                } catch (e: Exception) { log.error(e) }
            }
        }
    }

    private fun processHeartbeatQueue() {
        if (!READY) return
        if (pluginString() == null) return

        checkApiKey();

        val heartbeat: Heartbeat = heartbeatsQueue.poll() ?: return;
        val extraHeartbeats: ArrayList<Heartbeat> = ArrayList<Heartbeat>();
        while (true) {
            val h: Heartbeat = heartbeatsQueue.poll() ?: break;
            extraHeartbeats.add(h);
        }

        sendHeartbeat(heartbeat, extraHeartbeats);
    }

    private fun setupQueueProcessor() {
        val handler: Runnable = Runnable { processHeartbeatQueue() }
        val delay: Long = queueTimeoutSeconds
        scheduledFixture = scheduler.scheduleAtFixedRate(handler, delay, delay, TimeUnit.SECONDS)
    }
}

private fun ArrayList<String>.add(e: Array<String>) {
    for (s in e) this.add(s)
}