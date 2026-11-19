package com.trove.server.ftp

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.trove.core.crypto.auth.CredentialManager
import com.trove.core.fs.LocalFileItem
import com.trove.core.fs.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.ServerSocket

class FtpServerService : Service() {
    companion object { /** Menu/overlay state mirror; authoritative lifecycle is per-instance. */
        @Volatile var running = false }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            val port = intent?.getIntExtra("port", 2121) ?: 2121
            val rootPath = intent?.getStringExtra("rootPath") ?: "/sdcard"
            
            val credentialManager = CredentialManager(this)
            
            // If first run or setup requested, initialize default/provided creds
            if (credentialManager.getAllCredentials().isEmpty()) {
                val user = intent?.getStringExtra("user") ?: "admin"
                val passHash = intent?.getStringExtra("pass_hash") 
                    ?: "\$2a\$10\$fy32JLXsrBesOAo.8Pa0DeamFvYJl2.7.1Lp0STVpgH1arVg1DAyW"
                credentialManager.storeHash(user, passHash)
            }
            
            startServer(port, rootPath, credentialManager.getAllCredentials())
        }
        return START_STICKY
    }

    private fun startServer(port: Int, rootPath: String, credentials: Map<String, String>) {
        isRunning = true
        running = true
        serviceScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                val root = LocalFileItem(File(rootPath))
                
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    FtpConnectionHandler(socket, root, serviceScope, credentials).handle()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isRunning = false
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        running = false
        serverSocket?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
