package com.sshborg.data

import java.util.concurrent.atomic.AtomicBoolean

/** One transport start, bound to the foreground generation in which it was authorized. */
internal class ConnectionAttemptGrant(private val isCurrent: () -> Boolean) {
    private val consumed = AtomicBoolean(false)

    fun consume(): Boolean {
        if (!consumed.compareAndSet(false, true)) return false
        return try { isCurrent() } catch (_: RuntimeException) { false }
    }
}
