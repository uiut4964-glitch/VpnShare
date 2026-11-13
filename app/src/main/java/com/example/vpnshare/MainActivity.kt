package com.example.vpnshare

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var portBox: EditText

    private val tick = object : Runnable {
        override fun run() {
            refresh(ProxyService.running)
            info.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val p = (resources.displayMetrics.density * 20).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(p, p * 2, p, p)

        portBox = EditText(this)
        portBox.inputType = InputType.TYPE_CLASS_NUMBER
        portBox.hint = "المنفذ"
        portBox.setText(ProxyService.savedPort(this).toString())

        val sw = Switch(this)
        sw.text = "مشاركة الإنترنت"
        sw.textSize = 20f
        sw.isChecked = ProxyService.running

        info = TextView(this)
        info.textSize = 16f
        info.setPadding(0, p, 0, p)

        val copyBtn = Button(this)
        copyBtn.text = "نسخ العنوان والمنفذ"
        copyBtn.setOnClickListener {
            val t = "${best()}:${portBox.text}"
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("proxy", t))
            Toast.makeText(this, "تم النسخ: $t", Toast.LENGTH_SHORT).show()
        }

        val testBtn = Button(this)
        testBtn.text = "اختبار UDP عبر الـ VPN"
        testBtn.setOnClickListener {
            Toast.makeText(this, "جارٍ الاختبار...", Toast.LENGTH_SHORT).show()
            Thread {
                val res = udpTest()
                runOnUiThread { Toast.makeText(this, res, Toast.LENGTH_LONG).show() }
            }.start()
        }

        sw.setOnCheckedChangeListener { _, on ->
            if (on) {
                val pt = portBox.text.toString().toIntOrNull()
                if (pt == null || pt < 1024 || pt > 65535) {
                    Toast.makeText(this, "المنفذ يجب أن يكون بين 1024 و 65535", Toast.LENGTH_LONG).show()
                    sw.isChecked = false
                    return@setOnCheckedChangeListener
                }
                getSharedPreferences("s", 0).edit().putInt("port", pt).apply()
                startForegroundService(Intent(this, ProxyService::class.java))
            } else {
                stopService(Intent(this, ProxyService::class.java))
            }
            refresh(on)
        }

        root.addView(sw)
        root.addView(portBox)
        root.addView(info)
        root.addView(copyBtn)
        root.addView(testBtn)
        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)
        refresh(sw.isChecked)
    }

    override fun onResume() {
        super.onResume()
        info.post(tick)
    }

    override fun onPause() {
        super.onPause()
        info.removeCallbacks(tick)
    }

    private fun udpTest(): String = try {
        val q = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7) +
            "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() +
            byteArrayOf(0, 0, 1, 0, 1)
        val s = DatagramSocket()
        s.soTimeout = 4000
        val t0 = System.currentTimeMillis()
        s.send(DatagramPacket(q, q.size, InetAddress.getByName("1.1.1.1"), 53))
        s.receive(DatagramPacket(ByteArray(512), 512))
        s.close()
        "UDP يعمل ✅ (${System.currentTimeMillis() - t0} ms)"
    } catch (e: Exception) {
        "UDP لا يعمل عبر الاتصال الحالي ❌"
    }

    private fun addrs(): List<Pair<String, String>> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { n ->
                n.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .map { n.name to it.hostAddress.orEmpty() }
            }
    } catch (e: Exception) { emptyList() }

    private fun best(): String {
        val l = addrs()
        return (l.firstOrNull { it.first.startsWith("ap") || it.first.startsWith("swlan") }
            ?: l.firstOrNull { it.first.startsWith("wlan") }
            ?: l.firstOrNull())?.second.orEmpty()
    }

    private fun refresh(on: Boolean) {
        val ips = addrs().joinToString("\n") { "${it.first}: ${it.second}" }
        info.text = (if (on) "الحالة: يعمل ✅" else "الحالة: متوقف") +
            "\n\nHTTP و HTTPS و SOCKS5 (مع UDP) على نفس المنفذ" +
            "\n\nالإحصائيات:" +
            "\nاتصالات TCP: ${ProxyService.tcpCount.get()}" +
            "\nجلسات UDP: ${ProxyService.udpAssoc.get()}" +
            "\nحزم UDP من الجهاز الآخر: ${ProxyService.udpOut.get()}" +
            "\nحزم UDP إلى الجهاز الآخر: ${ProxyService.udpIn.get()}" +
            "\n\nالعناوين:\n$ips" +
            "\n\nاستخدم عنوان الهوتسبوت (غالباً يبدأ بـ 192.168 ويكون على ap0 أو swlan0)." +
            "\n\nلتغيير المنفذ: أوقف المفتاح ثم شغّله من جديد."
    }
}
