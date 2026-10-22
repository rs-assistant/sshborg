package com.sshborg.data.ssh

import com.jcraft.jsch.JSchException
import com.sshborg.TestConfig
import java.security.Security
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The SSH layer against a real server, which is the only place some of this can be checked at
 * all: whether a key actually authenticates is a fact about a server, not about our code.
 *
 * Run scripts/run-local-security-tests.py for disposable loopback fixtures. Direct Gradle runs
 * skip this suite unless the SSHBORG_SSH_* environment or test.properties supplies a server.
 */
class SshIntegrationTest {

    private val configured = TestConfig.sshServer()

    private fun server(): TestConfig.Server {
        assumeTrue(TestConfig.NO_SERVER, configured != null)
        return configured!!
    }

    private fun connect(auth: SshAuth, throughItself: Boolean = false): ShellSession {
        val s = server()
        val params = SshConnectionParams(
            hostname = s.host,
            port = s.port,
            username = s.user,
            auth = auth,
            jumpHosts = if (!throughItself) emptyList() else listOf(
                JumpHost(host = s.host, port = s.port, username = s.user, knownHostsEntry = null, auth = auth),
            ),
        )
        return runBlocking { SshManager.openShell(params) { _, _, _ -> true } }
    }

    /**
     * Asks the shell to say something and waits until it does. The word is put together by the
     * shell itself, so the echo of the command cannot be mistaken for its output.
     */
    private fun ShellSession.assertAlive() {
        write("printf 'sshborg%s\\n' '-alive'\n".toByteArray())
        val deadline = System.currentTimeMillis() + 20_000
        val seen = StringBuilder()
        val buffer = ByteArray(4096)
        while (System.currentTimeMillis() < deadline) {
            if (inputStream.available() > 0) {
                val read = inputStream.read(buffer)
                if (read < 0) break
                seen.append(String(buffer, 0, read))
                if (seen.contains("sshborg-alive")) return
            } else {
                Thread.sleep(25)
            }
        }
        fail("the shell never answered; what came back was:\n$seen")
    }

    /**
     * Issue #18 from end to end, per algorithm: an encrypted key is imported the way the app
     * imports it, and what the app would have stored is then used to log in. Until 1.18.0 this
     * could not work for anyone — the key was stored still encrypted and the passphrase thrown
     * away — and nothing in the app noticed, which is why this test exists at all.
     */
    private fun unlockAndLogIn(name: String) {
        val s = server()
        val imported = SshManager.importPrivateKey(s.key(name), s.keyPassphrase)
        assertFalse("what we store must not be encrypted", SshManager.isKeyEncrypted(imported.pem))
        val session = connect(SshAuth.PublicKey(imported.pem))
        try {
            session.assertAlive()
        } finally {
            session.disconnect()
        }
    }

    @Test fun `an encrypted ed25519 key logs in after import`() = unlockAndLogIn("i_ed25519")
    @Test fun `an encrypted rsa key logs in after import`() = unlockAndLogIn("i_rsa")
    @Test fun `an encrypted ecdsa key logs in after import`() = unlockAndLogIn("i_ecdsa")

    @Test fun `an unencrypted key logs in`() {
        val session = connect(SshAuth.PublicKey(server().key("i_plain")))
        try {
            session.assertAlive()
        } finally {
            session.disconnect()
        }
    }

    /**
     * What makes the tests above mean anything: a key the server does not know must be turned
     * away. Uses the key corpus, whose keys are deliberately in no authorized_keys anywhere.
     */
    @Test fun `a key the server does not know is refused`() {
        val corpus = TestConfig.directory("KEY_CORPUS")
        assumeTrue(TestConfig.NO_KEY_CORPUS, corpus != null)
        val stranger = java.io.File(corpus, "k_plain")
        assumeTrue("k_plain is missing from the key corpus", stranger.isFile)
        try {
            connect(SshAuth.PublicKey(stranger.readText())).disconnect()
            fail("a key that is in no authorized_keys must not get in")
        } catch (e: JSchException) {
            assertTrue("refused, but not for the right reason: ${e.message}",
                e.message?.contains("Auth fail", ignoreCase = true) == true ||
                e.message?.contains("auth", ignoreCase = true) == true)
        }
    }

