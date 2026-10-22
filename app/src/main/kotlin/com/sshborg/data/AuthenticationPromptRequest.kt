package com.sshborg.data

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One prompt attempt. Cancellation invalidates it immediately, even when UI cleanup is queued.
 * Cleanup and result delivery always run together on the UI thread, with cleanup first so an
 * old attempt cannot dismiss a new prompt started by the resumed caller.
 */
internal class AuthenticationPromptRequest<T : Any>(
    private val executeOnMain: (() -> Unit) -> Unit,
    private val canAccept: (T) -> Boolean,
    private val cleanup: (cancelPrompt: Boolean) -> Unit,
    private val deliver: (T?) -> Unit,
) {
    private val finished = AtomicBoolean(false)
    private val invalidated = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    fun succeed(value: T) = finish(value, cancelPrompt = false, deliverResult = true)

    fun reject() {
        invalidated.set(true)
        finish(null, cancelPrompt = true, deliverResult = true)
    }

    /** The coroutine is already cancelled, so only invalidate and clean up; do not resume it. */
    fun cancel() {
        cancelled.set(true)
        invalidated.set(true)
        finish(null, cancelPrompt = true, deliverResult = false)
    }

    private fun finish(value: T?, cancelPrompt: Boolean, deliverResult: Boolean) {
        if (!finished.compareAndSet(false, true)) return
        executeOnMain {
            val accepted = try {
                cleanup(cancelPrompt || invalidated.get())
                value?.takeIf { !invalidated.get() && canAccept(it) && !invalidated.get() }
            } catch (_: RuntimeException) {
                null
            }
            if (deliverResult && !cancelled.get()) deliver(accepted)
        }
    }
}
