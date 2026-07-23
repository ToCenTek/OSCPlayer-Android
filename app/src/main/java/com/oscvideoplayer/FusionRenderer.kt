package com.oscvideoplayer

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLES11Ext
import android.opengl.GLSurfaceView
import android.opengl.Matrix
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
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUV = aUV;
}
"""
        private const val FSH = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUV;
uniform samplerExternalOES uTex;
void main() {
    gl_FragColor = texture2D(uTex, vUV);
}
"""
    }

    private var program = 0
    private var aPosLoc = 0
    private var aUVLoc = 0
    private var uTexLoc = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var surfaceTextureId = 0
    private var frameAvailable = false
    private var frameCount = 0
    @Volatile var surfaceReady: Boolean = false
        private set
    private var surfaceObj: Surface? = null
    val videoSurface: Surface? get() = surfaceObj

    // Vertex buffer for mesh cells (max 65x65 grid = 64*64*6 = 24576 vertices)
    private val MAX_VERTS = 66 * 66 * 6
    private var vertBuffer: FloatBuffer = ByteBuffer.allocateDirect(MAX_VERTS * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var vertCount = 0

    var glSurfaceView: GLSurfaceView? = null
    @Volatile var enabled: Boolean = true
    @Volatile var bezier: Boolean = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = createProgram(VSH, FSH)
        aPosLoc = GLES20.glGetAttribLocation(program, "aPos")
        aUVLoc = GLES20.glGetAttribLocation(program, "aUV")
        uTexLoc = GLES20.glGetUniformLocation(program, "uTex")

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
        surfaceObj = Surface(surfaceTexture!!)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        surfaceReady = true
        onSurfaceCreated(surfaceTexture!!)
        Log.d(TAG, "Surface created, tex=$surfaceTextureId, surfaceReady=true")
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (frameAvailable) {
            frameAvailable = false
            try {
                surfaceTexture?.updateTexImage()
                frameCount++
            } catch (e: Exception) {
                Log.w(TAG, "updateTexImage: ${e.message}")
            }
        }
        if (surfaceTextureId == 0) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(uTexLoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, surfaceTextureId)

        buildMesh()
        if (vertCount == 0) return

        vertBuffer.position(0)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, vertBuffer)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        vertBuffer.position(2)
        GLES20.glVertexAttribPointer(aUVLoc, 2, GLES20.GL_FLOAT, false, 16, vertBuffer)
        GLES20.glEnableVertexAttribArray(aUVLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertCount)
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        frameAvailable = true
    }

    fun markMeshDirty() { }

    private fun buildMesh() {
        val mesh = meshProvider() ?: return
        if (!enabled) {
            vertBuffer.clear(); vertCount = 0
            emit(0f, 0f, 0f, 0f); emit(1f, 0f, 1f, 0f); emit(0f, 1f, 0f, 1f)
            emit(1f, 0f, 1f, 0f); emit(1f, 1f, 1f, 1f); emit(0f, 1f, 0f, 1f)
            vertBuffer.position(0); return
        }

        val cols = mesh.cols; val rows = mesh.rows
        vertBuffer.clear()
        vertCount = 0

        // For each cell, render 2 triangles
        for (r in 0 until rows - 1) {
            for (c in 0 until cols - 1) {
                if (!bezier) {
                    val p00 = mesh.points[r][c]; val p10 = mesh.points[r][c + 1]
                    val p01 = mesh.points[r + 1][c]; val p11 = mesh.points[r + 1][c + 1]
                    val tu0 = c.toFloat() / (cols - 1); val tu1 = (c + 1).toFloat() / (cols - 1)
                    val tv0 = r.toFloat() / (rows - 1); val tv1 = (r + 1).toFloat() / (rows - 1)
                    emit(p00.x, p00.y, tu0, tv0); emit(p10.x, p10.y, tu1, tv0)
                    emit(p01.x, p01.y, tu0, tv1)
                    emit(p10.x, p10.y, tu1, tv0); emit(p11.x, p11.y, tu1, tv1)
                    emit(p01.x, p01.y, tu0, tv1)
                } else {
                    for (sr in 0 until 8) for (sc in 0 until 8) {
                        val fu = sc.toFloat() / 8f; val fv = sr.toFloat() / 8f
                        val fu1 = (sc + 1).toFloat() / 8f; val fv1 = (sr + 1).toFloat() / 8f
                        val p00 = getPoint(mesh, r, c, fu, fv)
                        val p10 = getPoint(mesh, r, c, fu1, fv)
                        val p01 = getPoint(mesh, r, c, fu, fv1)
                        val p11 = getPoint(mesh, r, c, fu1, fv1)
                        val tu = (c + fu) / (cols - 1); val tv = (r + fv) / (rows - 1)
                        val tu1 = (c + fu1) / (cols - 1); val tv1 = (r + fv1) / (rows - 1)
                        emit(p00.x, p00.y, tu, tv); emit(p10.x, p10.y, tu1, tv)
                        emit(p01.x, p01.y, tu, tv1)
                        emit(p10.x, p10.y, tu1, tv); emit(p11.x, p11.y, tu1, tv1)
                        emit(p01.x, p01.y, tu, tv1)
                    }
                }
            }
        }
        vertBuffer.position(0)
    }

    private fun getPoint(mesh: FusionMesh, r: Int, c: Int, fu: Float, fv: Float): FusionMesh.Point {
        val p00 = mesh.points[r][c]; val p10 = mesh.points[r][c + 1]
        val p01 = mesh.points[r + 1][c]; val p11 = mesh.points[r + 1][c + 1]
        if (!bezier) {
            return FusionMesh.Point(
                lerp(lerp(p00.x, p10.x, fu), lerp(p01.x, p11.x, fu), fv),
                lerp(lerp(p00.y, p10.y, fu), lerp(p01.y, p11.y, fu), fv)
            )
        }
        // Bezier Coons patch: blend 4 edges
        val topx = mesh.bezierX(r, c, FusionMesh.Dir.RIGHT, fu, true)
        val botx = mesh.bezierX(r + 1, c, FusionMesh.Dir.RIGHT, fu, true)
        val leftx = mesh.bezierX(r, c, FusionMesh.Dir.DOWN, fv, true)
        val rightx = mesh.bezierX(r, c + 1, FusionMesh.Dir.DOWN, fv, true)
        val topy = mesh.bezierX(r, c, FusionMesh.Dir.RIGHT, fu, false)
        val boty = mesh.bezierX(r + 1, c, FusionMesh.Dir.RIGHT, fu, false)
        val lefty = mesh.bezierX(r, c, FusionMesh.Dir.DOWN, fv, false)
        val righty = mesh.bezierX(r, c + 1, FusionMesh.Dir.DOWN, fv, false)
        val bilinx = lerp(lerp(p00.x, p10.x, fu), lerp(p01.x, p11.x, fu), fv)
        val biliny = lerp(lerp(p00.y, p10.y, fu), lerp(p01.y, p11.y, fu), fv)
        return FusionMesh.Point(
            lerp(topx, botx, fv) + lerp(leftx, rightx, fu) - bilinx,
            lerp(topy, boty, fv) + lerp(lefty, righty, fu) - biliny
        )
    }

    private fun emit(x: Float, y: Float, tu: Float, tv: Float) {
        if (vertCount >= MAX_VERTS) return
        // Map mesh point (0-1) to NDC (-1 to 1), flip Y for OpenGL convention
        vertBuffer.put(x * 2f - 1f)
        vertBuffer.put((1f - y) * 2f - 1f)
        vertBuffer.put(tu)
        vertBuffer.put(tv)
        vertCount++
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun catmullRom1D(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t; val t3 = t2 * t
        return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
    }

    // For bezier: interpolate 4 points in a row/column

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
