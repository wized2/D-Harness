package com.endroid.dharness.bridge

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Background delays that never touch the main thread. */
object BridgeExec {
    private val io = Executors.newCachedThreadPool { r ->
        Thread(r, "dh-bridge-io").apply { isDaemon = true }
    }

    fun interruptibleSleep(ms: Long) {
        val wait = ms.coerceIn(0L, 30_000L)
        if (wait <= 0L) return
        try {
            Thread.sleep(wait)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun runAsync(block: () -> Unit) {
        io.execute {
            try {
                block()
            } catch (_: Exception) {
            }
        }
    }

    fun <T> callWithTimeout(timeoutMs: Long, block: () -> T): T? {
        val f = io.submit(block)
        return try {
            f.get(timeoutMs.coerceIn(100L, 120_000L), TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            f.cancel(true)
            null
        }
    }
}
