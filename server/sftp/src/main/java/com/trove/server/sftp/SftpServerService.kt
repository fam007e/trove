package com.trove.server.sftp

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.trove.core.crypto.auth.CredentialManager
import com.trove.core.fs.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SftpServerService : Service() {
    companion object { @Volatile var running = false }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sftpServerManager: SftpServerManager? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", 2222) ?: 2222
        val rootPath = intent?.getStringExtra("rootPath") ?: "/sdcard"

        if (sftpServerManager == null || !sftpServerManager!!.isRunning) {
            val credentialManager = CredentialManager(this)

            // If no credentials configured yet, store default admin credential
            if (credentialManager.getAllCredentials().isEmpty()) {
                val user = intent?.getStringExtra("user") ?: "admin"
                val passHash = intent?.getStringExtra("pass_hash")
                    ?: "\$2a\$10\$fy32JLXsrBesOAo.8Pa0DeamFvYJl2.7.1Lp0STVpgH1arVg1DAyW"
                credentialManager.storeHash(user, passHash)
            }

            startSftpServer(port, rootPath, credentialManager)
        }
        return START_STICKY
    }

    private fun startSftpServer(port: Int, rootPath: String, credentialManager: CredentialManager) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val hostKeyProvider = HostKeyProviderFactory.createHostKeyProvider(this@SftpServerService)
                val passwordAuthenticator = SftpPasswordAuthenticator(credentialManager)

                val manager = SftpServerManager(
                    host = "0.0.0.0",
                    port = port,
                    rootPath = rootPath,
                    keyPairProvider = hostKeyProvider,
                    passwordAuthenticator = passwordAuthenticator
                )
                manager.start()
                sftpServerManager = manager
                running = true
            } catch (e: Exception) {
                e.printStackTrace()
                sftpServerManager = null
            }
        }
    }

    override fun onDestroy() {
        running = false
        sftpServerManager?.stop()
        sftpServerManager = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
