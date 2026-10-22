package com.sshborg.data

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationPromptRequestTest {
    /** A queued UI executor lets tests deliver cancellation/stop before UI callbacks run. */
    private class Prompt {
        private val mainThread = Thread.currentThread()
        private val queue = ConcurrentLinkedQueue<() -> Unit>()
        val expectedObject = Any()
        var foreground = true
        var sameActivity = true
        val results = mutableListOf<Any?>()
        val cancelledPrompts = mutableListOf<Boolean>()
        val events = mutableListOf<String>()
        var cleanupFailure = false
        var verificationFailure = false
        var onCleanup: (() -> Unit)? = null
        var onResult: (() -> Unit)? = null

        val request = AuthenticationPromptRequest<Any>(
            executeOnMain = { action -> queue.add(action) },
            canAccept = { value ->
                assertSame(mainThread, Thread.currentThread())
                if (verificationFailure) error("test verification failure")
                foreground && sameActivity && value === expectedObject
            },
            cleanup = { cancel ->
                assertSame(mainThread, Thread.currentThread())
                cancelledPrompts += cancel
                events += "cleanup"
                onCleanup?.invoke()
                if (cleanupFailure) error("test cleanup failure")
            },
            deliver = { result ->
                assertSame(mainThread, Thread.currentThread())
                results += result
                events += "result"
                onResult?.invoke()
            },
        )

        fun drainUi() {
            while (true) (queue.poll() ?: break).invoke()
        }
    }

    @Test fun `success cleans up the old observer before resuming the caller`() {
        val prompt = Prompt()
        prompt.request.succeed(prompt.expectedObject)
        assertTrue(prompt.results.isEmpty())
        prompt.drainUi()
        assertEquals(listOf("cleanup", "result"), prompt.events)
        assertSame(prompt.expectedObject, prompt.results.single())
        assertEquals(listOf(false), prompt.cancelledPrompts)
    }

    @Test fun `duplicate success callbacks can resume the caller only once`() {
        val prompt = Prompt()
        repeat(3) { prompt.request.succeed(prompt.expectedObject) }
        prompt.drainUi()
        assertEquals(1, prompt.results.size)
        assertEquals(1, prompt.cancelledPrompts.size)
    }

    @Test fun `a rejected attempt cannot be revived by a late success`() {
        val prompt = Prompt()
        prompt.request.reject()
        prompt.request.succeed(prompt.expectedObject)
        prompt.drainUi()
        assertEquals(listOf<Any?>(null), prompt.results)
        assertEquals(listOf(true), prompt.cancelledPrompts)
    }

    @Test fun `stopping before queued success delivery invalidates the grant`() {
        val prompt = Prompt()
        prompt.request.succeed(prompt.expectedObject)
        prompt.foreground = false
        prompt.request.reject()
        // Returning to the foreground cannot revive an attempt invalidated by ON_STOP.
        prompt.foreground = true
        prompt.drainUi()
        assertEquals(listOf<Any?>(null), prompt.results)
        assertEquals(listOf(true), prompt.cancelledPrompts)
    }

    @Test fun `cancellation before success cleans up without resuming the cancelled caller`() {
        val prompt = Prompt()
        prompt.request.cancel()
        prompt.request.succeed(prompt.expectedObject)
        prompt.request.reject()
        prompt.drainUi()
        assertTrue(prompt.results.isEmpty())
        assertEquals(listOf(true), prompt.cancelledPrompts)
    }

    @Test fun `cancellation also suppresses an already queued success`() {
        val prompt = Prompt()
        prompt.request.succeed(prompt.expectedObject)
        prompt.request.cancel()
        prompt.drainUi()
        assertTrue(prompt.results.isEmpty())
        assertEquals(listOf(true), prompt.cancelledPrompts)
    }

    @Test fun `foreground state is checked when the result is delivered`() {
        val prompt = Prompt()
        prompt.request.succeed(prompt.expectedObject)
        prompt.foreground = false
        prompt.drainUi()
        assertNull(prompt.results.single())
    }

    @Test fun `a replacement activity cannot receive the old activity authorization`() {
        val prompt = Prompt()
        prompt.request.succeed(prompt.expectedObject)
        prompt.sameActivity = false
        prompt.drainUi()
        assertNull(prompt.results.single())
    }

    @Test fun `a crypto callback returning a different object is refused`() {
        val prompt = Prompt()
        prompt.request.succeed(Any())
        prompt.drainUi()
        assertNull(prompt.results.single())
    }

    @Test fun `cleanup failure cannot deliver a successful authorization`() {
        val prompt = Prompt()
        prompt.cleanupFailure = true
        prompt.request.succeed(prompt.expectedObject)
        prompt.drainUi()
        assertNull(prompt.results.single())
    }

    @Test fun `verification failure cannot deliver a successful authorization`() {
        val prompt = Prompt()
        prompt.verificationFailure = true
        prompt.request.succeed(prompt.expectedObject)
        prompt.drainUi()
        assertNull(prompt.results.single())
    }

    @Test fun `an error callback emitted while cancelling cannot trigger duplicate completion`() {
        val prompt = Prompt()
        prompt.onCleanup = { prompt.request.reject() }
        prompt.request.reject()
        prompt.drainUi()
        assertEquals(listOf<Any?>(null), prompt.results)
        assertEquals(listOf(true), prompt.cancelledPrompts)
    }

    @Test fun `old cleanup finishes before the resumed caller starts a new prompt`() {
        val prompt = Prompt()
        val next = Prompt()
        prompt.onResult = {
            assertEquals(listOf(false), prompt.cancelledPrompts)
            next.request.succeed(next.expectedObject)
        }
        prompt.request.succeed(prompt.expectedObject)
        prompt.drainUi()
        next.drainUi()
        assertSame(next.expectedObject, next.results.single())
        assertEquals(listOf(false), prompt.cancelledPrompts)
    }

    @Test fun `worker cancellation queues cleanup on the UI thread and rejects stale callbacks`() {
        val prompt = Prompt()
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit { prompt.request.cancel() }.get(5, TimeUnit.SECONDS)
            assertTrue(prompt.cancelledPrompts.isEmpty())
            prompt.request.succeed(prompt.expectedObject)
            prompt.drainUi()
            assertEquals(listOf(true), prompt.cancelledPrompts)
            assertTrue(prompt.results.isEmpty())
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun `concurrent success and rejection produce one denial and one cleanup`() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(20) {
                val prompt = Prompt()
                val start = CountDownLatch(1)
                val success = workers.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    prompt.request.succeed(prompt.expectedObject)
                }
                val rejection = workers.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    prompt.request.reject()
                }
                start.countDown()
                success.get(5, TimeUnit.SECONDS)
                rejection.get(5, TimeUnit.SECONDS)
                prompt.drainUi()
                assertEquals(listOf<Any?>(null), prompt.results)
                assertEquals(listOf(true), prompt.cancelledPrompts)
            }
        } finally {
            workers.shutdownNow()
        }
    }
}
