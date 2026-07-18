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
        findViewById<TextView>(R.id.aboutCommands).text = buildCommandHelp()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun buildCommandHelp(): String {
        return buildString {
            appendLine("播放控制")
            appendLine("  /play[name]      播放视频")
            appendLine("  /stop[/t|f]     停止 / 关机 / 重启")
            appendLine("  /pause[/0|1]    暂停 / 恢复")
            appendLine("  /volume/0-1     音量")
            appendLine("  /seek/s         跳转")
            appendLine("  /speed/0.25-4   速度")
            appendLine()
            appendLine("文件管理")
            appendLine("  /rm/name        删除")
            appendLine("  /rename/old/new 重命名")
            appendLine("  /cp/tousb/name  拷贝到USB")
            appendLine("  /cp/tointernal  从USB拷贝")
            appendLine()
            appendLine("播放列表 / 显示")
            appendLine("  /playlist/...   add/remove/next/prev")
            appendLine("  /tct/text/size  文字叠加")
            appendLine("  /screenshot     截图")
            appendLine()
            appendLine("配置 / 定时")
            appendLine("  /config/...     dir/watchdog/startup")
            appendLine("  /schedule/...   start/stop/clear")
            appendLine("  /power/on|off   显示器电源")
            appendLine()
            appendLine("信息 / 系统")
            appendLine("  /info[/name]    视频信息")
            appendLine("  /status         完整状态")
            appendLine("  /list/videos    视频列表")
            appendLine("  /list/external  外置存储列表")
            appendLine("  /fps [/n]       帧率")
            appendLine("  /launcher       返回桌面")
        }.trimEnd()
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
