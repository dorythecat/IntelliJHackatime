package io.github.dorythecat.intellijhackatime

class LineStats (
    var lineCount: Int? = null,
    var lineNumber: Int? = null,
    var cursorPosition: Int? = null
) {

    val isOK: Boolean get() = lineCount != null && lineNumber != null && cursorPosition != null

    override fun toString(): String {
        return "LineStats(lineCount=$lineCount, lineNumber=$lineNumber, cursorPosition=$cursorPosition)"
    }
}