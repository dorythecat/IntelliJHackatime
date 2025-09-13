package io.github.dorythecat.intellijhackatime

import java.math.BigDecimal

class Heartbeat {
    var entity: String? = null
    var lineCount: Int? = null
    var lineNumber: Int? = null
    var cursorPosition: Int? = null
    var timestamp: BigDecimal? = null
    var isWrite: Boolean? = null
    var isUnsavedFile: Boolean? = null
    var project: String? = null
    var language: String? = null
    var isBuilding: Boolean? = null
}