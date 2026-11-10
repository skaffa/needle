package dev.needle.core;

/**
 * How long a track must be heard before it is scrobbled. Last.fm's own rule is "half the track or
 * 4 minutes, whichever comes first, and never tracks under 30 seconds"; the fraction and cap are
 * configurable, the 30 second minimum is not (Last.fm rejects shorter tracks).
 */
public record Threshold(double fraction, int maxRequiredMinutes) {
	public static final long MIN_TRACK_MS = 30_000;
	public static final Threshold LAST_FM = new Threshold(0.5, 4);

	public Threshold {
		if (!(fraction >= 0.1 && fraction <= 1.0)) {
			throw new IllegalArgumentException("fraction must be 0.1-1.0: " + fraction);
		}
		if (maxRequiredMinutes < 0) {
			throw new IllegalArgumentException("maxRequiredMinutes must be >= 0: " + maxRequiredMinutes);
		}
	}

	/** Milliseconds of listening needed, or -1 when the track can never be scrobbled (unknown or too short). */
	public long requiredMs(long lengthMs) {
		if (lengthMs <= MIN_TRACK_MS) {
			return -1;
		}
		long required = (long) Math.ceil(fraction * lengthMs);
		if (maxRequiredMinutes > 0) {
			required = Math.min(required, maxRequiredMinutes * 60_000L);
		}
		return required;
	}
}
