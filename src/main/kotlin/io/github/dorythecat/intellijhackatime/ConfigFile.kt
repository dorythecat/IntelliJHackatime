package io.github.dorythecat.intellijhackatime

import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.IOException
import java.io.PrintWriter
import java.io.UnsupportedEncodingException
import java.util.*


class ConfigFile {
    companion object {
        private val fileName: String = ".wakatime.cfg"
        private val internalFileName: String = "wakatime-internal.cfg"
        private val defaultDashboardUrl: String = "https://wakatime.com/dashboard"
        private var cachedHomeFolder: String? = null
        private var _api_key: String = ""
        private var _dashboard_url: String = ""
        private var _usingVaultCmd: Boolean = false

        private fun getConfigFilePath(internal: Boolean): String {
            if (cachedHomeFolder == null) {
                if (System.getenv("WAKATIME_HOME") != null && !System.getenv("WAKATIME_HOME").trim { it <= ' ' }
                        .isEmpty()) {
                    val folder: File = File(System.getenv("WAKATIME_HOME"))
                    if (folder.exists()) {
                        cachedHomeFolder = folder.getAbsolutePath()
                        println("Using \$WAKATIME_HOME for config folder: $cachedHomeFolder")
                        return (if (internal)
                            File(File(cachedHomeFolder, ".wakatime"), internalFileName)
                        else File(cachedHomeFolder, fileName)).absolutePath
                    }
                }
                cachedHomeFolder = File(System.getProperty("user.home")).absolutePath
                println("Using \$HOME for config folder: $cachedHomeFolder")
            }
            return (if (internal)
                File(File(cachedHomeFolder, ".wakatime"), internalFileName)
            else File(cachedHomeFolder, fileName)).absolutePath
        }

        operator fun set(section: String, key: String?, internal: Boolean, `val`: String?) {
            var key = key
            var `val` = `val`
            key = removeNulls(key)
            `val` = removeNulls(`val`)

            val file: String = getConfigFilePath(internal)
            var contents = StringBuilder()
            try {
                val br = BufferedReader(FileReader(file))
                try {
                    var currentSection = ""
                    var line = br.readLine()
                    var found = false
                    while (line != null) {
                        line = removeNulls(line)
                        if (line!!.trim { it <= ' ' }.startsWith("[") && line.trim { it <= ' ' }.endsWith("]")) {
                            if (section.lowercase(Locale.getDefault()) == currentSection && !found) {
                                contents.append(key + " = " + `val` + "\n")
                                found = true
                            }
                            currentSection = line.trim { it <= ' ' }.substring(1, line.trim { it <= ' ' }.length - 1)
                                .lowercase(Locale.getDefault())
                            contents.append(line + "\n")
                        } else {
                            if (section.lowercase(Locale.getDefault()) == currentSection) {
                                val parts: Array<String?> =
                                    line.split("=".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                                val currentKey = parts[0]!!.trim { it <= ' ' }
                                if (currentKey == key) {
                                    if (found) continue
                                    contents.append(key + " = " + `val` + "\n")
                                    found = true
                                } else contents.append(line + "\n")
                            } else contents.append(line + "\n")
                        }
                        line = br.readLine()
                    }
                    if (!found) {
                        if (section.lowercase(Locale.getDefault()) != currentSection)
                            contents.append("[" + section.lowercase(Locale.getDefault()) + "]\n")
                        contents.append("$key = $`val`\n")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try { br.close() } catch (e: IOException) { e.printStackTrace() }
                }
            } catch (e1: FileNotFoundException) {
                // cannot read config file, so create it

                contents = StringBuilder()
                contents.append("[" + section.lowercase(Locale.getDefault()) + "]\n")
                contents.append(key + " = " + `val` + "\n")
            }

            var writer: PrintWriter? = null
            try {
                writer = PrintWriter(file, "UTF-8")
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            } catch (e: UnsupportedEncodingException) {
                e.printStackTrace()
            }
            if (writer != null) {
                writer.print(contents.toString())
                writer.close()
            }
        }

        fun getApiKey(): String {
            if (_usingVaultCmd) return ""
            if (_api_key != "") return _api_key

            var apiKey = get("settings", "api_key", false)
            if (apiKey == null) {
                val vaultCmd = get("settings", "api_key_vault_cmd", false)
                if (vaultCmd != null && vaultCmd.trim { it <= ' ' } != "") {
                    _usingVaultCmd = true
                    return ""
                }
                apiKey = ""
            }

            _api_key = apiKey
            return apiKey
        }

        fun usingVaultCmd(): Boolean {
            return _usingVaultCmd
        }

        fun setApiKey(apiKey: String) {
            set("settings", "api_key", false, apiKey)
            _api_key = apiKey
        }

        fun getDashboardUrl(): String {
            if (_dashboard_url != "") return _dashboard_url

            val apiUrl = get("settings", "api_url", false)
            _dashboard_url = if (apiUrl == null) defaultDashboardUrl else convertApiUrlToDashboardUrl(apiUrl)
            return _dashboard_url
        }

        private fun convertApiUrlToDashboardUrl(apiUrl: String): String {
            val apiIndex = apiUrl.indexOf("/api")
            return if (apiIndex == -1) defaultDashboardUrl else apiUrl.take(apiIndex)
        }

        private fun removeNulls(s: String?): String? {
            return s?.replace("\u0000", "")
        }

        fun get(section: String, key: String?, internal: Boolean): String? {
            val file: String = getConfigFilePath(internal)
            var value = ""
            try {
                val br = BufferedReader(FileReader(file))
                var currentSection = ""
                try {
                    var line = br.readLine()
                    while (line != null) {
                        if (line.trim { it <= ' ' }.startsWith("[") && line.trim { it <= ' ' }.endsWith("]")) {
                            currentSection = line.trim { it <= ' ' }.substring(1, line.trim { it <= ' ' }.length - 1)
                                .lowercase(Locale.getDefault())
                        } else {
                            if (section.lowercase(Locale.getDefault()) == currentSection) {
                                val parts: Array<String> =
                                    line.split("=".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                                if (parts.size != 2 || parts[0].trim { it <= ' ' } != key) continue
                                value = parts[1].trim { it <= ' ' }
                                br.close()
                                return value
                            }
                        }
                        line = br.readLine()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally { try { br.close() } catch (e: IOException) { e.printStackTrace() } }
            } catch (e: FileNotFoundException) { log.debug(e) }
            return value
        }
    }
}