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

    var glSurfaceView: android.opengl.GLSurfaceView? = null

    companion object {
        private const val TAG = "FusionRenderer"
        private const val VSH = """
attribute vec2 aPos;
attribute vec2 aUV;
varying vec2 vUV;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUV = aUV;
}
"""
        private const val FSH = """
precision mediump float;
varying vec2 vUV;
uniform sampler2D uTex;
uniform sampler2D uMesh;
uniform vec2 uMeshSize;
void main() {
    vec2 uv = vUV;
    vec2 cell = uv * (uMeshSize - 1.0);
    vec2 f = fract(cell);
    vec2 c = floor(cell) / max(uMeshSize - 1.0, 1.0);
    vec2 c1 = (floor(cell) + 1.0) / max(uMeshSize - 1.0, 1.0);
    float wx = mix(mix(texture2D(uMesh, vec2(c.x, c.y)).r, texture2D(uMesh, vec2(c1.x, c.y)).r, f.x),
                   mix(texture2D(uMesh, vec2(c.x, c1.y)).r, texture2D(uMesh, vec2(c1.x, c1.y)).r, f.x), f.y);
    float wy = mix(mix(texture2D(uMesh, vec2(c.x, c.y)).g, texture2D(uMesh, vec2(c1.x, c.y)).g, f.x),
                   mix(texture2D(uMesh, vec2(c.x, c1.y)).g, texture2D(uMesh, vec2(c1.x, c1.y)).g, f.x), f.y);
    gl_FragColor = texture2D(uTex, vec2(wx, wy));
}
"""
    }

    private var program = 0
    private var aPosLoc = 0
    private var aUVLoc = 0
    private var uTexLoc = 0
    private var uMeshLoc = 0
    private var uMeshSizeLoc = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var surfaceTextureId = 0
    private var frameAvailable = false
    private var meshTexId = 0
    private var meshNeedsUpload = true
    private var lastMeshSig = 0
    @Volatile var surfaceReady: Boolean = false
        private set
    @Volatile private var surfaceObj: android.view.Surface? = null

    val videoSurface: android.view.Surface?
        get() = surfaceObj

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f
            ))
            position(0)
        }

    private val stMatrix = FloatArray(16)
    val surface: Surface?
        get() = surfaceTexture?.let { Surface(it) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = createProgram(VSH, FSH)
        aPosLoc = GLES20.glGetAttribLocation(program, "aPos")
        aUVLoc = GLES20.glGetAttribLocation(program, "aUV")
        uTexLoc = GLES20.glGetUniformLocation(program, "uTex")
        uMeshLoc = GLES20.glGetUniformLocation(program, "uMesh")
        uMeshSizeLoc = GLES20.glGetUniformLocation(program, "uMeshSize")

        // Create source texture for SurfaceTexture
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        surfaceTextureId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, surfaceTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(surfaceTextureId).apply {
            setOnFrameAvailableListener(this@FusionRenderer)
        }
        surfaceObj = android.view.Surface(surfaceTexture!!)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        // Create mesh texture
        val mIds = IntArray(1)
        GLES20.glGenTextures(1, mIds, 0)
        meshTexId = mIds[0]

        onSurfaceCreated(surfaceTexture!!)
        surfaceReady = true
        Log.d(TAG, "Surface created, tex=$surfaceTextureId")
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Update video frame if available
        if (frameAvailable) {
            frameAvailable = false
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(stMatrix)
            } catch (e: Exception) {
                Log.w(TAG, "updateTexImage: ${e.message}")
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (surfaceTextureId == 0) return

        GLES20.glUseProgram(program)

        // Source texture (external OES)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, surfaceTextureId)
        GLES20.glUniform1i(uTexLoc, 0)

        // Mesh texture
        uploadMesh()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, meshTexId)
        GLES20.glUniform1i(uMeshLoc, 1)

        val mesh = meshProvider()
        if (mesh != null) {
            GLES20.glUniform2f(uMeshSizeLoc, mesh.cols.toFloat(), mesh.rows.toFloat())
        }

        quad.position(0)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUVLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUVLoc)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        frameAvailable = true
        glSurfaceView?.requestRender()
    }

    fun markMeshDirty() { meshNeedsUpload = true }

    private fun uploadMesh() {
        val mesh = meshProvider() ?: return
        val sig = mesh.cols * 31 + mesh.rows * 37 + (mesh.points[0][0].x * 100).toInt()
        if (!meshNeedsUpload && sig == lastMeshSig) return
        lastMeshSig = sig; meshNeedsUpload = false

        val cols = mesh.cols; val rows = mesh.rows
        val buf = ByteBuffer.allocateDirect(cols * rows * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (r in 0 until rows)
            for (c in 0 until cols) {
                val p = mesh.points[r][c]
                buf.put(p.x); buf.put(p.y); buf.put(0f); buf.put(1f)
            }
        buf.position(0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, meshTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, cols, rows,
            0, GLES20.GL_RGBA, GLES20.GL_FLOAT, buf)
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
        if (status[0] == 0) {
            Log.e(TAG, "Shader error: ${GLES20.glGetShaderInfoLog(s)}")
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }
}
