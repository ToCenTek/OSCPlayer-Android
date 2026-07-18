package com.oscvideoplayer

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder

class HttpUploadServer(
    private val port: Int = 8080,
    private val uploadDir: String,
    private val videoListProvider: (() -> List<VideoScanner.VideoItem>)? = null,
    private val playProvider: ((String) -> Unit)? = null,
    private val getVideoInfoProvider: ((String) -> Map<String, Any>)? = null,
    private val togglePlayPauseProvider: (() -> Unit)? = null,
    private val isPlayingProvider: (() -> Boolean)? = null,
    private val currentVideoPathProvider: (() -> String?)? = null
) {
    private val tag = "HttpUploadServer"
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val crlf = "\r\n".toByteArray()
    private val crlfcrlf = "\r\n\r\n".toByteArray()
    private val boundaryPrefix = "--".toByteArray()

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.d(tag, "HTTP server started on port $port, upload dir: $uploadDir")
                while (running) {
                    try {
                        serverSocket!!.accept().use { handleClient(it) }
                    } catch (e: Exception) {
                        if (running) Log.w(tag, "Client error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(tag, "Server error: ${e.message}")
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun handleClient(client: Socket) {
        val input = client.getInputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1]

        val headers = mutableMapOf<String, String>()
        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(": ")
            if (idx > 0) {
                val key = line.substring(0, idx).lowercase()
                val value = line.substring(idx + 2)
                headers[key] = value
                when (key) {
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                }
            }
        }

        try {
            when {
                method == "GET" && path == "/" -> serveHtml(client)
                method == "GET" && path.startsWith("/files") -> serveFiles(client, path)
                method == "GET" && path.startsWith("/screenshots") -> serveScreenshots(client, path)
                method == "POST" && path == "/upload" -> handleUpload(client, input, headers, contentLength)
                else -> sendResponse(client, 404, "Not Found", "text/plain", "Not Found")
            }
        } catch (e: OutOfMemoryError) {
            Log.e(tag, "OOM: ${e.message}")
            sendResponse(client, 500, "Error", "text/plain", "文件过大, 内存不足")
        } catch (e: Exception) {
            Log.w(tag, "handle error: ${e.message}")
            sendResponse(client, 500, "Error", "text/plain", e.message ?: "Error")
        }
    }

    // ────── /files ──────

    private fun serveFiles(client: Socket, rawPath: String) {
        // Parse query params (supports both key=value and key-only)
        val query = if (rawPath.contains("?")) rawPath.substringAfter("?") else ""
        val params = mutableMapOf<String, String>()
        for (kv in query.split("&").filter { it.isNotEmpty() }) {
            val eq = kv.indexOf("=")
            if (eq > 0) {
                params[kv.substring(0, eq)] = URLDecoder.decode(kv.substring(eq + 1), "UTF-8")
            } else {
                params[kv] = ""
            }
        }

        // JSON state endpoint
        if ("state" in params) {
            val currentPath = currentVideoPathProvider?.invoke() ?: ""
            val isPlaying = isPlayingProvider?.invoke() ?: false
            val json = """{"playing":$isPlaying,"currentPath":"${currentPath.replace("\"", "\\\"")}"}"""
            return sendResponse(client, 200, "OK", "application/json", json.toByteArray(), "Cache-Control: no-cache")
        }

        val action = params.keys.firstOrNull { it in setOf("play", "toggle", "dl", "del", "info") }
        val filePath = params[action]

        if (action != null && filePath != null) {
            val file = File(filePath)
            when (action) {
                "play" -> {
                    playProvider?.invoke(filePath)
                    val redirect = "HTTP/1.1 302 Found\r\nLocation: /files\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    try { client.getOutputStream().write(redirect.toByteArray()); client.getOutputStream().flush() } catch (_: Exception) {}
                }
                "toggle" -> {
                    val currentPath = currentVideoPathProvider?.invoke()
                    if (filePath == currentPath) {
                        togglePlayPauseProvider?.invoke()
                    } else {
                        playProvider?.invoke(filePath)
                    }
                    val redirect = "HTTP/1.1 302 Found\r\nLocation: /files\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    try { client.getOutputStream().write(redirect.toByteArray()); client.getOutputStream().flush() } catch (_: Exception) {}
                }
                "dl" -> {
                    if (file.exists() && file.isFile) {
                        sendResponse(client, 200, "OK", "application/octet-stream", file.readBytes(),
                            "Content-Disposition: attachment; filename=\"${file.name}\"")
                    } else {
                        sendResponse(client, 404, "Not Found", "text/plain", "文件不存在")
                    }
                }
                "del" -> {
                    if (FileManager.isExternalPath(filePath)) {
                        sendResponse(client, 403, "Forbidden", "text/html; charset=utf-8",
                            """<script>alert('USB/SD卡文件不允许删除');location.href='/files'</script>""".toByteArray())
                    } else if (file.exists()) {
                        file.delete()
                        Log.d(tag, "Deleted: $filePath")
                        val redirect = "HTTP/1.1 302 Found\r\nLocation: /files\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        try { client.getOutputStream().write(redirect.toByteArray()); client.getOutputStream().flush() } catch (_: Exception) {}
                    } else {
                        sendResponse(client, 404, "Not Found", "text/plain", "文件不存在")
                    }
                }
                "info" -> {
                    val info = getVideoInfoProvider?.invoke(filePath) ?: mapOf("path" to filePath)
                    val rows = buildString {
                        info.forEach { (k, v) ->
                            val label = when (k) {
                                "path" -> "路径"
                                "name" -> "文件名"
                                "size" -> "大小"
                                "duration" -> "时长"
                                "width" -> "宽度"
                                "height" -> "高度"
                                "frameRate" -> "帧率"
                                "mime" -> "格式"
                                "bitrate" -> "码率"
                                "isExternal" -> "存储位置"
                                else -> k
                            }
                            val value = when (k) {
                                "size" -> formatSize((v as? Number)?.toLong() ?: 0)
                                "duration" -> formatDuration((v as? Number)?.toLong() ?: 0)
                                "frameRate" -> String.format("%.2f fps", v)
                                "isExternal" -> if (v == true) "USB/SD卡" else "内部存储"
                                else -> v.toString()
                            }
                            append("<tr><td class=lk>$label</td><td class=lv>$value</td></tr>\n")
                        }
                    }
                    val html = """<!DOCTYPE html>
<html lang=zh><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>文件信息 - OSCPlayer</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:#0f0f0f;color:#e0e0e0;padding:20px}
h1{font-size:20px;color:#fc0;margin-bottom:16px;text-align:center}
table{width:100%;max-width:600px;margin:0 auto;border-collapse:collapse;font-size:13px;background:rgba(255,255,255,.03);border-radius:10px;overflow:hidden}
tr{border-bottom:1px solid rgba(255,255,255,.06)}
tr:last-child{border-bottom:none}
td{padding:10px 14px;vertical-align:top;word-break:break-all}
td.lk{color:#888;white-space:nowrap;width:80px}
td.lv{color:#ccc}
.back{display:block;text-align:center;margin-top:24px;color:#fc0;text-decoration:none;font-size:14px;opacity:.6}
.back:hover{opacity:1}
</style></head>
<body><h1>文件信息</h1><table>$rows</table>
<a class=back href="/files">&larr; 返回文件列表</a>
</body></html>"""
                    sendResponse(client, 200, "OK", "text/html; charset=utf-8", html.toByteArray(), "Cache-Control: no-cache")
                }
            }
            return
        }

        // Show file listing page
        val videos = videoListProvider?.invoke() ?: emptyList()
        if (videos.isEmpty()) {
            return sendResponse(client, 200, "OK", "text/html; charset=utf-8", """
                <!DOCTYPE html><html lang=zh><head><meta charset=utf-8>
                <meta name=viewport content="width=device-width,initial-scale=1">
                <title>文件管理 - OSCPlayer</title>
                <style>body{font-family:sans-serif;background:#0f0f0f;color:#e0e0e0;padding:20px;text-align:center}
                .empty{margin-top:80px;font-size:18px;color:#666}
                .back{display:block;margin-top:24px;color:#fc0;text-decoration:none;font-size:14px;opacity:.6}
                .back:hover{opacity:1}</style></head>
                <body><div class=empty>暂无文件</div>
                <a class=back href="/">&larr; 返回上传</a></body></html>""")
        }

        val internalItems = videos.filter { !it.isFromUSB }
        val usbItems = videos.filter { it.isFromUSB }

        fun renderGroup(items: List<VideoScanner.VideoItem>, title: String, icon: String): String {
            if (items.isEmpty()) return ""
            val rows = items.joinToString("\n") { v ->
                val encPath = URLEncoder.encode(v.path, "UTF-8")
                val size = formatSize(v.size)
                val typeBadge = when (v.mediaType) {
                    "video" -> "<span class=badge style='background:#1a6b3c'>视频</span>"
                    "audio" -> "<span class=badge style='background:#6b4c1a'>音频</span>"
                    "image" -> "<span class=badge style='background:#3c1a6b'>图片</span>"
                    else -> "<span class=badge style='background:#444'>${v.mediaType}</span>"
                }
                val delClass = if (FileManager.isExternalPath(v.path)) "del muted" else "del"
                val delTitle = if (FileManager.isExternalPath(v.path)) "USB文件不可删除" else "删除"
                val delConfirm = if (FileManager.isExternalPath(v.path)) "" else "onclick=\"return confirm('确定删除 ${v.name} ?')\""
                val delHref = if (FileManager.isExternalPath(v.path)) "#" else "/files?del=$encPath"
                val delHandler = if (FileManager.isExternalPath(v.path)) "onclick=\"alert('USB/SD卡文件不允许删除');return false\"" else delConfirm
                """<div class=item>
<div class=ih><div class=iname>$typeBadge <span class=name>${v.name}</span></div><span class=isize>$size</span></div>
<div class=ia>
<a class="ab play-btn" href="/files?toggle=$encPath" data-path="$encPath">&#x25B6; 播放</a>
<a class=ab href="/files?dl=$encPath" title="下载">&#x2913; 下载</a>
<a class=ab href="/files?info=$encPath" title="信息">&#x2139; 信息</a>
<a class="ab $delClass" href="$delHref" $delHandler title="$delTitle">&#x2715; 删除</a>
</div></div>"""
            }
            return """<h2>$icon $title <span class=count>${items.size}</span></h2>
<div class=list>$rows</div>"""
        }

        val internalSection = renderGroup(internalItems, "内部存储", "&#x1F4BE;")
        val usbSection = renderGroup(usbItems, "USB/SD卡", "&#x1F4E5;")

        val html = """<!DOCTYPE html>
<html lang=zh>
<head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>文件管理 - OSCPlayer</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:#0f0f0f;color:#e0e0e0;padding:20px;max-width:900px;margin:0 auto}
h1{font-size:22px;color:#fc0;margin-bottom:20px;text-align:center}
h2{font-size:16px;color:#eee;margin:20px 0 10px;display:flex;align-items:center;gap:8px}
h2 .count{font-size:12px;color:#888;background:rgba(255,255,255,.08);padding:2px 10px;border-radius:10px;font-weight:400}
.list{display:flex;flex-direction:column;gap:8px}
.item{background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.06);border-radius:10px;padding:10px 14px;transition:background .15s}
.item:hover{background:rgba(255,255,255,.07)}
.ih{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:6px}
.iname{display:flex;align-items:center;gap:6px;min-width:0;flex:1}
.name{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:14px;color:#ddd}
.isize{font-size:12px;color:#666;white-space:nowrap}
.badge{font-size:11px;padding:2px 8px;border-radius:4px;color:#fff;white-space:nowrap}
.ia{display:flex;gap:6px;flex-wrap:wrap}
.ab{display:inline-flex;align-items:center;gap:4px;padding:5px 12px;border-radius:6px;font-size:12px;text-decoration:none;color:#aaa;background:rgba(255,255,255,.06);transition:all .15s}
.ab:hover{background:rgba(255,255,255,.12);color:#fc0}
.ab.del:hover{color:#f44;background:rgba(255,68,68,.12)}
.ab.muted{opacity:.35;cursor:not-allowed;pointer-events:none}
.back{display:block;text-align:center;margin-top:24px;color:#fc0;text-decoration:none;font-size:14px;opacity:.6;padding:8px}
.back:hover{opacity:1}
.sep{height:1px;background:rgba(255,255,255,.06);margin:8px 0}
</style></head>
<body>
<h1>&#x1F4C1; 文件管理</h1>
$internalSection
${if (internalItems.isNotEmpty() && usbItems.isNotEmpty()) "<div class=sep></div>" else ""}
$usbSection
<a class=back href="/">&larr; 返回上传</a>
<script>
function updatePlayButtons(){
 var x=new XMLHttpRequest();
 x.open('GET','/files?state',true);
 x.onload=function(){
  try{
   var s=JSON.parse(x.responseText);
   document.querySelectorAll('.play-btn').forEach(function(b){
    var same=s.currentPath&&decodeURIComponent(b.dataset.path)===s.currentPath;
    if(same&&s.playing){b.innerHTML='&#x23F8; 暂停';b.title='暂停'}
    else if(same){b.innerHTML='&#x25B6; 继续';b.title='继续播放'}
    else{b.innerHTML='&#x25B6; 播放';b.title='播放'}
   });
  }catch(e){}
 };
 x.send();
}
updatePlayButtons();
setInterval(updatePlayButtons,3000);
</script>
</body></html>"""
        sendResponse(client, 200, "OK", "text/html; charset=utf-8", html.toByteArray(), "Cache-Control: no-cache")
    }

    // ────── Homepage ──────

    private fun serveHtml(client: Socket) {
        val html = """<!DOCTYPE html>
<html lang=zh>
<head>
<meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>OSCPlayer</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:linear-gradient(135deg,#0f0f0f 0,#1a1a2e 50%,#16213e 100%);color:#e0e0e0;min-height:100vh;display:flex;align-items:center;justify-content:center}
.card{background:rgba(255,255,255,.05);backdrop-filter:blur(20px);-webkit-backdrop-filter:blur(20px);border:1px solid rgba(255,255,255,.08);border-radius:20px;padding:48px 40px;width:90%;max-width:480px;text-align:center;box-shadow:0 25px 60px rgba(0,0,0,.5)}
.logo{font-size:32px;font-weight:700;color:#fc0;margin-bottom:4px;letter-spacing:1px}
.sub{font-size:14px;color:#888;margin-bottom:32px}
.drop-zone{border:2px dashed rgba(255,204,0,.3);border-radius:14px;padding:40px 20px;cursor:pointer;transition:all .25s;background:rgba(255,204,0,.03)}
.drop-zone:hover,.drop-zone.dragover{border-color:#fc0;background:rgba(255,204,0,.08)}
.drop-zone.loaded{border-color:rgba(34,197,94,.5);background:rgba(34,197,94,.06)}
.drop-zone.uploading{opacity:.5;cursor:default;pointer-events:none}
.drop-zone .icon{font-size:48px;margin-bottom:12px;opacity:.6}
.drop-zone .hint{font-size:15px;color:#aaa}
.drop-zone .hint span{color:#fc0;text-decoration:underline}
input[type=file]{display:none}
.file-name{font-size:13px;color:#888;margin-top:10px;min-height:20px}
.btn-wrap{position:relative;border-radius:10px;margin-top:24px;overflow:hidden;width:100%;background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.12)}
.btn-progress{position:absolute;left:0;top:0;height:100%;width:0%;background:linear-gradient(90deg,#e6b800,#fc0);border-radius:10px;transition:width .15s}
.btn-wrap button{position:relative;z-index:1;display:block;width:100%;padding:14px 40px;border:none;border-radius:10px;font-size:16px;font-weight:600;cursor:pointer;color:#e0e0e0;background:transparent;transition:transform .15s,box-shadow .2s}
.btn-wrap button:disabled{cursor:not-allowed}
.btn-wrap button:hover:not(:disabled){transform:translateY(-1px);box-shadow:0 8px 24px rgba(255,204,0,.3)}
.btn-wrap button:active:not(:disabled){transform:translateY(0)}
.footer{font-size:12px;color:#555;margin-top:28px;line-height:2}
.footer a{color:#666;text-decoration:none;margin:0 6px}
.footer a:hover{color:#fc0}
@keyframes spin{to{transform:rotate(360deg)}}
</style>
</head>
<body>
<div class=card>
<div class=logo>OSCPlayer</div>
<div class=sub>上传视频文件</div>
<div class=drop-zone id=dropZone>
<div class=icon>&#x1F4C1;</div>
<div class=hint id=dropHint>拖拽文件到此处<br>或 <span>点击选择文件</span></div>
</div>
<input type=file id=fileInput accept="video/*">
<div class=file-name id=fileName></div>
<div class=btn-wrap>
<div class=btn-progress id=btnProgress></div>
<button id=uploadBtn disabled>上传</button>
</div>
<div class=footer>
<a href="/files">文件管理</a> &middot;
<a href="/screenshots">截图</a> &middot;
OSCPlayer 视频播放管理系统
</div>
</div>
<script>
var zone=document.getElementById('dropZone'),input=document.getElementById('fileInput'),btn=document.getElementById('uploadBtn'),fname=document.getElementById('fileName'),hint=document.getElementById('dropHint'),prog=document.getElementById('btnProgress');
zone.onclick=function(){if(!zone.classList.contains('uploading'))input.click()};
zone.ondragover=function(e){e.preventDefault();zone.classList.add('dragover')};
zone.ondragleave=function(){zone.classList.remove('dragover')};
zone.ondrop=function(e){e.preventDefault();zone.classList.remove('dragover');if(e.dataTransfer.files.length&&!zone.classList.contains('uploading')){input.files=e.dataTransfer.files;onFile()}};
input.onchange=onFile;
function onFile(){var f=input.files[0];if(f){fname.textContent=f.name+' ('+(f.size/1048576).toFixed(1)+' MB)';zone.classList.add('loaded');hint.innerHTML='<span>点击重新选择</span>';btn.disabled=false;btn.textContent='上传'}else{fname.textContent='';zone.classList.remove('loaded');hint.innerHTML='拖拽文件到此处<br>或 <span>点击选择文件</span>';btn.disabled=true;btn.textContent='上传';prog.style.width='0%'}}
btn.onclick=function(){
 var f=input.files[0];if(!f)return;
 btn.disabled=true;btn.textContent='0%';prog.style.width='0%';zone.classList.add('uploading');hint.innerHTML='正在上传...';
 var xhr=new XMLHttpRequest();
 xhr.upload.addEventListener('progress',function(e){
  if(!e.lengthComputable)return;
  var p=Math.round(e.loaded/e.total*100);
  prog.style.width=p+'%';btn.textContent=p+'%';
 });
 xhr.addEventListener('load',function(){
  prog.style.width='100%';zone.classList.remove('uploading');zone.classList.remove('loaded');
  if(xhr.status==200){btn.textContent='上传成功';fname.textContent='';input.value='';hint.innerHTML='拖拽文件到此处<br>或 <span>点击选择文件</span>'}
  else{btn.textContent='上传失败';setTimeout(function(){btn.textContent='上传';btn.disabled=false},3000)}
 });
 xhr.addEventListener('error',function(){
  prog.style.width='100%';zone.classList.remove('uploading');btn.textContent='上传失败';setTimeout(function(){btn.textContent='上传';btn.disabled=false},3000);
 });
 var fd=new FormData();fd.append('file',f);
 xhr.open('POST','/upload',true);xhr.send(fd);
};
</script>
</body></html>"""
        sendResponse(client, 200, "OK", "text/html; charset=utf-8", html.toByteArray(), "Cache-Control: no-cache, no-store, must-revalidate")
    }

    // ────── Screenshots ──────

    private fun serveScreenshots(client: Socket, path: String) {
        val screenshotsDir = File(uploadDir, ".screenshots")

        val deletePrefix = "/screenshots/delete/"
        if (path.startsWith(deletePrefix)) {
            val name = path.removePrefix(deletePrefix)
            val file = File(screenshotsDir, name)
            if (file.exists()) file.delete()
            val redirect = "HTTP/1.1 302 Found\r\nLocation: /screenshots\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            try { client.getOutputStream().write(redirect.toByteArray()); client.getOutputStream().flush() } catch (_: Exception) {}
            return
        }

        val filename = path.removePrefix("/screenshots/")
        if (filename.isNotEmpty() && filename.contains(".") && !filename.contains("/")) {
            val file = File(screenshotsDir, filename)
            if (file.exists() && file.isFile) {
                return sendResponse(client, 200, "OK", "image/png", file.readBytes(),
                    "Cache-Control: max-age=3600")
            }
            return sendResponse(client, 404, "Not Found", "text/plain", "Not Found")
        }

        if (!screenshotsDir.exists()) {
            return sendResponse(client, 200, "OK", "text/html; charset=utf-8", """
                <!DOCTYPE html><html lang=zh><head><meta charset=utf-8>
                <title>截图 - OSCPlayer</title><meta name=viewport content="width=device-width,initial-scale=1">
                <style>body{font-family:sans-serif;background:#0f0f0f;color:#e0e0e0;padding:20px;text-align:center}
                .empty{margin-top:80px;font-size:18px;color:#666}</style></head>
                <body><div class=empty>暂无截图</div></body></html>""")
        }

        val files = screenshotsDir.listFiles()?.filter { it.name.endsWith(".png") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

        val items = files.joinToString("\n") { f ->
            val name = f.name
            val size = when {
                f.length() >= 1024L * 1024 -> String.format("%.1f MB", f.length() / (1024.0 * 1024.0))
                f.length() >= 1024 -> "${f.length() / 1024} KB"
                else -> "${f.length()} B"
            }
            """<div class=item>
<img src="/screenshots/$name" loading=lazy onclick="openViewer('$name')">
<div class=info>
<span class=name>$name</span>
<span class=size>$size</span>
<span class=actions>
<a href="/screenshots/$name" download class=btn title="下载">&#x2913;</a>
<a href="/screenshots/delete/$name" class="btn del" title="删除" onclick="return confirm('确定删除 $name ?')">&#x2715;</a>
</span>
</div></div>"""
        }

        val html = """<!DOCTYPE html>
<html lang=zh>
<head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>截图 - OSCPlayer</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#0f0f0f;color:#e0e0e0;padding:20px}
h1{font-size:22px;color:#fc0;margin-bottom:20px;text-align:center}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:16px}
.item{background:rgba(255,255,255,.05);border-radius:10px;overflow:hidden;border:1px solid rgba(255,255,255,.08)}
.item img{width:100%;height:auto;aspect-ratio:16/9;object-fit:contain;background:#000;display:block;cursor:pointer;transition:opacity .2s}
.item img:hover{opacity:.85}
.item .info{padding:8px 12px;display:flex;align-items:center;font-size:12px;gap:8px}
.item .name{color:#aaa;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;min-width:0}
.item .size{color:#555;white-space:nowrap}
.item .actions{display:flex;gap:2px}
.item .btn{display:inline-flex;align-items:center;justify-content:center;width:28px;height:28px;border-radius:6px;text-decoration:none;font-size:15px;color:#888;transition:background .2s,color .2s}
.item .btn:hover{background:rgba(255,255,255,.1);color:#fc0}
.item .btn.del:hover{color:#f44}
.back{display:block;text-align:center;margin-top:24px;color:#fc0;text-decoration:none;font-size:14px;opacity:.6;padding:8px}
.back:hover{opacity:1}
#viewer{display:none;position:fixed;z-index:999;inset:0;background:rgba(0,0,0,.95);justify-content:center;align-items:center}
#viewer.open{display:flex}
#viewer img{max-width:95vw;max-height:95vh;object-fit:contain;border-radius:4px;box-shadow:0 0 60px rgba(0,0,0,.8)}
#viewer .close{position:absolute;top:16px;right:20px;font-size:36px;color:#888;cursor:pointer;width:48px;height:48px;display:flex;align-items:center;justify-content:center;border-radius:50%;background:rgba(255,255,255,.08);border:none;transition:background .2s,color .2s;z-index:10}
#viewer .close:hover{background:rgba(255,255,255,.15);color:#fff}
#viewer .vdl{position:absolute;top:16px;right:80px;font-size:14px;color:#fc0;text-decoration:none;padding:12px 20px;border-radius:8px;background:rgba(255,255,255,.08);transition:background .2s}
#viewer .vdl:hover{background:rgba(255,255,255,.15)}
</style>
</head>
<body>
<h1>&#x1F4F7; 截图</h1>
<div class=grid id=grid>$items</div>
<a class=back href="/">&larr; 返回上传</a>
<div id=viewer onclick="if(event.target==this)closeViewer()">
<button class=close onclick="closeViewer()">&times;</button>
<a class=vdl id=vdl href="#" download>下载</a>
<img id=vimg src="" alt="">
</div>
<script>
function openViewer(name){
 document.getElementById('vimg').src='/screenshots/'+name;
 document.getElementById('vdl').href='/screenshots/'+name;
 document.getElementById('viewer').classList.add('open');
 document.body.style.overflow='hidden';
}
function closeViewer(){
 document.getElementById('viewer').classList.remove('open');
 document.getElementById('vimg').src='';
 document.body.style.overflow='';
}
document.addEventListener('keydown',function(e){if(e.key==='Escape')closeViewer()});
</script>
</body></html>"""
        sendResponse(client, 200, "OK", "text/html; charset=utf-8", html.toByteArray(), "Cache-Control: no-cache")
    }

    // ────── Upload ──────

    private fun handleUpload(client: Socket, input: InputStream, headers: Map<String, String>, contentLength: Int) {
        val MAX_BODY = 200 * 1024 * 1024
        if (contentLength > MAX_BODY) {
            input.skip(contentLength.toLong())
            return sendResponse(client, 413, "Too Large", "text/plain; charset=utf-8", "文件超过200MB限制".toByteArray())
        }
        val ct = headers["content-type"] ?: return sendResponse(client, 400, "Bad Request", "text/plain; charset=utf-8", "缺少 Content-Type".toByteArray())
        val bIdx = ct.indexOf("boundary=")
        if (bIdx < 0) return sendResponse(client, 400, "Bad Request", "text/plain; charset=utf-8", "缺少 boundary".toByteArray())
        val boundary = ("--" + ct.substring(bIdx + 9).trim()).toByteArray()
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        if (read < contentLength) return sendResponse(client, 400, "Incomplete", "text/plain; charset=utf-8", "数据不完整".toByteArray())

        val uploads = mutableListOf<String>()
        var pos = 0
        while (true) {
            val bStart = indexOf(body, boundary, pos)
            if (bStart < 0) break
            val bEnd = bStart + boundary.size
            if (bEnd + 2 <= body.size && body[bEnd] == '-'.code.toByte() && body[bEnd + 1] == '-'.code.toByte()) break

            val hEnd = indexOf(body, crlfcrlf, bEnd)
            if (hEnd < 0) break
            val headerStr = body.copyOfRange(bEnd, hEnd).toString(Charsets.UTF_8)
            val dataStart = hEnd + crlfcrlf.size

            val nextB = indexOf(body, boundary, dataStart)
            if (nextB < 0) break
            val dataEnd = nextB - 2
            val dataLen = if (dataEnd > dataStart) dataEnd - dataStart else 0

            val filename = parseFilenameFromStr(headerStr)
            if (filename != null && dataLen > 0) {
                val safeName = File(filename).name
                val dest = uniqueFile(File(uploadDir, safeName))
                FileOutputStream(dest).use { it.write(body, dataStart, dataLen) }
                Log.d(tag, "Saved: ${dest.absolutePath} ($dataLen bytes)")
                uploads.add(dest.name)
            }
            pos = nextB + boundary.size
        }
        if (uploads.isEmpty())
            sendResponse(client, 400, "No File", "text/plain; charset=utf-8", "未收到有效文件".toByteArray())
        else
            sendResponse(client, 200, "OK", "text/plain; charset=utf-8", "上传成功: ${uploads.joinToString(", ")}".toByteArray())
    }

    private fun uniqueFile(f: File): File {
        if (!f.exists()) return f
        val name = f.name.substringBeforeLast('.')
        val ext = f.name.substringAfterLast('.', "")
        var counter = 1
        while (true) {
            val candidate = File(f.parentFile, "${name}_$counter.$ext")
            if (!candidate.exists()) return candidate
            counter++
        }
    }

    // ────── Helpers ──────

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "未知"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun parseFilename(headers: Map<String, String>): String? {
        val cd = headers["content-disposition"] ?: return null
        val fIdx = cd.indexOf("filename=\"")
        if (fIdx < 0) return null
        val start = fIdx + 10
        val end = cd.indexOf("\"", start)
        return if (end > start) URLDecoder.decode(cd.substring(start, end), "UTF-8") else null
    }

    private fun parseFilenameFromStr(header: String): String? {
        val lower = header.lowercase()
        val cd = "content-disposition:"
        val cdIdx = lower.indexOf(cd)
        if (cdIdx < 0) return null
        val disp = header.substring(cdIdx + cd.length)
        val fIdx = disp.indexOf("filename=\"")
        if (fIdx < 0) return null
        val start = fIdx + 10
        val end = disp.indexOf("\"", start)
        return if (end > start) URLDecoder.decode(disp.substring(start, end), "UTF-8") else null
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        outer@ for (i in from..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun readLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (baos.size() > 0) baos.toString(Charsets.UTF_8.name()) else null
            if (b == '\n'.code) return baos.toString(Charsets.UTF_8.name())
            if (b != '\r'.code) baos.write(b)
        }
    }

    private fun sendResponse(client: Socket, code: Int, reason: String, contentType: String, body: String, extraHeaders: String = "") {
        sendResponse(client, code, reason, contentType, body.toByteArray(), extraHeaders)
    }

    private fun sendResponse(client: Socket, code: Int, reason: String, contentType: String, body: ByteArray, extraHeaders: String = "") {
        try {
            val out = client.getOutputStream()
            out.write("HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close${if (extraHeaders.isNotEmpty()) "\r\n$extraHeaders" else ""}\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        } catch (_: Exception) {}
    }
}
