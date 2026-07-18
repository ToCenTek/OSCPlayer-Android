package com.oscvideoplayer

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.NetworkInterface

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        findViewById<TextView>(R.id.aboutPort).text = OSCServer.getPort().toString()
        findViewById<TextView>(R.id.aboutIP).text = getLocalIPAddress() ?: "未知"

        matchLogoWidthToTitle()
    }

    private fun matchLogoWidthToTitle() {
        val logo = findViewById<android.widget.ImageView>(R.id.aboutLogo)
        val title = findViewById<TextView>(R.id.aboutTitle)
        logo.post {
            val tw = title.paint.measureText(title.text.toString()).toInt()
            val d = logo.drawable
            if (d != null && tw > 0) {
                val aspect = d.intrinsicWidth.toFloat() / d.intrinsicHeight.toFloat()
                val lp = logo.layoutParams
                lp.width = tw
                lp.height = (tw / aspect).toInt()
                logo.layoutParams = lp
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun getLocalIPAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
