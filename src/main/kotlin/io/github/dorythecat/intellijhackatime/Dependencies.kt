package io.github.dorythecat.intellijhackatime

import java.io.File
import java.util.*

class Dependencies {
    private var resourcesLocation: String? = null
    private var originalProxyHost: String? = null
    private var originalProxyPort: String? = null
    private val githubReleasesUrl: String = "https://api.github.com/repos/wakatime/wakatime-cli/releases/latest"
    private val githubDownloadUrl: String = "https://github.com/wakatime/wakatime-cli/releases/latest/download"

    fun isWindows(): Boolean {
        return System.getProperty("os.name").contains("Windows")
    }

    fun osname(): String {
        if (isWindows()) return "windows"
        val os = System.getProperty("os.name").lowercase(Locale.getDefault())
        if (os.contains("mac") || os.contains("darwin")) return "darwin"
        if (os.contains("linux")) return "linux"
        return os
    }

    fun architecture(): String {
        val arch = System.getProperty("os.arch")
        if (arch.contains("386") || arch.contains("32")) return "386"
        if (arch == "aarch64") return "arm64"
        if (osname() == "darwin" && arch.contains("arm")) return "arm64"
        if (arch.contains("64")) return "amd64"
        return arch
    }

    fun combinePaths(vararg args: String?): String? {
        var path: File? = null
        for (arg in args)
            if (arg != null)
                path = if (path == null) File(arg) else File(path, arg)
        return path?.toString()
    }

    fun getResourcesLocation(): String? {
        if (resourcesLocation != null) return resourcesLocation

        if (System.getenv("WAKATIME_HOME") != null && !System.getenv("WAKATIME_HOME").trim { it <= ' ' }.isEmpty()) {
            val resourcesFolder: File = File(System.getenv("WAKATIME_HOME"))
            if (resourcesFolder.exists()) {
                resourcesLocation = resourcesFolder.absolutePath
                println("Using \$WAKATIME_HOME for resources folder: $resourcesLocation")
                return resourcesLocation
            }
        }

        if (isWindows()) {
            val windowsHome: File = File(System.getenv("USERPROFILE"))
            val resourcesFolder: File = File(windowsHome, ".wakatime")
            resourcesLocation = resourcesFolder.absolutePath
            return resourcesLocation
        }

        val userHomeDir: File = File(System.getProperty("user.home"))
        val resourcesFolder: File = File(userHomeDir, ".wakatime")
        resourcesLocation = resourcesFolder.absolutePath
        return resourcesLocation
    }

    fun getCLILocation(): String? {
        if (System.getenv("WAKATIME_CLI_LOCATION") != null && !System.getenv("WAKATIME_CLI_LOCATION").trim { it <= ' ' }
                .isEmpty()) {
            val wakatimeCLI = File(System.getenv("WAKATIME_CLI_LOCATION"))
            if (wakatimeCLI.exists()) {
                println("Using \$WAKATIME_CLI_LOCATION as CLI Executable: " + wakatimeCLI)
                return System.getenv("WAKATIME_CLI_LOCATION")
            }
        }

        val ext = if (isWindows()) ".exe" else ""
        return combinePaths(getResourcesLocation(), "wakatime-cli-" + osname() + "-" + architecture() + ext)
    }

    fun isCLIInstalled(): Boolean {
        val cli: File = File(getCLILocation())
        return cli.exists()
    }
}