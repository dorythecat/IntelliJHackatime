package io.github.dorythecat.intellijhackatime

import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginManagerCore.getPlugin
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.io.File
import java.math.BigDecimal
import java.math.MathContext


var VERSION: String = ""
var IDE_NAME: String = ""
var IDE_VERSION: String = ""
var READY = false

val log = com.intellij.openapi.diagnostic.Logger.getInstance("Hackatime")

fun getCurrentTimestamp(): BigDecimal {
    return BigDecimal((System.currentTimeMillis() / 1000.0).toString()).setScale(4).round(MathContext.DECIMAL32)
}

class IntelliJHackatime : ProjectActivity {

    private val dependencies = Dependencies()

    override suspend fun execute(project: Project) {
        try {
            // support older IDE versions with deprecated PluginManager
            VERSION = PluginManager.getPlugin(PluginId.getId("io.github.dorythecat.IntelliJHackatime"))!!.version
        } catch (e: Exception) {
            // use PluginManagerCore if PluginManager deprecated
            VERSION = getPlugin(PluginId.getId("io.github.dorythecat.IntelliJHackatime"))!!.version
        }
        IDE_NAME = com.intellij.openapi.application.ApplicationInfo.getInstance().versionName
        IDE_VERSION = com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion
        log.info("Hackatime v$VERSION running on $IDE_NAME v$IDE_VERSION")

        checkCli()
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
}