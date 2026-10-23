package dev.mmetal.metal.render;

import org.lwjgl.opengl.GL41C;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL41C.*;

/**
 * OpenGL helpers for the capture/present sides of the GL &harr; Metal bridge.
 * MC 26.3 uses the RenderPearl OpenGL backend (GL 4.1 core on macOS through SDL),
 * so everything here sticks to GL 3.3-4.1 core entry points via LWJGL.
 */
final class GlContext {
	private int quadVao;
	private int quadVbo;
	private int srcFbo;
	private int dstFbo;
	private int nearestSampler;
	private int linearSampler;
	private boolean ready;

	GlContext() {
	}

	boolean ensure() {
		if (ready) {
			return true;
		}
		try {
			int vao = glGenVertexArrays();
			int vbo = glGenBuffers();
			glBindVertexArray(vao);
			glBindBuffer(GL_ARRAY_BUFFER, vbo);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				FloatBuffer verts = stack.floats(
					-1.0f, -1.0f,
					1.0f, -1.0f,
					-1.0f, 1.0f,
					1.0f, 1.0f);
				glBufferData(GL_ARRAY_BUFFER, verts, GL_STATIC_DRAW);
			}
			glEnableVertexAttribArray(0);
			glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0);
			glBindVertexArray(0);
			glBindBuffer(GL_ARRAY_BUFFER, 0);

			this.quadVao = vao;
			this.quadVbo = vbo;
			this.srcFbo = glGenFramebuffers();
			this.dstFbo = glGenFramebuffers();

			// Sampler objects override per-texture parameters, so sampling the game's
			// depth attachment never mutates the game's texture state.
			this.nearestSampler = glGenSamplers();
			glSamplerParameteri(nearestSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glSamplerParameteri(nearestSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			glSamplerParameteri(nearestSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glSamplerParameteri(nearestSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			glSamplerParameteri(nearestSampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);

			this.linearSampler = glGenSamplers();
			glSamplerParameteri(linearSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glSamplerParameteri(linearSampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glSamplerParameteri(linearSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glSamplerParameteri(linearSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			glSamplerParameteri(linearSampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);

			ready = true;
			return true;
		} catch (Throwable t) {
			MetalPipeline.LOGGER.error("MMetal GL context init failed", t);
			destroy();
			return false;
		}
	}

	/** Compiles a program; returns 0 on failure. */
	int program(String vertexSource, String fragmentSource, String label) {
		int vs = compile(vertexSource, GL_VERTEX_SHADER, label + ".vert");
		int fs = compile(fragmentSource, GL_FRAGMENT_SHADER, label + ".frag");
		if (vs == 0 || fs == 0) {
			return 0;
		}
		int prog = glCreateProgram();
		glAttachShader(prog, vs);
		glAttachShader(prog, fs);
		glLinkProgram(prog);
		glDeleteShader(vs);
		glDeleteShader(fs);
		IntBuffer ok = MemoryStack.stackCallocInt(1);
		glGetProgramiv(prog, GL_LINK_STATUS, ok);
		if (ok.get(0) == 0) {
			String log = glGetProgramInfoLog(prog);
			MetalPipeline.LOGGER.error("MMetal program '{}' link failed: {}", label, log);
			glDeleteProgram(prog);
			return 0;
		}
		return prog;
	}

	private static int compile(String source, int type, String label) {
		int shader = glCreateShader(type);
		glShaderSource(shader, source);
		glCompileShader(shader);
		IntBuffer ok = MemoryStack.stackCallocInt(1);
		glGetShaderiv(shader, GL_COMPILE_STATUS, ok);
		if (ok.get(0) == 0) {
			String log = glGetShaderInfoLog(shader);
			MetalPipeline.LOGGER.error("MMetal shader '{}' compile failed: {}", label, log);
			glDeleteShader(shader);
			return 0;
		}
		return shader;
	}

	/** Binds a framebuffer with the given texture as its color attachment.
	 *  The target must match how the texture object was created (GL_TEXTURE_2D for
	 *  vanilla textures, GL_TEXTURE_RECTANGLE for our IOSurface-backed ones). */
	void bindFboWithColor(int fbo, int texture, int textureTarget, int width, int height) {
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, textureTarget, texture, 0);
		glDrawBuffer(GL_COLOR_ATTACHMENT0);
		glReadBuffer(GL_COLOR_ATTACHMENT0);
		glViewport(0, 0, width, height);
		if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
			MetalPipeline.LOGGER.warn("MMetal FBO {} incomplete (texture target mismatch?)", fbo);
		}
	}

	void bindReadFbo(int fbo) {
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
	}

	void bindWriteFbo(int fbo) {
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
	}

	/** Copies srcFbo color attachment into the currently bound draw framebuffer. */
	void blitColor(int srcFbo, int srcW, int srcH, int dstW, int dstH) {
		glBindFramebuffer(GL_READ_FRAMEBUFFER, srcFbo);
		glBlitFramebuffer(0, 0, srcW, srcH, 0, 0, dstW, dstH,
			GL_COLOR_BUFFER_BIT, GL_LINEAR);
	}

	/** Draws the fullscreen quad with the given program (must be in use). */
	void drawQuad() {
		glBindVertexArray(quadVao);
		glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
		glBindVertexArray(0);
	}

	int srcFbo() {
		return srcFbo;
	}

	int dstFbo() {
		return dstFbo;
	}

	int nearestSampler() {
		return nearestSampler;
	}

	int linearSampler() {
		return linearSampler;
	}

	boolean ready() {
		return ready;
	}

	void destroy() {
		if (quadVao != 0) {
			glDeleteVertexArrays(quadVao);
			quadVao = 0;
		}
		if (quadVbo != 0) {
			glDeleteBuffers(quadVbo);
			quadVbo = 0;
		}
		if (srcFbo != 0) {
			glDeleteFramebuffers(srcFbo);
			srcFbo = 0;
		}
		if (dstFbo != 0) {
			glDeleteFramebuffers(dstFbo);
			dstFbo = 0;
		}
		if (nearestSampler != 0) {
			glDeleteSamplers(nearestSampler);
			nearestSampler = 0;
		}
		if (linearSampler != 0) {
			glDeleteSamplers(linearSampler);
			linearSampler = 0;
		}
		ready = false;
	}
}