package dev.mmetal.metal.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.mmetal.metal.config.MMetalConfig;
import dev.mmetal.metal.nativebridge.MetalFx;
import dev.mmetal.metal.nativebridge.MetalNative;
import dev.mmetal.metal.nativebridge.SystemInfo;
import dev.mmetal.metal.ui.MMetalConfigScreen;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Locale;

import static org.lwjgl.opengl.GL41C.*;

/**
 * The MMetal render pipeline, driven from the head of the swapchain present call:
 *
 * <ol>
 *   <li>Capture the game's main render target (color + depth) into IOSurface-backed
 *       GL textures (zero-copy GL &rarr; Metal sharing).</li>
 *   <li>On Metal: MetalFX upscaling (spatial or temporal), Metal-compute ray traced
 *       lighting (shadows, AO, reflections, GI) and motion blur.</li>
 *   <li>Present the Metal result to the window's default framebuffer from GL and let
 *       the vanilla swap proceed.</li>
 * </ol>
 *
 * <p>Every stage degrades gracefully: a single fatal error disables the mod for the
 * session instead of crashing the game.</p>
 */
public final class MetalPipeline {
	public static final Logger LOGGER = LoggerFactory.getLogger("MMetal");

	private static MetalPipeline instance;

	// GLSL shared by the depth-extract and present programs.
	private static final String VERT_SRC = """
		#version 410 core
		in vec2 aPos;
		out vec2 vUv;
		void main() {
			vUv = aPos * 0.5 + 0.5;
			gl_Position = vec4(aPos, 0.0, 1.0);
		}
		""";
	private static final String PRESENT_FRAG = """
		#version 410 core
		uniform sampler2DRect uTex;
		uniform vec2 uSize;
		in vec2 vUv;
		out vec4 o;
		void main() {
			o = vec4(texture(uTex, vUv * uSize).rgb, 1.0);
		}
		""";
	private static final String DEPTH_FRAG = """
		#version 410 core
		uniform sampler2D uDepth;
		in vec2 vUv;
		out vec4 o;
		void main() {
			// Reversed depth: 1.0 = near plane, 0.0 = far/sky (cleared to 0).
			// Pack 24-bit fixed point into RGB (Metal unpacks: r=MSB, g=mid, b=LSB).
			// BGRA8 is the one 4-byte IOSurface format combo this GL supports.
			float d = clamp(texture(uDepth, vUv).r, 0.0, 1.0);
			float u = d * 16777215.0;
			float b = floor(mod(u, 256.0)) / 255.0;
			float g = floor(mod(u / 256.0, 256.0)) / 255.0;
			float r = floor(u / 65536.0) / 255.0;
			o = vec4(r, g, b, 1.0);
		}
		""";

	// EffectParams uniform offsets (see MMetalRaytrace.metal).
	private static final int OFF_VIEWPORT = 0, OFF_NEAR = 8, OFF_FAR = 12, OFF_SUN_STRENGTH = 16,
		OFF_AO_STRENGTH = 20, OFF_STEPS = 24, OFF_RAY_LENGTH = 28, OFF_SUN_DIR = 32,
		OFF_TIME = 48, OFF_TANHALF = 52, OFF_ASPECT = 56, OFF_MB_AMOUNT = 64,
		OFF_REFL_STRENGTH = 68, OFF_GI_STRENGTH = 72, OFF_DENOISE = 76, OFF_RT_MIX = 80,
		EFFECT_PARAMS_SIZE = 96;
	// RtShadowParams layout (mm_rt_shadows).
	private static final int RT_VIEWPORT = 0, RT_NEAR = 8, RT_FAR = 12, RT_VIEW_ROT_INV = 16,
		RT_EYE = 80, RT_SUN_DIR = 96, RT_TANHALF = 112, RT_ASPECT = 116, RT_JITTER = 120,
		RT_RAYS = 124, RT_PARAMS_SIZE = 128;
	// MotionParams uniform offsets.
	private static final int M_OFF_IN_SIZE = 0, M_OFF_OUT_SIZE = 8, M_OFF_CUR_ROT = 16, M_OFF_PREV_ROT = 80,
		M_OFF_CUR_VP = 144, M_OFF_PREV_VP = 208, M_OFF_CUR_EYE = 272, M_OFF_PREV_EYE = 288,
		M_OFF_NEAR = 304, M_OFF_FAR = 308, M_OFF_TANHALF = 312, M_OFF_ASPECT = 316,
		M_OFF_FIRST = 320, MOTION_PARAMS_SIZE = 336;

	private final MMetalConfig config;
	private final GlContext gl = new GlContext();
	private final FrameState frames = new FrameState();
	private final Arena frameArena = Arena.global();

	private Path configPath;
	private FileTime configStamp;

	private MetalLibrary library;
	private boolean bootstrapped;
	private boolean disabled;
	private float currentScaleFactor = 1.0f;
	private boolean uiWasOpen;
	/** Feedback controller for the Dynamic quality preset. */
	private final DynamicResolution dynamicResolution = new DynamicResolution();
	private boolean dynamicSeeded;

	// Per-size resources.
	private int scaledW = -1;
	private int scaledH = -1;
	private int fullW = -1;
	private int fullH = -1;
	private IosurfacePair inColor;
	private IosurfacePair inDepth;
	private IosurfacePair outColor;
	private MemorySegment privateFullTex;
	private MemorySegment postFxTex;
	private MemorySegment maskTex;
	private MemorySegment prevColorTex;
	private MemorySegment depthR32Tex;
	private MemorySegment blurredTex;
	private MemorySegment reflectionTex;
	private MemorySegment giTex;
	private MemorySegment rtShadowTex;
	private MemorySegment motionTex;
	private final RtShadowBvh rtBvh = new RtShadowBvh(LOGGER);
	private MemorySegment spatialScaler;
	private MemorySegment temporalScaler;
	private boolean depthCapturedThisFrame;
	private int presentProgram;
	private int depthProgram;
	private long lastFrameNanos;
	private float deltaTime = 1.0f / 60.0f;
	private boolean resetPending = true;
	private boolean lastEffectsActive;
	private boolean lastPackOwnsLook;
	private long lastPresentStartNanos;
	private int pendingW = -1;
	private int pendingH = -1;
	private int pendingStableFrames;
	private long lastRebuildNanos;
	// Sizes of the last successful resource build; MetalFX objects are size-bound, and a
	// throttled rebuild can leave them stale for a few frames.
	private int resInW = -1;
	private int resInH = -1;
	private int resOutW = -1;
	private int resOutH = -1;

