package com.oscvideoplayer

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLES11Ext
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class FusionRenderer(
    private val meshProvider: () -> FusionMesh?,
    private val onSurfaceCreated: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "FusionRenderer"
        private const val VSH = """
attribute vec2 aPos;
attribute vec2 aUV;
varying vec2 vUV;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); vUV = aUV; }
"""
        private const val FSH = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUV;
uniform samplerExternalOES uTex;
void main() { gl_FragColor = texture2D(uTex, vUV); }
"""
        private const val LSH = """
precision mediump float;
void main() { gl_FragColor = vec4(0.0, 1.0, 0.0, 0.6); }
"""
    }

    private var program = 0
    private var aPosLoc = 0; private var aUVLoc = 0; private var uTexLoc = 0

    private var lineProgram = 0
    private var lPosLoc = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var surfaceTextureId = 0
    private var frameAvailable = false
    private var frameCount = 0
    @Volatile var surfaceReady: Boolean = false; private set
    private var surfaceObj: Surface? = null
    val videoSurface: Surface? get() = surfaceObj

    private val MAX_VERTS = 66 * 66 * 6
    private var vertBuffer: FloatBuffer = ByteBuffer.allocateDirect(MAX_VERTS * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var vertCount = 0

    var glSurfaceView: GLSurfaceView? = null
    @Volatile var enabled: Boolean = true
    @Volatile var bezier: Boolean = false
    @Volatile var showGrid: Boolean = true

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = createProgram(VSH, FSH)
        aPosLoc = GLES20.glGetAttribLocation(program, "aPos")
        aUVLoc = GLES20.glGetAttribLocation(program, "aUV")
        uTexLoc = GLES20.glGetUniformLocation(program, "uTex")

        lineProgram = createProgram(VSH, LSH)
        lPosLoc = GLES20.glGetAttribLocation(lineProgram, "aPos")

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        surfaceTextureId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, surfaceTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(surfaceTextureId).apply { setOnFrameAvailableListener(this@FusionRenderer) }
        surfaceObj = Surface(surfaceTexture!!)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        surfaceReady = true
        onSurfaceCreated(surfaceTexture!!)
        Log.d(TAG, "Surface created, tex=$surfaceTextureId")
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { GLES20.glViewport(0, 0, w, h) }

    override fun onDrawFrame(gl: GL10?) {
        if (frameAvailable) {
            frameAvailable = false
            try { surfaceTexture?.updateTexImage(); frameCount++ } catch (_: Exception) {}
        }
        if (surfaceTextureId == 0) { GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); return }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(uTexLoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, surfaceTextureId)

        buildMesh()
        if (vertCount > 0) {
            vertBuffer.position(0)
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, vertBuffer)
            GLES20.glEnableVertexAttribArray(aPosLoc)
            vertBuffer.position(2)
            GLES20.glVertexAttribPointer(aUVLoc, 2, GLES20.GL_FLOAT, false, 16, vertBuffer)
            GLES20.glEnableVertexAttribArray(aUVLoc)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertCount)
        }

        if (showGrid) drawGrid()
    }

    override fun onFrameAvailable(st: SurfaceTexture?) { frameAvailable = true }

    fun markMeshDirty() {}

    private fun drawGrid() {
        val mesh = meshProvider() ?: return
        GLES20.glUseProgram(lineProgram)
        GLES20.glEnableVertexAttribArray(lPosLoc)

        val cols = mesh.cols; val rows = mesh.rows
        // Each line segment = 2 vertices. Horizontal: rows * (cols-1). Vertical: cols * (rows-1).
        val lineCount = rows * (cols - 1) + cols * (rows - 1)
        val buf = ByteBuffer.allocateDirect(lineCount * 2 * 4 * 2).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (r in 0 until rows) for (c in 0 until cols - 1) {
            val p = mesh.points[r][c]; val q = mesh.points[r][c + 1]
            buf.put(p.x * 2f - 1f); buf.put((1f - p.y) * 2f - 1f)
            buf.put(q.x * 2f - 1f); buf.put((1f - q.y) * 2f - 1f)
        }
        for (c in 0 until cols) for (r in 0 until rows - 1) {
            val p = mesh.points[r][c]; val q = mesh.points[r + 1][c]
            buf.put(p.x * 2f - 1f); buf.put((1f - p.y) * 2f - 1f)
            buf.put(q.x * 2f - 1f); buf.put((1f - q.y) * 2f - 1f)
        }
        buf.position(0)
        GLES20.glVertexAttribPointer(lPosLoc, 2, GLES20.GL_FLOAT, false, 8, buf)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, lineCount * 2)
    }

    private fun buildMesh() {
        val mesh = meshProvider() ?: return
        if (!enabled) {
            vertBuffer.clear(); vertCount = 0
            emit(0f, 0f, 0f, 0f); emit(1f, 0f, 1f, 0f); emit(0f, 1f, 0f, 1f)
            emit(1f, 0f, 1f, 0f); emit(1f, 1f, 1f, 1f); emit(0f, 1f, 0f, 1f)
            vertBuffer.position(0); return
        }
        val cols = mesh.cols; val rows = mesh.rows
        vertBuffer.clear(); vertCount = 0
        for (r in 0 until rows - 1) for (c in 0 until cols - 1) {
            val p00 = mesh.points[r][c]; val p10 = mesh.points[r][c + 1]
            val p01 = mesh.points[r + 1][c]; val p11 = mesh.points[r + 1][c + 1]
            val tu0 = c.toFloat() / (cols - 1); val tu1 = (c + 1).toFloat() / (cols - 1)
            val tv0 = r.toFloat() / (rows - 1); val tv1 = (r + 1).toFloat() / (rows - 1)
            emit(p00.x, p00.y, tu0, tv0); emit(p10.x, p10.y, tu1, tv0)
            emit(p01.x, p01.y, tu0, tv1)
            emit(p10.x, p10.y, tu1, tv0); emit(p11.x, p11.y, tu1, tv1)
            emit(p01.x, p01.y, tu0, tv1)
        }
        vertBuffer.position(0)
    }

    private fun emit(x: Float, y: Float, tu: Float, tv: Float) {
        if (vertCount >= MAX_VERTS) return
        vertBuffer.put(x * 2f - 1f); vertBuffer.put((1f - y) * 2f - 1f)
        vertBuffer.put(tu); vertBuffer.put(tv)
        vertCount++
    }

    private fun createProgram(vsh: String, fsh: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsh)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsh)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs); GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) { Log.e(TAG, "Shader error: ${GLES20.glGetShaderInfoLog(s)}"); GLES20.glDeleteShader(s); return 0 }
        return s
    }
}
