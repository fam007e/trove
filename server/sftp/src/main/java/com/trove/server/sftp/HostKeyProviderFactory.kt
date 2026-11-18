package com.trove.server.sftp

import android.content.Context
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.io.File
import java.nio.file.Path

object HostKeyProviderFactory {

    private const val HOST_KEY_FILE = "sftp_host_key.ser"

    fun createHostKeyProvider(context: Context): KeyPairProvider {
        val keyFile = File(context.filesDir, HOST_KEY_FILE)
        return SimpleGeneratorHostKeyProvider(keyFile.toPath()).apply {
            algorithm = "RSA"
            keySize = 2048
        }
    }

    fun createHostKeyProvider(keyPath: Path): KeyPairProvider {
        return SimpleGeneratorHostKeyProvider(keyPath).apply {
            algorithm = "RSA"
            keySize = 2048
        }
    }
}