	private MetalPipeline(MMetalConfig config) {
		this.config = config;
	}

	// ------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------

	public static void initialize(MMetalConfig config) {
		instance = new MetalPipeline(config);
		LOGGER.info("MMetal pipeline created ({})", config.summary());
	}

	public static MetalPipeline instance() {
		return instance;
	}

	/** The live, shared config instance (mutated by the UI and /mmetal commands). */
	public static MMetalConfig config() {
		return instance != null ? instance.config : null;
	}

	/** Call after mutating any config field outside of file reloads. */
	public static void onConfigMutated() {
		MetalPipeline p = instance;
		if (p != null) {
			p.resetPending = true;
			p.dynamicSeeded = false;
			// Discrete setting changes (quality preset, upscaler mode, ...) should take
			// effect immediately: bypass the resize-stability throttle.
			p.pendingStableFrames = 3;
			p.lastRebuildNanos = 0;
			// The GUI coordinate space scales with the render scale, so an open screen
			// must re-layout its widgets after a preset change.
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.gui != null && mc.gui.screen() != null) {
				((dev.mmetal.metal.mixin.MinecraftInvoker) mc).mmetal$resizeGui();
			}
		}
	}

	/** Human-readable status for /mmetal status. */
	public static String statusLine() {
		MetalPipeline p = instance;
		if (p == null) {
			return "inactive (not initialized)";
		}
		if (p.disabled) {
			return "disabled for this session (see logs)";
		}
		return String.format(Locale.ROOT, "%s | %s", MMetalConfigScreen.capabilitySummary(), p.config.summary());
	}

	public boolean isDisabled() {
		return disabled;
	}

	/**
	 * Captures the world depth into the IOSurface BEFORE the GUI phase runs: the vanilla
	 * GUI clears the main target's depth attachment (GuiRenderer.draw), so a capture
	 * taken at present time would see "far" everywhere. Called from GuiRenderer.draw HEAD.
	 */
	public static void captureDepthEarly() {
		captureEarly();
	}

	/**
	 * Called just BEFORE the game clears the main depth texture for the first time in a
	 * frame. {@code renderLevel} clears it (to 0.0, reversed-Z far) before drawing the
	 * first-person hand and {@code render} clears it again before the GUI, so by the GUI
	 * phase the whole texture is zero - the world depth has to be read before either.
	 */
	public static void captureDepthBeforeClear() {
		captureEarly();
	}

	private static void captureEarly() {
		MetalPipeline p = instance;
		if (p == null || p.disabled || !p.config.enabled || !p.bootstrapped
			|| p.inDepth == null || p.depthProgram == 0 || p.scaledW <= 0 || p.depthCapturedThisFrame) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.gameRenderer == null) {
			return;
		}
		var main = mc.gameRenderer.mainRenderTarget();
		if (main == null || main.width <= 0 || main.height <= 0) {
			return;
		}
		p.depthCapturedThisFrame = true;
		p.captureDepth(main, main.width, main.height);
	}

	/** GPU-side depth pack: main depth attachment -> packed BGRA8 IOSurface. */
	private void captureDepth(RenderTarget main, int inW, int inH) {
		int savedProgram = glGetInteger(GL_CURRENT_PROGRAM);
		int savedVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
		int savedDrawFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		int savedReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
		int[] vp = new int[4];
		glGetIntegerv(GL_VIEWPORT, vp);
		int savedSampler = glGetInteger(GL_SAMPLER_BINDING);
		int savedTex2D = glGetInteger(GL_TEXTURE_BINDING_2D);
		int savedActive = glGetInteger(GL_ACTIVE_TEXTURE);
		// The capture runs mid-frame (GUI phase start / present), where the world pass
		// may have left the scissor/depth/cull tests enabled. A scissored fullscreen
		// draw silently writes nothing, which is exactly how the packed depth ended up
		// all-zero (effects starved of depth).
		boolean savedBlend = glIsEnabled(GL_BLEND);
		boolean savedDepthTest = glIsEnabled(GL_DEPTH_TEST);
		boolean savedScissor = glIsEnabled(GL_SCISSOR_TEST);
		boolean savedCull = glIsEnabled(GL_CULL_FACE);
		try {
			glDisable(GL_BLEND);
			glDisable(GL_DEPTH_TEST);
			glDisable(GL_SCISSOR_TEST);
			glDisable(GL_CULL_FACE);
			int depthGlId = textureGlId(main.getDepthTexture());
			gl.bindFboWithColor(gl.dstFbo(), inDepth.glTexture, GL_TEXTURE_RECTANGLE, scaledW, scaledH);
			glUseProgram(depthProgram);
			glActiveTexture(GL_TEXTURE0);
			glBindTexture(GL_TEXTURE_2D, depthGlId);
			glBindSampler(0, gl.nearestSampler());
			glUniform2f(glGetUniformLocation(depthProgram, "uSize"), inW, inH);
			gl.drawQuad();
			glBindSampler(0, 0);
		} catch (Throwable t) {
			LOGGER.warn("MMetal early depth capture failed", t);
		} finally {
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, savedDrawFb);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, savedReadFb);
			glViewport(vp[0], vp[1], vp[2], vp[3]);
			glUseProgram(savedProgram);
			glBindVertexArray(savedVao);
			glBindSampler(0, savedSampler);
			glActiveTexture(GL_TEXTURE0);
			glBindTexture(GL_TEXTURE_2D, savedTex2D);
			glActiveTexture(savedActive);
			if (savedBlend) glEnable(GL_BLEND);
			if (savedDepthTest) glEnable(GL_DEPTH_TEST);
			if (savedScissor) glEnable(GL_SCISSOR_TEST);
			if (savedCull) glEnable(GL_CULL_FACE);
		}
	}

	/** Render scale in effect while a frame is being rendered (0 disables scaling). */
	public static float currentScaleFactor() {
		MetalPipeline p = instance;
		if (p == null || p.disabled || !p.config.enabled || !p.bootstrapped) {
			return 1.0f;
		}
		return p.currentScaleFactor;
	}

	private void fatal(Throwable t) {
		if (!disabled) {
			disabled = true;
			currentScaleFactor = 1.0f;
			LOGGER.error("MMetal disabled for this session after an error", t);
		}
	}

	private void checkConfigReload() {
		try {
			if (configPath == null) {
				configPath = Path.of("config", "mmetal.json").toAbsolutePath();
			}
			if (Files.exists(configPath)) {
				FileTime stamp = Files.getLastModifiedTime(configPath);
				if (!stamp.equals(configStamp)) {
					configStamp = stamp;
					MMetalConfig reloaded = MMetalConfig.load();
					config.enabled = reloaded.enabled;
					config.qualityPreset = reloaded.qualityPreset;
					config.renderScale = reloaded.renderScale;
					config.dynamicMinScale = reloaded.dynamicMinScale;
					config.dynamicMaxScale = reloaded.dynamicMaxScale;
					config.dynamicTargetFps = reloaded.dynamicTargetFps;
					dynamicSeeded = false;
					config.upscaleMode = reloaded.upscaleMode;
					config.effects = reloaded.effects;
					resetPending = true;
					LOGGER.info("MMetal config reloaded ({})", config.summary());
				}
			}
		} catch (Throwable ignored) {
		}
	}

	// ------------------------------------------------------------------
	// Present entry point (called from the GlSurface mixin, render thread)
	// ------------------------------------------------------------------

	public static void onPresent(long windowHandle, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		MetalPipeline p = instance;
		if (p == null || p.disabled || !p.config.enabled) {
			return;
		}
		try {
			p.checkConfigReload();
			if (!p.config.enabled) {
				return;
			}
			p.present(windowHandle, ci);
		} catch (Throwable t) {
			p.fatal(t);
		}
	}

	private void present(long windowHandle, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		long tPresentStart = System.nanoTime();
		MetalStats.updateConfigSnapshot(config);
		MetalStats.frames++;
		if (resetPending && bootstrapped) {
			MetalStats.historyResets++;
		}
		if (!bootstrapped) {
			bootstrap();
			if (!bootstrapped) {
				return;
			}
		}
		if (!gl.ensure()) {
			fatal(new IllegalStateException("GL helpers unavailable"));
			return;
		}

		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.gameRenderer == null) {
			return;
		}
		// While an Iris shader pack renders it owns the look of the world: MetalFX
		// upscaling still runs over its output, but MMetal's lighting and motion blur
		// stay out of the way.
		boolean packOwnsLook = dev.mmetal.metal.hook.Compat.irisShadersActive();

		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		if (main == null || main.width <= 0 || main.height <= 0) {
			return;
		}
		int inW = main.width;
		int inH = main.height;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			java.nio.IntBuffer wBuf = stack.mallocInt(1);
			java.nio.IntBuffer hBuf = stack.mallocInt(1);
			SDLVideo.SDL_GetWindowSizeInPixels(windowHandle, wBuf, hBuf);
			fullW = wBuf.get(0);
			fullH = hBuf.get(0);
		}
		if (fullW <= 0 || fullH <= 0) {
			return;
		}

		// Temporal upscaling and motion blur must never blend across a UI state change
		// (pause menu opening/closing, etc.) - reset history.
		boolean uiOpen = mc.gui != null && mc.gui.screen() != null;

		float scale = clampScale(frameRenderScale(mc, uiOpen, tPresentStart));
		currentScaleFactor = scale;
		boolean upscale = scale < 0.999f && config.upscaleMode != MMetalConfig.UpscaleMode.OFF;
		boolean effects = config.effects.enabled && !packOwnsLook;
		boolean motionBlur = effects && config.effects.motionBlur;
		boolean temporal = upscale && config.upscaleMode == MMetalConfig.UpscaleMode.TEMPORAL;

		if (uiOpen != uiWasOpen) {
			uiWasOpen = uiOpen;
			resetPending = true;
		}
		// A pipeline-mode boundary (a shader pack activating/deactivating toggles the
		// lighting chain) or a long gap between presents (the loading screen stalls
		// frames) means the stored history belongs to a completely different image -
		// carrying it over ghosts the old frame into the new one.
		if (effects != lastEffectsActive || packOwnsLook != lastPackOwnsLook
				|| (lastPresentStartNanos != 0
					&& tPresentStart - lastPresentStartNanos > 250_000_000L)) {
			resetPending = true;
		}
		lastEffectsActive = effects;
		lastPackOwnsLook = packOwnsLook;
		lastPresentStartNanos = tPresentStart;
		if (config.effects.hardwareRtShadows && effects) {
			rtBvh.update(mc, Math.max(8, config.effects.rtShadowRadius));
			MetalStats.hardwareShadowPrimitives = rtBvh.primitiveCount();
		}

		if (!upscale && !effects) {
			currentScaleFactor = 1.0f;
			tickFrameTime(); // the dynamic scaler still needs the interval at native scale
			return; // nothing to do - vanilla present runs untouched
		}

		boolean camOk = frames.capture(mc);

		// Defer resource rebuilds while the target size churns (slider drags, window
		// resizes): rebuild once the size has been stable for a few frames or 250ms
		// elapsed, so dragging the scale slider doesn't reallocate everything per frame.
		boolean sizeChanged = inW != scaledW || inH != scaledH;
		if (sizeChanged) {
			if (inW == pendingW && inH == pendingH) {
				pendingStableFrames++;
			} else {
				pendingW = inW;
				pendingH = inH;
				pendingStableFrames = 0;
			}
		}
		boolean rebuildThrottled = sizeChanged && scaledW > 0
			&& pendingStableFrames < 3
			&& System.nanoTime() - lastRebuildNanos < 250_000_000L;
		if (!rebuildThrottled) {
			ensureResources(inW, inH, upscale, effects);
		}
		int[] savedViewport = new int[4];

		// ---- Save GL state (comprehensive: RenderPearl caches GL state and a leak
		// here desyncs its cache, causing missing textures/fonts next frame) ----
		try (MemoryStack state = MemoryStack.stackPush()) {
			java.nio.IntBuffer vp = state.mallocInt(4);
			glGetIntegerv(GL_VIEWPORT, vp);
			savedViewport = new int[] { vp.get(0), vp.get(1), vp.get(2), vp.get(3) };
		}
		int savedActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
		glActiveTexture(GL_TEXTURE0);
		int savedTex2D = glGetInteger(GL_TEXTURE_BINDING_2D);
		int savedTexRect = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
		int savedSampler = glGetInteger(GL_SAMPLER_BINDING);
		int savedDrawFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		int savedReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
		int savedProgram = glGetInteger(GL_CURRENT_PROGRAM);
		int savedVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
		boolean blend = glIsEnabled(GL_BLEND);
		boolean depth = glIsEnabled(GL_DEPTH_TEST);
		boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
		boolean cull = glIsEnabled(GL_CULL_FACE);

		try {
			// ---- 1. Capture color: main target -> IOSurface (GPU-side blit) ----
			LOGGER.debug("MMetal present: capture");
			int colorGlId = textureGlId(main.getColorTexture());
			// Vanilla's textures are GL_TEXTURE_2D objects; our IOSurface-backed ones are
			// GL_TEXTURE_RECTANGLE. Attaching with the wrong target silently no-ops the blit.
			gl.bindFboWithColor(gl.srcFbo(), colorGlId, GL_TEXTURE_2D, inW, inH);
			gl.bindFboWithColor(gl.dstFbo(), inColor.glTexture, GL_TEXTURE_RECTANGLE, scaledW, scaledH);
			gl.blitColor(gl.srcFbo(), inW, inH, scaledW, scaledH);

			// ---- 2. Capture depth (packed 24-bit reversed depth in BGRA8) ----
			// Normally captured at the GUI phase start (before the GUI clears the depth
			// attachment); fall back to capturing here if that hook did not run.
			boolean needDepth = effects || temporal;
			if (needDepth && !depthCapturedThisFrame) {
				captureDepth(main, inW, inH);
			}
			// Make sure the GL queue has COMPLETED the IOSurface writes before Metal
			// reads them. IOSurface does not auto-synchronize GL<->Metal; glFlush only
			// submits, leaving a race that shows up as flickering/black frames.
			glFinish();

			// ---- 3. Metal: upscaling, lighting, motion blur ----
			LOGGER.debug("MMetal present: metal encode");
			tickFrameTime();
			long tPipelineStart = System.nanoTime();
			encodeMetal(upscale, effects, camOk, needDepth, motionBlur);
			MetalStats.pipelineAvgMs = MetalStats.pipelineAvgMs * 0.85
				+ (System.nanoTime() - tPipelineStart) / 1.0e6 * 0.15;

			// ---- 4. Present (we take over the swap) ----
			// Cancel vanilla's present() UNCONDITIONALLY: we always perform the swap
			// ourselves. Missing this in any path causes a second SDL_GL_SwapWindow per
			// frame, which alternates the double-buffered window and flickers.
			ci.cancel();
			presentToWindow(windowHandle, outColor);
			MetalStats.presentMs = (System.nanoTime() - tPresentStart) / 1.0e6;
		} catch (Throwable t) {
			fatal(t);
		} finally {
			// ---- Restore GL state (exact inverse of the save above) ----
			if (blend) {
				glEnable(GL_BLEND);
			} else {
				glDisable(GL_BLEND);
			}
			if (depth) {
				glEnable(GL_DEPTH_TEST);
			} else {
				glDisable(GL_DEPTH_TEST);
			}
			if (scissor) {
				glEnable(GL_SCISSOR_TEST);
			} else {
				glDisable(GL_SCISSOR_TEST);
			}
			if (cull) {
				glEnable(GL_CULL_FACE);
			} else {
				glDisable(GL_CULL_FACE);
			}
			glUseProgram(savedProgram);
			glBindVertexArray(savedVao);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, savedDrawFb);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, savedReadFb);
			glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
			glBindSampler(0, savedSampler);
			glActiveTexture(GL_TEXTURE0);
			glBindTexture(GL_TEXTURE_RECTANGLE, savedTexRect);
			glBindTexture(GL_TEXTURE_2D, savedTex2D);
			glActiveTexture(savedActiveTexture);
		}

		if (camOk) {
			frames.advance();
		}
		resetPending = false;
		depthCapturedThisFrame = false;
	}

	private void presentToWindow(long windowHandle, IosurfacePair source) {
		presentQuad(source.glTexture, source.width, source.height);
		// EXACTLY ONE swap per frame: a second swap would alternate the double buffer and flash.
		SDLVideo.SDL_GL_SwapWindow(windowHandle);
	}

	/** Draws the upscaled frame: IOSurface rectangle texture, opaque. */
	private void presentQuad(int rectTextureId, int texW, int texH) {
		// Make sure GL sees Metal's latest writes to the shared surface (Apple's
		// docs: re-bind the IOSurface-backed texture on the reading context).
		glBindTexture(GL_TEXTURE_RECTANGLE, rectTextureId);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		// The drawable is always the full window; the quad stretches the source texture
		// (which may be a different size if a rebuild is still pending) across it.
		glViewport(0, 0, fullW, fullH);
		glDisable(GL_BLEND);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_SCISSOR_TEST);
		glDisable(GL_CULL_FACE);
		glUseProgram(presentProgram);
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_RECTANGLE, rectTextureId);
		glBindSampler(0, gl.linearSampler());
		glUniform2f(glGetUniformLocation(presentProgram, "uSize"), texW, texH);
		glUniform1i(glGetUniformLocation(presentProgram, "uTex"), 0);
		gl.drawQuad();
		glBindSampler(0, 0);
		glBindTexture(GL_TEXTURE_RECTANGLE, 0);
	}

	// ------------------------------------------------------------------
	// Metal encode
	// ------------------------------------------------------------------

	private void encodeMetal(boolean upscale, boolean effects, boolean camOk, boolean needDepth,
	                         boolean motionBlur) {
		long tEncodeStart = System.nanoTime();
		MemorySegment cmd = MetalNative.newCommandBuffer();
		if (cmd == null || cmd.address() == 0) {
			throw new IllegalStateException("Metal command buffer allocation failed");
		}

		// 0) Unpack the GL-side packed depth into a native R32Float texture.
		if (needDepth) {
			MemorySegment encoder = MetalNative.computeEncoder(cmd);
			MetalNative.setComputePipeline(encoder, library.depthPipeline());
			MetalNative.setTexture(encoder, inDepth.metalTexture, 0);
			MetalNative.setTexture(encoder, depthR32Tex, 1);
			MetalNative.dispatch(encoder, groupCount(scaledW), groupCount(scaledH), 8, 8);
			MetalNative.endEncoder(encoder);
		}

		// 1) Upscale (or copy) into the full-res private working texture.
		// MetalFX objects are bound to the sizes they were created with. During a
		// throttled rebuild (rapid quality/scale switches, window churn) the current
		// sizes can differ for a few frames, and the scaler may have been destroyed
		// while the pipeline ran at native resolution - falling back to a plain copy
		// keeps presenting instead of throwing from a null/mismatched scaler.
		boolean sizesMatch = scaledW == resInW && scaledH == resInH
			&& fullW == resOutW && fullH == resOutH;
		boolean scaled = false;
		if (upscale && sizesMatch) {
			if (config.upscaleMode == MMetalConfig.UpscaleMode.TEMPORAL && temporalScaler != null) {
				// Temporal upscaling consumes motion vectors - generate them first.
				dispatchMotion(cmd, camOk);
				MetalFx.setTemporalState(temporalScaler, depthR32Tex, motionTex,
					0.0f, 0.0f, (float) scaledW / fullW, (float) scaledH / fullH, resetPending);
				MetalFx.configureScaler(temporalScaler, inColor.metalTexture, privateFullTex, scaledW, scaledH);
				MetalFx.encodeScaler(temporalScaler, cmd);
				scaled = true;
			} else if (spatialScaler != null) {
				MetalFx.configureScaler(spatialScaler, inColor.metalTexture, privateFullTex, scaledW, scaledH);
				MetalFx.encodeScaler(spatialScaler, cmd);
				scaled = true;
			}
		}
		if (!scaled) {
			MetalNative.copyTexture(cmd, inColor.metalTexture, privateFullTex, fullW, fullH);
		}

		// 2) Metal-compute ray traced lighting: shadows/AO, reflections, GI, hardware
		//    shadows -> composite.
		MemorySegment image = privateFullTex;
		if (effects) {
			if (config.effects.rayTracing) {
				dispatchSsr(cmd);
			}
			if (config.effects.reflections) {
				dispatchReflections(cmd);
			}
			if (config.effects.globalIllumination) {
				dispatchGi(cmd);
			}
			if (hardwareShadowsActive()) {
				dispatchRtShadows(cmd);
			}
			dispatchComposite(cmd);
			image = postFxTex;
		}

		// 3) Motion blur (blends the image with the previous frame's image).
		if (motionBlur && blurredTex != null) {
			MemorySegment encoder = MetalNative.computeEncoder(cmd);
			MetalNative.setComputePipeline(encoder, library.motionBlurPipeline());
			MetalNative.setTexture(encoder, image, 0);
			MetalNative.setTexture(encoder, prevColorTex, 1);
			MetalNative.setTexture(encoder, blurredTex, 2);
			MemorySegment params = writeEffectParams(motionBlurAmount());
			MetalNative.setBytes(encoder, params, EFFECT_PARAMS_SIZE, 0);
			MetalNative.dispatch(encoder, groupCount(fullW), groupCount(fullH), 8, 8);
			MetalNative.endEncoder(encoder);
			// Keep the un-blurred frame as the next frame's history.
			MetalNative.copyTexture(cmd, image, prevColorTex, fullW, fullH);
			image = blurredTex;
		}

		// 4) Publish to the GL-visible surface.
		MetalNative.copyTexture(cmd, image, outColor.metalTexture, fullW, fullH);

		MetalNative.commit(cmd);
		long tCommit = System.nanoTime();
		// The output is read back through the shared IOSurface by GL, so wait for the GPU.
		MetalNative.waitCompleted(cmd);
		long tDone = System.nanoTime();
		MetalStats.encodeMs = (tCommit - tEncodeStart) / 1.0e6;
		MetalStats.waitMs = (tDone - tCommit) / 1.0e6;
		MetalStats.gpuMs = MetalNative.gpuTimeMs(cmd);
	}

	private void dispatchSsr(MemorySegment cmd) {
		MemorySegment encoder = MetalNative.computeEncoder(cmd);
		MetalNative.setComputePipeline(encoder, library.ssrPipeline());
		MetalNative.setTexture(encoder, depthR32Tex, 0);
		MetalNative.setTexture(encoder, privateFullTex, 1);
		MetalNative.setTexture(encoder, depthR32Tex, 2); // sample view of the same texture
		MetalNative.setTexture(encoder, maskTex, 3);
		MemorySegment params = writeEffectParams(0.0f);
		MetalNative.setBytes(encoder, params, EFFECT_PARAMS_SIZE, 0);
		MetalNative.dispatch(encoder, groupCount(fullW), groupCount(fullH), 8, 8);
		MetalNative.endEncoder(encoder);
	}

	private void dispatchReflections(MemorySegment cmd) {
		MemorySegment encoder = MetalNative.computeEncoder(cmd);
		MetalNative.setComputePipeline(encoder, library.reflectionsPipeline());
		MetalNative.setTexture(encoder, privateFullTex, 0); // sample view
		MetalNative.setTexture(encoder, depthR32Tex, 1);    // sample view
		MetalNative.setTexture(encoder, reflectionTex, 2);
		MemorySegment params = writeEffectParams(0.0f);
		MetalNative.setBytes(encoder, params, EFFECT_PARAMS_SIZE, 0);
		MetalNative.dispatch(encoder, groupCount(fullW), groupCount(fullH), 8, 8);
		MetalNative.endEncoder(encoder);
	}

	private void dispatchGi(MemorySegment cmd) {
		MemorySegment encoder = MetalNative.computeEncoder(cmd);
		MetalNative.setComputePipeline(encoder, library.giPipeline());
		MetalNative.setTexture(encoder, privateFullTex, 0); // sample view
		MetalNative.setTexture(encoder, depthR32Tex, 1);    // sample view
		MetalNative.setTexture(encoder, giTex, 2);
		MemorySegment params = writeEffectParams(0.0f);
		MetalNative.setBytes(encoder, params, EFFECT_PARAMS_SIZE, 0);
		MetalNative.dispatch(encoder, groupCount(fullW), groupCount(fullH), 8, 8);
		MetalNative.endEncoder(encoder);
	}

	/** Hardware ray-traced shadows: traces against the block BVH on the GPU. */
	private void dispatchRtShadows(MemorySegment cmd) {
		MemorySegment encoder = MetalNative.computeEncoder(cmd);
		MetalNative.setComputePipeline(encoder, library.rtShadowPipeline());
		MetalNative.setTexture(encoder, depthR32Tex, 0); // sample view
		MetalNative.setTexture(encoder, rtShadowTex, 1);
		MetalNative.setAccelerationStructure(encoder, rtBvh.accelerationStructure(), 0);
		MemorySegment params = frameArena.allocate(RT_PARAMS_SIZE);
		params.set(ValueLayout.JAVA_FLOAT, RT_VIEWPORT, (float) fullW);
		params.set(ValueLayout.JAVA_FLOAT, RT_VIEWPORT + 4, (float) fullH);
		params.set(ValueLayout.JAVA_FLOAT, RT_NEAR, cameraNear());
		params.set(ValueLayout.JAVA_FLOAT, RT_FAR, frames.curDepthFar);
		Matrix4f invRot = new Matrix4f(frames.viewRotation(false)).transpose();
		putMatrix(params, RT_VIEW_ROT_INV, invRot);
		Vector3f eye = frames.eye(false);
		putFloat4(params, RT_EYE, eye.x, eye.y, eye.z, 0.0f);
		Vector3f sunWorld = sunDirectionWorld().normalize();
		putFloat4(params, RT_SUN_DIR, sunWorld.x, sunWorld.y, sunWorld.z,
			(float) Math.max(16.0, config.effects.rayLength * 16.0));
		params.set(ValueLayout.JAVA_FLOAT, RT_TANHALF, tanHalfFov());
		params.set(ValueLayout.JAVA_FLOAT, RT_ASPECT, (float) fullW / fullH);
		params.set(ValueLayout.JAVA_FLOAT, RT_JITTER,
			(System.nanoTime() % 1_000_000_000L) / 1.0e9f);
		params.set(ValueLayout.JAVA_FLOAT, RT_RAYS, 2.0f);
		MetalNative.setBytes(encoder, params, RT_PARAMS_SIZE, 1);
		MetalNative.dispatch(encoder, groupCount(fullW), groupCount(fullH), 8, 8);
		MetalNative.endEncoder(encoder);
	}

	private void dispatchComposite(MemorySegment cmd) {
		MemorySegment encoder = MetalNative.computeEncoder(cmd);
		MetalNative.setComputePipeline(encoder, library.compositePipeline());
		MetalNative.setTexture(encoder, privateFullTex, 0);
		MetalNative.setTexture(encoder, maskTex, 1);
		MetalNative.setTexture(encoder, reflectionTex, 2); // sample view
		MetalNative.setTexture(encoder, giTex, 3);         // sample view
		MetalNative.setTexture(encoder, rtShadowTex, 4);   // sample view
		MetalNative.setTexture(encoder, postFxTex, 5);
		MemorySegment params = writeEffectParams(0.0f);
		MetalNative.setBytes(encoder, params, EFFECT_PARAMS_SIZE, 0);
		MetalNative.dispatch(encoder, groupCount(fullW), groupCount(fullH), 8, 8);
		MetalNative.endEncoder(encoder);
	}

	private void dispatchMotion(MemorySegment cmd, boolean camOk) {
		MemorySegment encoder = MetalNative.computeEncoder(cmd);
		MetalNative.setComputePipeline(encoder, library.motionPipeline());
		MetalNative.setTexture(encoder, depthR32Tex, 0);
		MetalNative.setTexture(encoder, motionTex, 1);
		MemorySegment params = writeMotionParams(camOk);
		MetalNative.setBytes(encoder, params, MOTION_PARAMS_SIZE, 0);
		MetalNative.dispatch(encoder, groupCount(scaledW), groupCount(scaledH), 8, 8);
		MetalNative.endEncoder(encoder);
	}

	private long groupCount(int dim) {
		return (dim + 7) / 8;
	}

	private MemorySegment writeEffectParams(float motionBlurAmount) {
		MemorySegment buffer = frameArena.allocate(EFFECT_PARAMS_SIZE);
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_VIEWPORT, (float) fullW);
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_VIEWPORT + 4, (float) fullH);
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_NEAR, cameraNear());
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_FAR, frames.curDepthFar);
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_SUN_STRENGTH,
			(float) (config.effects.rayTracing && config.effects.sunShadows ? config.effects.sunStrength : 0.0));
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_AO_STRENGTH,
			(float) (config.effects.rayTracing && config.effects.ambientOcclusion ? config.effects.aoStrength : 0.0));
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_STEPS,
			(float) Math.max(4, Math.min(32, config.effects.marchSteps)));
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_RAY_LENGTH, (float) config.effects.rayLength);
		Vector3f sun = sunDirectionEye();
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_SUN_DIR, sun.x);
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_SUN_DIR + 4, sun.y);
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_SUN_DIR + 8, sun.z);
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_TIME,
			(System.nanoTime() - lastFrameNanos) / 1.0e9f);
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_TANHALF, tanHalfFov());
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_ASPECT, (float) fullW / fullH);
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_MB_AMOUNT, motionBlurAmount);
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_REFL_STRENGTH,
			(float) (config.effects.enabled && config.effects.reflections
				? config.effects.reflectionStrength : 0.0));
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_GI_STRENGTH,
			(float) (config.effects.enabled && config.effects.globalIllumination
				? config.effects.giStrength : 0.0));
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_DENOISE,
			config.effects.denoise ? 1.0f : 0.0f);
		buffer.set(ValueLayout.JAVA_FLOAT, OFF_RT_MIX,
			hardwareShadowsActive() ? 1.0f : 0.0f);
		return buffer;
	}

	private static float displayRefreshHz(Minecraft mc) {
		try {
			if (mc.getWindow() != null && mc.getWindow().getActiveVideoMode() != null) {
				float rate = mc.getWindow().getActiveVideoMode().getRefreshRate();
				if (rate >= 30.0f) {
					return rate;
				}
			}
		} catch (Throwable ignored) {
		}
		return 60.0f;
	}

	/** Stamps the present-to-present interval used by the dynamic scaler and shaders. */
	private void tickFrameTime() {
		long now = System.nanoTime();
		if (lastFrameNanos != 0) {
			deltaTime = (float) Math.min((now - lastFrameNanos) / 1.0e9, 0.25);
		}
		lastFrameNanos = now;
	}

	/**
	 * Render scale for this frame: the preset's fixed scale, or - for the Dynamic preset -
	 * whatever keeps the measured frame interval at the target.
	 */
	private float frameRenderScale(Minecraft mc, boolean uiOpen, long now) {
		if (config.qualityPreset != MMetalConfig.QualityPreset.DYNAMIC) {
			dynamicSeeded = false;
			return (float) config.scale();
		}
		if (config.upscaleMode == MMetalConfig.UpscaleMode.OFF) {
			return 1.0f; // nothing would restore the resolution
		}
		float min = (float) config.dynamicMinScale;
		float max = (float) config.dynamicMaxScale;
		if (!dynamicSeeded) {
			float start = (float) Math.max(min, Math.min(max, MMetalConfig.QualityPreset.DYNAMIC.scale));
			dynamicResolution.reset(start, now);
			dynamicSeeded = true;
		}
		float scale = dynamicResolution.update(deltaTime * 1000.0, targetFrameMs(mc), min, max, now, uiOpen);
		MetalStats.dynamicScale = scale;
		return scale;
	}

	/** Interval the game should hold: display refresh (or the configured fps), capped by Max FPS. */
	private double targetFrameMs(Minecraft mc) {
		double fps = config.dynamicTargetFps > 0 ? config.dynamicTargetFps : displayRefreshHz(mc);
		try {
			Integer limit = mc.options.framerateLimit().get();
			if (limit != null && limit > 0 && limit < 260) { // 260 = "unlimited" in vanilla
				fps = Math.min(fps, limit);
			}
		} catch (Throwable ignored) {
		}
		return 1000.0 / Math.max(15.0, fps);
	}

	/** Camera-motion amount for motion blur (0..1, already scaled by the configured strength). */
	private float motionBlurAmount() {
		if (!config.effects.motionBlur || !frames.hasPrevious || resetPending) {
			return 0.0f;
		}
		float dYaw = Math.abs(wrapDegrees(frames.curYRot - frames.prevYRot));
		float dPitch = Math.abs(frames.curXRot - frames.prevXRot);
		float angle = (float) Math.toRadians(Math.sqrt(dYaw * dYaw + dPitch * dPitch));
		return Math.min(1.0f, angle / 0.35f) * (float) config.effects.motionBlurStrength;
	}

	private static float wrapDegrees(float delta) {
		float d = delta % 360.0f;
		if (d > 180.0f) {
			d -= 360.0f;
		} else if (d < -180.0f) {
			d += 360.0f;
		}
		return d;
	}

	private MemorySegment writeMotionParams(boolean camOk) {
		MemorySegment buffer = frameArena.allocate(MOTION_PARAMS_SIZE);
		putFloat2(buffer, M_OFF_IN_SIZE, scaledW, scaledH);
		putFloat2(buffer, M_OFF_OUT_SIZE, fullW, fullH);
		putMatrix(buffer, M_OFF_CUR_ROT, frames.viewRotation(false));
		putMatrix(buffer, M_OFF_PREV_ROT, frames.viewRotation(true));
		putMatrix(buffer, M_OFF_CUR_VP, frames.viewRotProjection(new Matrix4f(), false));
		putMatrix(buffer, M_OFF_PREV_VP, frames.viewRotProjection(new Matrix4f(), true));
		Vector3f curEye = frames.eye(false);
		Vector3f prevEye = frames.eye(true);
		putFloat4(buffer, M_OFF_CUR_EYE, curEye.x, curEye.y, curEye.z, 0.0f);
		putFloat4(buffer, M_OFF_PREV_EYE, prevEye.x, prevEye.y, prevEye.z, 0.0f);
		buffer.set(ValueLayout.JAVA_FLOAT, M_OFF_NEAR, cameraNear());
		buffer.set(ValueLayout.JAVA_FLOAT, M_OFF_FAR, frames.curDepthFar);
		buffer.set(ValueLayout.JAVA_FLOAT, M_OFF_TANHALF, tanHalfFov());
		buffer.set(ValueLayout.JAVA_FLOAT, M_OFF_ASPECT, (float) fullW / fullH);
		buffer.set(ValueLayout.JAVA_FLOAT, M_OFF_FIRST,
			frames.hasPrevious && camOk ? 0.0f : 1.0f);
		return buffer;
	}

	private static void putFloat2(MemorySegment buffer, int offset, float x, float y) {
		buffer.set(ValueLayout.JAVA_FLOAT, offset, x);
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 4, y);
	}

	private static void putFloat4(MemorySegment buffer, int offset, float x, float y, float z, float w) {
		putFloat2(buffer, offset, x, y);
		putFloat2(buffer, offset + 8, z, w);
	}

	/** Writes a JOML 4x4 matrix in column-major (OpenGL/simd) order. */
	private static void putMatrix(MemorySegment buffer, int offset, Matrix4f m) {
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 0, m.m00());
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 4, m.m01());
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 8, m.m02());
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 12, m.m03());
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 16, m.m10());
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 20, m.m11());
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 24, m.m12());
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 28, m.m13());
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 32, m.m20());
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 36, m.m21());
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 40, m.m22());
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 44, m.m23());
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 48, m.m30());
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 52, m.m31());
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 56, m.m32());
		buffer.set(ValueLayout.JAVA_FLOAT, offset + 60, m.m33());
	}

	/** World-space direction toward the sun (from the world clock, or the config angles). */
	private Vector3f sunDirectionWorld() {
		if (config.effects.sunFromWorldTime) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.level != null) {
				long dayTime = mc.level.getOverworldClockTime();
				float frac = Math.floorMod(dayTime, 24000L) / 24000.0f;
				double angle = frac * Math.PI * 2.0; // 0 = sunrise (east), 0.25 = noon
				return new Vector3f((float) Math.cos(angle), (float) Math.sin(angle), 0.0f);
			}
		}
		double elevation = Math.toRadians(config.effects.sunElevationDeg);
		double azimuth = Math.toRadians(config.effects.sunAzimuthDeg);
		return new Vector3f(
			(float) (Math.cos(azimuth) * Math.cos(elevation)),
			(float) Math.sin(elevation),
			(float) (Math.sin(azimuth) * Math.cos(elevation)));
	}

	private Vector3f sunDirectionEye() {
		Vector3f eye = new Vector3f(sunDirectionWorld());
		frames.viewRotation(false).transformDirection(eye);
		return eye.normalize();
	}

	private boolean hardwareShadowsActive() {
		return config.effects.enabled && config.effects.hardwareRtShadows && rtBvh.isReady();
	}

	private float tanHalfFov() {
		return (float) Math.tan(Math.toRadians(frames.fovDegrees) * 0.5);
	}

	private float cameraNear() {
		try {
			return net.minecraft.client.Camera.PROJECTION_Z_NEAR;
		} catch (Throwable t) {
			return 0.05f;
		}
	}

	// ------------------------------------------------------------------
	// Resources
	// ------------------------------------------------------------------

	private void bootstrap() {
		try {
			if (!SystemInfo.isMac()) {
				LOGGER.warn("MMetal requires macOS; staying inactive.");
				disabled = true;
				return;
			}
			if (!MetalNative.available()) {
				LOGGER.warn("No Metal device available; staying inactive.");
				disabled = true;
				return;
			}
			if (!MetalFx.supportsSpatial(MetalNative.device())) {
				LOGGER.warn("MetalFX spatial scaling is not supported on this Mac; staying inactive.");
				disabled = true;
				return;
			}
			LOGGER.info("Metal device: {}", MetalNative.deviceName());
			MetalStats.deviceName = MetalNative.deviceName();
			LOGGER.info("MetalFX spatial: yes, temporal: {}",
				MetalFx.supportsTemporal(MetalNative.device()));
			library = MetalLibrary.load();
			bootstrapped = true;
			LOGGER.info("MMetal Metal compute library loaded (shadows/AO/reflections/GI/motion kernels ready).");
		} catch (Throwable t) {
			fatal(t);
		}
	}

	private void ensureResources(int inW, int inH, boolean upscale, boolean effects) {
		boolean upscaleModeMatches = !upscale
			|| (config.upscaleMode == MMetalConfig.UpscaleMode.TEMPORAL) == (temporalScaler != null);
		if (scaledW == inW && scaledH == inH && inColor != null && inDepth != null
			&& outColor != null && upscaleModeMatches
			&& presentProgram != 0 && library != null) {
			return;
		}
		destroySizeResources();
		scaledW = inW;
		scaledH = inH;

		long ioUsage = MetalNative.USAGE_SHADER_READ | MetalNative.USAGE_SHADER_WRITE;
		inColor = IosurfacePair.create(inW, inH, IosurfacePair.Kind.COLOR, ioUsage);
		// Depth shares the BGRA8 IOSurface combo (the only widely supported 4-byte one);
		// mm_depth unpacks it into a native R32Float texture for the kernels/MetalFX.
		inDepth = IosurfacePair.create(inW, inH, IosurfacePair.Kind.COLOR, ioUsage);
		outColor = IosurfacePair.create(fullW, fullH, IosurfacePair.Kind.COLOR, ioUsage);
		long rwUsage = MetalNative.USAGE_SHADER_READ | MetalNative.USAGE_SHADER_WRITE;
		privateFullTex = privateTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage);
		postFxTex = privateTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage);
		prevColorTex = privateTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage);
		blurredTex = privateTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage);
		maskTex = privateTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_RGBA8_UNORM, rwUsage);
		reflectionTex = privateTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage);
		giTex = privateTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage);
		rtShadowTex = privateTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage);
		depthR32Tex = privateTexture(inW, inH, MetalNative.PIXEL_FORMAT_R32_FLOAT, rwUsage);
		motionTex = privateTexture(inW, inH, MetalNative.PIXEL_FORMAT_RG16_FLOAT, rwUsage);

		// MetalFX objects are size-bound and must be recreated on resize.
		if (upscale) {
			// The spatial scaler is always created as the baseline upscaler; the
			// temporal scaler is added on top when explicitly requested and supported.
			// Perceptual colour processing: correct for 8-bit SDR surfaces. Scaling in
			// linear space requires 16-bit intermediates - with 8-bit textures the sRGB
			// decode clusters midtones into very few codes and the re-encode produces
			// heavy banding (large angular steps in skies).
			spatialScaler = MetalFx.createSpatial(MetalNative.device(),
				MetalNative.PIXEL_FORMAT_BGRA8_UNORM, MetalNative.PIXEL_FORMAT_BGRA8_UNORM,
				inW, inH, fullW, fullH, MetalFx.COLOR_PROCESSING_PERCEPTUAL);
			if (config.upscaleMode == MMetalConfig.UpscaleMode.TEMPORAL
				&& MetalFx.supportsTemporal(MetalNative.device())) {
				temporalScaler = MetalFx.createTemporal(MetalNative.device(),
					MetalNative.PIXEL_FORMAT_BGRA8_UNORM, MetalNative.PIXEL_FORMAT_R32_FLOAT,
					MetalNative.PIXEL_FORMAT_RG16_FLOAT, MetalNative.PIXEL_FORMAT_BGRA8_UNORM,
					inW, inH, fullW, fullH, true);
			}
		}

		if (presentProgram == 0) {
			presentProgram = gl.program(VERT_SRC, PRESENT_FRAG, "mmetal-present");
			depthProgram = gl.program(VERT_SRC, DEPTH_FRAG, "mmetal-depth");
			if (presentProgram == 0 || depthProgram == 0) {
				throw new IllegalStateException("MMetal GL programs failed to compile");
			}
		}
		LOGGER.info(String.format(Locale.ROOT,
			"MMetal resources ready: %dx%d -> %dx%d (upscale=%s effects=%s)",
			inW, inH, fullW, fullH, upscale, effects));
		resInW = inW;
		resInH = inH;
		resOutW = fullW;
		resOutH = fullH;
		lastRebuildNanos = System.nanoTime();
		MetalStats.renderWidth = inW;
		MetalStats.renderHeight = inH;
		MetalStats.outputWidth = fullW;
		MetalStats.outputHeight = fullH;
	}

	private static MemorySegment privateTexture(int w, int h, long format, long usage) {
		return MetalNative.newTexture(w, h, format, usage, MetalNative.STORAGE_MODE_PRIVATE);
	}

	private void destroySizeResources() {
		closeQuietly(inColor);
		closeQuietly(inDepth);
		closeQuietly(outColor);
		inColor = null;
		inDepth = null;
		outColor = null;
		// Metal objects (MTLTexture/MTLComputePipelineState/...) are ARC-managed and
		// autoreleased - there is no -close/-release we can safely send from Java, so
		// per-size resources intentionally live until the process exits. IOSurfaces
		// (CF objects) are released properly in IosurfacePair.close().
		privateFullTex = null;
		postFxTex = null;
		prevColorTex = null;
		blurredTex = null;
		maskTex = null;
		reflectionTex = null;
		giTex = null;
		rtShadowTex = null;
		depthR32Tex = null;
		motionTex = null;
		spatialScaler = null;
		temporalScaler = null;
		resetPending = true;
	}

	private static void closeQuietly(IosurfacePair pair) {
		if (pair != null) {
			try {
				pair.close();
			} catch (Throwable ignored) {
			}
		}
	}

	private static int textureGlId(com.mojang.renderpearl.api.textures.GpuTexture texture) {
		if (texture instanceof GlTexture glTexture) {
			return glTexture.glId();
		}
		throw new IllegalStateException("Main render target texture is not the OpenGL backend texture");
	}

	private static float clampScale(float scale) {
		return Math.max(0.25f, Math.min(1.0f, scale));
	}
}