    /**
     * A jump chain, with the server as its own hop. Worth the oddity: the chain is where a lost
     * banner once deadlocked the connection for good — the target's greeting arrived before
     * anything was reading the channel and was dropped silently.
     */
    @Test fun `a jump chain reaches the target`() {
        val session = connect(SshAuth.PublicKey(server().key("i_plain")), throughItself = true)
        try {
            session.assertAlive()
            assertTrue("a hop with no stored key should hand one back",
                session.newJumpHostKeyLines.isNotEmpty())
        } finally {
            session.disconnect()
        }
    }

    /** A host key we hand back has to be accepted next time without asking the user again. */
    @Test fun `the known hosts line it produces is good enough for the next connect`() {
        val s = server()
        val auth = SshAuth.PublicKey(s.key("i_plain"))
        val first = connect(auth)
        val line = first.hostKeyLine
        first.disconnect()
        assertTrue("a known-hosts line should name the host", line.contains(s.host))

        var asked = false
        val params = SshConnectionParams(
            hostname = s.host, port = s.port, username = s.user, auth = auth, knownHostsEntry = line,
        )
        val second = runBlocking { SshManager.openShell(params) { _, _, _ -> asked = true; true } }
        try {
            second.assertAlive()
        } finally {
            second.disconnect()
        }
        assertFalse("a stored host key must not be asked about again", asked)
    }

    /** A changed pin is not first-use trust: it must fail before another acceptance prompt. */
    @Test fun `a changed target host key is refused without asking to trust it again`() {
        val s = server()
        val params = SshConnectionParams(
            hostname = s.host, port = s.port, username = s.user,
            auth = SshAuth.PublicKey(s.key("i_plain")), knownHostsEntry = mismatchedPin(),
        )
        var asked = false
        assertThrows(JSchException::class.java) {
            runBlocking { SshManager.openShell(params) { _, _, _ -> asked = true; true } }.disconnect()
        }
        assertFalse("a changed pin must not be presented as a new host", asked)
    }

    @Test fun `a changed jump host key is refused without asking to trust it again`() {
        val s = server()
        val auth = SshAuth.PublicKey(s.key("i_plain"))
        val params = SshConnectionParams(
            hostname = s.host, port = s.port, username = s.user, auth = auth,
            jumpHosts = listOf(JumpHost(s.host, s.port, s.user, mismatchedPin(), auth)),
        )
        var asked = false
        assertThrows(JSchException::class.java) {
            runBlocking { SshManager.openShell(params) { _, _, _ -> asked = true; true } }.disconnect()
        }
        assertFalse("a changed jump pin must stop the chain before target trust is requested", asked)
    }

    @Test fun `rejecting first use host trust prevents a shell connection`() {
        val s = server()
        val params = SshConnectionParams(
            hostname = s.host, port = s.port, username = s.user,
            auth = SshAuth.PublicKey(s.key("i_plain")),
        )
        var asked = false
        assertThrows(JSchException::class.java) {
            runBlocking { SshManager.openShell(params) { _, _, _ -> asked = true; false } }.disconnect()
        }
        assertTrue("first use must ask for explicit trust", asked)
    }

    private fun mismatchedPin(): String {
        val corpus = TestConfig.directory("KEY_CORPUS")
        assumeTrue(TestConfig.NO_KEY_CORPUS, corpus != null)
        val original = connect(SshAuth.PublicKey(server().key("i_plain")))
        val marker = try { original.hostKeyLine.substringBefore(' ') } finally { original.disconnect() }
        val publicKey = java.io.File(corpus, "k_plain.pub").readText().trim().split(Regex("\\s+"))
        return "$marker ${publicKey[0]} ${publicKey[1]}"
    }

    companion object {
        /**
         * Android ships an old BouncyCastle, so the app replaces the provider at startup and the
         * SSH layer counts on it being there. Mirrors SshBorgApp.onCreate.
         */
        @BeforeClass @JvmStatic fun useOurBouncyCastle() {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
    }
}
