package com.oscvideoplayer

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLES11Ext
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class FusionRenderer(
    private val meshProvider: () -> FusionMesh?,
    private val onSurfaceCreated: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    companion object {
        private const val TAG = "FusionRenderer"
        private const val VSH = "attribute vec2 aPos;attribute vec2 aUV;varying vec2 vUV;void main(){gl_Position=vec4(aPos,0.0,1.0);vUV=aUV;}"
        private const val FSH = "#extension GL_OES_EGL_image_external:require\nprecision mediump float;varying vec2 vUV;uniform samplerExternalOES uTex;uniform sampler2D uMesh;uniform vec2 uMeshSize;void main(){vec2 c=vUV*(uMeshSize-1.0);vec2 f=fract(c);vec2 g=floor(c)/max(uMeshSize-1.0,1.0);vec2 g1=(floor(c)+1.0)/max(uMeshSize-1.0,1.0);float wx=mix(mix(texture2D(uMesh,vec2(g.x,g.y)).r,texture2D(uMesh,vec2(g1.x,g.y)).r,f.x),mix(texture2D(uMesh,vec2(g.x,g1.y)).r,texture2D(uMesh,vec2(g1.x,g1.y)).r,f.x),f.y);float wy=mix(mix(texture2D(uMesh,vec2(g.x,g.y)).g,texture2D(uMesh,vec2(g1.x,g.y)).g,f.x),mix(texture2D(uMesh,vec2(g.x,g1.y)).g,texture2D(uMesh,vec2(g1.x,g1.y)).g,f.x),f.y);gl_FragColor=texture2D(uTex,vec2(wx,1.0-wy));}"
        private const val SVS = "attribute vec2 aPos;void main(){gl_Position=vec4(aPos,0.0,1.0);}"
        private const val SFS = "precision mediump float;void main(){gl_FragColor=vec4(0.0,0.0,0.0,0.0);}"
    }
    private var program=0;private var aPosLoc=0;private var aUVLoc=0;private var uTexLoc=0;private var uMeshLoc=0;private var uMeshSizeLoc=0
    private var stencilProgram=0;private var sPosLoc=0
    private var surfaceTexture:SurfaceTexture?=null;private var surfaceTextureId=0;private var meshTexId=0
    private var frameAvailable=false
    @Volatile var surfaceReady:Boolean=false;private set
    private var surfaceObj:Surface?=null;val videoSurface:Surface? get()=surfaceObj
    var glSurfaceView:GLSurfaceView?=null
    @Volatile var enabled:Boolean=true

    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?){
        GLES20.glClearColor(0f,0f,0f,1f);GLES20.glClearStencil(0)
        program=createProgram(VSH,FSH);aPosLoc=GLES20.glGetAttribLocation(program,"aPos")
        aUVLoc=GLES20.glGetAttribLocation(program,"aUV");uTexLoc=GLES20.glGetUniformLocation(program,"uTex")
        uMeshLoc=GLES20.glGetUniformLocation(program,"uMesh");uMeshSizeLoc=GLES20.glGetUniformLocation(program,"uMeshSize")
        stencilProgram=createProgram(SVS,SFS);sPosLoc=GLES20.glGetAttribLocation(stencilProgram,"aPos")
        val ids=IntArray(2);GLES20.glGenTextures(2,ids,0);surfaceTextureId=ids[0];meshTexId=ids[1]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,surfaceTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,meshTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture=SurfaceTexture(surfaceTextureId).apply{setOnFrameAvailableListener(this@FusionRenderer)}
        surfaceObj=Surface(surfaceTexture!!);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,0)
        surfaceReady=true;onSurfaceCreated(surfaceTexture!!)
        Log.d(TAG,"init")
    }
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int){GLES20.glViewport(0,0,w,h)}
    override fun onDrawFrame(gl:GL10?){
        if(frameAvailable){frameAvailable=false;try{surfaceTexture?.updateTexImage()}catch(_:Exception){}}
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)
        if(surfaceTextureId==0)return
        val mesh=meshProvider()
        if(mesh!=null&&meshTexId>0){
            // Use 33x33 subdivided mesh with perspective correction for 2x2 grids
            val usePersp=mesh.cols==2&&mesh.rows==2&&enabled
            val sc=if(usePersp)33 else mesh.cols;val sr=if(usePersp)33 else mesh.rows
            val invMatrix=if(usePersp){
                val m=android.graphics.Matrix()
                val src=floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f)
                val p00=mesh.points[0][0];val p10=mesh.points[0][1];val p01=mesh.points[1][0];val p11=mesh.points[1][1]
                m.setPolyToPoly(src,0, floatArrayOf(p00.x,p00.y,p10.x,p10.y,p01.x,p01.y,p11.x,p11.y),0,4)
                val inv=android.graphics.Matrix();m.invert(inv);inv
            }else null
            val buf=ByteBuffer.allocateDirect(sc*sr*4).order(ByteOrder.nativeOrder())
            val pts=FloatArray(2)
            for(r in 0 until sr)for(c in 0 until sc){
                pts[0]=c.toFloat()/(sc-1);pts[1]=r.toFloat()/(sr-1)
                if(invMatrix!=null)invMatrix.mapPoints(pts)
                buf.put((pts[0].coerceIn(0f,1f)*255f).toInt().coerceIn(0,255).toByte())
                buf.put((pts[1].coerceIn(0f,1f)*255f).toInt().coerceIn(0,255).toByte())
                buf.put(0.toByte());buf.put(255.toByte())
            }
            buf.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,meshTexId)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,sc,sr,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,buf)
            GLES20.glUniform2f(uMeshSizeLoc,sc.toFloat(),sr.toFloat())
        }
        if(enabled&&mesh!=null){
            GLES20.glEnable(GLES20.GL_STENCIL_TEST)
            GLES20.glStencilFunc(GLES20.GL_ALWAYS,1,0xFF)
            GLES20.glStencilOp(GLES20.GL_KEEP,GLES20.GL_KEEP,GLES20.GL_REPLACE)
            GLES20.glColorMask(false,false,false,false)
            GLES20.glUseProgram(stencilProgram);GLES20.glEnableVertexAttribArray(sPosLoc)
            val cols=mesh.cols;val rows=mesh.rows
            for(r in 0 until rows-1)for(c in 0 until cols-1){
                val p00=Pair(mesh.points[r][c].x*2f-1f,(1f-mesh.points[r][c].y)*2f-1f)
                val p10=Pair(mesh.points[r][c+1].x*2f-1f,(1f-mesh.points[r][c+1].y)*2f-1f)
                val p01=Pair(mesh.points[r+1][c].x*2f-1f,(1f-mesh.points[r+1][c].y)*2f-1f)
                val p11=Pair(mesh.points[r+1][c+1].x*2f-1f,(1f-mesh.points[r+1][c+1].y)*2f-1f)
                val tri=floatArrayOf(p00.first,p00.second,p10.first,p10.second,p11.first,p11.second,p00.first,p00.second,p11.first,p11.second,p01.first,p01.second)
                val b=ByteBuffer.allocateDirect(tri.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(tri).apply{position(0)}
                GLES20.glVertexAttribPointer(sPosLoc,2,GLES20.GL_FLOAT,false,8,b)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,6)
            }
            GLES20.glColorMask(true,true,true,true)
            GLES20.glStencilFunc(GLES20.GL_EQUAL,1,0xFF)
            GLES20.glStencilOp(GLES20.GL_KEEP,GLES20.GL_KEEP,GLES20.GL_KEEP)
        }
        GLES20.glUseProgram(program);GLES20.glUniform1i(uTexLoc,0);GLES20.glUniform1i(uMeshLoc,1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,surfaceTextureId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,meshTexId)
        val quad=floatArrayOf(-1f,-1f,0f,0f,1f,-1f,1f,0f,-1f,1f,0f,1f,1f,-1f,1f,0f,1f,1f,1f,1f,-1f,1f,0f,1f)
        val qbuf=ByteBuffer.allocateDirect(quad.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad).apply{position(0)}
        GLES20.glVertexAttribPointer(aPosLoc,2,GLES20.GL_FLOAT,false,16,qbuf)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        qbuf.position(2)
        GLES20.glVertexAttribPointer(aUVLoc,2,GLES20.GL_FLOAT,false,16,qbuf)
        GLES20.glEnableVertexAttribArray(aUVLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,6)
        if(enabled)GLES20.glDisable(GLES20.GL_STENCIL_TEST)
    }
    override fun onFrameAvailable(st:SurfaceTexture?){frameAvailable=true}
    fun markMeshDirty(){}
    private fun compileShader(type:Int,src:String):Int{
        val s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,src);GLES20.glCompileShader(s)
        val st=IntArray(1);GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,st,0)
        if(st[0]==0){Log.e(TAG,"shaderErr:"+GLES20.glGetShaderInfoLog(s));GLES20.glDeleteShader(s);return 0}
        return s
    }
    private fun createProgram(vsh:String,fsh:String):Int{
        val vs=compileShader(GLES20.GL_VERTEX_SHADER,vsh)
        val fs=compileShader(GLES20.GL_FRAGMENT_SHADER,fsh)
        val p=GLES20.glCreateProgram();GLES20.glAttachShader(p,vs);GLES20.glAttachShader(p,fs)
        GLES20.glLinkProgram(p);return p
    }
}
