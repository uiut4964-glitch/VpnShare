package com.example.vpnshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

class ProxyService : Service() {
    companion object {
        @Volatile var running = false
        val tcpCount = java.util.concurrent.atomic.AtomicLong()
        val udpAssoc = java.util.concurrent.atomic.AtomicLong()
        val udpOut = java.util.concurrent.atomic.AtomicLong()
        val udpIn = java.util.concurrent.atomic.AtomicLong()
        fun savedPort(c: Context): Int = c.getSharedPreferences("s", 0).getInt("port", 8080)
    }

    private var port = 8080
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        port = savedPort(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("proxy", "Proxy", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "proxy")
            .setContentTitle("مشاركة VPN تعمل")
            .setContentText("المنفذ $port")
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
            s.bind(InetSocketAddress("0.0.0.0", port))
            server = s
            while (running) {
                val c = s.accept()
                pool.execute { handle(c) }
            }
        } catch (_: Exception) {
        } finally {
            running = false
            stopSelf()
        }
    }

    // أول بايت: 5 = SOCKS5، غير ذلك = HTTP
    private fun handle(c: Socket) {
        try {
            c.soTimeout = 30000
            val pin = PushbackInputStream(c.getInputStream(), 1)
            val b = pin.read()
            if (b < 0) {
                try { c.close() } catch (_: Exception) {}
                return
            }
            pin.unread(b)
            if (b == 5) {
                socks(c, pin, c.getOutputStream())
            } else {
                http(c, pin, c.getOutputStream())
            }
        } catch (_: Exception) {
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun http(c: Socket, cin: InputStream, cout: OutputStream) {
        val r = Socket()
        try {
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
                        !l.startsWith("Connection:", true)
                    ) {
                        sb.append(l).append("\r\n")
                    }
                }
                sb.append("Connection: close\r\n\r\n")
                r.getOutputStream().write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                r.getOutputStream().flush()
            }
            c.soTimeout = 0
            tcpCount.incrementAndGet(); pool.execute { pipe(r, c, customOut = cout) }
            pipe(c, r, customIn = cin)
        } catch (_: Exception) {
            try { c.close() } catch (_: Exception) {}
            try { r.close() } catch (_: Exception) {}
        }
    }

    private fun socks(c: Socket, pin: InputStream, out: OutputStream) {
        try {
            val d = DataInputStream(pin)
            d.readByte()
            d.readFully(ByteArray(d.readUnsignedByte()))
            out.write(byteArrayOf(5, 0))
            out.flush()
            val h = ByteArray(4)
            d.readFully(h)
            val cmd = h[1].toInt()
            val host = when (h[3].toInt()) {
                1 -> {
                    val a = ByteArray(4)
                    d.readFully(a)
                    InetAddress.getByAddress(a).hostAddress.orEmpty()
                }
                3 -> {
                    val a = ByteArray(d.readUnsignedByte())
                    d.readFully(a)
                    String(a)
                }
                4 -> {
                    val a = ByteArray(16)
                    d.readFully(a)
                    InetAddress.getByAddress(a).hostAddress.orEmpty()
                }
                else -> throw Exception("atyp")
            }
            val port = d.readUnsignedShort()
            when (cmd) {
                1 -> {
                    val r = Socket()
                    try {
                        r.connect(InetSocketAddress(host, port), 15000)
                    } catch (e: Exception) {
                        try {
                            out.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                            out.flush()
                        } catch (_: Exception) {}
                        try { r.close() } catch (_: Exception) {}
                        throw e
                    }
                    out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    out.flush()
                    c.soTimeout = 0
                    tcpCount.incrementAndGet(); pool.execute { pipe(r, c, customOut = out) }
                    pipe(c, r, customIn = pin)
                }
                3 -> udp(c, pin, out)
                else -> {
                    try {
                        out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                        out.flush()
                    } catch (_: Exception) {}
                    throw Exception("cmd")
                }
            }
        } catch (_: Exception) {
            try { c.close() } catch (_: Exception) {}
        }
    }

    // SOCKS5 UDP ASSOCIATE
    private fun udp(c: Socket, pin: InputStream, out: OutputStream) {
        val u = DatagramSocket(0); udpAssoc.incrementAndGet()
        try {
            val la = c.localAddress.address
            val ip = if (la.size == 4) la else ByteArray(4)
            out.write(
                byteArrayOf(5, 0, 0, 1) + ip +
                    byteArrayOf((u.localPort shr 8).toByte(), u.localPort.toByte())
            )
            out.flush()
            c.soTimeout = 0
            val client = c.inetAddress
            var cport = 0
            pool.execute {
                try {
                    val buf = ByteArray(65535)
                    while (running && !u.isClosed && !c.isClosed) {
                        val p = DatagramPacket(buf, buf.size)
                        u.receive(p)
                        try {
                            if (p.address == client && (cport == 0 || p.port == cport)) {
                                cport = p.port
                                val d = buf.copyOf(p.length)
                                if (d.size > 10 && d[2].toInt() == 0) {
                                    var o: Int
                                    val dst: InetAddress? = when (d[3].toInt()) {
                                        1 -> {
                                            o = 8
                                            if (d.size >= 10) InetAddress.getByAddress(d.copyOfRange(4, 8)) else null
                                        }
                                        3 -> {
                                            val l = d[4].toInt() and 255
                                            o = 5 + l
                                            if (d.size >= o + 2) InetAddress.getByName(String(d, 5, l)) else null
                                        }
                                        4 -> {
                                            o = 20
                                            if (d.size >= 22) InetAddress.getByAddress(d.copyOfRange(4, 20)) else null
                                        }
                                        else -> {
                                            o = 0
                                            null
                                        }
                                    }
                                    if (dst != null && d.size >= o + 2) {
                                        val dp = ((d[o].toInt() and 255) shl 8) or (d[o + 1].toInt() and 255)
                                        u.send(DatagramPacket(d, o + 2, d.size - o - 2, dst, dp)); udpOut.incrementAndGet()
                                    }
                                }
                            } else if (cport != 0) {
                                val ra = p.address.address
                                val hdr = byteArrayOf(0, 0, 0, if (ra.size == 4) 1 else 4) + ra +
                                    byteArrayOf((p.port shr 8).toByte(), p.port.toByte())
                                val resp = hdr + buf.copyOf(p.length)
                                u.send(DatagramPacket(resp, resp.size, client, cport)); udpIn.incrementAndGet()
                            }
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { u.close() } catch (_: Exception) {}
                }
            }
            val t = ByteArray(256)
            while (pin.read(t) >= 0) {
            }
        } catch (_: Exception) {
        } finally {
            try { u.close() } catch (_: Exception) {}
            try { c.close() } catch (_: Exception) {}
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

    private fun pipe(src: Socket, dst: Socket, customIn: InputStream? = null, customOut: OutputStream? = null) {
        try {
            val i = customIn ?: src.getInputStream()
            val o = customOut ?: dst.getOutputStream()
            val buf = ByteArray(16384)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                o.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { src.close() } catch (_: Exception) {}
            try { dst.close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        try {
            if (wl?.isHeld == true) wl?.release()
        } catch (_: Exception) {}
        pool.shutdownNow()
        super.onDestroy()
    }
}
