package dev.mikuteto.mtlrender.pack.precision;

public record ChannelMask(int bits, boolean unknown) {
	public static final int RED = 1;
	public static final int GREEN = 2;
	public static final int BLUE = 4;
	public static final int ALPHA = 8;
	public static final int RGB = RED | GREEN | BLUE;
	public static final int RGBA = RGB | ALPHA;

	public ChannelMask {
		if ((bits & ~RGBA) != 0) throw new IllegalArgumentException("invalid channel mask");
	}

	public static ChannelMask known(int bits) {
		return new ChannelMask(bits, false);
	}

	public static ChannelMask unknownMask() {
		return new ChannelMask(0, true);
	}

	public boolean includes(int channel) {
		return (bits & channel) != 0;
	}

	public boolean alphaUnused() {
		return !unknown && !includes(ALPHA);
	}

	@Override
	public String toString() {
		return unknown ? "unknown" : Integer.toString(bits);
	}
}
