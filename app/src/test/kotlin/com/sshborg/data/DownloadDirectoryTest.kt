package com.sshborg.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadDirectoryTest {
    @Test fun tabletDebugAndPersonalKeepTheSameDirectory() {
        assertEquals("SSHBorg-debug", downloadDirectoryName("com.sshborg.debug"))
    }

    @Test fun storePackageKeepsItsSeparateDirectory() {
        assertEquals("SSHBorg", downloadDirectoryName("com.sshborg"))
    }
}
