package dev.mmetal.metal.render;

/**
 * Frame-time feedback controller behind the "Dynamic" quality preset: picks the render
 * scale that keeps the measured frame interval at the target.
 *
 * <p>A scale change resizes the main render target, which rebuilds every size-bound
 * Metal/IOSurface resource (and those are never freed - see
 * {@code MetalPipeline.destroyTexture}). So this controller is deliberately
 * conservative: coarse {@value #STEP}-sized steps, a decision window instead of a
 * per-frame reaction, and exponential back-off after a probe upward that immediately
 * missed the target. In steady state it settles on one scale and stops rebuilding.</p>
 *
 * <p>The frame interval is capped from below by vsync / Max FPS, so headroom cannot be
 * measured directly. The controller therefore probes: when the target is being met it
 * periodically tries one step higher and backs off if that misses.</p>
 */
final class DynamicResolution {
	static final float STEP = 0.05f;

	private static final long WINDOW_NANOS = 750_000_000L;
	private static final long SETTLE_NANOS = 700_000_000L;
	private static final long BASE_PROBE_NANOS = 6_000_000_000L;
	private static final long CALM_RESET_NANOS = 60_000_000_000L;
	private static final int MIN_SAMPLES = 8;
	private static final double MISS_RATIO = 1.08;
	private static final double MET_RATIO = 1.04;

	private float scale = 1.0f;
	private long windowStart;
	private double windowSumMs;
	private int windowSamples;
	/** Samples are ignored until the resize triggered by the last change has settled. */
	private long ignoreUntil;
	private long probeBlockedUntil;
	private long lastUpStep;
	private long lastDownStep;
	private int failedProbes;

	float scale() {
		return scale;
	}

	/** Restarts the controller from a given scale (preset switched, config reloaded). */
	void reset(float initialScale, long now) {
		scale = quantize(initialScale);
		windowStart = now;
		windowSumMs = 0.0;
		windowSamples = 0;
		ignoreUntil = now + SETTLE_NANOS;
		probeBlockedUntil = now + BASE_PROBE_NANOS;
		lastUpStep = 0;
		lastDownStep = 0;
		failedProbes = 0;
	}

	/**
	 * Feeds one frame interval and returns the scale to render at.
	 *
	 * @param frameMs  measured interval since the previous present
	 * @param targetMs the interval the game should hold (display refresh, Max FPS)
	 * @param paused   true while the interval says nothing about world cost (menus)
	 */
	float update(double frameMs, double targetMs, float minScale, float maxScale, long now, boolean paused) {
		float lo = Math.min(minScale, maxScale);
		float hi = Math.max(minScale, maxScale);
		if (scale < lo || scale > hi) {
			setScale(Math.max(lo, Math.min(hi, scale)), now);
		}
		// Gaps (loading screens, window drags) and open menus say nothing about world cost.
		if (paused || frameMs > 250.0 || now < ignoreUntil) {
			windowStart = now;
			windowSumMs = 0.0;
			windowSamples = 0;
			return scale;
		}
		windowSumMs += frameMs;
		windowSamples++;
		if (now - windowStart < WINDOW_NANOS || windowSamples < MIN_SAMPLES) {
			return scale;
		}
		double avgMs = windowSumMs / windowSamples;
		windowStart = now;
		windowSumMs = 0.0;
		windowSamples = 0;

		if (now - Math.max(lastUpStep, lastDownStep) > CALM_RESET_NANOS) {
			failedProbes = 0;
		}
		if (avgMs > targetMs * MISS_RATIO && scale > lo) {
			// Pixel count (and so cost) goes with scale^2.
			float ideal = scale * (float) Math.sqrt(targetMs / avgMs);
			float next = (float) Math.floor(ideal / STEP) * STEP;
			next = Math.min(next, scale - STEP);
			if (lastUpStep != 0 && now - lastUpStep < BASE_PROBE_NANOS) {
				failedProbes = Math.min(failedProbes + 1, 3);
			}
			lastDownStep = now;
			probeBlockedUntil = now + (BASE_PROBE_NANOS << failedProbes);
			setScale(Math.max(lo, next), now);
		} else if (avgMs < targetMs * MET_RATIO && scale < hi && now >= probeBlockedUntil) {
			lastUpStep = now;
			probeBlockedUntil = now + BASE_PROBE_NANOS / 3;
			setScale(Math.min(hi, scale + STEP), now);
		}
		return scale;
	}

	private void setScale(float next, long now) {
		next = quantize(next);
		if (next != scale) {
			scale = next;
			ignoreUntil = now + SETTLE_NANOS;
		}
	}

	private static float quantize(float value) {
		return Math.round(value / STEP) * STEP;
	}
}
