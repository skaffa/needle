package dev.needle.core;

/** Artist and title parsed from the game's own "Artist - Title" names (en_us.json). */
public record TrackName(String artist, String title) {
	private static final String SEPARATOR = " - ";

	/** Splits on the first {@code " - "}; returns null when either side would be empty. */
	public static TrackName parse(String value) {
		if (value == null) {
			return null;
		}
		int i = value.indexOf(SEPARATOR);
		if (i < 0) {
			return null;
		}
		String artist = value.substring(0, i).strip();
		String title = value.substring(i + SEPARATOR.length()).strip();
		if (artist.isEmpty() || title.isEmpty()) {
			return null;
		}
		return new TrackName(artist, title);
	}

	@Override
	public String toString() {
		return artist + SEPARATOR + title;
	}
}
