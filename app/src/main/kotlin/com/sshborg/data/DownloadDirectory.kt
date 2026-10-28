package com.sshborg.data

/** Storage belongs to the installed package, not its debuggable flag. */
fun downloadDirectoryName(applicationId: String): String =
    if (applicationId == "com.sshborg.debug") "SSHBorg-debug" else "SSHBorg"
