package com.prof.blackhole.client.particle;

/** Blackbody colours shared by the particles and the fallback renderer, matching the shader's {@code blackbodyRGB}. */
public final class DiskColors {
	private DiskColors() {
	}

	/**
	 * Planckian-locus colour (shader blackbodyRGB: Kim et al. 2002 fits, blended toward 1000 K below 1667 K), as
	 * linear sRGB scaled so the largest channel is 1.
	 */
	public static float[] blackbody(double temperature) {
		double t = Math.min(Math.max(temperature, 1000.0), 1.0e6);
		double x;
		double y;
		if (t < 1667.0) {
			double k = (t - 1000.0) / 667.0;
			x = 0.6528 + (0.5646 - 0.6528) * k;
			y = 0.3444 + (0.4029 - 0.3444) * k;
		} else {
			double u = 1000.0 / t;
			double u2 = u * u;
			double u3 = u2 * u;
			x = t <= 4000.0
					? -0.2661239 * u3 - 0.2343589 * u2 + 0.8776956 * u + 0.179910
					: -3.0258469 * u3 + 2.1070379 * u2 + 0.2226347 * u + 0.240390;
			double x2 = x * x;
			double x3 = x2 * x;
			if (t <= 2222.0) {
				y = -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683;
			} else if (t <= 4000.0) {
				y = -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867;
			} else {
				y = 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483;
			}
		}
		double bigX = x / y;
		double bigZ = (1.0 - x - y) / y;
		// CIE XYZ (Y = 1) -> linear sRGB (D65).
		double red = Math.max(3.2404542 * bigX - 1.5371385 - 0.4985314 * bigZ, 0.0);
		double green = Math.max(-0.9692660 * bigX + 1.8760108 + 0.0415560 * bigZ, 0.0);
		double blue = Math.max(0.0556434 * bigX - 0.2040259 + 1.0572252 * bigZ, 0.0);
		double max = Math.max(Math.max(red, green), Math.max(blue, 1.0e-6));
		return new float[] {(float) (red / max), (float) (green / max), (float) (blue / max)};
	}
}
