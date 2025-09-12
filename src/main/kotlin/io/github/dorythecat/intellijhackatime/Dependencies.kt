package io.github.dorythecat.intellijhackatime

import com.jetbrains.cef.remote.thrift.annotation.Nullable
import java.io.*
import java.math.BigInteger
import java.net.MalformedURLException
import java.net.URL
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager


class Response(var statusCode: Int, var body: String?, @param:Nullable var lastModified: String?)

class Dependencies {
    private var resourcesLocation: String = ""
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

    fun combinePaths(vararg args: String?): String {
        var path: File? = null
        for (arg in args)
            if (arg != null)
                path = if (path == null) File(arg) else File(path, arg)
        return path.toString()
    }

    fun getResourcesLocation(): String {
        if (resourcesLocation != "") return resourcesLocation

        if (System.getenv("WAKATIME_HOME") != null && !System.getenv("WAKATIME_HOME").trim { it <= ' ' }.isEmpty()) {
            val resourcesFolder: File = File(System.getenv("WAKATIME_HOME"))
            if (resourcesFolder.exists()) {
                resourcesLocation = resourcesFolder.absolutePath
                log.info("Using \$WAKATIME_HOME for resources folder: $resourcesLocation")
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

    fun getCLILocation(): String {
        if (System.getenv("WAKATIME_CLI_LOCATION") != null && !System.getenv("WAKATIME_CLI_LOCATION").trim { it <= ' ' }
                .isEmpty()) {
            val wakatimeCLI = File(System.getenv("WAKATIME_CLI_LOCATION"))
            if (wakatimeCLI.exists()) {
                log.info("Using \$WAKATIME_CLI_LOCATION as CLI Executable: $wakatimeCLI")
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

    private fun reportMissingPlatformSupport(osname: String, architecture: String) {
        val url =
            "https://api.wakatime.com/api/v1/cli-missing?osname=$osname&architecture=$architecture&plugin=$IDE_NAME"
        try {
            getUrlAsString(url, null, false)
        } catch (e: Exception) { log.error(e) }
    }

    private fun checkMissingPlatformSupport() {
        val osname: String = osname()
        val arch: String = architecture()

        val validCombinations = arrayOf<String>(
            "darwin-amd64",
            "darwin-arm64",
            "freebsd-386",
            "freebsd-amd64",
            "freebsd-arm",
            "linux-386",
            "linux-amd64",
            "linux-arm",
            "linux-arm64",
            "netbsd-386",
            "netbsd-amd64",
            "netbsd-arm",
            "openbsd-386",
            "openbsd-amd64",
            "openbsd-arm",
            "openbsd-arm64",
            "windows-386",
            "windows-amd64",
            "windows-arm64",
        )
        if (!listOf(*validCombinations).contains("$osname-$arch")) {
            log.warn("Platform $osname-$arch is not officially supported by wakatime-cli")
            reportMissingPlatformSupport(osname, arch)
        }
    }

    fun latestCliVersion(): String {
        try {
            val resp =
                getUrlAsString(githubReleasesUrl, ConfigFile.get("internal", "cli_version_last_modified", true), true)
                    ?: return "Unknown"
            val p: Pattern = Pattern.compile(".*\"tag_name\":\\s*\"([^\"]+)\",.*")
            val m: Matcher = p.matcher(resp.body)
            if (m.find()) {
                val cliVersion: String = m.group(1)
                if (resp.lastModified != null) {
                    ConfigFile["internal", "cli_version_last_modified", true] = resp.lastModified
                    ConfigFile["internal", "cli_version", true] = cliVersion
                }
                val now: BigInteger = getCurrentTimestamp().toBigInteger()
                ConfigFile["internal", "cli_version_last_accessed", true] = now.toString()
                return cliVersion
            }
        } catch (e: java.lang.Exception) { log.error(e) }
        return "Unknown"
    }

    private fun getCLIDownloadUrl(): String {
        return "https://github.com/wakatime/wakatime-cli/releases/download/" + latestCliVersion() + "/wakatime-cli-" + osname() + "-" + architecture() + ".zip"
    }

    fun getUrlAsString(url: String, @Nullable lastModified: String?, updateLastModified: Boolean): Response? {
        val text = StringBuilder()

        var downloadUrl: URL? = null
        try {
            downloadUrl = URL(url)
        } catch (e: MalformedURLException) {
            log.error("getUrlAsString($url) failed to init new URL")
            log.error(e)
            return null
        }

        log.debug("getUrlAsString($downloadUrl)")

        var responseLastModified: String? = null
        var statusCode = -1
        try {
            val conn = downloadUrl.openConnection() as HttpsURLConnection
            conn.setRequestProperty("User-Agent", "github.com/wakatime/jetbrains-wakatime")
            if (lastModified != null && lastModified.trim { it <= ' ' } != "") {
                conn.setRequestProperty("If-Modified-Since", lastModified.trim { it <= ' ' })
            }
            statusCode = conn.getResponseCode()
            if (statusCode == 304) return null
            val inputStream = downloadUrl.openStream()
            val buffer = ByteArray(4096)
            while (inputStream.read(buffer) != -1) {
                text.append(String(buffer, charset("UTF-8")))
            }
            inputStream.close()
            if (updateLastModified && conn.getResponseCode() == 200) responseLastModified =
                conn.getHeaderField("Last-Modified")
        } catch (e: java.lang.RuntimeException) {
            log.error(e)
            try {
                // try downloading without verifying SSL cert (https://github.com/wakatime/jetbrains-wakatime/issues/46)
                val SSL_CONTEXT = SSLContext.getInstance("SSL")
                SSL_CONTEXT.init(null, arrayOf<TrustManager>(LocalSSLTrustManager()), null)
                HttpsURLConnection.setDefaultSSLSocketFactory(SSL_CONTEXT.getSocketFactory())
                val conn = downloadUrl.openConnection() as HttpsURLConnection
                conn.setRequestProperty("User-Agent", "github.com/wakatime/jetbrains-wakatime")
                if (lastModified != null && lastModified.trim { it <= ' ' } != "") {
                    conn.setRequestProperty("If-Modified-Since", lastModified.trim { it <= ' ' })
                }
                statusCode = conn.getResponseCode()
                if (statusCode == 304) return null
                val inputStream = conn.getInputStream()
                val buffer = ByteArray(4096)
                while (inputStream.read(buffer) != -1) {
                    text.append(String(buffer, charset("UTF-8")))
                }
                inputStream.close()
                if (updateLastModified && conn.getResponseCode() == 200) responseLastModified =
                    conn.getHeaderField("Last-Modified")
            } catch (e: Exception) { log.error(e) }
        } catch (e: Exception) { log.error(e) }
        return Response(statusCode, text.toString(), responseLastModified)
    }

    @Throws(IOException::class)
    private fun unzip(zipFile: String, outputDir: File) {
        if (!outputDir.exists()) outputDir.mkdirs()

        val buffer = ByteArray(1024)
        val zis = ZipInputStream(FileInputStream(zipFile))
        var ze = zis.getNextEntry()

        while (ze != null) {
            val fileName = ze.getName()
            val newFile = File(outputDir, fileName)

            if (ze.isDirectory) newFile.mkdirs()
            else {
                val fos = FileOutputStream(newFile.absolutePath)
                var len: Int
                while ((zis.read(buffer).also { len = it }) > 0) fos.write(buffer, 0, len)
                fos.close()
            }

            ze = zis.getNextEntry()
        }

        zis.closeEntry()
        zis.close()
    }

    private fun isSymLink(filepath: File): Boolean {
        try {
            return Files.isSymbolicLink(filepath.toPath())
        } catch (e: SecurityException) {
            log.error(e)
            return false
        }
    }

    private fun isDirectory(filepath: File): Boolean {
        try {
            return filepath.isDirectory()
        } catch (e: SecurityException) {
            log.error(e)
            return false
        }
    }

    private fun recursiveDelete(path: File) {
        if (path.exists()) {
            if (isDirectory(path)) {
                val files = path.listFiles()
                for (i in files!!.indices) {
                    if (isDirectory(files[i])) recursiveDelete(files[i]!!)
                    else files[i]!!.delete()
                }
            }
            path.delete()
        }
    }

    fun downloadFile(url: String, saveAs: String): Boolean {
        val outFile = File(saveAs)

        // create output directory if does not exist
        val outDir = outFile.getParentFile()
        if (!outDir.exists()) outDir.mkdirs()

        var downloadUrl: URL? = null
        try {
            downloadUrl = URL(url)
        } catch (e: MalformedURLException) {
            log.error("DownloadFile($url) failed to init new URL")
            log.error(e)
            return false
        }

        log.debug("DownloadFile($downloadUrl)")

        var rbc: ReadableByteChannel? = null
        var fos: FileOutputStream? = null
        try {
            rbc = Channels.newChannel(downloadUrl.openStream())
            fos = FileOutputStream(saveAs)
            fos.getChannel().transferFrom(rbc, 0, Long.Companion.MAX_VALUE)
            fos.close()
            return true
        } catch (e: RuntimeException) {
            log.error(e)
            try {
                // try downloading without verifying SSL cert (https://github.com/wakatime/jetbrains-wakatime/issues/46)
                val SSL_CONTEXT = SSLContext.getInstance("SSL")
                SSL_CONTEXT.init(null, arrayOf<TrustManager>(LocalSSLTrustManager()), null)
                HttpsURLConnection.setDefaultSSLSocketFactory(SSL_CONTEXT.socketFactory)
                val conn = downloadUrl.openConnection() as HttpsURLConnection
                conn.setRequestProperty("User-Agent", "github.com/wakatime/jetbrains-wakatime")
                val inputStream: InputStream = conn.getInputStream()
                fos = FileOutputStream(saveAs)
                var bytesRead = -1
                val buffer = ByteArray(4096)
                while ((inputStream.read(buffer).also { bytesRead = it }) != -1) {
                    fos.write(buffer, 0, bytesRead)
                }
                inputStream.close()
                fos.close()
                return true
            } catch (e: NoSuchAlgorithmException) {
                log.error(e)
            } catch (e: KeyManagementException) {
                log.error(e)
            } catch (e: IOException) {
                log.error(e)
            } catch (e: IllegalArgumentException) {
                log.error(e)
            } catch (e: java.lang.Exception) {
                log.error(e)
            }
        } catch (e: IOException) { log.error(e) }
        return false
    }

    @Throws(IOException::class)
    private fun makeExecutable(filePath: String) {
        val file = File(filePath)
        try { file.setExecutable(true) } catch (e: SecurityException) { println(e) }
    }

    fun installCLI() {
        val resourceDir = File(getResourcesLocation())
        if (!resourceDir.exists()) resourceDir.mkdirs()

        checkMissingPlatformSupport()

        val url: String = getCLIDownloadUrl()
        val zipFile: String = combinePaths(getResourcesLocation(), "wakatime-cli.zip")!!

        if (downloadFile(url, zipFile)) {
            // Delete old wakatime-cli if it exists

            val file = File(getCLILocation())
            recursiveDelete(file)

            val outputDir = File(getResourcesLocation())
            try {
                unzip(zipFile, outputDir)
                if (!isWindows()) makeExecutable(getCLILocation())
                val oldZipFile = File(zipFile)
                oldZipFile.delete()
            } catch (e: IOException) { log.error(e) }
        }
    }

    fun isCLIOld(): Boolean {
        if (!isCLIInstalled()) return false
        val cmds = ArrayList<String?>()
        cmds.add(getCLILocation())
        cmds.add("--version")
        try {
            val p = Runtime.getRuntime().exec(cmds.toTypedArray<String?>())
            val stdInput = BufferedReader(InputStreamReader(p.inputStream))
            val stdError = BufferedReader(InputStreamReader(p.errorStream))
            p.waitFor()
            var output = ""
            var s: String?
            while ((stdInput.readLine().also { s = it }) != null) output += s
            while ((stdError.readLine().also { s = it }) != null) output += s
            log.info("wakatime-cli local version output: \"$output\"")
            log.info("wakatime-cli local version exit code: " + p.exitValue())

            if (p.exitValue() != 0) return true

            // disable updating wakatime-cli when it was built from source
            if (output.trim { it <= ' ' } == "<local-build>") return false

            //val accessed: String? = ConfigFile.get("internal", "cli_version_last_accessed", true)
            val now: BigInteger = getCurrentTimestamp().toBigInteger()

            val cliVersion = latestCliVersion()
            println("Latest wakame-cli version: $cliVersion")
            if (output.trim { it <= ' ' } == cliVersion) return false
        } catch (e: java.lang.Exception) { log.error(e) }
        return true
    }

    fun createSymlink(source: String, destination: String) {
        val sourceLink = File(source)
        if (isDirectory(sourceLink)) recursiveDelete(sourceLink)
        if (!isWindows()) {
            if (!isSymLink(sourceLink)) {
                recursiveDelete(sourceLink)
                try {
                    Files.createSymbolicLink(sourceLink.toPath(), File(destination).toPath())
                } catch (e: java.lang.Exception) {
                    println(e)
                    try {
                        Files.copy(File(destination).toPath(), sourceLink.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } catch (e: java.lang.Exception) { log.error(e) }
                }
            }
        } else {
            try {
                Files.copy(File(destination).toPath(), sourceLink.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.lang.Exception) { log.error(e) }
        }
    }
}