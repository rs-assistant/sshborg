package com.sshborg.data.ssh

import com.sshborg.data.db.HostEntity

/** Resolve every configured hop in order; a damaged chain must never become a shorter route. */
internal suspend fun resolveJumpHostEntities(
    idList: String?,
    findHost: suspend (Long) -> HostEntity?,
): List<HostEntity> {
    if (idList.isNullOrBlank()) return emptyList()
    val ids = idList.split(",").map { token ->
        val id = token.trim().toLongOrNull()
        check(id != null && id > 0) { "Invalid jump host selection; edit the jump host chain" }
        id
    }
    return ids.map { id ->
        checkNotNull(findHost(id)) { "A configured jump host is missing; edit the jump host chain" }
    }
}
