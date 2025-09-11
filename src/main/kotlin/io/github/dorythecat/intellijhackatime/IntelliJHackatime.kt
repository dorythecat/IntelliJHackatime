package io.github.dorythecat.intellijhackatime

import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.project.Project

class IntelliJHackatime : StartupActivity {
    override fun runActivity(project: Project) {
        println("Test")
    }
}