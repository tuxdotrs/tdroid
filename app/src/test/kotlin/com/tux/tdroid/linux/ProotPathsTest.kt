package com.tux.tdroid.linux

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ProotPathsTest {
    private val rootfs = File("/data/user/0/com.tux.tdroid/files/usr")
    private val tmp = File("/data/user/0/com.tux.tdroid/files/tmp")

    private fun host(path: String) = Proot.hostFile(path, rootfs, listOf("/tmp" to tmp)).path

    /** Nix-on-Droid's dirs are bound over Android's, as in its login script. */
    @Test fun systemDirsAreTheInstallationDirs() {
        assertEquals("${rootfs.path}/nix/store/x-bash/bin/bash", host("/nix/store/x-bash/bin/bash"))
        assertEquals("${rootfs.path}/etc/static", host("/etc/static"))
        assertEquals("${rootfs.path}/usr/local/bin/owl-check", host("/usr/local/bin/owl-check"))
        assertEquals("${rootfs.path}/bin", host("/bin"))
    }

    @Test fun bindsComeFirst() {
        assertEquals("${tmp.path}/keymap.xkb", host("/tmp/keymap.xkb"))
    }

    /** Everything else is Android's own root, which the guest shares; /android is the same, unbound. */
    @Test fun theRestIsAndroids() {
        assertEquals("${Proot.GUEST_HOME}/.config", host("${Proot.GUEST_HOME}/.config"))
        assertEquals("/system/fonts", host("/system/fonts"))
        assertEquals("/system/bin/sh", host("/android/system/bin/sh"))
        assertEquals("/", host("/android"))
        // Only whole components: /usrx isn't /usr.
        assertEquals("/usrx", host("/usrx"))
    }
}
