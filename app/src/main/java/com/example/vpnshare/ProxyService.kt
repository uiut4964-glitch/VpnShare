package com.example.vpnshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

class ProxyService : Service() {
    companion object {
        @Volatile var running = false
        const val PORT = 8080
    }

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("proxy", "Proxy", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "proxy")
            .setContentTitle("مشاركة VPN تعمل")
            .setContentText("المنفذ $PORT")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        if (!running) {
            running = true
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vpnshare:proxy").also { it.acquire() }
            pool.execute { serve() }
        }
        return START_STICKY
    }

    private fun serve() {
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress("0.0.0.0", PORT))
            server = s
            while (running) {
                val c = s.accept()
                pool.execute { handle(c) }
            }
        } catch (e: Exception) {
        } finally {
            running = false
        }
    }

    private fun handle(c: Socket) {
        val r = Socket()
        try {
            c.soTimeout = 30000
            val cin = c.getInputStream()
            val cout = c.getOutputStream()
            val head = readHead(cin) ?: throw Exception("bad head")
            val lines = head.split("\r\n")
            val parts = lines[0].split(" ")
            val method = parts[0]
            val target = parts[1]
            val ver = parts[2]
            if (method == "CONNECT") {
                val host = target.substringBeforeLast(":").trim('[', ']')
                val port = target.substringAfterLast(":").toInt()
                r.connect(InetSocketAddress(host, port), 15000)
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                cout.flush()
            } else {
                val u = URL(target)
                val port = if (u.port == -1) 80 else u.port
                r.connect(InetSocketAddress(u.host, port), 15000)
                val path = if (u.file.isEmpty()) "/" else u.file
                val sb = StringBuilder("$method $path $ver\r\n")
                for (l in lines.drop(1)) {
                    if (l.isNotEmpty() && !l.startsWith("Proxy-", true) &&
                        !l.startsWith("Connection:", true)) sb.append(l).append("\r\n")
                }
                sb.append("Connection: close\r\n\r\n")
                r.getOutputStream().write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            }
            c.soTimeout = 0
            pool.execute { pipe(r.getInputStream(), cout, c, r) }
            pipe(cin, r.getOutputStream(), c, r)
        } catch (e: Exception) {
            try { c.close() } catch (_: Exception) {}
            try { r.close() } catch (_: Exception) {}
        }
    }

    private fun readHead(i: InputStream): String? {
        val bo = ByteArrayOutputStream()
        var m = 0
        while (bo.size() < 16384) {
            val b = i.read()
            if (b < 0) return null
            bo.write(b)
            m = when {
                b == 13 && m % 2 == 0 -> m + 1
                b == 10 && m % 2 == 1 -> m + 1
                b == 13 -> 1
                else -> 0
            }
            if (m == 4) return bo.toString("ISO-8859-1")
        }
        return null
    }

    private fun pipe(i: InputStream, o: OutputStream, a: Socket, b: Socket) {
        try {
            val buf = ByteArray(16384)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                o.flush()
            }
        } catch (e: Exception) {
        } finally {
            try { a.close() } catch (_: Exception) {}
            try { b.close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        try { wl?.release() } catch (_: Exception) {}
        pool.shutdown()
        super.onDestroy()
    }
}
