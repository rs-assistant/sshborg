package com.sshborg.ui.hosts

/** Invalid stored tokens remain visible as unselected hops, never as a shorter route. */
internal fun parseJumpHostSelection(raw: String?): List<Long> =
    if (raw.isNullOrBlank()) emptyList()
    else raw.split(",").map { it.trim().toLongOrNull() ?: 0L }

/** Every displayed hop must be selected or explicitly removed before saving. */
internal fun encodeJumpHostSelection(ids: List<Long>): String? {
    require(ids.all { it > 0 }) { "Select or remove every jump host" }
    return ids.joinToString(",").takeIf { it.isNotEmpty() }
}
