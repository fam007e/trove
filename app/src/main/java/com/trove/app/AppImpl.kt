package com.trove.app

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class AppImpl : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Enforce Conscrypt as the primary security provider for modern, secure TLS 1.3
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
