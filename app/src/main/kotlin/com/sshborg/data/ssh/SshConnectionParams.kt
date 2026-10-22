package com.sshborg.data.ssh

/**
 * A single local port-forwarding rule (-L).
 * Connections to [bindAddress]:[localPort] are tunnelled to [remoteHost]:[remotePort]
 * through the SSH session.
 */
data class PortForwarding(
    val bindAddress: String = "127.0.0.1",
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
)

/**
 * Parses a newline-separated list of -L forwarding specs into [PortForwarding] objects.
 * Accepted formats:  localPort:remoteHost:remotePort
 *                    bindAddress:localPort:remoteHost:remotePort
 * Lines that are blank or unparseable are silently skipped.
 */
fun parsePortForwardings(raw: String?): List<PortForwarding> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.lines().mapNotNull { line ->
        val spec = line.trim().removePrefix("-L").trim()
        if (spec.isEmpty()) return@mapNotNull null
        val parts = spec.split(":")
        when (parts.size) {
            3 -> {
                val lPort = parts[0].toIntOrNull() ?: return@mapNotNull null
                val rPort = parts[2].toIntOrNull() ?: return@mapNotNull null
                PortForwarding(localPort = lPort, remoteHost = parts[1], remotePort = rPort)
            }
            4 -> {
                val lPort = parts[1].toIntOrNull() ?: return@mapNotNull null
                val rPort = parts[3].toIntOrNull() ?: return@mapNotNull null
                PortForwarding(bindAddress = parts[0], localPort = lPort, remoteHost = parts[2], remotePort = rPort)
            }
            else -> null
        }
    }
}

/** A single jump host in a ProxyJump chain. */
data class JumpHost(
    val host: String,
    val port: Int,
    /** If null, the target host's username is used for this hop. */
    val username: String?,
    /** Known-hosts line for this jump host — null on first connect, non-null on subsequent connects. */
    val knownHostsEntry: String?,
    /**
     * Explicit auth for this hop. If null, the target host's auth is used (simple-mode behaviour).
     * Set when the jump host comes from the host-list (each hop has its own credentials).
     */
    val auth: SshAuth? = null,
    /**
     * DB id of the HostEntity this hop was built from (host-list mode only).
     * Used to persist newly-seen host keys back to the jump host's own record.
     */
    val hostId: Long? = null,
)

/** All parameters needed to open an SSH connection. */
data class SshConnectionParams(
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val auth: SshAuth,
    val agentForwarding: Boolean = false,
    /** If null, fingerprint verification is skipped (only on first connect) */
    val knownHostsEntry: String? = null,
    /** Ordered list of SSH jump hosts to tunnel through before reaching the target. */
    val jumpHosts: List<JumpHost> = emptyList(),
    /** Local port-forwarding rules to activate after connecting. */
    val portForwardings: List<PortForwarding> = emptyList(),
    /** If true, legacy/weak ciphers are appended to the negotiation list (for old servers). */
    val allowLegacyCiphers: Boolean = false,
)

sealed interface SshAuth {
    data class Password(val password: String) : SshAuth
    data class PublicKey(val privateKeyPem: String, val passphrase: String? = null) : SshAuth
}

/**
 * Parses a jump-hosts string ("user@host1:22,host2:port") and a newline-delimited known_hosts
 * blob into a list of [JumpHost].
 *
 * Each token format: [user@]host[:port] — bracket IPv6 addresses, e.g. [::1]:2222.
 * An invalid/empty hop rejects the entire chain; it must never become a shorter/direct route.
 */
fun parseJumpHosts(raw: String?, knownKeysBlob: String?): List<JumpHost> {
    if (raw.isNullOrBlank()) return emptyList()
    // Build a map: "host:port" -> known_hosts line.
    // JSch writes port-22 entries as "hostname ..." and non-22 entries as "[hostname]:port ...".
    // Normalise both to "host:port" so the lookup below always works regardless of port.
    val keysByHost: Map<String, String> = knownKeysBlob
        ?.lines()
        ?.filter { it.isNotBlank() }
        ?.mapNotNull { line ->
            val marker = line.trim().split(Regex("\\s+"), limit = 2).first()
            val canonical = if (marker.startsWith("[")) {
                // [host]:port format
                val h = marker.substringAfter("[").substringBefore("]")
                val p = marker.substringAfterLast("]:").toIntOrNull() ?: 22
                "$h:$p"
            } else {
                // bare hostname → port 22
                "$marker:22"
            }
            canonical to line
        }
        ?.toMap()
        ?: emptyMap()
    return raw.split(",").map { token ->
        val trimmed = token.trim()
        require(trimmed.isNotEmpty()) { "Empty jump host; edit the jump host chain" }
        require(trimmed.none { it.isWhitespace() || it.isISOControl() }) { "Invalid jump host" }

        // Split off optional username: user@host:port
        val atIdx = trimmed.indexOf('@')
        val username: String?
        val hostPort: String
        if (atIdx != -1) {
            username = trimmed.substring(0, atIdx)
            require(username.isNotEmpty() && trimmed.lastIndexOf('@') == atIdx) { "Invalid jump host username" }
            hostPort = trimmed.substring(atIdx + 1)
        } else {
            username = null
            hostPort = trimmed
        }

        // A colon explicitly selects a port, not an alternate hostname on port 22.
        // Brackets make IPv6 addresses unambiguous and are not passed to JSch as host text.
        val host: String
        val portText: String?
        if (hostPort.startsWith('[')) {
            val closing = hostPort.indexOf(']')
            require(closing > 1) { "Invalid bracketed jump host" }
            host = hostPort.substring(1, closing)
            require(':' in host) { "Only IPv6 jump hosts use brackets" }
            val suffix = hostPort.substring(closing + 1)
            require(suffix.isEmpty() || suffix.startsWith(':')) { "Invalid jump host port" }
            portText = if (suffix.isEmpty()) null else suffix.substring(1)
        } else {
            require(hostPort.count { it == ':' } <= 1) { "Bracket IPv6 jump host addresses" }
            host = hostPort.substringBefore(':')
            portText = if (':' in hostPort) hostPort.substringAfter(':') else null
        }
        require(host.isNotEmpty() && host.none { it in "[]@/\\" }) { "Invalid jump host address" }
        val port = if (portText == null) 22 else {
            require(portText.isNotEmpty() && portText.all { it in '0'..'9' }) { "Invalid jump host port" }
            requireNotNull(portText.toIntOrNull()?.takeIf { it in 1..65535 }) { "Invalid jump host port" }
        }

        JumpHost(host, port, username, keysByHost["$host:$port"])
    }
}
