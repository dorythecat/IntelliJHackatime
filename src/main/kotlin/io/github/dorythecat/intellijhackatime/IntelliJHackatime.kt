package io.github.dorythecat.intellijhackatime

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import java.io.File
import java.math.BigDecimal


class IntelliJHackatime : StartupActivity {
    companion object {
        var VERSION: String? = null
        var IDE_NAME: String? = null
        var IDE_VERSION: String? = null
        var READY = false

        fun getCurrentTimestamp(): BigDecimal {
            return BigDecimal((System.currentTimeMillis() / 1000.0).toString()).setScale(4, BigDecimal.ROUND_HALF_UP)
        }
    }

    private val dependencies = Dependencies()

    override fun runActivity(project: Project) {
        VERSION = this.javaClass.`package`.implementationVersion
        IDE_NAME = System.getProperty("idea.platform.prefix")
        IDE_VERSION = System.getProperty("idea.version")
        println("Hackatime v$VERSION running on $IDE_NAME v$IDE_VERSION")

        checkCli()
    }

    private fun checkCli() {
        ApplicationManager.getApplication().executeOnPooledThread(object : Runnable {
            override fun run() {
                if (!dependencies.isCLIInstalled()) {
                    println("Downloading and installing wakatime-cli...")
                    dependencies.installCLI()
                    READY = true
                    println("Finished downloading and installing wakatime-cli.")
                } else if (dependencies.isCLIOld()) {
                    if (System.getenv("WAKATIME_CLI_LOCATION") != null && !System.getenv("WAKATIME_CLI_LOCATION")
                            .trim { it <= ' ' }.isEmpty()
                    ) {
                        val wakatimeCLI: File = File(System.getenv("WAKATIME_CLI_LOCATION"))
                        if (wakatimeCLI.exists()) {
                            println("\$WAKATIME_CLI_LOCATION is out of date, please update it.")
                        }
                    } else {
                        println("Upgrading wakatime-cli ...")
                        dependencies.installCLI()
                        READY = true
                        println("Finished upgrading wakatime-cli.")
                    }
                } else {
                    READY = true
                    println("wakatime-cli is up to date.")
                }
                dependencies.createSymlink(
                    dependencies.combinePaths(
                        dependencies.getResourcesLocation(),
                        "wakatime-cli"
                    ), dependencies.getCLILocation()
                )
                println("wakatime-cli location: " + dependencies.getCLILocation())
            }
        })
    }
}