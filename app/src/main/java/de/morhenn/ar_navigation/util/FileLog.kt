package de.morhenn.ar_navigation.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.exitProcess

object FileLog {

    private const val FILE_NAME = "ArNavLog.txt"
    private const val FATAL_TAG = "FATAL"
    private const val MAX_FILE_LINES = 3000
    private const val MAX_RANK = 10
    private var logToLogcat = false
    private var fileDir: File? = null

    fun init(context: Context, logToLogcat: Boolean) {
        fileDir = context.filesDir
        writeBlankLine()
        write("[-- AR-Navigation launched --]")
        FileLog.logToLogcat = logToLogcat
        if (FileLog.logToLogcat) {
            Log.i("de.morhenn.ar_navigation.util.FileLog", "AR-Navigation launched")
        }
    }

    fun d(tag: String, message: String) {
        if (logToLogcat) {
            Log.d(tag, message)
            if (message.length > 4000) {
                Log.d(tag, "Message was too long, a part might have been cut off! Length was ${message.length}")
            }
        }
        write("D/$tag: $message")
    }

    fun d(tag: String, logBuffer: LogBuffer) {
        d(tag, "", logBuffer)
    }

    fun d(tag: String, message: String, logBuffer: LogBuffer) {
        d(tag, "$message\n${logBuffer.retrieveLog()}")
    }

    fun w(tag: String, message: String) {
        if (logToLogcat) {
            Log.w(tag, message)
        }
        write("W/$tag: $message")
    }

    fun e(tag: String, message: String) {
        if (logToLogcat) {
            Log.e(tag, message)
        }
        write("E/$tag: $message")
    }

    fun e(tag: String, throwable: Throwable) {
        if (logToLogcat) {
            Log.e(tag, null, throwable)
        }
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        write("E/$tag: $sw")
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        if (logToLogcat) {
            Log.e(tag, message, throwable)
        }
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        write("E/$tag: $message ${System.lineSeparator()} $sw")
    }

    fun fatal(throwable: Throwable) {
        //surround with try and catch to prevent having to handle a potential log crash (infinite loop)
        try {
            e(FATAL_TAG, "A fatal crash caused the app to stop", throwable)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        exitProcess(1)
    }

    private fun writeBlankLine() {
        val file = File(fileDir, FILE_NAME)
        file.appendText(System.lineSeparator())
    }

    private fun write(text: String) {
        val file = File(fileDir, FILE_NAME)
        file.appendText("${currentTimeAsString()} $text")
        file.appendText(System.lineSeparator())
        var lines = file.readLines()
        val size = lines.size
        val overSize = size - MAX_FILE_LINES
        if (overSize > 0) {
            lines = lines.drop(overSize)
            val result = lines.joinToString(separator = System.lineSeparator())
            file.writeText(result)
            file.appendText(System.lineSeparator())
        }
    }

    private fun currentTimeAsString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return sdf.format(Date())
    }

    open class LogBuffer {
        //TODO limit lines to prevent overflow / heavy memory usage
        private val stringBuilder = StringBuilder()
        fun append(msg: String) {
            stringBuilder.append(msg)
        }

        fun appendLine(msg: String) {
            append("$msg\n")
        }

        fun retrieveLog(): String {
            val result = stringBuilder.toString()
            stringBuilder.clear()
            return result
        }

        fun reset() {
            stringBuilder.clear()
        }
    }

    class RankedLogBuffer : LogBuffer() {
        private val prefixStringBuilder = StringBuilder()
        fun appendLine(msg: String, rank: Int) {
            if (rank < 0 || rank > MAX_RANK) {
                throw IllegalArgumentException("Rank may only be between 0 and $MAX_RANK (inclusive)")
            }
            if (rank != 0) {
                repeat(rank - 1) {
                    append("│   ")
                }
                append("├─ ")
            }
            if (msg.contains("\n")) {
                var newMsg = msg
                if (rank != 0) {
                    repeat(rank - 1) {
                        prefixStringBuilder.append("│   ")
                    }
                    prefixStringBuilder.append("├─  ")    //Two spaces indicate a newline inside the ranked message
                    newMsg = msg.replace("\n", "\n$prefixStringBuilder").removeSuffix("\n$prefixStringBuilder")
                    prefixStringBuilder.clear()
                }
                appendLine(newMsg)
            } else {
                appendLine(msg)
            }
        }
    }
}