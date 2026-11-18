package com.trove.core.fs.utils

import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * Finds the first available LAN address within RFC 1918 ranges.
     * Falls back to loopback if no LAN address is found.
     */
    fun getLanAddress(): InetAddress {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            
            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is java.net.Inet4Address) {
                    val bytes = addr.address
                    // RFC 1918 check: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
                    val b1 = bytes[0].toInt() and 0xFF
                    val b2 = bytes[1].toInt() and 0xFF
                    if (b1 == 10 || (b1 == 172 && b2 in 16..31) || (b1 == 192 && b2 == 168)) {
                        return addr
                    }
                }
            }
        }
        return InetAddress.getByName("127.0.0.1")
    }
}
