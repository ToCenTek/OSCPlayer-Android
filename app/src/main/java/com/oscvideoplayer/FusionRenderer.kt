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
        private const val VSH = "attribute vec2 aPos;attribute vec2 aUV;varying vec2 vUV;void main(){gl_Position=vec4(aPos,0.0,1.0);vUV=aUV;}"
        private const val FSH = "#extension GL_OES_EGL_image_external:require\nprecision mediump float;varying vec2 vUV;uniform samplerExternalOES uTex;void main(){gl_FragColor=texture2D(uTex,vUV);}"
    }
    private var program=0;private var aPosLoc=0;private var aUVLoc=0;private var uTexLoc=0
    private var surfaceTexture:SurfaceTexture?=null;private var surfaceTextureId=0
    private var frameAvailable=false
    @Volatile var surfaceReady:Boolean=false;private set
    private var surfaceObj:Surface?=null;val videoSurface:Surface? get()=surfaceObj
    private val MAX_VERTS=130*130*6
    private var vertBuffer:FloatBuffer=ByteBuffer.allocateDirect(MAX_VERTS*4*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var vertCount=0;private var meshHash=0L
    var glSurfaceView:GLSurfaceView?=null
    @Volatile var enabled:Boolean=true

    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?){
        GLES20.glClearColor(0f,0f,0f,1f)
        program=createProgram(VSH,FSH);aPosLoc=GLES20.glGetAttribLocation(program,"aPos")
        aUVLoc=GLES20.glGetAttribLocation(program,"aUV");uTexLoc=GLES20.glGetUniformLocation(program,"uTex")
        val ids=IntArray(1);GLES20.glGenTextures(1,ids,0);surfaceTextureId=ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,surfaceTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture=SurfaceTexture(surfaceTextureId).apply{setOnFrameAvailableListener(this@FusionRenderer)}
        surfaceObj=Surface(surfaceTexture!!);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,0)
        surfaceReady=true;onSurfaceCreated(surfaceTexture!!)
        Log.d(TAG,"init")
    }
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int){GLES20.glViewport(0,0,w,h)}
    override fun onDrawFrame(gl:GL10?){
        if(frameAvailable){frameAvailable=false;try{surfaceTexture?.updateTexImage()}catch(_:Exception){}}
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if(surfaceTextureId==0)return
        GLES20.glUseProgram(program);GLES20.glUniform1i(uTexLoc,0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,surfaceTextureId)
        buildMesh()
        if(vertCount>0){
            vertBuffer.position(0)
            GLES20.glVertexAttribPointer(aPosLoc,2,GLES20.GL_FLOAT,false,16,vertBuffer)
            GLES20.glEnableVertexAttribArray(aPosLoc)
            vertBuffer.position(2)
            GLES20.glVertexAttribPointer(aUVLoc,2,GLES20.GL_FLOAT,false,16,vertBuffer)
            GLES20.glEnableVertexAttribArray(aUVLoc)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,vertCount)
        }
    }
    override fun onFrameAvailable(st:SurfaceTexture?){frameAvailable=true}
    fun markMeshDirty(){meshHash=0L}
    private fun lerp(a:Float,b:Float,t:Float)=a+(b-a)*t

    private fun buildMesh(){
        val mesh=meshProvider()?:return
        val hash=mesh.points[0][0].x.toLong()*31+mesh.points[0][mesh.cols-1].y.toLong()*37
        if(hash==meshHash&&vertCount>0)return
        meshHash=hash
        if(!enabled){vertBuffer.clear();vertCount=0
            emit(0f,0f,0f,0f);emit(1f,0f,1f,0f);emit(0f,1f,0f,1f)
            emit(1f,0f,1f,0f);emit(1f,1f,1f,1f);emit(0f,1f,0f,1f);vertBuffer.position(0);return}
        val cols=mesh.cols;val rows=mesh.rows
        val sub=(96/(cols-1)).coerceIn(4,96)
        vertBuffer.clear();vertCount=0
        for(r in 0 until rows-1)for(c in 0 until cols-1){
            val p00=mesh.points[r][c];val p10=mesh.points[r][c+1];val p01=mesh.points[r+1][c];val p11=mesh.points[r+1][c+1]
            val sx={u:Float,v:Float->lerp(lerp(p00.x,p10.x,u),lerp(p01.x,p11.x,u),v)}
            val sy={u:Float,v:Float->lerp(lerp(p00.y,p10.y,u),lerp(p01.y,p11.y,u),v)}
            for(sr in 0 until sub)for(sc in 0 until sub){
                val fu=sc.toFloat()/sub;val fv=sr.toFloat()/sub;val fu1=(sc+1).toFloat()/sub;val fv1=(sr+1).toFloat()/sub
                val dx=sx(fu,fv);val dy=sy(fu,fv);val dx1=sx(fu1,fv);val dy1=sy(fu1,fv)
                val dx2=sx(fu,fv1);val dy2=sy(fu,fv1);val dx3=sx(fu1,fv1);val dy3=sy(fu1,fv1)
                val tu=(c+fu)/(cols-1);val tv=(r+fv)/(rows-1);val tu1=(c+fu1)/(cols-1);val tv1=(r+fv1)/(rows-1)
                emit(dx,dy,tu,tv);emit(dx1,dy1,tu1,tv)
                emit(dx2,dy2,tu,tv1);emit(dx1,dy1,tu1,tv)
                emit(dx3,dy3,tu1,tv1);emit(dx2,dy2,tu,tv1)
            }
        }
        vertBuffer.position(0)
    }

    private fun emit(x:Float,y:Float,tu:Float,tv:Float){
        if(vertCount>=MAX_VERTS)return
        vertBuffer.put(x*2f-1f);vertBuffer.put((1f-y)*2f-1f);vertBuffer.put(tu);vertBuffer.put(tv);vertCount++
    }
    private fun compileShader(type:Int,src:String):Int{
        val s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,src);GLES20.glCompileShader(s)
        val st=IntArray(1);GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,st,0)
        if(st[0]==0){Log.e(TAG,"shader:"+GLES20.glGetShaderInfoLog(s));GLES20.glDeleteShader(s);return 0}
        return s
    }
    private fun createProgram(vsh:String,fsh:String):Int{
        val vs=compileShader(GLES20.GL_VERTEX_SHADER,vsh)
        val fs=compileShader(GLES20.GL_FRAGMENT_SHADER,fsh)
        val p=GLES20.glCreateProgram();GLES20.glAttachShader(p,vs);GLES20.glAttachShader(p,fs)
        GLES20.glLinkProgram(p);return p
    }
}
