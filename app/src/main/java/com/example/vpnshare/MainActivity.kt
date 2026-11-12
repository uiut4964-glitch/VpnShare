package com.example.vpnshare

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var info: TextView

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

        val sw = Switch(this)
        sw.text = "مشاركة الإنترنت (البروكسي)"
        sw.textSize = 20f
        sw.isChecked = ProxyService.running

        info = TextView(this)
        info.textSize = 17f
        info.setPadding(0, p, 0, p)

        val btn = Button(this)
        btn.text = "تحديث العناوين"
        btn.setOnClickListener { refresh(sw.isChecked) }

        sw.setOnCheckedChangeListener { _, on ->
            val i = Intent(this, ProxyService::class.java)
            if (on) startForegroundService(i) else stopService(i)
            refresh(on)
        }

        root.addView(sw)
        root.addView(info)
        root.addView(btn)
        setContentView(root)
        refresh(sw.isChecked)
    }

    private fun refresh(on: Boolean) {
        val ips = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { n ->
                    n.inetAddresses.toList().filterIsInstance<Inet4Address>()
                        .map { "${n.name}: ${it.hostAddress}" }
                }.joinToString("\n")
        } catch (e: Exception) { "" }
        info.text = (if (on) "الحالة: يعمل ✅" else "الحالة: متوقف") +
            "\n\nالمنفذ: ${ProxyService.PORT}" +
            "\n\nالعناوين:\n$ips" +
            "\n\nاستخدم في الجهاز الآخر عنوان الهوتسبوت (غالباً يبدأ بـ 192.168 ويكون على ap0 أو swlan0)."
    }
}
