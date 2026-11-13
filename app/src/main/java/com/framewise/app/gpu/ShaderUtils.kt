package com.framewise.app.gpu

import android.content.Context
import android.opengl.GLES30
import androidx.annotation.RawRes

object ShaderUtils {

    private val FULLSCREEN_QUAD_VERTEX_310 = """
        #version 310 es
        in vec2 aPosition;
        out vec2 vUv;
        void main() {
            vUv = aPosition * 0.5 + 0.5;
            gl_Position = vec4(aPosition, 0.0, 1.0);
        }
    """.trimIndent()

    fun loadRawText(context: Context, @RawRes rawResId: Int): String {
        return context.resources.openRawResource(rawResId).bufferedReader().use { it.readText() }
    }

    fun buildQuadProgramFromRaw(context: Context, @RawRes fragRawResId: Int): Int {
        val frag310 = loadRawText(context, fragRawResId)
        val es31Program = tryCompileProgram(FULLSCREEN_QUAD_VERTEX_310, frag310)
        if (es31Program != 0) return es31Program

        // Fallback to GLSL 300 es on OpenGL ES 3.0-only GPUs.
        val vert300 = FULLSCREEN_QUAD_VERTEX_310.replace("#version 310 es", "#version 300 es")
        val frag300 = frag310.replace("#version 310 es", "#version 300 es")
        val es30Program = tryCompileProgram(vert300, frag300)
        require(es30Program != 0) { "Failed to compile shader program for raw res $fragRawResId" }
        return es30Program
    }

    fun tryCompileProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        if (vs == 0) return 0
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fs == 0) {
            GLES30.glDeleteShader(vs)
            return 0
        }
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            GLES30.glDeleteProgram(program)
            return 0
        }
        return program
    }

    fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
