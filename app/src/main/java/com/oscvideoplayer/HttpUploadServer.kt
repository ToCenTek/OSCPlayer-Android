package com.oscvideoplayer

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

class HttpUploadServer(
    private val port: Int = 8080,
    private val uploadDir: String
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
                method == "GET" -> serveHtml(client)
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
.footer{font-size:12px;color:#555;margin-top:28px}
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
<div class=footer>OSCPlayer &middot; 视频播放管理系统</div>
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

    private fun handleUpload(client: Socket, input: InputStream, headers: Map<String, String>, contentLength: Int) {
        val MAX_BODY = 200 * 1024 * 1024 // 200MB limit
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
