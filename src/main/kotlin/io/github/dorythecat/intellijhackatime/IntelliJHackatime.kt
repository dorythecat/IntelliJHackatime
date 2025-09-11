package io.github.dorythecat.intellijhackatime

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class IntelliJHackatime : StartupActivity {
    var VERSION: String? = null
    var IDE_NAME: String? = null
    var IDE_VERSION: String? = null

    override fun runActivity(project: Project) {
        VERSION = this.javaClass.`package`.implementationVersion
        IDE_NAME = System.getProperty("idea.platform.prefix")
        IDE_VERSION = System.getProperty("idea.version")
        println("Hackatime v$VERSION running on $IDE_NAME v$IDE_VERSION")
    }
}