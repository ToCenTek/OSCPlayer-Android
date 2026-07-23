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
    private val currentVideoPathProvider: (() -> String?)? = null,
    private val fusionProvider: (() -> FusionAPI?)? = null
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
                        serverSocket!!.accept().let { socket ->
                        Thread {
                            try { handleClient(socket) } finally { socket.close() }
                        }.apply { isDaemon = true; start() }
                    }
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
        var contentLength = 0L
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(": ")
            if (idx > 0) {
                val key = line.substring(0, idx).lowercase()
                val value = line.substring(idx + 2)
                headers[key] = value
                when (key) {
                    "content-length" -> contentLength = value.toLongOrNull() ?: 0L
                }
            }
        }

        try {
            when {
                method == "GET" && path == "/" -> serveHtml(client)
                method == "GET" && path.startsWith("/files") -> serveFiles(client, path)
                method == "POST" && path == "/upload" -> handleUpload(client, input, headers, contentLength)
                method == "GET" && path == "/fusion/editor" -> serveFusionEditor(client)
                method == "GET" && path.startsWith("/fusion/api") -> handleFusionApi(client, method, path, input, contentLength)
                method == "POST" && path.startsWith("/fusion/api") -> handleFusionApi(client, method, path, input, contentLength)
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

    // ────── Upload (streaming multipart, O(1) memory) ──────

    private fun handleUpload(client: Socket, input: InputStream, headers: Map<String, String>, contentLength: Long) {
        if (contentLength > Int.MAX_VALUE.toLong()) {
            input.skip(contentLength)
            return sendResponse(client, 413, "Too Large", "text/plain; charset=utf-8", "文件超过2GB限制".toByteArray())
        }
        val ct = headers["content-type"] ?: return sendResponse(client, 400, "Bad Request", "text/plain; charset=utf-8", "缺少 Content-Type".toByteArray())
        val bIdx = ct.indexOf("boundary=")
        if (bIdx < 0) return sendResponse(client, 400, "Bad Request", "text/plain; charset=utf-8", "缺少 boundary".toByteArray())
        val boundary = ("--" + ct.substring(bIdx + 9).trim()).toByteArray()

        try {
            val firstPart = readPartHeaders(input, boundary, contentLength)
            if (firstPart == null) return sendResponse(client, 400, "No File", "text/plain; charset=utf-8", "未收到有效文件".toByteArray())

            val filename = parseFilenameFromStr(firstPart.headers)
            if (filename == null) return sendResponse(client, 400, "No File", "text/plain; charset=utf-8", "未检测到文件名".toByteArray())

            val dest = uniqueFile(File(uploadDir, File(filename).name))
            val total = streamPartData(input, dest, boundary, firstPart.initialData, firstPart.remaining)
            if (total <= 0) return sendResponse(client, 400, "No Data", "text/plain; charset=utf-8", "文件内容为空".toByteArray())

            Log.d(tag, "Saved: ${dest.absolutePath} ($total bytes)")
            sendResponse(client, 200, "OK", "text/plain; charset=utf-8", "上传成功: ${dest.name}".toByteArray())
        } catch (e: Exception) {
            Log.e(tag, "Upload error: ${e.message}")
            sendResponse(client, 500, "Error", "text/plain; charset=utf-8", "上传失败".toByteArray())
        }
    }

    private class PartResult(val headers: String, val initialData: ByteArray, val remaining: Long)

    private fun readPartHeaders(input: InputStream, boundary: ByteArray, totalRemaining: Long): PartResult? {
        var rem = totalRemaining
        val buf = ByteArray(4096)
        val acc = java.io.ByteArrayOutputStream()
        while (rem > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
            if (n < 0) break
            rem -= n
            acc.write(buf, 0, n)
            val data = acc.toByteArray()
            // find first boundary
            val bIdx = indexOf(data, boundary, 0)
            if (bIdx >= 0) {
                val afterB = data.copyOfRange(bIdx + boundary.size, data.size)
                val crlfIdx = indexOf(afterB, crlfcrlf, 0)
                if (crlfIdx >= 0) {
                    val headers = afterB.copyOfRange(0, crlfIdx).toString(Charsets.UTF_8)
                    val initialData = afterB.copyOfRange(crlfIdx + crlfcrlf.size, afterB.size)
                    return PartResult(headers, initialData, rem + initialData.size)
                }
                // headers not complete yet, continue reading
            }
        }
        return null
    }

    private fun streamPartData(input: InputStream, dest: File, boundary: ByteArray, initial: ByteArray, remaining: Long): Long {
        var total = 0L
        // sliding window: only keep the trailing portion needed to detect boundary across chunk edges
        val window = java.io.ByteArrayOutputStream()
        window.write(initial)
        val maxOverlap = boundary.size - 1

        dest.outputStream().use { out ->
            fun flushToBoundary(): Boolean {
                val data = window.toByteArray()
                val bIdx = indexOf(data, boundary, 0)
                if (bIdx >= 0) {
                    val writeLen = if (bIdx >= 2) bIdx - 2 else 0
                    if (writeLen > 0) out.write(data, 0, writeLen)
                    total += writeLen
                    // keep the rest after boundary for next cycle
                    window.reset()
                    val after = data.size - (bIdx + boundary.size)
                    if (after > 0) window.write(data, bIdx + boundary.size, after)
                    return true
                }
                // no boundary found: write everything except the trailing overlap bytes
                val writeLen = (data.size - maxOverlap).coerceAtLeast(0)
                if (writeLen > 0) out.write(data, 0, writeLen)
                total += writeLen
                // keep the trailing overlap for next chunk
                val keep = data.copyOfRange(writeLen, data.size)
                window.reset()
                if (keep.isNotEmpty()) window.write(keep)
                return false
            }

            flushToBoundary()

            val buf = ByteArray(65536)
            var rem = remaining
            while (rem > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                if (n < 0) break
                rem -= n
                window.write(buf, 0, n)
                if (flushToBoundary()) {
                    // boundary found, write remaining data after it
                    val leftover = window.toByteArray()
                    val closeBoundary = "--".toByteArray()
                    val closeIdx = indexOf(leftover, closeBoundary, 0)
                    if (closeIdx >= 0) {
                        val writeLen = if (closeIdx >= 2) closeIdx - 2 else 0
                        if (writeLen > 0) out.write(leftover, 0, writeLen)
                        total += writeLen
                    } else if (leftover.isNotEmpty()) {
                        val writeLen = (leftover.size - 2).coerceAtLeast(0)
                        if (writeLen > 0) out.write(leftover, 0, writeLen)
                        total += writeLen
                    }
                    window.reset()
                    break
                }
            }
        }
        return total
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

    // ────── Fusion API ──────

    interface FusionAPI {
        fun getJson(): String
        fun setPoint(row: Int, col: Int, x: Float, y: Float)
        fun setHandle(row: Int, col: Int, dir: Int, x: Float, y: Float)
        fun resize(cols: Int, rows: Int)
        fun setSubdiv(sx: Int, sy: Int)
        fun regularize(sel: String? = null)
        fun reset()
        fun enable(on: Boolean)
        fun isEnabled(): Boolean
fun getStateJson(): String
        fun savePreset(name: String): Boolean
        fun loadPreset(name: String): Boolean
        fun listPresets(): String
    }

    private var _fusionApi: FusionAPI? = null
    private val fusionApi: FusionAPI?
        get() {
            if (_fusionApi == null) _fusionApi = fusionProvider?.invoke()
            return _fusionApi
        }

    private fun serveFusionEditor(client: Socket) {
        sendResponse(client, 200, "OK", "text/html; charset=utf-8", FusionEditorHtml.HTML)
    }

    private fun handleFusionApi(client: Socket, method: String, path: String, input: java.io.InputStream, contentLength: Long) {
        val api = fusionApi
        if (api == null) {
            return sendResponse(client, 503, "Unavailable", "application/json", """{"error":"fusion not initialized"}""")
        }
        val cmd = path.removePrefix("/fusion/api/").removeSuffix("/")
        val firstSlash = cmd.indexOf('/')
        val mainCmd = if (firstSlash > 0) cmd.substring(0, firstSlash) else cmd
        val subPath = if (firstSlash > 0) cmd.substring(firstSlash + 1) else ""
        try {
            when (mainCmd) {
                "state" -> sendResponse(client, 200, "OK", "application/json", api.getStateJson())
                "mesh" -> {
                    if (method == "POST" && contentLength > 0) {
                        val body = readBody(input, contentLength)
                        val json = org.json.JSONObject(body)
                        when (json.optString("action")) {
                            "set" -> {
                                val row = json.getInt("row")
                                val col = json.getInt("col")
                                val x = json.getDouble("x").toFloat()
                                val y = json.getDouble("y").toFloat()
                                api.setPoint(row, col, x, y)
                                sendResponse(client, 200, "OK", "application/json", """{"ok":true}""")
                            }
                            "set_handle" -> {
                                val row = json.getInt("row")
                                val col = json.getInt("col")
                                val dir = json.getInt("dir")
                                val x = json.getDouble("x").toFloat()
                                val y = json.getDouble("y").toFloat()
                                api.setHandle(row, col, dir, x, y)
                                sendResponse(client, 200, "OK", "application/json", """{"ok":true}""")
                            }
                            "set_multi" -> {
                                val pts = json.getJSONArray("points")
                                for (i in 0 until pts.length()) {
                                    val p = pts.getJSONObject(i)
                                    api.setPoint(p.getInt("row"), p.getInt("col"),
                                        p.getDouble("x").toFloat(), p.getDouble("y").toFloat())
                                }
                                sendResponse(client, 200, "OK", "application/json", """{"ok":true}""")
                            }
                            "resize" -> {
                                val newCols = json.optInt("cols", 9).coerceIn(2, 65)
                                val newRows = json.optInt("rows", 9).coerceIn(2, 65)
                                api.resize(newCols, newRows)
                                sendResponse(client, 200, "OK", "application/json", api.getJson())
                            }
                            "subdiv" -> {
                                val sx = json.optInt("subdivX", 0)
                                val sy = json.optInt("subdivY", 0)
                                api.setSubdiv(sx, sy)
                                sendResponse(client, 200, "OK", "application/json", api.getJson())
                            }
                            "regularize" -> {
                                val pts = json.optString("points", "")
                                api.regularize(sel = pts.ifEmpty { null })
                                sendResponse(client, 200, "OK", "application/json", api.getJson())
                            }
                            "reset" -> {
                                api.reset()
                                sendResponse(client, 200, "OK", "application/json", api.getJson())
                            }
                            else -> sendResponse(client, 400, "Bad Request", "application/json", """{"error":"unknown action"}""")
                        }
                    } else {
                        sendResponse(client, 200, "OK", "application/json", api.getJson())
                    }
                }
                "enable" -> {
                    if (method == "POST" && contentLength > 0) {
                        val body = readBody(input, contentLength)
                        val json = org.json.JSONObject(body)
                        api.enable(json.optBoolean("enable", true))
                    }
                    sendResponse(client, 200, "OK", "application/json", """{"enabled":${api.isEnabled()}}""")
                }
                "preset" -> {
                    when {
                        subPath == "list" -> sendResponse(client, 200, "OK", "application/json", api.listPresets())
                        subPath.startsWith("save/") -> {
                            val ok = api.savePreset(subPath.removePrefix("save/"))
                            sendResponse(client, 200, "OK", "application/json", """{"ok":$ok}""")
                        }
                        subPath.startsWith("load/") -> {
                            val ok = api.loadPreset(subPath.removePrefix("load/"))
                            if (ok) sendResponse(client, 200, "OK", "application/json", api.getStateJson())
                            else sendResponse(client, 404, "Not Found", "application/json", """{"error":"preset not found"}""")
                        }
                        else -> sendResponse(client, 400, "Bad Request", "application/json", """{"error":"unknown preset command"}""")
                    }
                }
                else -> sendResponse(client, 404, "Not Found", "application/json", """{"error":"unknown endpoint"}""")
            }
        } catch (e: Exception) {
            Log.w(tag, "Fusion API error: ${e.message}")
            sendResponse(client, 500, "Error", "application/json", """{"error":"${e.message?.replace("\"", "\\\"") ?: "unknown"}"}""")
        }
    }

    private fun readBody(input: java.io.InputStream, length: Long): String {
        val buf = ByteArrayOutputStream()
        var remaining = length
        val tmp = ByteArray(4096)
        while (remaining > 0) {
            val n = input.read(tmp, 0, minOf(tmp.size, remaining.toInt())).coerceAtLeast(0)
            if (n == 0) break
            buf.write(tmp, 0, n)
            remaining -= n
        }
        return buf.toString("UTF-8")
    }

    companion object {
    }
}
