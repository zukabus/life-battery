package dev.jenil.capt

/**
 * A bidirectional byte pipe to the printer.
 *
 * Deliberately narrow so the same [CaptSession] and job state machine work
 * over three very different links:
 *  - Android USB host bulk endpoints (the OTG-cable app),
 *  - a TCP socket to an ESP32-S3 acting as a USB bridge,
 *  - a canned script in unit tests.
 *
 * Implementations must be usable from a background thread and may block.
 */
interface CaptTransport {

    /**
     * The IEEE 1284 device ID string, e.g.
     * `MFG:Canon;CMD:CAPT;MDL:LBP2900;CLS:PRINTER;...`
     *
     * Used to confirm the attached printer is actually an LBP2900 before
     * sending anything to it.
     */
    fun deviceId(): String

    /** Writes exactly [length] bytes. Must throw on short write. */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size)

    /**
     * Reads up to [length] bytes into [dest] at [offset].
     * @return the number of bytes actually read; 0 on timeout, -1 on error.
     */
    fun read(dest: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    fun close()
}

/** Raised for any protocol-level failure; the job aborts and reports this. */
class CaptException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Sink for the running commentary. On Android this feeds the on-screen log. */
fun interface CaptLogger {
    fun log(line: String)

    companion object {
        val NONE = CaptLogger { }
        val STDOUT = CaptLogger { println(it) }
    }
}

/** Injectable so tests run instantly instead of actually sleeping. */
fun interface Sleeper {
    fun sleep(millis: Long)

    companion object {
        val REAL = Sleeper { Thread.sleep(it) }
        val NONE = Sleeper { }
    }
}
