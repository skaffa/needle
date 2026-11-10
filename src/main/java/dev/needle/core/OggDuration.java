package dev.needle.core;

import java.io.IOException;
import java.io.InputStream;

/**
 * Length of an Ogg Vorbis file: sample rate from the Vorbis identification header on the first page,
 * total samples from the granule position of the last page of the same stream. CRCs are not checked;
 * a damaged file just gives a wrong or unknown length.
 */
public final class OggDuration {
	private static final int PAGE_HEADER = 27;
	private static final long NO_GRANULE = -1L;

	private OggDuration() {
	}

	/** Length in milliseconds, or -1 when the data is not a readable Ogg Vorbis stream. */
	public static long lengthMs(InputStream in) throws IOException {
		return lengthMs(in.readAllBytes());
	}

	/** Length in milliseconds, or -1 when the data is not a readable Ogg Vorbis stream. */
	public static long lengthMs(byte[] data) {
		if (!isPage(data, 0)) {
			return -1;
		}
		int serial = int32(data, 14);
		int segments = data[26] & 0xFF;
		int body = PAGE_HEADER + segments;
		// Identification header: type 1, "vorbis", version (4), channels (1), sample rate (4)
		if (body + 16 > data.length || data[body] != 1 || !ascii(data, body + 1, "vorbis")) {
			return -1;
		}
		long rate = int32(data, body + 12) & 0xFFFFFFFFL;
		if (rate == 0) {
			return -1;
		}
		for (int i = data.length - PAGE_HEADER; i >= 0; i--) {
			if (isPage(data, i) && int32(data, i + 14) == serial) {
				long granule = int64(data, i + 6);
				if (granule != NO_GRANULE && granule > 0) {
					return granule * 1000 / rate;
				}
			}
		}
		return -1;
	}

	private static boolean isPage(byte[] d, int i) {
		return i >= 0 && i + PAGE_HEADER <= d.length && ascii(d, i, "OggS") && d[i + 4] == 0;
	}

	private static boolean ascii(byte[] d, int i, String s) {
		if (i + s.length() > d.length) {
			return false;
		}
		for (int k = 0; k < s.length(); k++) {
			if (d[i + k] != s.charAt(k)) {
				return false;
			}
		}
		return true;
	}

	private static int int32(byte[] d, int i) {
		return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8 | (d[i + 2] & 0xFF) << 16 | (d[i + 3] & 0xFF) << 24;
	}

	private static long int64(byte[] d, int i) {
		return (int32(d, i) & 0xFFFFFFFFL) | (long) int32(d, i + 4) << 32;
	}
}
