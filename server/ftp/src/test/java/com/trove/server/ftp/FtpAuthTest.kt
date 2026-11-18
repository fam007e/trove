package com.trove.server.ftp

import org.junit.Assert.assertTrue
import org.junit.Test
import org.mindrot.jbcrypt.BCrypt

class FtpAuthTest {

    @Test
    fun `test default admin bcrypt hash`() {
        // The default hash used in FtpServerService for 'admin'
        val hash = "\$2a\$10\$fy32JLXsrBesOAo.8Pa0DeamFvYJl2.7.1Lp0STVpgH1arVg1DAyW"
        assertTrue("Stored hash should verify against password 'admin'", 
            BCrypt.checkpw("admin", hash))
    }
}
