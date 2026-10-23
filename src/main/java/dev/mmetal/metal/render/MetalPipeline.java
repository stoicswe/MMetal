package dev.mmetal.metal.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.mmetal.api.MMetalStyle;
import dev.mmetal.api.MMetalStyles;
import dev.mmetal.metal.config.MMetalConfig;
import dev.mmetal.metal.nativebridge.MetalFx;
import dev.mmetal.metal.nativebridge.MetalNative;
import dev.mmetal.metal.nativebridge.SystemInfo;
import dev.mmetal.metal.ui.MMetalConfigScreen;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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
 *   <li>On Metal: MetalFX upscaling (spatial or temporal), Metal-compute ray-marched
 *       lighting effects, MetalFX motion vectors and Frame Interpolation.</li>
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
		OFF_TIME = 48, OFF_TANHALF = 52, OFF_ASPECT = 56, OFF_SUN_SCREEN = 64,
		OFF_MB_AMOUNT = 80, OFF_EXPOSURE = 84, OFF_TONEMODE = 88, OFF_HEADROOM = 92,
		OFF_REFL_STRENGTH = 96, OFF_GI_STRENGTH = 100, OFF_DENOISE = 104, OFF_BLOOM = 108,
		OFF_HAND_GLOW = 112, OFF_HAND_POS = 128, OFF_RT_MIX = 136, OFF_STYLE = 140,
		OFF_HDR_DARKNESS = 144, OFF_PALETTE = 148, OFF_UI_MASK = 152, EFFECT_PARAMS_SIZE = 156;
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
	private final HdrPresenter hdrPresenter = new HdrPresenter(LOGGER);
	private boolean bootstrapped;
	private boolean disabled;
	private float currentScaleFactor = 1.0f;
	private boolean renderedAnyFrame;
	private boolean uiWasOpen;
	/** Source/bloom textures for the HDR (EDR) tonemap at present time. */
	private MemorySegment hdrSource;
	private MemorySegment hdrBloom;
	/** FPS limit bookkeeping for frame generation pacing. */
	private Integer savedFpsLimit;
	private boolean fiFpsApplied;
	/** Rolling pipeline cost (capture + Metal + sync) used to decide if FG helps. */
	private double avgPipelineMs = 16.0;
	/** True when we are actually double-presenting this frame. */
	private boolean fiDoublePresent;

	// Per-size resources.
	private int scaledW = -1;
	private int scaledH = -1;
	private int fullW = -1;
	private int fullH = -1;
	private IosurfacePair inColor;
	private IosurfacePair inDepth;
	private IosurfacePair outColor;
	private IosurfacePair interpColor;
	private MemorySegment privateFullTex;
	private MemorySegment postFxTex;
	private MemorySegment maskTex;
	/** World-only color captured at GUI start, used to keep the UI out of the HDR boost. */
	private IosurfacePair inWorld;
	private boolean worldCapturedThisFrame;
	private MemorySegment prevColorTex;
	private MemorySegment depthR32Tex;
	private MemorySegment volumetricTex;
	private MemorySegment blurredTex;
	private MemorySegment tonemappedTex;
	private MemorySegment styleTex;
	private MemorySegment interpOutTex;
	private MemorySegment reflectionTex;
	private MemorySegment giTex;
	private MemorySegment bloomTexA;
	private MemorySegment bloomTexB;
	private MemorySegment rtShadowTex;
	private MemorySegment worldLightTex;
	private MemorySegment motionTex;
	private final RtShadowBvh rtBvh = new RtShadowBvh(LOGGER);
	private MemorySegment interpPrivateTex;
	private MemorySegment spatialScaler;
	private MemorySegment temporalScaler;
	private MemorySegment frameInterpolator;
	private boolean depthCapturedThisFrame;
	private final java.util.Map<String, MemorySegment> externalStylePipelines = new java.util.HashMap<>();
	private int presentProgram;
	private int depthProgram;
	private long lastFrameNanos;
	private float deltaTime = 1.0f / 60.0f;
	private boolean resetPending = true;
	private boolean lastEffectsActive;
	private boolean lastPackOwnsLook;
	private long lastPresentStartNanos;
	/** True when this frame is consumed by the HDR tonemap on the same Metal queue. */
	private boolean hdrEncodeFrame;
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
		MetalPipeline p = instance;
		if (p == null || p.disabled || !p.config.enabled || !p.bootstrapped
			|| p.inDepth == null || p.depthProgram == 0 || p.scaledW <= 0) {
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
		if (!p.depthCapturedThisFrame) {
			p.depthCapturedThisFrame = true;
			p.captureDepth(main, main.width, main.height);
		}
		// HDR keeps the world in EDR but must leave the GUI/HUD at SDR appearance: grab
		// the world-only frame before the GUI draws so the tonemap can tell them apart.
		if (!p.worldCapturedThisFrame && p.config.hdrOutput && p.hdrPresenter.isReady()
			&& p.inWorld != null) {
			p.worldCapturedThisFrame = true;
			p.captureWorldColor(main, main.width, main.height);
		}
	}

	/** World-only color: main color attachment -> world IOSurface (same space as inColor). */
	private void captureWorldColor(com.mojang.blaze3d.pipeline.RenderTarget main, int inW, int inH) {
		try {
			int colorGlId = textureGlId(main.getColorTexture());
			gl.bindFboWithColor(gl.srcFbo(), colorGlId, GL_TEXTURE_2D, inW, inH);
			gl.bindFboWithColor(gl.dstFbo(), inWorld.glTexture, GL_TEXTURE_RECTANGLE, scaledW, scaledH);
			gl.blitColor(gl.srcFbo(), inW, inH, scaledW, scaledH);
		} catch (Throwable t) {
			LOGGER.warn("MMetal world color capture failed", t);
		}
	}

	/** GPU-side depth pack: main depth attachment -> packed BGRA8 IOSurface. */
	private void captureDepth(com.mojang.blaze3d.pipeline.RenderTarget main, int inW, int inH) {
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
		// all-zero (haze at max everywhere, effects starved of depth).
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
		// While an Iris shader pack renders and MMetal is configured to STAND_DOWN, the
		// scale must be released, or the game keeps rendering into the scaled target and
		// vanilla's 1:1 blit fills only part of the window (stale borders). In the
		// METALFX/FULL modes the scale stays: the pack renders at reduced resolution
		// and MMetal upscales its output.
		if (dev.mmetal.metal.hook.Compat.irisShadersActive()
				&& p.config.irisMode == MMetalConfig.IrisMode.STAND_DOWN) {
			return 1.0f;
		}
		return p.currentScaleFactor;
	}

	private void fatal(Throwable t) {
		if (!disabled) {
			disabled = true;
			currentScaleFactor = 1.0f;
			// The EDR overlay covers the window; if the pipeline stops presenting while
			// it is up, the visible image freezes even though the game runs underneath.
			hdrPresenter.teardown();
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
				if (stamp.equals(configStamp)) {
					;
				} else {
					configStamp = stamp;
					MMetalConfig reloaded = MMetalConfig.load();
					config.enabled = reloaded.enabled;
					config.qualityPreset = reloaded.qualityPreset;
					config.renderScale = reloaded.renderScale;
					config.upscaleMode = reloaded.upscaleMode;
					config.irisMode = reloaded.irisMode;
					config.palette = reloaded.palette;
					config.frameInterpolation = reloaded.frameInterpolation;
					config.hdrOutput = reloaded.hdrOutput;
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
			if (p != null) {
				// Drop the EDR overlay whenever the pipeline stops presenting: a stale
				// Metal layer would keep covering the GL window with a frozen frame.
				p.hdrPresenter.teardown();
			}
			return;
		}
		try {
			p.checkConfigReload();
			if (!p.config.enabled) {
				p.hdrPresenter.teardown();
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
		MetalStats.pipelineAvgMs = avgPipelineMs;
		MetalStats.fiDoublePresenting = fiDoublePresent;
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
		// Tiered behaviour while an Iris shader pack renders (config.irisMode):
		//  STAND_DOWN - vanilla presents the pack's frame untouched.
		//  METALFX    - the pipeline keeps running over the pack's output, but the pack
		//               owns the look, so only present-time features run (MetalFX
		//               upscale + frame interpolation, no MMetal lighting on top).
		//  FULL       - the whole pipeline (MMetal lighting effects included) runs.
		boolean irisActive = dev.mmetal.metal.hook.Compat.irisShadersActive();
		if (irisActive && config.irisMode == MMetalConfig.IrisMode.STAND_DOWN) {
			return;
		}
		boolean packOwnsLook = irisActive && config.irisMode == MMetalConfig.IrisMode.METALFX;

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

		float scale = clampScale((float) config.scale());
		currentScaleFactor = scale;
		boolean upscale = scale < 0.999f && config.upscaleMode != MMetalConfig.UpscaleMode.OFF;
		boolean effects = config.effects.enabled && !packOwnsLook;
		boolean fi = config.frameInterpolation && SystemInfo.supportsFrameInterpolation();
		boolean motionBlur = effects && config.effects.motionBlur;

		// Frame interpolation and temporal upscaling must never blend across a UI
		// state change (pause menu opening/closing, etc.) - reset history and
		// present real frames while a screen is open.
		boolean uiOpen = Minecraft.getInstance() != null && Minecraft.getInstance().gui != null
			&& Minecraft.getInstance().gui.screen() != null;
		if (uiOpen != uiWasOpen) {
			uiWasOpen = uiOpen;
			resetPending = true;
		}
		// A pipeline-mode boundary (a shader pack activating/deactivating switches the
		// frame source and toggles the lighting chain) or a long gap between presents
		// (the loading screen stalls frames) means the stored history belongs to a
		// completely different image - carrying it over ghosts the old frame into the
		// new one (e.g. the MOJANG splash bleeding into the title screen).
		if (effects != lastEffectsActive || packOwnsLook != lastPackOwnsLook
				|| (lastPresentStartNanos != 0
					&& tPresentStart - lastPresentStartNanos > 250_000_000L)) {
			resetPending = true;
		}
		lastEffectsActive = effects;
		lastPackOwnsLook = packOwnsLook;
		lastPresentStartNanos = tPresentStart;
		boolean frameGenSafe = fi && !uiOpen;

		// Frame generation only helps when the game cannot reach the display refresh by
		// itself. Gate on the MEASURED frame interval (not the pipeline cost: the SDR
		// path's GPU wait inflates that, which engaged FI at full refresh rate, halved
		// the frame rate and ghosted). Once doubling, the interval is ~2x vsync, so this
		// stays engaged (hysteresis); at native rate it stays idle.
		float refreshHz = displayRefreshHz(mc);
		double vsyncMs = 1000.0 / Math.max(30.0, refreshHz);
		float frameMs = deltaTime * 1000.0f;
		boolean fiUseful = frameMs > vsyncMs * 1.1;
		fiDoublePresent = frameGenSafe && fiUseful;
		updateFrameGenerationPacing(mc, fiDoublePresent);
		applySwapInterval(mc, fiDoublePresent);
		HeldLight.update(mc);
		if (config.effects.hardwareRtShadows && effects) {
			rtBvh.update(mc, Math.max(8, config.effects.rtShadowRadius));
			MetalStats.hardwareShadowPrimitives = rtBvh.primitiveCount();
		}

		if (!upscale && !effects && !fi) {
			currentScaleFactor = 1.0f;
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
			ensureResources(inW, inH, upscale, effects, fi);
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
			if ((effects || fi || motionBlur || styleActive()) && !depthCapturedThisFrame) {
				captureDepth(main, inW, inH);
			}
			// Make sure the GL queue has COMPLETED the IOSurface writes before Metal
			// reads them. IOSurface does not auto-synchronize GL<->Metal; glFlush only
			// submits, leaving a race that shows up as flickering/black frames.
			glFinish();

			// ---- 3. Metal: upscaling, effects, frame interpolation ----
			LOGGER.debug("MMetal present: metal encode");
			long now = System.nanoTime();
			if (lastFrameNanos != 0) {
				deltaTime = (float) Math.min((now - lastFrameNanos) / 1.0e9, 0.25);
			}
			lastFrameNanos = now;
			boolean needDepth = effects || fi || motionBlur || styleActive();
			long tPipelineStart = System.nanoTime();
			// HDR consumes the encoded frame on this same Metal queue (tonemap -> drawable),
			// so the SDR-only CPU wait below is not needed - and a stalled GPU command
			// buffer must never freeze the render thread.
			hdrEncodeFrame = config.hdrOutput && hdrPresenter.isReady();
			boolean haveInterp = encodeMetal(upscale, effects, fiDoublePresent, camOk, needDepth, motionBlur,
				!uiOpen, packOwnsLook);
			avgPipelineMs = avgPipelineMs * 0.85
				+ (System.nanoTime() - tPipelineStart) / 1.0e6 * 0.15;

			// ---- 4. Present (we take over the swap) ----
			// Cancel vanilla's present() UNCONDITIONALLY: we always perform the swap
			// ourselves. Missing this in any path causes a second SDL_GL_SwapWindow per
			// frame, which alternates the double-buffered window and flickers.
			ci.cancel();
			// HDR is independent of the effects chain and of Iris (the pack's output is
			// tonemapped into EDR the same way). initialize() is idempotent and must run
			// before isReady() can ever become true.
			boolean hdrReady = config.hdrOutput && hdrPresenter.initialize(windowHandle)
				&& hdrPresenter.isReady();
			if (hdrReady) {
				// EDR path: tonemap straight into Metal drawables and present them.
				MetalStats.edrHeadroom = hdrPresenter.headroom();
				if (fiDoublePresent && haveInterp) {
					// Interpolated frame first, then the real frame - the same temporal
					// order as the SDR path. Without this the HDR path shows only the real
					// frame, so FI's half-rate pacing judders and the interpolated frames
					// never reach the display.
					presentHdrFrame(interpPrivateTex);
				}
				presentHdrFrame(hdrSource != null ? hdrSource : postFxTex);
			} else {
				if (!config.hdrOutput) {
					// Toggling HDR off mid-session: drop the EDR overlay so the GL swap
					// underneath becomes visible again.
					hdrPresenter.teardown();
				}
				presentToWindow(windowHandle, haveInterp ? interpColor : outColor);
				if (fiDoublePresent && haveInterp) {
					// Interpolated frame first, then the real frame - correct temporal order.
					presentToWindow(windowHandle, outColor);
				}
			}
			renderedAnyFrame = true;
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
		worldCapturedThisFrame = false;
	}

	/**
	 * Tonemaps one pre-display texture straight into an EDR drawable and presents it.
	 * Used for both the real and the interpolated frame so the HDR path honours frame
	 * interpolation exactly like the SDR path.
	 */
	private void presentHdrFrame(MemorySegment source) {
		if (source == null || source.address() == 0) {
			return;
		}
		MemorySegment drawableTex = hdrPresenter.acquireDrawable(fullW, fullH);
		if (drawableTex == null || drawableTex.address() == 0) {
			MetalStats.skippedDrawables++;
			return;
		}
		MemorySegment cmd = MetalNative.newCommandBuffer();
		MemorySegment encoder = MetalNative.computeEncoder(cmd);
		MetalNative.setComputePipeline(encoder, library.tonemapPipeline());
		MetalNative.setTexture(encoder, source, 0);
		MetalNative.setTexture(encoder, hdrBloom != null ? hdrBloom : bloomTexA, 1);
		MetalNative.setTexture(encoder, drawableTex, 2);
		if (inWorld != null) {
			MetalNative.setTexture(encoder, inWorld.metalTexture, 3);
		}
		MemorySegment params = writeEffectParams(0.0f);
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_TONEMODE, 2.0f);
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_HEADROOM, hdrPresenter.headroom());
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_EXPOSURE,
			(float) (config.effects.exposure * config.hdrBrightness));
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_HDR_DARKNESS,
			(float) config.hdrDarkness);
		// Only add bloom when this frame actually produced a bloom texture.
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_BLOOM,
			hdrBloom != null ? (float) config.effects.bloomStrength : 0.0f);
		// Keep GUI/HUD pixels at SDR appearance (world-only capture vs full frame).
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_UI_MASK,
			inWorld != null && worldCapturedThisFrame ? 1.0f : 0.0f);
		MetalNative.setBytes(encoder, params, EFFECT_PARAMS_SIZE, 0);
		MetalNative.dispatch(encoder, groupCount(fullW), groupCount(fullH), 8, 8);
		MetalNative.endEncoder(encoder);
		// No blocking wait: the drawable presentation is scheduled by Core Animation and
		// the next frame's encode is ordered after this one on the queue. Waiting here
		// bought nothing but a render-thread stall (and could freeze the game if the GPU
		// command buffer never completed).
		MetalNative.commit(cmd);
		hdrPresenter.presentDrawable();
	}

	private void presentToWindow(long windowHandle, IosurfacePair source) {
		presentQuad(windowHandle, source.glTexture, source.width, source.height);
		// EXACTLY ONE swap per frame: the swap lives here, after every layer is drawn.
		// (A swap inside presentQuad would alternate the double buffer and flash.)
		SDLVideo.SDL_GL_SwapWindow(windowHandle);
	}

	/** Draws the upscaled world layer: IOSurface rectangle texture, opaque. */
	private void presentQuad(long windowHandle, int rectTextureId, int texW, int texH) {
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

	private boolean encodeMetal(boolean upscale, boolean effects, boolean fi, boolean camOk, boolean needDepth,
	                            boolean motionBlur, boolean depthValid, boolean packOwnsLook) {
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

		// 2) Metal-compute lighting effects: RT shadows/AO -> volumetric -> composite.
		if (effects) {
			if (config.effects.rayTracing) {
				dispatchSsr(cmd);
			}
			if (config.effects.volumetricLight) {
				dispatchVolumetric(cmd);
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
			if (heldLightActive()) {
				dispatchWorldLight(cmd);
			}
			dispatchComposite(cmd);
		} else {
			MetalNative.copyTexture(cmd, privateFullTex, postFxTex, fullW, fullH);
		}

		// 3) Motion blur (blends the final image with the previous final image).
		float mbAmount = motionBlurAmount();
		MemorySegment finalPre = postFxTex;
		if (motionBlur && blurredTex != null) {
			MemorySegment encoder = MetalNative.computeEncoder(cmd);
			MetalNative.setComputePipeline(encoder, library.motionBlurPipeline());
			MetalNative.setTexture(encoder, postFxTex, 0);
			MetalNative.setTexture(encoder, prevColorTex, 1);
			MetalNative.setTexture(encoder, blurredTex, 2);
			MemorySegment params = writeEffectParams(mbAmount);
			MetalNative.setBytes(encoder, params, EFFECT_PARAMS_SIZE, 0);
			MetalNative.dispatch(encoder, groupCount(fullW), groupCount(fullH), 8, 8);
			MetalNative.endEncoder(encoder);
			finalPre = blurredTex;
		}

		// 4) Motion vectors + MetalFX frame interpolation (pre-tonemap, consistent
		//    encoding between the current and previous frames).
		boolean haveInterp = false;
		boolean fiUsable = fi && camOk && frames.hasPrevious && frameInterpolator != null
			&& sizesMatch && prevColorTex != null && depthR32Tex != null
			&& motionTex != null && interpPrivateTex != null;
		if (fiUsable) {
			dispatchMotion(cmd, camOk);
			MetalFx.configureInterpolator(frameInterpolator,
				finalPre, prevColorTex, depthR32Tex, motionTex, interpPrivateTex,
				deltaTime, cameraNear(), frames.curDepthFar, frames.fovDegrees,
				(float) fullW / fullH, resetPending);
			MetalFx.encodeInterpolator(frameInterpolator, cmd);
			haveInterp = true;
		}

		// 5) Emissive bloom (torches, lava, glowstone, sun) - feeds the tonemap.
		MemorySegment bloom = null;
		if (effects && config.effects.bloom && bloomTexA != null) {
			bloom = dispatchBloom(cmd);
		}

		// 6) Tonemap / display transform + publish to the GL-visible surfaces.
		//    Both the real frame and (when frame generation is on) the interpolated
		//    frame are tonemapped, otherwise the interpolated presentation would be
		//    a stale/empty surface (the old flashing bug).
		boolean hdrActive = config.hdrOutput && hdrPresenter.isReady();
		hdrSource = finalPre;
		hdrBloom = bloom;
		if (!hdrActive) {
			boolean style = styleActive() && styleTex != null;
			// Under a shader pack (METALFX tier) the pack owns the display transform:
			// its output is already graded, so MMetal's tonemap/bloom must not run on
			// top of it. Besides double-grading, the bloom texture would still hold
			// whatever last ran the bloom pass (the loading screen), smearing it into
			// every later frame.
			if (!packOwnsLook && config.effects.tonemap && tonemappedTex != null) {
				dispatchTonemap(cmd, finalPre, bloom, tonemappedTex);
				MemorySegment realOut = tonemappedTex;
				if (style && dispatchLook(cmd, tonemappedTex, styleTex, depthValid)) {
					realOut = styleTex;
				}
				MetalNative.copyTexture(cmd, realOut, outColor.metalTexture, fullW, fullH);
				if (haveInterp) {
					dispatchTonemap(cmd, interpPrivateTex, bloom, interpOutTex);
					MemorySegment interpOut = interpOutTex;
					if (style && dispatchLook(cmd, interpOutTex, styleTex, depthValid)) {
						interpOut = styleTex;
					}
					MetalNative.copyTexture(cmd, interpOut, interpColor.metalTexture, fullW, fullH);
				}
			} else {
				MemorySegment realOut = finalPre;
				if (style && dispatchLook(cmd, finalPre, styleTex, depthValid)) {
					realOut = styleTex;
				}
				MetalNative.copyTexture(cmd, realOut, outColor.metalTexture, fullW, fullH);
				if (haveInterp) {
					MemorySegment interpOut = interpPrivateTex;
					if (style && dispatchLook(cmd, interpPrivateTex, styleTex, depthValid)) {
						interpOut = styleTex;
					}
					MetalNative.copyTexture(cmd, interpOut, interpColor.metalTexture, fullW, fullH);
				}
			}
		}

		// 7) Keep the pre-tonemap frame for frame interpolation + the next motion blur.
		if (fi || motionBlur) {
			MetalNative.copyTexture(cmd, postFxTex, prevColorTex, fullW, fullH);
		}

		MetalNative.commit(cmd);
		long tCommit = System.nanoTime();
		// When HDR is active the frame is consumed on this same Metal queue (tonemap ->
		// drawable), so no CPU-side wait is required - and a stuck GPU command buffer
		// must never freeze the render thread. The SDR path still waits: its output is
		// read back through the shared IOSurface by GL.
		if (!hdrEncodeFrame) {
			MetalNative.waitCompleted(cmd);
		}
		long tDone = System.nanoTime();
		MetalStats.encodeMs = (tCommit - tEncodeStart) / 1.0e6;
		MetalStats.waitMs = (tDone - tCommit) / 1.0e6;
		MetalStats.gpuMs = hdrEncodeFrame ? 0.0 : MetalNative.gpuTimeMs(cmd);
		return haveInterp;
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

	private void dispatchVolumetric(MemorySegment cmd) {
		MemorySegment encoder = MetalNative.computeEncoder(cmd);
		MetalNative.setComputePipeline(encoder, library.volumetricPipeline());
		MetalNative.setTexture(encoder, privateFullTex, 0); // sample view
		MetalNative.setTexture(encoder, depthR32Tex, 1);    // sample view
		MetalNative.setTexture(encoder, volumetricTex, 2);
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

	/** Emissive bloom: bright-pass, then a separable gaussian (H then V). Returns the final bloom texture. */
	private MemorySegment dispatchBloom(MemorySegment cmd) {
		MemorySegment params = writeEffectParams(0.0f);
		// bright pass: postFx -> bloomA
		MemorySegment encoder = MetalNative.computeEncoder(cmd);
		MetalNative.setComputePipeline(encoder, library.bloomExtractPipeline());
		MetalNative.setTexture(encoder, postFxTex, 0);
		MetalNative.setTexture(encoder, bloomTexA, 1);
		MetalNative.setBytes(encoder, params, EFFECT_PARAMS_SIZE, 0);
		MetalNative.dispatch(encoder, groupCount(fullW), groupCount(fullH), 8, 8);
		MetalNative.endEncoder(encoder);

		// horizontal: bloomA -> bloomB
		encoder = MetalNative.computeEncoder(cmd);
		MemorySegment dir = frameArena.allocate(8);
		dir.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 0, 1.0f);
		dir.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 4, 0.0f);
		MetalNative.setComputePipeline(encoder, library.bloomBlurPipeline());
		MetalNative.setTexture(encoder, bloomTexA, 0);
		MetalNative.setTexture(encoder, bloomTexA, 1); // sample view
		MetalNative.setTexture(encoder, bloomTexB, 2);
		MetalNative.setBytes(encoder, params, EFFECT_PARAMS_SIZE, 0);
		MetalNative.setBytes(encoder, dir, 8, 1);
		MetalNative.dispatch(encoder, groupCount(fullW), groupCount(fullH), 8, 8);
		MetalNative.endEncoder(encoder);

		// vertical: bloomB -> bloomA
		encoder = MetalNative.computeEncoder(cmd);
		dir = frameArena.allocate(8);
		dir.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 0, 0.0f);
		dir.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 4, 1.0f);
		MetalNative.setComputePipeline(encoder, library.bloomBlurPipeline());
		MetalNative.setTexture(encoder, bloomTexB, 0);
		MetalNative.setTexture(encoder, bloomTexB, 1); // sample view
		MetalNative.setTexture(encoder, bloomTexA, 2);
		MetalNative.setBytes(encoder, params, EFFECT_PARAMS_SIZE, 0);
		MetalNative.setBytes(encoder, dir, 8, 1);
		MetalNative.dispatch(encoder, groupCount(fullW), groupCount(fullH), 8, 8);
		MetalNative.endEncoder(encoder);
		return bloomTexA;
	}

	/** Tonemap into a destination texture, adding bloom. */
	private void dispatchTonemap(MemorySegment cmd, MemorySegment source, MemorySegment bloom,
	                             MemorySegment destination) {
		MemorySegment encoder = MetalNative.computeEncoder(cmd);
		MetalNative.setComputePipeline(encoder, library.tonemapPipeline());
		MetalNative.setTexture(encoder, source, 0);
		MetalNative.setTexture(encoder, bloom != null ? bloom : bloomTexA, 1); // sample view
		MetalNative.setTexture(encoder, destination, 2);
		MemorySegment params = writeEffectParams(0.0f);
		MetalNative.setBytes(encoder, params, EFFECT_PARAMS_SIZE, 0);
		MetalNative.dispatch(encoder, groupCount(fullW), groupCount(fullH), 8, 8);
		MetalNative.endEncoder(encoder);
	}

	/** True when a registered look (not "none") is selected with a non-zero strength. */
	private boolean styleActive() {
		return config.styleStrength > 0.01 && MMetalStyles.isActive(config.styleId);
	}

	private MMetalStyle currentStyle() {
		return MMetalStyles.get(config.styleId);
	}

	/**
	 * Applies the selected look to an LDR image after the display transform. Built-in
	 * styles use MMetal's {@code mm_style} kernel; contributed styles run their own
	 * kernel from a metallib (see {@code dev.mmetal.api.MMetalStyle}).
	 */
	private boolean dispatchLook(MemorySegment cmd, MemorySegment source, MemorySegment destination,
	                             boolean depthValid) {
		MMetalStyle style = currentStyle();
		MemorySegment pipeline = externalStylePipelines.get(style.id());
		if (pipeline == null) {
			try {
				pipeline = style.metallibPath() != null
					? MetalLibrary.loadKernel(style.metallibPath(), style.kernelName())
					: MetalLibrary.loadKernelFromResource(style.metallibResource(), style.kernelName());
				LOGGER.info("MMetal external style loaded: {} (kernel {})", style.id(), style.kernelName());
			} catch (Throwable t) {
				LOGGER.warn("MMetal style '{}' failed to load - style disabled for this session",
					style.id(), t);
				pipeline = MemorySegment.NULL;
			}
			externalStylePipelines.put(style.id(), pipeline);
		}
		if (pipeline == null || pipeline.address() == 0) {
			return false;
		}
		MemorySegment encoder = MetalNative.computeEncoder(cmd);
		MetalNative.setComputePipeline(encoder, pipeline);
		MetalNative.setTexture(encoder, source, 0);
		MetalNative.setTexture(encoder, depthR32Tex != null ? depthR32Tex : source, 1);
		MetalNative.setTexture(encoder, destination, 2);
		MetalNative.setTexture(encoder, bloomTexA, 3);
		MemorySegment params = writeEffectParams(0.0f);
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_HEADROOM,
			(float) Math.max(0.0, Math.min(1.0, config.styleStrength)));
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_MB_AMOUNT, depthValid ? 1.0f : 0.0f);
		MetalNative.setBytes(encoder, params, EFFECT_PARAMS_SIZE, 0);
		int customCount = Math.max(0, Math.min(64, style.customFloatCount()));
		if (customCount > 0) {
			MemorySegment custom = frameArena.allocate(customCount * 4L);
			float[] values = new float[customCount];
			try {
				style.writeCustomParams(values);
			} catch (Throwable t) {
				LOGGER.warn("MMetal style '{}' custom params failed", style.id(), t);
			}
			for (int i = 0; i < customCount; i++) {
				custom.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, i * 4L, values[i]);
			}
			MetalNative.setBytes(encoder, custom, customCount * 4L, 1);
		}
		MetalNative.dispatch(encoder, groupCount(fullW), groupCount(fullH), 8, 8);
		MetalNative.endEncoder(encoder);
		return true;
	}

	private boolean heldLightActive() {
		return config.effects.enabled && config.effects.heldLightGlow && HeldLight.strength > 0.0;
	}

	/** Held light source: depth-reconstructed world-space illumination. */
	private void dispatchWorldLight(MemorySegment cmd) {
		MemorySegment encoder = MetalNative.computeEncoder(cmd);
		MetalNative.setComputePipeline(encoder, library.worldLightPipeline());
		MetalNative.setTexture(encoder, depthR32Tex, 0); // sample view
		MetalNative.setTexture(encoder, worldLightTex, 1);
		MemorySegment params = frameArena.allocate(144);
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 0, (float) fullW);
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 4, (float) fullH);
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 8, cameraNear());
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 12, frames.curDepthFar);
		Matrix4f invRot = new Matrix4f(frames.viewRotation(false)).transpose();
		putMatrix(params, 16, invRot);
		Vector3f eye = frames.eye(false);
		putFloat4(params, 80, eye.x, eye.y, eye.z, 0.0f);
		// The emitter's real world position (computed in HeldLight from the hand).
		float radius = 12.0f;
		putFloat4(params, 96, (float) HeldLight.lightX, (float) HeldLight.lightY,
			(float) HeldLight.lightZ, radius);
		float flicker = config.effects.heldLightFlicker ? HeldLight.flicker : 1.0f;
		float intensity = (float) (HeldLight.strength * config.effects.heldLightStrength * 1.7 * flicker);
		putFloat4(params, 112, HeldLight.red, HeldLight.green, HeldLight.blue, intensity);
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 128, tanHalfFov());
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 132, (float) fullW / fullH);
		MetalNative.setBytes(encoder, params, 144, 0);
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
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, RT_VIEWPORT, (float) fullW);
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, RT_VIEWPORT + 4, (float) fullH);
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, RT_NEAR, cameraNear());
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, RT_FAR, frames.curDepthFar);
		Matrix4f invRot = new Matrix4f(frames.viewRotation(false)).transpose();
		putMatrix(params, RT_VIEW_ROT_INV, invRot);
		Vector3f eye = frames.eye(false);
		putFloat4(params, RT_EYE, eye.x, eye.y, eye.z, 0.0f);
		Vector3f sunWorld = sunDirectionWorld().normalize();
		putFloat4(params, RT_SUN_DIR, sunWorld.x, sunWorld.y, sunWorld.z,
			(float) Math.max(16.0, config.effects.rayLength * 16.0));
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, RT_TANHALF, tanHalfFov());
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, RT_ASPECT, (float) fullW / fullH);
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, RT_JITTER,
			(System.nanoTime() % 1_000_000_000L) / 1.0e9f);
		params.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, RT_RAYS, 2.0f);
		MetalNative.setBytes(encoder, params, RT_PARAMS_SIZE, 1);
		MetalNative.dispatch(encoder, groupCount(fullW), groupCount(fullH), 8, 8);
		MetalNative.endEncoder(encoder);
	}

	private void dispatchComposite(MemorySegment cmd) {
		MemorySegment encoder = MetalNative.computeEncoder(cmd);
		MetalNative.setComputePipeline(encoder, library.compositePipeline());
		MetalNative.setTexture(encoder, privateFullTex, 0);
		MetalNative.setTexture(encoder, maskTex, 1);
		MetalNative.setTexture(encoder, volumetricTex, 2); // sample view
		MetalNative.setTexture(encoder, reflectionTex, 3); // sample view
		MetalNative.setTexture(encoder, giTex, 4);         // sample view
		MetalNative.setTexture(encoder, rtShadowTex, 5);   // sample view
		MetalNative.setTexture(encoder, worldLightTex, 6); // sample view
		MetalNative.setTexture(encoder, postFxTex, 7);
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
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_VIEWPORT, (float) fullW);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_VIEWPORT + 4, (float) fullH);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_NEAR, cameraNear());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_FAR, frames.curDepthFar);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_SUN_STRENGTH,
			(float) (config.effects.rayTracing && config.effects.sunShadows ? config.effects.sunStrength : 0.0));
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_AO_STRENGTH,
			(float) (config.effects.rayTracing && config.effects.ambientOcclusion ? config.effects.aoStrength : 0.0));
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_STEPS,
			(float) Math.max(4, Math.min(32, config.effects.marchSteps)));
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_RAY_LENGTH, (float) config.effects.rayLength);
		Vector3f sun = sunDirectionEye();
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_SUN_DIR, sun.x);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_SUN_DIR + 4, sun.y);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_SUN_DIR + 8, sun.z);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_TIME,
			(System.nanoTime() - lastFrameNanos) / 1.0e9f);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_TANHALF, tanHalfFov());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_ASPECT, (float) fullW / fullH);
		Vector4f sunScreen = sunScreenUv();
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_SUN_SCREEN, sunScreen.x);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_SUN_SCREEN + 4, sunScreen.y);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_SUN_SCREEN + 8, sunScreen.z);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_SUN_SCREEN + 12, sunScreen.w);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_MB_AMOUNT, motionBlurAmount);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_EXPOSURE,
			(float) config.effects.exposure);
		// Palette: the display transform (tonemap mode) plus the optional grade.
		// The HDR present path overrides the mode to 2 (EDR drawable) but keeps both.
		float tonemapMode = switch (config.palette) {
			case FILMIC -> config.effects.tonemap ? 1.0f : 0.0f;
			case EXTENDED -> 3.0f;
			case VIBRANT, WARM, COOL, NATURAL, NEUTRAL -> 0.0f;
		};
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_TONEMODE, tonemapMode);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_HEADROOM, 1.0f);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_HDR_DARKNESS,
			(float) config.hdrDarkness);
		// Grade id for the kernel: 0 none, 1 vibrant, 2 warm, 3 cool, 4 natural.
		float paletteGrade = switch (config.palette) {
			case VIBRANT -> 1.0f;
			case WARM -> 2.0f;
			case COOL -> 3.0f;
			case NATURAL -> 4.0f;
			case FILMIC, EXTENDED, NEUTRAL -> 0.0f;
		};
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_PALETTE, paletteGrade);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_UI_MASK, 0.0f);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_REFL_STRENGTH,
			(float) (config.effects.enabled && config.effects.reflections
				? config.effects.reflectionStrength : 0.0));
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_GI_STRENGTH,
			(float) (config.effects.enabled && config.effects.globalIllumination
				? config.effects.giStrength : 0.0));
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_DENOISE,
			config.effects.denoise ? 1.0f : 0.0f);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_BLOOM,
			(float) (config.effects.enabled && config.effects.bloom
				? config.effects.bloomStrength : 0.0));
		// Only a subtle halo around the emitter itself - the real illumination comes
		// from mm_world_light (depth-reconstructed world space).
		float heldStrength = (float) (config.effects.enabled && config.effects.heldLightGlow
			? HeldLight.strength * config.effects.heldLightStrength * 0.25 : 0.0);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_HAND_GLOW, HeldLight.red);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_HAND_GLOW + 4, HeldLight.green);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_HAND_GLOW + 8, HeldLight.blue);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_HAND_GLOW + 12, heldStrength);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_HAND_POS, HeldLight.posX);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_HAND_POS + 4, HeldLight.posY);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, OFF_RT_MIX,
			hardwareShadowsActive() ? 1.0f : 0.0f);
		return buffer;
	}

	/** Projects the sun direction to screen UV: (u, v, onScreen, strength). */
	private Vector4f sunScreenUv() {
		Vector3f sunEye = sunDirectionEye();
		float far = Math.max(frames.curDepthFar, 16.0f);
		Vector4f viewPos = new Vector4f(sunEye.x * far, sunEye.y * far, sunEye.z * far, 1.0f);
		frames.curProjection.transform(viewPos);
		if (viewPos.w <= 0.001f) {
			return new Vector4f(0, 0, 0, 0);
		}
		float u = viewPos.x / viewPos.w * 0.5f + 0.5f;
		float v = 0.5f - viewPos.y / viewPos.w * 0.5f;
		boolean onScreen = u >= -0.1f && u <= 1.1f && v >= -0.1f && v <= 1.1f;
		// Fade shafts out as the sun approaches/below the horizon.
		float dayFade = (float) Math.min(1.0, Math.max(0.0, sunEye.y * 3.0));
		return new Vector4f(u, v, onScreen ? 1.0f : 0.0f,
			(float) (config.effects.volumetricLight
				? config.effects.volumetricStrength * dayFade : 0.0));
	}

	/**
	 * Frame generation only produces more frames than the game renders when the game
	 * runs at (roughly) half the display refresh rate. Cap Max FPS accordingly while
	 * FI is enabled and restore the player's setting afterwards.
	 */
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

	/** Frame generation needs vsync-paced swaps; otherwise both frames land in one refresh. */
	private static void applySwapInterval(Minecraft mc, boolean doublePresent) {
		try {
			boolean vsync;
			if (doublePresent) {
				vsync = true; // two presents must be spread across two refresh intervals
			} else {
				Boolean option = mc.options.enableVsync().get();
				vsync = option != null && option;
			}
			org.lwjgl.sdl.SDLVideo.SDL_GL_SetSwapInterval(vsync ? 1 : 0);
		} catch (Throwable ignored) {
		}
	}

	private void updateFrameGenerationPacing(Minecraft mc, boolean fiEnabled) {
		try {
			if (fiEnabled && !fiFpsApplied) {
				float refresh = displayRefreshHz(mc);
				int target = Math.max(30, Math.round(refresh / 2.0f));
				Integer current = mc.options.framerateLimit().get();
				if (current == null || current > target) {
					savedFpsLimit = current;
					mc.options.framerateLimit().set(target);
					LOGGER.info("MMetal frame generation: Max FPS capped to {} (display {} Hz)", target, Math.round(refresh));
				}
				fiFpsApplied = true;
			} else if (!fiEnabled && fiFpsApplied) {
				if (savedFpsLimit != null) {
					mc.options.framerateLimit().set(savedFpsLimit);
				}
				fiFpsApplied = false;
				savedFpsLimit = null;
			}
		} catch (Throwable t) {
			fiFpsApplied = true; // never retry-loop
		}
	}

	/** Camera-motion amount for motion blur (0..1, already scaled by the configured strength). */
	private float motionBlurAmount() {
		if (!config.effects.motionBlur || !frames.hasPrevious) {
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
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, M_OFF_NEAR, cameraNear());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, M_OFF_FAR, frames.curDepthFar);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, M_OFF_TANHALF, tanHalfFov());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, M_OFF_ASPECT, (float) fullW / fullH);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, M_OFF_FIRST,
			frames.hasPrevious && camOk ? 0.0f : 1.0f);
		return buffer;
	}

	private static void putFloat2(MemorySegment buffer, int offset, float x, float y) {
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset, x);
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 4, y);
	}

	private static void putFloat4(MemorySegment buffer, int offset, float x, float y, float z, float w) {
		putFloat2(buffer, offset, x, y);
		putFloat2(buffer, offset + 8, z, w);
	}

	/** Writes a JOML 4x4 matrix in column-major (OpenGL/simd) order. */
	private static void putMatrix(MemorySegment buffer, int offset, Matrix4f m) {
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 0, m.m00());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 4, m.m01());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 8, m.m02());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 12, m.m03());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 16, m.m10());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 20, m.m11());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 24, m.m12());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 28, m.m13());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 32, m.m20());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 36, m.m21());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 40, m.m22());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 44, m.m23());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 48, m.m30());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 52, m.m31());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 56, m.m32());
		buffer.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, offset + 60, m.m33());
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
			LOGGER.info("MetalFX spatial: yes, temporal: {}, frame interpolation: {}",
				MetalFx.supportsTemporal(MetalNative.device()),
				SystemInfo.supportsFrameInterpolation()
					&& MetalFx.supportsFrameInterpolation(MetalNative.device()));
			library = MetalLibrary.load();
			bootstrapped = true;
			LOGGER.info("MMetal Metal compute library loaded (shadows/AO/motion kernels ready).");
		} catch (Throwable t) {
			fatal(t);
		}
	}

	private void ensureResources(int inW, int inH, boolean upscale, boolean effects, boolean fi) {
		boolean interpolatorStateMatches = fi == (frameInterpolator != null);
		boolean upscaleModeMatches = !upscale
			|| (config.upscaleMode == MMetalConfig.UpscaleMode.TEMPORAL) == (temporalScaler != null);
		if (scaledW == inW && scaledH == inH && inColor != null && inDepth != null
			&& outColor != null && interpolatorStateMatches && upscaleModeMatches
			&& presentProgram != 0 && library != null) {
			return;
		}
		destroySizeResources();
		scaledW = inW;
		scaledH = inH;

		long ioUsage = MetalNative.USAGE_SHADER_READ | MetalNative.USAGE_SHADER_WRITE;
		inColor = IosurfacePair.create(inW, inH, IosurfacePair.Kind.COLOR, ioUsage);
		// World-only capture (pre-GUI); used by the HDR tonemap to keep the UI at SDR.
		inWorld = IosurfacePair.create(inW, inH, IosurfacePair.Kind.COLOR, ioUsage);
		// Depth shares the BGRA8 IOSurface combo (the only widely supported 4-byte one);
		// mm_depth unpacks it into a native R32Float texture for the kernels/MetalFX.
		inDepth = IosurfacePair.create(inW, inH, IosurfacePair.Kind.COLOR, ioUsage);
		outColor = IosurfacePair.create(fullW, fullH, IosurfacePair.Kind.COLOR, ioUsage);
		if (fi) {
			interpColor = IosurfacePair.create(fullW, fullH, IosurfacePair.Kind.COLOR, ioUsage);
		}
		long rwUsage = MetalNative.USAGE_SHADER_READ | MetalNative.USAGE_SHADER_WRITE;
		privateFullTex = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		postFxTex = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		prevColorTex = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		maskTex = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_RGBA8_UNORM, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		depthR32Tex = MetalNative.newTexture(inW, inH, MetalNative.PIXEL_FORMAT_R32_FLOAT, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		volumetricTex = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		blurredTex = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		tonemappedTex = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		styleTex = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		interpOutTex = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		reflectionTex = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		giTex = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		bloomTexA = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		bloomTexB = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		rtShadowTex = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		worldLightTex = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		motionTex = MetalNative.newTexture(inW, inH, MetalNative.PIXEL_FORMAT_RG16_FLOAT, rwUsage,
			MetalNative.STORAGE_MODE_PRIVATE);
		if (fi) {
			interpPrivateTex = MetalNative.newTexture(fullW, fullH, MetalNative.PIXEL_FORMAT_BGRA8_UNORM,
				rwUsage, MetalNative.STORAGE_MODE_PRIVATE);
		}

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
		if (fi && SystemInfo.supportsFrameInterpolation()
			&& MetalFx.supportsFrameInterpolation(MetalNative.device())) {
			// The interpolator consumes the POST-upscale frames (finalPre / prevColorTex /
			// interpPrivateTex are full resolution), so it must be created at full size -
			// creating it at the pre-upscale size made MetalFX produce smeared frames and
			// killed the frame rate under any scaling preset.
			frameInterpolator = MetalFx.createFrameInterpolator(MetalNative.device(),
				MetalNative.PIXEL_FORMAT_BGRA8_UNORM, MetalNative.PIXEL_FORMAT_BGRA8_UNORM,
				MetalNative.PIXEL_FORMAT_R32_FLOAT, MetalNative.PIXEL_FORMAT_RG16_FLOAT,
				fullW, fullH, fullW, fullH);
		}

		if (presentProgram == 0) {
			presentProgram = gl.program(VERT_SRC, PRESENT_FRAG, "mmetal-present");
			depthProgram = gl.program(VERT_SRC, DEPTH_FRAG, "mmetal-depth");
			if (presentProgram == 0 || depthProgram == 0) {
				throw new IllegalStateException("MMetal GL programs failed to compile");
			}
		}
		LOGGER.info(String.format(Locale.ROOT,
			"MMetal resources ready: %dx%d -> %dx%d (upscale=%s effects=%s fi=%s)",
			inW, inH, fullW, fullH, upscale, effects, fi));
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

	private void destroySizeResources() {
		closeQuietly(inColor);
		closeQuietly(inDepth);
		closeQuietly(outColor);
		closeQuietly(interpColor);
		closeQuietly(inWorld);
		inColor = null;
		inDepth = null;
		outColor = null;
		interpColor = null;
		inWorld = null;
		destroyTexture(privateFullTex);
		destroyTexture(postFxTex);
		destroyTexture(prevColorTex);
		destroyTexture(maskTex);
		destroyTexture(depthR32Tex);
		destroyTexture(volumetricTex);
		destroyTexture(blurredTex);
		destroyTexture(tonemappedTex);
		destroyTexture(styleTex);
		destroyTexture(interpOutTex);
		destroyTexture(reflectionTex);
		destroyTexture(giTex);
		destroyTexture(bloomTexA);
		destroyTexture(bloomTexB);
		destroyTexture(rtShadowTex);
		destroyTexture(worldLightTex);
		destroyTexture(motionTex);
		destroyTexture(interpPrivateTex);
		privateFullTex = null;
		postFxTex = null;
		prevColorTex = null;
		maskTex = null;
		depthR32Tex = null;
		volumetricTex = null;
		blurredTex = null;
		tonemappedTex = null;
		interpOutTex = null;
		reflectionTex = null;
		giTex = null;
		bloomTexA = null;
		bloomTexB = null;
		rtShadowTex = null;
		worldLightTex = null;
		motionTex = null;
		interpPrivateTex = null;
		styleTex = null;
		spatialScaler = null;
		temporalScaler = null;
		frameInterpolator = null;
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

	private static void destroyTexture(MemorySegment texture) {
		// Metal objects (MTLTexture/MTLComputePipelineState/...) are ARC-managed and
		// autoreleased - there is no -close/-release we can safely send from Java, so
		// per-size resources intentionally live until the process exits. IOSurfaces
		// (CF objects) are released properly in IosurfacePair.close().
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