package dev.mikuteto.mtlrender.backend;

import com.mojang.blaze3d.GpuFormat;

final class Formats {
	private Formats() {
	}

	static int pixelFormat(GpuFormat format) {
		return switch (format) {
			case R8_UNORM -> 10;
			case R8_SNORM -> 12;
			case RG8_UNORM -> 30;
			case RG8_SNORM -> 32;
			case RGB8_UNORM -> 45;
			case RGB8_SNORM -> 46;
			case RGBA8_UNORM -> 70;
			case RGBA8_SNORM -> 72;
			case R16_UNORM -> 20;
			case R16_SNORM -> 22;
			case RG16_UNORM -> 60;
			case RG16_SNORM -> 62;
			case RGB16_UNORM -> 95;
			case RGB16_SNORM -> 96;
			case RGBA16_UNORM -> 110;
			case RGBA16_SNORM -> 112;
			case R8_UINT -> 13;
			case R8_SINT -> 14;
			case RG8_UINT -> 33;
			case RG8_SINT -> 34;
			case RGB8_UINT -> 47;
			case RGB8_SINT -> 48;
			case RGBA8_UINT -> 73;
			case RGBA8_SINT -> 74;
			case R16_UINT -> 23;
			case R16_SINT -> 24;
			case RG16_UINT -> 63;
			case RG16_SINT -> 64;
			case RGB16_UINT -> 97;
			case RGB16_SINT -> 98;
			case RGBA16_UINT -> 113;
			case RGBA16_SINT -> 114;
			case R32_UINT -> 53;
			case R32_SINT -> 54;
			case RG32_UINT -> 103;
			case RG32_SINT -> 104;
			case RGB32_UINT -> 120;
			case RGB32_SINT -> 121;
			case RGBA32_UINT -> 123;
			case RGBA32_SINT -> 124;
			case R16_FLOAT -> 25;
			case RG16_FLOAT -> 65;
			case RGB16_FLOAT -> 99;
			case RGBA16_FLOAT -> 115;
			case R32_FLOAT -> 55;
			case RG32_FLOAT -> 105;
			case RGB32_FLOAT -> 122;
			case RGBA32_FLOAT -> 125;
			case RGB10A2_UNORM -> 90;
			case RGB10A2_UINT -> 91;
			case RG11B10_FLOAT -> 92;
			case D32_FLOAT -> 252;
			case D32_FLOAT_S8_UINT, D24_UNORM_S8_UINT -> 260;
			case D16_UNORM -> 250;
			case S8_UINT -> 253;
		};
	}

	static int vertexFormat(GpuFormat format) {
		return switch (format) {
			case R8_UNORM -> 47;
			case R8_SNORM -> 48;
			case RG8_UNORM -> 7;
			case RG8_SNORM -> 10;
			case RGB8_UNORM -> 8;
			case RGB8_SNORM -> 11;
			case RGBA8_UNORM -> 9;
			case RGBA8_SNORM -> 12;
			case R16_UNORM -> 51;
			case R16_SNORM -> 52;
			case RG16_UNORM -> 19;
			case RG16_SNORM -> 22;
			case RGB16_UNORM -> 20;
			case RGB16_SNORM -> 23;
			case RGBA16_UNORM -> 21;
			case RGBA16_SNORM -> 24;
			case R8_UINT -> 45;
			case R8_SINT -> 46;
			case RG8_UINT -> 1;
			case RG8_SINT -> 4;
			case RGB8_UINT -> 2;
			case RGB8_SINT -> 5;
			case RGBA8_UINT -> 3;
			case RGBA8_SINT -> 6;
			case R16_UINT -> 49;
			case R16_SINT -> 50;
			case RG16_UINT -> 13;
			case RG16_SINT -> 16;
			case RGB16_UINT -> 14;
			case RGB16_SINT -> 17;
			case RGBA16_UINT -> 15;
			case RGBA16_SINT -> 18;
			case R32_UINT -> 36;
			case R32_SINT -> 32;
			case RG32_UINT -> 37;
			case RG32_SINT -> 33;
			case RGB32_UINT -> 38;
			case RGB32_SINT -> 34;
			case RGBA32_UINT -> 39;
			case RGBA32_SINT -> 35;
			case R16_FLOAT -> 53;
			case RG16_FLOAT -> 25;
			case RGB16_FLOAT -> 26;
			case RGBA16_FLOAT -> 27;
			case R32_FLOAT -> 28;
			case RG32_FLOAT -> 29;
			case RGB32_FLOAT -> 30;
			case RGBA32_FLOAT -> 31;
			case RGB10A2_UNORM -> 41;
			case RG11B10_FLOAT -> 54;
			case RGB10A2_UINT, D32_FLOAT, D32_FLOAT_S8_UINT, D24_UNORM_S8_UINT, D16_UNORM, S8_UINT ->
				throw new IllegalArgumentException(format + " cannot be used as a vertex attribute");
		};
	}
}
