package com.trove.server.webdav

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.trove.core.crypto.auth.CredentialManager
import com.trove.core.fs.LocalFileItem
import com.trove.core.fs.utils.NetworkUtils
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import java.io.File
import org.mindrot.jbcrypt.BCrypt

class WebDavServerService : Service() {
    companion object { @Volatile var running = false }
    private var server: NettyApplicationEngine? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", 8080) ?: 8080
        val rootPath = intent?.getStringExtra("rootPath") ?: "/sdcard"
        
        if (server == null) {
            startWebDavServer(port, rootPath)
            running = true
        }
        return START_STICKY
    }

    private fun startWebDavServer(port: Int, rootPath: String) {
        val credentialManager = CredentialManager(this)
        val rootFile = LocalFileItem(File(rootPath))
        val lanAddress = NetworkUtils.getLanAddress().hostAddress ?: "127.0.0.1"

        server = embeddedServer(Netty, port = port, host = lanAddress) {
            install(Authentication) {
                basic("webdav") {
                    realm = "Trove WebDAV"
                    validate { credentials ->
                        val expectedHash = credentialManager.getHash(credentials.name)
                        if (expectedHash != null && BCrypt.checkpw(credentials.password, expectedHash)) {
                            UserIdPrincipal(credentials.name)
                        } else {
                            null
                        }
                    }
                }
            }
            
            routing {
                authenticate("webdav") {
                    configureWebDavRoutes(rootFile)
                }
            }
        }.start(wait = false)
        Log.i("WebDavServer", "Server started on port $port")
    }

    override fun onDestroy() {
        running = false
        server?.stop(1000, 5000)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
