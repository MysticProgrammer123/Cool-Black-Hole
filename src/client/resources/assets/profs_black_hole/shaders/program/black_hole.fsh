#version 150

// ============================================================================
//  Black Hole - black_hole.fsh
//
//  Full-screen post-process that ray-marches Kerr null geodesics backwards
//  from the camera and composites a relativistic thin accretion disk over the
//  gravitationally lensed scene. Run once per visible hole (farthest first),
//  each pass lensing the result of the one before.
//
//  White holes (uWhite = 1) use the same integrator with the sign of the mass
//  flipped (S = -1): a negative-mass Kerr field, whose repulsion bends light
//  AWAY from the hole (a diverging lens that pushes the background outward).
//  There is no horizon; rays that reach r = 2M strike a blinding white-gold
//  core instead, and the disk is an outflowing, blue-white ejection disk.
//
//  Units: geometric, G = c = 1, every length in units of M = r_s / 2 (blocks).
//  Spin parameter a = a* (|a| < 1), spin axis = world +Y.
//
//  Boyer-Lindquist (BL) <-> world mapping, shared with the Java side:
//      x = sqrt(r^2 + a^2) sin(theta) sin(phi)
//      z = sqrt(r^2 + a^2) sin(theta) cos(phi)
//      y = r cos(theta)
//  so phi = atan(x, z) increases in the prograde sense, cross(+Y, r).
//
//  Pipeline per pixel:
//    1. rebuild the camera ray and scene distance from the depth copy;
//    2. rays that end (geometry) or pass outside the influence sphere only get
//       the analytic first-order bending, and are left untouched when that
//       shift is below half a pixel;
//    3. otherwise bend the ray analytically up to the sphere entry (or start
//       at the camera when it is inside) and turn it into constants of motion
//       (lambda = L/E, eta = Q/E^2), measuring its direction in the local
//       ZAMO frame;
//    4. integrate the second-order Mino-time geodesic equations in u = 1/r
//       with adaptive RK4 until the ray is captured, escapes, or reaches
//       scene geometry;
//    5. accumulate disk emission at every equatorial crossing (Novikov-Thorne
//       temperature, Keplerian gas, exact Kerr redshift, g^4 beaming);
//    6. add the analytic bending beyond the exit point, sample the lensed
//       background, tone map, composite.
//
//  References:
//    Carter (1968), Phys. Rev. 174, 1559 ............ Carter constant, separation
//    Bardeen, Press & Teukolsky (1972), ApJ 178, 347 . ZAMO frame, ISCO, Kepler u^t
//    Bardeen (1973), in "Black Holes" (Les Houches) .. photon orbits, shadow
//    Novikov & Thorne (1973); Page & Thorne (1974) ... thin disk emission
//    Luminet (1979), A&A 75, 228 ..................... disk image and redshift
//    Chandrasekhar (1983), Math. Theory of Black Holes
//    Mino (2003), PRD 67, 084027 ..................... Mino time parameter
//    Kim et al. (2002) ............................... Planckian locus fit
// ============================================================================

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D uSceneColor;     // unit 0: copy of main framebuffer colour
uniform sampler2D uSceneDepth;     // unit 1: copy of main framebuffer depth
// Units 2-5: Fabulous graphics draws translucent terrain (water), item
// entities, particles and clouds into their own framebuffers, so their depth
// never reaches the main one. Outside Fabulous these are uSceneDepth again.
uniform sampler2D uTranslucentDepth;
uniform sampler2D uItemEntityDepth;
uniform sampler2D uParticlesDepth;
uniform sampler2D uCloudsDepth;
uniform mat4  uProjection;         // Minecraft projection (incl. bobbing)
uniform mat4  uInvProjection;
uniform mat4  uView;               // view rotation only (camera-relative world -> view)
uniform mat4  uInvView;
uniform vec3  uBlackHolePos;       // hole centre relative to the camera, blocks
uniform vec3  uEyeOffset;          // true eye (centre of projection, moved by view bobbing) relative to the camera
uniform vec3  uBlackHoleWorldPos;  // absolute hole position (noise seed only)
uniform float uRs;                 // Schwarzschild radius, blocks
uniform float uSpin;               // a* in [0, 0.99]
uniform int   uDiskEnabled;        // 0 / 1
uniform float uDiskTemperature;    // peak disk temperature, Kelvin
uniform float uTime;               // seconds
uniform vec2  uScreenSize;         // pixels
uniform float uAspect;             // width / height (unused: the matrices carry it)
uniform float uInfluenceRadius;    // blocks
uniform int   uWhite;              // 1: white hole (negative mass, blinding core)
uniform int   uGoggles;            // 1: Event Horizon Goggles (lensing grid, photon ring, dimmed disk)
// Point lights near the hole (torches, lanterns, ...), traced along the bent
// rays so that their lensed images and Einstein rings appear even when the
// light itself is off screen, where the screen-space scene lookup cannot see it.
const int   MAX_LIGHTS       = 8;
uniform int   uLightCount;
uniform vec4  uLights[MAX_LIGHTS];      // xyz: camera-relative position, w: glow radius (blocks)
uniform vec3  uLightColors[MAX_LIGHTS]; // linear HDR colour

// ---------------------------------------------------------------------------
// Tunables
// ---------------------------------------------------------------------------
const int   MAX_STEPS        = 300;
const float TWO_PI           = 6.28318530717959;
const float SKY_DEPTH        = 0.9999995;  // cleared depth (sky) is exactly 1.0
const float MIN_SPHERE_RS    = 16.0;       // lensing sphere is never smaller than 16 r_s (32 M)
const float DISK_IN_M        = 4.0;        // inner disk edge, 2 r_s
const float DISK_OUT_M       = 12.0;       // outer disk edge, 6 r_s
const float DISK_TAU0        = 1.2;        // vertical optical depth scale of the disk
const float DISK_EXPOSURE    = 1.6;        // HDR exposure applied before tone mapping
const float ANIM_RATE        = 12.0;       // geometric time units (M) per real second
const float FLOW_PERIOD      = 6.0;        // seconds per noise-layer cycle (divides 1200 s)
const float FLOW_CYCLES      = 200.0;      // uTime wraps every 1200 s = 200 cycles
const float NOISE_PHI_CELLS  = 16.0;       // noise cells around the disk (periodic)
const float NOISE_LOGR_SCALE = 8.0;        // noise cells per unit of ln(r)
const float STAR_CELLS       = 128.0;      // star grid cells per cube face
const float DOPPLER_BOOST    = 3.0;        // extra beaming exponent at a* = 1 (scaled by a*^2)
const float WEAK_FIELD_IMPACT_M = 24.0;    // impact parameter (M) beyond which rays may skip integration
// Lensed light-source images are drawn only where the ray has turned by more
// than about 1.6 degrees (1 - cos), so unbent, directly seen lights (which the
// scene already shows) get no extra halo.
const float LIGHT_BEND_MIN   = 4.0e-4;
const float LIGHT_BEND_FULL  = 1.0e-2;
// White holes.
// Optical strength of the repulsion relative to a black hole's attraction.
// At full strength a diverging lens throws every ray within ~8 M of the core
// tens of degrees out of the field of view, where a screen-space lookup has
// nothing to show; at 0.35 the background still visibly bulges away from the
// core while what it shows stays on screen.
const float WHITE_LENS       = 0.35;
const float WHITE_CORE_M     = 2.0;        // radius of the blinding core (r_s)
const float CORE_INTENSITY   = 5.0;        // HDR radiance of the core
const float WHITE_OUTFLOW    = 0.35;       // radial drift of the ejection disk's pattern, ln(r) per second
const vec3  CORE_GOLD        = vec3(1.0, 0.76, 0.38);
const vec3  CORE_WHITE       = vec3(1.0, 0.97, 0.92);
const vec3  GLOW_COLOR       = vec3(1.0, 0.88, 0.66);
const vec3  WHITE_DISK_TINT  = vec3(0.55, 0.9, 1.0);
// Event Horizon Goggles.
const float GRID_STEP        = 0.17453293; // 10 degrees
const float GRID_REACH       = 0.6;        // the grid fades out by this fraction of the lensing sphere
const vec3  GRID_COLOR       = vec3(0.3, 0.9, 1.0);
const vec3  RING_COLOR       = vec3(1.0, 0.72, 0.28);

// Sign of the mass: +1 for a black hole, -1 for a white hole. Set at the top
// of main() from uWhite, before any geometry function runs.
float S = 1.0;

// ---------------------------------------------------------------------------
// Hashing and noise ("hash without sine", D. Hoskins: no sin() precision
// problems, identical results across vendors)
// ---------------------------------------------------------------------------
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 hash32(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yxz + 33.33);
    return fract((p3.xxy + p3.yzz) * p3.zyx);
}

// Value noise that is periodic in x with an integer period (used for the
// azimuthal disk coordinate so the pattern has no seam at phi = 2 pi).
float valueNoisePeriodic(vec2 p, float period) {
    vec2 i = floor(p);
    vec2 f = p - i;
    vec2 u = f * f * (3.0 - 2.0 * f);
    float x0 = mod(i.x, period);
    float x1 = mod(i.x + 1.0, period);
    float v00 = hash12(vec2(x0, i.y));
    float v10 = hash12(vec2(x1, i.y));
    float v01 = hash12(vec2(x0, i.y + 1.0));
    float v11 = hash12(vec2(x1, i.y + 1.0));
    return mix(mix(v00, v10, u.x), mix(v01, v11, u.x), u.y);
}

float fbmPeriodic(vec2 p, float period) {
    float sum = 0.0;
    float amp = 0.5;
    float norm = 0.0;
    for (int o = 0; o < 4; ++o) {
        sum += amp * valueNoisePeriodic(p, period);
        norm += amp;
        // Doubling x together with the period keeps every octave seamless.
        p = vec2(p.x * 2.0, p.y * 2.0 + 17.31);
        period *= 2.0;
        amp *= 0.5;
    }
    return sum / norm;
}

// ---------------------------------------------------------------------------
// Colour
// ---------------------------------------------------------------------------
vec3 toLinear(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(2.2));
}

vec3 toSrgb(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2));
}

// ACES filmic fit (Narkowicz 2015).
vec3 acesFitted(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

// Blackbody chromaticity -> linear sRGB with unit luminance.
// Planckian locus: Kim et al. (2002) cubic fits, valid 1667 K .. 25000 K; the
// 1/T expansion converges on the T -> infinity locus point (0.240, 0.235), so
// hotter temperatures extrapolate correctly. Below 1667 K the chromaticity is
// blended toward the 1000 K locus point (0.6528, 0.3444).
vec3 blackbodyRGB(float temperature) {
    float t = clamp(temperature, 1000.0, 1.0e6);
    float x = 0.3127;
    float y = 0.3290;
    if (t < 1667.0) {
        float k = (t - 1000.0) / 667.0;
        x = mix(0.6528, 0.5646, k);
        y = mix(0.3444, 0.4029, k);
    } else {
        float u = 1000.0 / t;          // the fits written in kK^-1 keep magnitudes moderate
        float u2 = u * u;
        float u3 = u2 * u;
        if (t <= 4000.0) {
            x = -0.2661239 * u3 - 0.2343589 * u2 + 0.8776956 * u + 0.179910;
        } else {
            x = -3.0258469 * u3 + 2.1070379 * u2 + 0.2226347 * u + 0.240390;
        }
        float x2 = x * x;
        float x3 = x2 * x;
        if (t <= 2222.0) {
            y = -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683;
        } else if (t <= 4000.0) {
            y = -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867;
        } else {
            y = 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483;
        }
    }
    vec3 xyz = vec3(x / y, 1.0, (1.0 - x - y) / y);
    // CIE XYZ -> linear sRGB (D65), column-major.
    const mat3 XYZ_TO_SRGB = mat3(
         3.2404542, -0.9692660,  0.0556434,
        -1.5371385,  1.8760108, -0.2040259,
        -0.4985314,  0.0415560,  1.0572252);
    vec3 rgb = max(XYZ_TO_SRGB * xyz, vec3(0.0));
    float lum = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    return rgb / max(lum, 1.0e-4);
}

// ---------------------------------------------------------------------------
// Kerr geometry
// ---------------------------------------------------------------------------

// World-axis Cartesian (hole-relative, units of M) -> BL (r, theta, phi).
// r^2 = 1/2 [ (rho^2 - a^2) + sqrt((rho^2 - a^2)^2 + 4 a^2 y^2) ]
vec3 cartToBL(vec3 p, float a) {
    float a2 = a * a;
    float w = dot(p, p) - a2;
    float r2 = 0.5 * (w + sqrt(w * w + 4.0 * a2 * p.y * p.y));
    float r = sqrt(max(r2, 1.0e-12));
    float th = acos(clamp(p.y / r, -1.0, 1.0));
    float ph = (abs(p.x) + abs(p.z) > 1.0e-12) ? atan(p.x, p.z) : 0.0;
    return vec3(r, th, ph);
}

// BL -> world-axis Cartesian (units of M); st, ct = sin/cos(theta), signed.
vec3 blToCart(float r, float st, float ct, float ph, float a) {
    float rc = sqrt(r * r + a * a) * st;
    return vec3(rc * sin(ph), r * ct, rc * cos(ph));
}

// Lapse alpha = sqrt(Sigma Delta / A) and frame-dragging rate omega = 2 a r / A
// of the zero-angular-momentum observer (ZAMO).
// With the mass sign S, Delta = r^2 - 2 S r + a^2 and omega = 2 S a r / A.
vec2 zamoAlphaOmega(float r, float ct, float a) {
    float a2 = a * a;
    float st2 = max(1.0 - ct * ct, 0.0);
    float sig = r * r + a2 * ct * ct;
    float del = r * r - 2.0 * S * r + a2;
    float r2a2 = r * r + a2;
    float bigA = r2a2 * r2a2 - a2 * del * st2;
    return vec2(sqrt(max(sig * del / bigA, 1.0e-12)), 2.0 * S * a * r / bigA);
}

// Prograde ISCO, Bardeen, Press & Teukolsky (1972).
float iscoPrograde(float a) {
    float a2 = a * a;
    float z1 = 1.0 + pow(1.0 - a2, 1.0 / 3.0) * (pow(1.0 + a, 1.0 / 3.0) + pow(1.0 - a, 1.0 / 3.0));
    float z2 = sqrt(3.0 * a2 + z1 * z1);
    return 3.0 + z2 - sqrt(max((3.0 - z1) * (3.0 + z1 + 2.0 * z2), 0.0));
}

// Novikov-Thorne-like temperature shape T ~ r^-3/4 (1 - sqrt(r_isco / r))^1/4
// (zero-torque inner boundary); zero inside the ISCO.
float ntProfile(float r, float rIsco) {
    float x = max(1.0 - sqrt(rIsco / r), 0.0);
    return pow(r, -0.75) * sqrt(sqrt(x));
}

// Right-hand side of the geodesic equations in BACKWARD Mino time mu
// (d mu = -d sigma / Sigma), photon energy normalised to E = 1.
// Carter's first integrals (Carter 1968):
//     (dr/dmu)^2 = R(r) = P^2 - Delta K,          P = r^2 + a^2 - a lambda
//     (dtheta/dmu)^2 = Theta = eta + a^2 cos^2 - lambda^2 cot^2
//     dphi/dmu (forward) = -(a - lambda / sin^2) + a P / Delta
// with K = eta + (lambda - a)^2 and Delta = r^2 - 2 r + a^2.
// R grows like r^4, so integrating r'' = R'(r)/2 directly turns a tiny
// relative error far away into a large one near the hole. Instead we use
// u = 1/r, for which du/dmu = -(dr/dmu)/r^2 stays O(1) everywhere:
//     (du/dmu)^2 = U(u) = (1 + beta u^2)^2 - K u^2 (1 - 2u + a^2 u^2),   beta = a^2 - a lambda
//     u''       = U'(u)/2 = 2 beta u (1 + beta u^2) - K u (1 - 3u + 2 a^2 u^2)
//     theta''   = Theta'(theta)/2 = cos(theta) (lambda^2 / sin^3(theta) - a^2 sin(theta))
// The second-order form passes radial / polar turning points without sign
// bookkeeping (u'' and theta'' are invariant under mu -> -mu); only phi'
// changes sign for backward tracing. P / Delta = (1 + beta u^2) / (1 - 2u + a^2 u^2).
// K packs (a, lambda, eta, eta + (lambda - a)^2). Returns (u'', theta'', phi').
// With the mass sign S (white holes: S = -1) every mass term flips:
//     U(u) = (1 + beta u^2)^2 - K u^2 (1 - 2 S u + a^2 u^2)
//     u''  = 2 beta u (1 + beta u^2) - K u (1 - 3 S u + 2 a^2 u^2)
vec3 geodesicRHS(float u, float st, float ct, vec4 K) {
    float a = K.x;
    float lam = K.y;
    float a2 = a * a;
    float beta = a2 - a * lam;
    float s = (abs(st) < 1.0e-6) ? ((st < 0.0) ? -1.0e-6 : 1.0e-6) : st;
    float s2 = s * s;
    float u2 = u * u;
    float d2u = 2.0 * beta * u * (1.0 + beta * u2) - K.w * u * (1.0 - 3.0 * S * u + 2.0 * a2 * u2);
    float d2th = ct * (lam * lam / (s2 * s) - a2 * s);
    float pOverDelta = (1.0 + beta * u2) / max(1.0 - 2.0 * S * u + a2 * u2, 1.0e-6);
    float dph = (a - lam / s2) - a * pOverDelta;
    return vec3(d2u, d2th, dph);
}

// First-order (weak-field) light bending outside the integration sphere,
// written in the same Schwarzschild/BL coordinates as the geodesic inside it
// (the world's straight lines are straight lines of r, theta, phi), so the
// two match at the sphere boundary without a seam.
// In the orbital plane the photon orbit obeys u'' + u = 3 M u^2 (u = 1/r).
// Perturbing the straight line u0 = sin(psi)/b that has the ray's position
// and direction at psi_1, the tangent turns toward the hole by
//     alpha(psi_1 -> psi) = (M/b) [3 (c1 - c) - (c1^3 - c^3)],   c = cos(psi)
// (4M/b over the whole line, 2M/b from closest approach to infinity).
// With t the distance along the line, tc its closest approach and
// g(t) = (t - tc) / q(t), q(t) = sqrt((t - tc)^2 + b^2)  (so c = -g):
//     alpha = (M/b) [3 (g2 - g1) - (g2^3 - g1^3)]
// and integrating alpha along the path gives the sideways displacement at t2
// (int g dt = q, int g^3 dt = q + b^2/q):
//     dx = (M/b) [3 (q2 - q1 - L g1) - (q2 + b^2/q2 - q1 - b^2/q1 - L g1^3)],  L = t2 - t1.
// t2 > 1e19 means "to infinity" (dx is then not needed). Returns (alpha, dx).
vec2 weakDeflection(float t1, float t2, float tc, float b, float mass) {
    if (b < 1.0e-3) {
        return vec2(0.0);
    }
    float s1 = t1 - tc;
    float q1 = sqrt(s1 * s1 + b * b);
    float g1 = s1 / q1;
    float g13 = g1 * g1 * g1;
    float k = mass / b;
    if (t2 > 1.0e19) {
        return vec2(k * (3.0 * (1.0 - g1) - (1.0 - g13)), 0.0);
    }
    float s2 = t2 - tc;
    float q2 = sqrt(s2 * s2 + b * b);
    float g2 = s2 / q2;
    float len = t2 - t1;
    float alpha = k * (3.0 * (g2 - g1) - (g2 * g2 * g2 - g13));
    float dx = k * (3.0 * (q2 - q1 - len * g1) - ((q2 + b * b / q2) - (q1 + b * b / q1) - len * g13));
    return vec2(alpha, dx);
}

// ---------------------------------------------------------------------------
// Background lookup
// ---------------------------------------------------------------------------

// Triangle wave of period 4 with tri(t) = t on [-1, 1]: mirrors angles back
// into the field of view.
float triangleWave(float t) {
    return abs(mod(t - 1.0, 4.0) - 2.0) - 1.0;
}

// Sparse, stable star field keyed on the world direction (cube-map cells).
vec3 starField(vec3 d) {
    if (dot(d, d) < 1.0e-12) {
        return vec3(0.0);
    }
    vec3 ad = abs(d);
    vec2 fuv = vec2(0.0);
    float face = 0.0;
    if (ad.x >= ad.y && ad.x >= ad.z) {
        fuv = d.zy / ad.x;
        face = (d.x > 0.0) ? 0.0 : 1.0;
    } else if (ad.y >= ad.z) {
        fuv = d.xz / ad.y;
        face = (d.y > 0.0) ? 2.0 : 3.0;
    } else {
        fuv = d.xy / ad.z;
        face = (d.z > 0.0) ? 4.0 : 5.0;
    }
    vec2 gpos = (fuv * 0.5 + 0.5) * STAR_CELLS;
    vec2 cell = floor(gpos);
    vec2 f = gpos - cell;
    vec2 key = cell + vec2(131.0 * face, 71.0 * face);
    vec3 h = hash32(key);
    if (h.x > 0.07) {
        return vec3(0.0);
    }
    // Keep the star core at least about one pixel wide to limit shimmering.
    float pixelAngle = 2.0 / (max(abs(uProjection[1][1]), 1.0e-3) * max(uScreenSize.y, 1.0));
    float radius = max(0.07, 0.6 * pixelAngle * STAR_CELLS * 0.5);
    float dist = length(f - (0.2 + 0.6 * h.yz));
    float core = exp(-(dist * dist) / (radius * radius));
    float mag = 0.25 + 1.75 * pow(hash12(key + 19.19), 6.0);
    vec3 tint = mix(vec3(1.0, 0.78, 0.55), vec3(0.72, 0.82, 1.0), h.y);
    return tint * (core * mag);
}

// Linear-space scene colour seen along a camera-relative point (w = 1) or a
// direction at infinity (w = 0).
// The lookup is written in angles measured from the clip coordinates:
//   yaw   = atan(x_c / s_x, w_c),   pitch = atan(y_c / s_y, w')
// which reproduces the exact projection for every on-screen direction. A
// direction that leaves the screen has its yaw mirrored back into the field
// of view (triangle wave, continuous all the way round behind the camera) and
// its pitch clamped to the top / bottom edge, so the visible environment is
// continued without seams; w' = max(w_c, |x_c|, -w_c) keeps the pitch
// continuous to the sides and behind. Stars fade in over a small angular
// border wherever that continued environment is dark.
vec3 sampleScene(vec3 p, float w) {
    vec4 clip = uProjection * (uView * vec4(p, w));
    float sx = max(abs(uProjection[0][0]), 1.0e-3);
    float sy = max(abs(uProjection[1][1]), 1.0e-3);
    float hx = atan(1.0 / sx);                    // half field of view, x
    float hy = atan(1.0 / sy);                    // half field of view, y
    float cx = clip.x / sx;
    float cy = clip.y / sy;
    float wP = max(clip.w, max(abs(clip.x), -clip.w));
    float yaw = (abs(cx) + abs(clip.w) > 1.0e-12) ? atan(cx, clip.w) : 0.0;
    float pitch = (abs(cy) + wP > 1.0e-12) ? atan(cy, wP) : 0.0;
    float yawM = triangleWave(yaw / hx) * hx;
    float pitchC = clamp(pitch, -hy, hy);
    vec2 uv = 0.5 + 0.5 * vec2(tan(yawM) * sx, tan(pitchC) * sy);
    vec3 c = toLinear(textureLod(uSceneColor, clamp(uv, vec2(0.0005), vec2(0.9995)), 0.0).rgb);

    float margin = min(hx - abs(yaw), hy - abs(pitch));   // > 0 on screen
    float offScreen = 1.0 - smoothstep(-0.03, 0.0, margin);
    if (offScreen > 0.0) {
        float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
        vec3 d = p / max(length(p), 1.0e-6);
        c += offScreen * (1.0 - smoothstep(0.02, 0.25, lum)) * starField(d);
    }
    return c;
}

// How far a camera-relative point (w = 1) or direction (w = 0) lies outside
// the field of view: 0 on screen, 1 well beyond its edge. A white hole's
// diverging lens sends rays near the screen edges further out, where the
// screen-space scene has nothing to show; those fade back to the unbent image
// instead of showing sampleScene's mirrored continuation.
float leavesScreen(vec3 p, float w) {
    vec4 clip = uProjection * (uView * vec4(p, w));
    float sx = max(abs(uProjection[0][0]), 1.0e-3);
    float sy = max(abs(uProjection[1][1]), 1.0e-3);
    float hx = atan(1.0 / sx);
    float hy = atan(1.0 / sy);
    float cx = clip.x / sx;
    float cy = clip.y / sy;
    float wP = max(clip.w, max(abs(clip.x), -clip.w));
    float yaw = (abs(cx) + abs(clip.w) > 1.0e-12) ? atan(cx, clip.w) : 0.0;
    float pitch = (abs(cy) + wP > 1.0e-12) ? atan(cy, wP) : 0.0;
    return smoothstep(-0.04, 0.1, max(abs(yaw) - hx, abs(pitch) - hy));
}

// ---------------------------------------------------------------------------
// Accretion disk
// ---------------------------------------------------------------------------

// Turbulent density in (phi - Omega t, ln r): every annulus rotates at its own
// Keplerian rate (differential rotation shears the pattern into spiral
// filaments). To stop the shear winding up forever, two layers cycle with a
// half-period offset, each restarting with a fresh seed while its weight is
// zero (flow-noise cross-fade with variance-preserving blend).
float diskTurbulence(float rh, float phHit, float omegaGas) {
    float seed = 97.0 * hash12(floor(uBlackHoleWorldPos.xz) * 0.0137 + floor(uBlackHoleWorldPos.y) * 0.0071);
    float cyc = uTime / FLOW_PERIOD;
    float c1 = fract(cyc);
    float c2 = fract(cyc + 0.5);
    // mod() keeps the seeds continuous across the renderer's 1200 s time wrap.
    float id1 = mod(floor(cyc), FLOW_CYCLES);
    float id2 = mod(floor(cyc + 0.5), FLOW_CYCLES);
    float w1 = 1.0 - abs(2.0 * c1 - 1.0);
    float w2 = 1.0 - w1;

    float y = log(rh) * NOISE_LOGR_SCALE + seed;
    float rot1 = omegaGas * c1 * FLOW_PERIOD * ANIM_RATE;
    float rot2 = omegaGas * c2 * FLOW_PERIOD * ANIM_RATE;
    // A white hole's gas streams outward: each layer's pattern drifts out in
    // ln(r) while it lives (restarting unseen when its weight is zero).
    float out1 = S < 0.0 ? c1 * FLOW_PERIOD * WHITE_OUTFLOW * NOISE_LOGR_SCALE : 0.0;
    float out2 = S < 0.0 ? c2 * FLOW_PERIOD * WHITE_OUTFLOW * NOISE_LOGR_SCALE : 0.0;
    float x1 = fract((phHit - rot1) / TWO_PI) * NOISE_PHI_CELLS;
    float x2 = fract((phHit - rot2) / TWO_PI) * NOISE_PHI_CELLS;
    float n1 = fbmPeriodic(vec2(x1, y - out1 + 41.0 * hash12(vec2(id1, 3.7))), NOISE_PHI_CELLS);
    float n2 = fbmPeriodic(vec2(x2, y - out2 + 41.0 * hash12(vec2(id2, 3.7))), NOISE_PHI_CELLS);
    float n = (w1 * n1 + w2 * n2 - 0.5) * inversesqrt(w1 * w1 + w2 * w2) + 0.5;
    return smoothstep(0.25, 0.8, n);
}

// Emission (rgb, HDR, linear) and opacity of one equatorial disk crossing.
//   rh, phHit : BL radius / azimuth of the crossing
//   mu        : |cos| of the angle between the ray and the disk normal
//   lam       : photon L/E (conserved)
//   obsFactor : (1 - omega_cam lambda) / alpha_cam, ZAMO camera
vec4 shadeDisk(float rh, float phHit, float mu, float lam, float obsFactor,
               float a, float rIsco, float invPeak) {
    // Emitted temperature relative to the visible peak (peak == uDiskTemperature).
    // Inside the ISCO the zero-torque profile vanishes; a faint, cooler floor
    // stands in for the plunging gas.
    float tRel;
    float rk;
    float omegaGas;
    float ut;
    if (S > 0.0) {
        float tNT = ntProfile(rh, rIsco) * invPeak;
        float q = rh / rIsco;
        float q2 = q * q;
        float tFloor = 0.4 * min(q2, 1.0 / (q2 * q2));
        tRel = max(tNT, tFloor);

        // Gas on prograde circular Keplerian orbits (Bardeen et al. 1972), ISCO
        // values inside the ISCO:
        //   Omega = 1 / (r^3/2 + a)
        //   u^t   = (r^3/2 + a) / (r^3/4 sqrt(r^3/2 - 3 r^1/2 + 2 a))
        rk = max(rh, rIsco);
        float sr = sqrt(rk);
        float r32 = rk * sr;
        omegaGas = 1.0 / (r32 + a);
        ut = (r32 + a) / sqrt(r32 * max(r32 - 3.0 * sr + 2.0 * a, 1.0e-5));
    } else {
        // Ejected gas: hottest where it leaves the core, cooling on its way
        // out. It swirls at the Kepler-like rate 1 / (r^3/2 + a) while
        // streaming outward; in the negative-mass field a slowly moving
        // clock runs fast, u^t = 1 / sqrt(1 - 2 S / r - v^2) with S < 0.
        tRel = pow(max(rh / DISK_IN_M, 1.0), -0.5);
        rk = rh;
        omegaGas = 1.0 / (rk * sqrt(rk) + a);
        float v = omegaGas * rk;
        ut = inversesqrt(max(1.0 - 2.0 * S / rk - v * v, 0.05));
    }

    // Redshift g = E_obs / E_em = [(1 - omega_cam lambda) / alpha_cam] / [u^t (1 - Omega lambda)].
    // Gravitational redshift, transverse and longitudinal Doppler in one factor;
    // lambda > 0 on the side where the gas approaches the camera -> g > 1 (blueshift).
    float g = obsFactor / (ut * max(1.0 - omegaGas * lam, 1.0e-3));

    float dens = diskTurbulence(rh, phHit, omegaGas);

    float innerFade = smoothstep(DISK_IN_M, DISK_IN_M * 1.12, rh);
    float outerFade = 1.0 - smoothstep(DISK_OUT_M * 0.72, DISK_OUT_M, rh);
    float plungeFade = S > 0.0 ? mix(0.35, 1.0, smoothstep(0.92 * rIsco, 1.02 * rIsco, rh)) : 1.0;
    float tauV = DISK_TAU0 * (0.25 + 1.5 * dens) * innerFade * outerFade * plungeFade;
    float opacity = 1.0 - exp(-tauV / max(mu, 0.06));   // slant optical depth

    // Liouville (I_nu / nu^3 invariant): the observed spectrum is a blackbody at
    // T_obs = g T_em and the bolometric intensity scales as g^4 T_em^4.
    float tLocal = tRel * (0.85 + 0.3 * dens);
    float gt = g * tLocal;
    // Doppler beaming boost (artistic): the line-of-sight Doppler part of g,
    // 1 / (1 - Omega lambda), is raised to an extra power that grows with spin,
    // so fast-spinning holes show a starkly lopsided disk (approaching side
    // much brighter, receding side much dimmer) on top of the physical g^4.
    float beaming = pow(max(1.0 - omegaGas * lam, 1.0e-3), -DOPPLER_BOOST * a * a);
    float intensity = DISK_EXPOSURE * gt * gt * gt * gt * beaming;
    vec3 rgb = blackbodyRGB(gt * uDiskTemperature) * intensity;
    if (S < 0.0) {
        // The ejection disk glows blue-white, with the core's gold at its inner edge.
        float lum = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
        float inner = 1.0 - smoothstep(DISK_IN_M, DISK_IN_M * 1.6, rh);
        rgb = mix(mix(rgb, WHITE_DISK_TINT * lum, 0.3), CORE_GOLD * lum, 0.5 * inner) * 1.3;
    }
    if (uGoggles != 0) {
        // See-through: the lensed sky behind the disk is what the goggles are for.
        rgb *= 0.35;
        opacity *= 0.35;
    }
    return vec4(rgb, opacity);
}

// Event Horizon Goggles: 10-degree celestial grid on a direction at infinity,
// lines of constant angular width (they thicken where the lens magnifies).
float celestialGrid(vec3 d, float pxPerRad) {
    vec3 n = d / max(length(d), 1.0e-6);
    float lon = atan(n.x, n.z);
    float lat = asin(clamp(n.y, -1.0, 1.0));
    float dLon = abs(fract(lon / GRID_STEP + 0.5) - 0.5) * GRID_STEP * cos(lat);
    float dLat = abs(fract(lat / GRID_STEP + 0.5) - 0.5) * GRID_STEP;
    float w = 1.4 / max(pxPerRad, 1.0);
    return 1.0 - smoothstep(0.5 * w, w, min(dLon, dLat));
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
void main() {
    vec4 scene = texture(uSceneColor, texCoord);
    // Nearest visible surface: without the separate layers a water surface or
    // a cloud would be traced through to the geometry behind it, so rays ended
    // too late and the lensed image tore along shorelines and cloud edges.
    float depth = min(min(texture(uSceneDepth, texCoord).r, texture(uTranslucentDepth, texCoord).r),
                      min(min(texture(uItemEntityDepth, texCoord).r, texture(uParticlesDepth, texCoord).r),
                          texture(uCloudsDepth, texCoord).r));

    if (uRs <= 1.0e-4) {
        fragColor = scene;
        return;
    }

    S = (uWhite != 0) ? -WHITE_LENS : 1.0;
    bool white = S < 0.0;
    bool goggles = uGoggles != 0;
    float M = 0.5 * uRs;                          // blocks per geometric unit
    float a = clamp(uSpin, 0.0, 0.99);
    float a2 = a * a;
    // Outer horizon of a black hole; the core surface of a white hole.
    float rPlus = white ? WHITE_CORE_M : 1.0 + sqrt(1.0 - a2);
    float sphereR = max(uInfluenceRadius, MIN_SPHERE_RS * uRs);

    // ---- 1. camera ray (eye-relative world space) --------------------------
    // Vanilla folds view bobbing into the projection matrix, translation
    // included, so the eye is not at the view-space origin. The ray is the
    // line through the pixel's near- and far-plane points, starting at the
    // true eye (uEyeOffset). Taking the near-plane point itself as the
    // direction, as if the eye were at the origin, swung rays by tens of
    // degrees with every step while walking: the bob offset (~0.1 block) is
    // larger than the near-plane distance (0.05 block).
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec4 nearH = uInvProjection * vec4(ndc, -1.0, 1.0);
    vec4 farH = uInvProjection * vec4(ndc, 1.0, 1.0);
    vec3 dirView = normalize(farH.xyz / farH.w - nearH.xyz / nearH.w);
    vec3 dir = normalize((uInvView * vec4(dirView, 0.0)).xyz);

    bool sky = depth >= SKY_DEPTH;
    float sceneDist = 1.0e30;
    if (!sky) {
        vec4 vp = uInvProjection * vec4(ndc, depth * 2.0 - 1.0, 1.0);
        sceneDist = length((uInvView * vec4(vp.xyz / vp.w, 0.0)).xyz - uEyeOffset);
    }
    float pxPerRad = 0.5 * uScreenSize.y * abs(uProjection[1][1]);

    // ---- 2. influence sphere; early outs ----------------------------------
    vec3 C = uBlackHolePos - uEyeOffset;           // hole relative to the eye
    float tca = dot(dir, C);
    vec3 perp = C - tca * dir;                    // impact vector, cancellation free
    float bImp = length(perp);                    // impact parameter, blocks
    vec3 eTow = perp / max(bImp, 1.0e-6);         // unit vector toward the hole, normal to dir
    float disc = sphereR * sphereR - bImp * bImp;
    float sq = sqrt(max(disc, 0.0));
    bool entersSphere = disc > 0.0 && tca + sq > 0.0;
    float tEntry = entersSphere ? max(tca - sq, 0.0) : 0.0;
    float mass = S * M;                           // signed mass for the weak-field bending

    // A white hole's blinding glow: light scattered around the core, by the
    // ray's closest approach to it; hidden behind nearer geometry.
    vec3 glow = vec3(0.0);
    if (white && tca > 0.0 && (sky || sceneDist > tca - 2.0 * M)) {
        float x = max(bImp / M - 1.7, 0.0);
        glow = GLOW_COLOR * (2.0 * exp(-x / 1.2) + 0.35 * exp(-x / 4.0) + 0.05 * exp(-x / 16.0));
    }
    bool glows = dot(glow, vec3(0.3333)) > 0.5 / 255.0;

    if (!entersSphere || sceneDist <= tEntry) {
        // The ray never reaches the strong field before it ends: weak-field
        // lensing only, skipped when the shift is below half a pixel.
        vec2 wl = weakDeflection(0.0, sky ? 1.0e20 : sceneDist, tca, bImp, mass);
        float shiftAngle = sky ? wl.x : wl.y / max(sceneDist, 1.0e-3);
        if (abs(shiftAngle) * pxPerRad < 0.5) {
            fragColor = glows ? vec4(toSrgb(clamp(acesFitted(glow) + toLinear(scene.rgb), 0.0, 1.0)), 1.0) : scene;
            return;
        }
        vec3 bent = sky ? dir * cos(wl.x) + eTow * sin(wl.x) : uEyeOffset + dir * sceneDist + eTow * wl.y;
        vec3 lensed = sampleScene(bent, sky ? 0.0 : 1.0);
        if (white) {
            lensed = mix(lensed, toLinear(scene.rgb), leavesScreen(bent, sky ? 0.0 : 1.0));
        }
        fragColor = vec4(toSrgb(clamp(acesFitted(glow) + lensed, 0.0, 1.0)), 1.0);
        return;
    }

    // Inside the sphere too, a ray that stays well clear of the strong field
    // (the disk, the photon orbits) and bends by under half a pixel is passed
    // through untouched. Re-integrating and resampling it would only blur the
    // image a little and make it shimmer as the camera moves. (Not with the
    // goggles on: their grid is drawn on every bent ray.)
    if (bImp > WEAK_FIELD_IMPACT_M * M && !(goggles && bImp < GRID_REACH * sphereR)) {
        vec2 wl = weakDeflection(0.0, sky ? 1.0e20 : sceneDist, tca, bImp, mass);
        float shiftAngle = sky ? wl.x : wl.y / max(sceneDist, 1.0e-3);
        if (abs(shiftAngle) * pxPerRad < 0.5) {
            fragColor = glows ? vec4(toSrgb(clamp(acesFitted(glow) + toLinear(scene.rgb), 0.0, 1.0)), 1.0) : scene;
            return;
        }
    }

    vec3 camRel = -C / M;                          // eye relative to the hole, units of M
    vec3 camBL = cartToBL(camRel, a);
    if (camBL.x <= rPlus) {
        // Inside a black hole's horizon: darkness. Inside a white hole's core: nothing but light.
        fragColor = white ? vec4(CORE_WHITE, 1.0) : vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // ---- 3. initial BL state ----------------------------------------------
    // Weak-field bending of the segment camera -> sphere entry.
    vec3 startPos = dir * tEntry;
    vec3 d0 = dir;
    if (tEntry > 0.0) {
        vec2 wl = weakDeflection(0.0, tEntry, tca, bImp, mass);
        startPos += eTow * wl.y;
        d0 = dir * cos(wl.x) + eTow * sin(wl.x);
    }
    vec3 p0 = (startPos - C) / M;

    // The BL axis (theta = 0, pi) is a coordinate singularity. A ray whose
    // orbital plane passes within ~1e-3 rad of the axis is rotated rigidly
    // about the hole centre (by at most that angle, sub-pixel) into the
    // nearest meridional plane; it then has lambda = 0 exactly and crosses
    // the axis smoothly (theta simply runs through 0 or pi). Every other ray
    // stays far enough from the axis for the adaptive step to resolve its
    // fast azimuthal swing.
    bool meridional = false;
    vec3 J = cross(p0, d0);                        // flat-space angular momentum
    float jLen = length(J);
    if (jLen <= 1.0e-6 * max(length(p0), 1.0e-6)) {
        meridional = true;                         // radial ray
    } else if (abs(J.y) < 1.0e-3 * jLen) {
        meridional = true;
        vec3 nOld = J / jLen;
        vec3 nNew = normalize(vec3(J.x, 0.0, J.z));
        vec3 k = cross(nOld, nNew);
        float sinA = length(k);
        if (sinA > 1.0e-9) {
            float cosA = dot(nOld, nNew);
            vec3 kh = k / sinA;
            p0 = p0 * cosA + cross(kh, p0) * sinA + kh * (dot(kh, p0) * (1.0 - cosA));
            d0 = d0 * cosA + cross(kh, d0) * sinA + kh * (dot(kh, d0) * (1.0 - cosA));
        }
    }

    vec3 bl0 = cartToBL(p0, a);
    float r = bl0.x;
    float th = bl0.y;
    float ph = bl0.z;
    float st = sin(th);
    float ct = cos(th);

    // Horizontal unit vector of the start meridian. On the axis itself the
    // meridian is chosen to contain the ray.
    float hxz = length(p0.xz);
    vec2 eH = vec2(0.0, 1.0);
    if (hxz > 1.0e-6 * max(length(p0), 1.0e-6)) {
        eH = p0.xz / hxz;
    } else {
        float dl = length(d0.xz);
        if (dl > 1.0e-6) {
            eH = d0.xz / dl;
        }
        ph = atan(eH.x, eH.y);
    }

    // World direction -> BL coordinate differentials (inverse Jacobian of the
    // mapping). With h = sqrt(r^2+a^2) sin(theta) the horizontal radius:
    //   dr     = sqrt(r^2+a^2) (r sin dh + sqrt(r^2+a^2) cos dy) / Sigma
    //   dtheta = (sqrt(r^2+a^2) cos dh - r sin dy) / Sigma
    //   dphi   = (d . e_phi) / h
    float r2a2 = r * r + a2;
    float sA = sqrt(r2a2);
    vec2 ePh = vec2(eH.y, -eH.x);                 // direction of increasing phi in (x, z)
    float dH = dot(d0.xz, eH);
    float dY = d0.y;
    float dP = meridional ? 0.0 : dot(d0.xz, ePh);
    float sig = r * r + a2 * ct * ct;
    float del = r * r - 2.0 * S * r + a2;
    float stS = max(st, 1.0e-6);
    float s2 = stS * stS;
    float dR = sA * (r * st * dH + sA * ct * dY) / sig;
    float dTh = (sA * ct * dH - r * st * dY) / sig;
    float dPh = dP / (sA * stS);

    // Measure that direction in the local ZAMO frame (orthonormal components
    // from the t = const spatial metric), which is well defined everywhere
    // outside the horizon, ergosphere included.
    float bigA = r2a2 * r2a2 - a2 * del * s2;
    float gpp = bigA * s2 / sig;
    float sgpp = sqrt(gpp);
    float alpha = sqrt(max(sig * del / bigA, 1.0e-12));
    float omega = 2.0 * S * a * r / bigA;
    vec3 n = normalize(vec3(sqrt(sig / max(del, 1.0e-6)) * dR, sqrt(sig) * dTh, sgpp * dPh));

    // The real photon travels toward the camera, local direction m = -n, with
    // future-directed momentum p = E_loc (e_t + m). Then
    //   E = -p_t = E_loc (alpha + omega sqrt(g_phiphi) m_phi),  L = p_phi = E_loc sqrt(g_phiphi) m_phi.
    // Normalise E = 1. We integrate k = -p (past directed, spatially along +n).
    float eDen = alpha - omega * sgpp * n.z;
    if (eDen <= 1.0e-4) {
        // Negative-energy photon (camera inside the ergosphere looking
        // retrograde): it cannot have arrived from outside.
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    float eLoc = 1.0 / eDen;
    float lam = meridional ? 0.0 : -n.z * sgpp * eLoc;      // lambda = L / E
    float u = 1.0 / r;
    // dr/dmu = Sigma k^r  ->  du/dmu = -(dr/dmu) / r^2
    float vu = -n.x * eLoc * sqrt(sig * max(del, 0.0)) * u * u;
    float vth = n.y * eLoc * sqrt(sig);                     // dtheta/dmu = Sigma k^theta
    // Carter constant per E^2: eta = p_theta^2 + cos^2 (lambda^2 / sin^2 - a^2).
    float eta = vth * vth + ct * ct * (lam * lam / s2 - a2);
    vec4 K = vec4(a, lam, eta, eta + (lam - a) * (lam - a));
    // Closest angular approach to the axis, sin(theta_min) ~ |lambda| / sqrt(eta + lambda^2 + a^2).
    float sMin = abs(lam) / sqrt(max(eta + lam * lam + a2, 1.0e-12));

    // Camera = ZAMO at its own position: E_cam = (1 - omega_cam lambda) / alpha_cam.
    vec2 zc = zamoAlphaOmega(camBL.x, camRel.y / camBL.x, a);
    float obsFactor = max(1.0 - zc.y * lam, 1.0e-3) / zc.x;

    // ---- disk / capture constants -----------------------------------------
    bool diskOn = uDiskEnabled != 0;
    float rIsco = iscoPrograde(a);
    // T(r) peaks at r = (49/36) r_isco; normalise to the peak inside the visible annulus.
    float rPk = clamp(1.3611 * rIsco, DISK_IN_M, DISK_OUT_M);
    float invPeak = 1.0 / max(ntProfile(rPk, rIsco), 1.0e-6);
    float rPhPro = 2.0 * (1.0 + cos(2.0 / 3.0 * acos(-a)));   // prograde equatorial photon orbit
    float rPhRetro = 2.0 * (1.0 + cos(2.0 / 3.0 * acos(a)));  // retrograde equatorial photon orbit
    // An ingoing ray inside the innermost photon orbit can no longer turn
    // around, so it is captured long before the (stiff) near-horizon region.
    // A white hole has no photon orbits: nothing is captured, nothing lingers.
    float rCap = white ? 0.0 : max(1.01 * rPlus, 0.97 * rPhPro);
    float uPlus = 1.0 / rPlus;
    float rEsc = sphereR / M;
    float rLinger = white ? 0.0 : 1.25 * rPhRetro;

    // ---- 4. adaptive RK4 integration --------------------------------------
    vec3 X = p0;                     // Cartesian position, units of M
    vec3 lastSeg = dir;
    float travelled = tEntry;        // path length from the camera, blocks
    vec3 diskAcc = vec3(0.0);        // accumulated disk radiance (HDR, linear)
    float trans = 1.0;               // transmittance through the disk so far
    int outcome = 0;                 // 0 running, 1 captured / opaque, 2 escaped, 3 hit scene, 4 white-hole core
    vec3 hitPos = vec3(0.0);
    int linger = 0;

    // Point lights, hole-relative in units of M; lightBest keeps each light's
    // strongest glow along the path (closest approach of the bent ray).
    vec3 lightM[MAX_LIGHTS];
    float lightBest[MAX_LIGHTS];
    int lightCount = clamp(uLightCount, 0, MAX_LIGHTS);
    for (int j = 0; j < MAX_LIGHTS; ++j) {
        lightM[j] = (uLights[j].xyz - uBlackHolePos) / M;
        lightBest[j] = 0.0;
    }

    for (int i = 0; i < MAX_STEPS; ++i) {
        vec3 k1 = geodesicRHS(u, st, ct, K);

        // Step size: Cartesian displacement ~ f * r per step (f grows slowly
        // with r where the field is weak), at most 0.15 rad of "kinematic"
        // azimuth (resolves passages near the axis), at most 0.5 rad of
        // frame-dragging azimuth, and never more than half-way to the horizon.
        float stC2 = max(st * st, 1.0e-12);
        float phiKin = abs(lam) / stC2;
        float phiDrag = abs(k1.z + lam / stC2);
        float f = clamp(0.0045 * r + 0.03, 0.04, 0.18);
        float vRel = vu / u;                                   // = -(dr/dmu) / r
        float speed = sqrt(vRel * vRel + vth * vth + st * st * phiKin * phiKin) + 1.0e-6;
        float h = f / speed;
        h = min(h, 0.15 / (phiKin + 1.0e-6));
        h = min(h, 0.5 / (phiDrag + 1.0e-6));
        if (lam != 0.0) {
            // Near the axis move theta by at most a quarter of the angular
            // distance to it, so the centrifugal barrier is resolved.
            h = min(h, 0.25 * max(abs(st), sMin) / (abs(vth) + 1.0e-6));
        }
        if (vu > 0.0) {                                        // moving inward
            h = min(h, 0.5 * (uPlus - u) / vu);
        }
        float hh = 0.5 * h;

        float uB = u + hh * vu;
        float tB = th + hh * vth;
        float vuB = vu + hh * k1.x;
        float vtB = vth + hh * k1.y;
        vec3 k2 = geodesicRHS(uB, sin(tB), cos(tB), K);

        float uC = u + hh * vuB;
        float tC = th + hh * vtB;
        float vuC = vu + hh * k2.x;
        float vtC = vth + hh * k2.y;
        vec3 k3 = geodesicRHS(uC, sin(tC), cos(tC), K);

        float uD = u + h * vuC;
        float tD4 = th + h * vtC;
        float vuD = vu + h * k3.x;
        float vtD = vth + h * k3.y;
        vec3 k4 = geodesicRHS(uD, sin(tD4), cos(tD4), K);

        float h6 = h / 6.0;
        float uN = max(u + h6 * (vu + 2.0 * vuB + 2.0 * vuC + vuD), 1.0e-6);
        float thN = th + h6 * (vth + 2.0 * vtB + 2.0 * vtC + vtD);
        float vuN = vu + h6 * (k1.x + 2.0 * k2.x + 2.0 * k3.x + k4.x);
        float vtN = vth + h6 * (k1.y + 2.0 * k2.y + 2.0 * k3.y + k4.y);
        float phN = ph + h6 * (k1.z + 2.0 * k2.z + 2.0 * k3.z + k4.z);
        float rN = 1.0 / uN;

        float stN = sin(thN);
        float ctN = cos(thN);
        vec3 XN = blToCart(rN, stN, ctN, phN, a);
        vec3 seg = XN - X;
        float segLen = length(seg);
        float segBlocks = segLen * M;

        // Depth: the ray reaches scene geometry once it has travelled the
        // pixel's scene distance (exact while the path is still straight).
        float tScene = 2.0;
        if (!sky && travelled + segBlocks >= sceneDist) {
            tScene = clamp((sceneDist - travelled) / max(segBlocks, 1.0e-6), 0.0, 1.0);
        }

        // Lensed images of the point lights: Gaussian glow in the ray's closest
        // approach to each light on this chord (up to any scene hit), weighted
        // by how far the ray has been bent and by the disk in front of it.
        if (lightCount > 0 && segLen > 1.0e-7) {
            float bendW = smoothstep(LIGHT_BEND_MIN, LIGHT_BEND_FULL, 1.0 - dot(seg / segLen, dir));
            if (bendW > 0.0) {
                float tEnd = min(tScene, 1.0);
                float segLen2 = segLen * segLen;
                for (int j = 0; j < MAX_LIGHTS; ++j) {
                    if (j >= lightCount) {
                        break;
                    }
                    vec3 rel = lightM[j] - X;
                    float tl = clamp(dot(rel, seg) / segLen2, 0.0, tEnd);
                    vec3 dv = (rel - tl * seg) * M;
                    float sigma = max(uLights[j].w, 0.05);
                    lightBest[j] = max(lightBest[j], bendW * trans * exp(-dot(dv, dv) / (sigma * sigma)));
                }
            }
        }

        // Equatorial crossing (y = r cos(theta) changes sign) -> thin disk.
        if (diskOn && X.y * XN.y < 0.0) {
            float tDisk = X.y / (X.y - XN.y);
            if (tDisk < tScene) {
                float rHit = mix(r, rN, tDisk);
                if (rHit > DISK_IN_M && rHit < DISK_OUT_M) {
                    float phHit = mix(ph, phN, tDisk);
                    float mu = abs(seg.y) / max(segLen, 1.0e-6);
                    vec4 em = shadeDisk(rHit, phHit, mu, lam, obsFactor, a, rIsco, invPeak);
                    diskAcc += trans * em.a * em.rgb;
                    trans *= 1.0 - em.a;
                }
            }
        }

        if (tScene <= 1.0) {
            outcome = 3;
            hitPos = uEyeOffset + C + mix(X, XN, tScene) * M;   // camera-relative, for sampleScene
            break;
        }

        travelled += segBlocks;
        u = uN;
        r = rN;
        th = thN;
        ph = phN;
        vu = vuN;
        vth = vtN;
        st = stN;
        ct = ctN;
        X = XN;
        if (segLen > 1.0e-7) {
            lastSeg = seg;
        }

        if (trans < 0.004) {                                   // disk is opaque here
            outcome = 1;
            break;
        }
        if (white) {
            if (r < rPlus) {                                   // struck the blinding core
                outcome = 4;
                break;
            }
        } else if (r < 1.01 * rPlus || (r < rCap && vu > 0.0)) { // captured by the hole
            outcome = 1;
            break;
        }
        if (dot(X, X) >= rEsc * rEsc && dot(X, seg) > 0.0) {   // left the sphere outward
            outcome = 2;
            break;
        }
        if (r < rLinger) {
            linger++;
        }
    }

    if (outcome == 0) {
        // Step budget exhausted: near the hole -> treat as captured (a white
        // hole: as having reached its core), otherwise continue along the last
        // direction.
        outcome = white ? (r < 2.0 * rPlus ? 4 : 2) : ((r < 2.0 * rPhRetro) ? 1 : 2);
    }

    // ---- 5. lensed background ---------------------------------------------
    vec3 bg = vec3(0.0);
    vec3 overlay = vec3(0.0);                          // goggles: grid and photon ring, HDR
    float lingerFrac = clamp(float(linger - 8) / 60.0, 0.0, 1.0);
    if (outcome == 2) {
        // Leave the sphere along the last chord, adding the weak-field bending
        // of the remaining path (units of M here: mass = S).
        vec3 escDir = normalize(lastSeg);
        float sE = dot(X, escDir);                     // closest approach of the exit line at t = -sE
        vec3 perpE = X - sE * escDir;
        float bE = length(perpE);
        vec3 towE = -perpE / max(bE, 1.0e-6);
        float remain = max(sceneDist - travelled, 0.0);  // blocks to the scene geometry
        vec2 wl = weakDeflection(0.0, sky ? 1.0e20 : remain / M, -sE, bE, S);
        if (sky) {
            vec3 farDir = escDir * cos(wl.x) + towE * sin(wl.x);
            bg = sampleScene(farDir, 0.0);             // point at infinity
            if (white) {
                bg = mix(bg, toLinear(scene.rgb), leavesScreen(farDir, 0.0));
            }
            if (goggles) {
                float fade = 1.0 - smoothstep(0.3 * sphereR, GRID_REACH * sphereR, bImp);
                overlay += GRID_COLOR * (0.55 * fade * celestialGrid(farDir, pxPerRad));
            }
        } else {
            vec3 farPoint = uEyeOffset + C + X * M + escDir * remain + towE * (wl.y * M);
            bg = sampleScene(farPoint, 1.0);
            if (white) {
                bg = mix(bg, toLinear(scene.rgb), leavesScreen(farPoint, 1.0));
            }
        }
        // Rays that wound around the photon orbit stack demagnified images of
        // the sky into the thin photon ring; brighten them slightly (and with
        // the goggles on, outline the ring in gold).
        bg *= 1.0 + 0.35 * lingerFrac;
        if (goggles && !white) {
            overlay += RING_COLOR * (0.9 * lingerFrac);
        }
    } else if (outcome == 3) {
        bg = sampleScene(hitPos, 1.0);
    } else if (outcome == 4) {
        // The core: white-hot at the centre of its image, gold toward the limb.
        float limb = clamp(bImp / (1.45 * M), 0.0, 1.0);
        diskAcc += trans * mix(CORE_WHITE, CORE_GOLD, smoothstep(0.35, 1.0, limb)) * CORE_INTENSITY;
    }
    // ---- 6. tone map and composite ----------------------------------------
    vec3 lightGlow = vec3(0.0);
    for (int j = 0; j < MAX_LIGHTS; ++j) {
        if (j >= lightCount) {
            break;
        }
        lightGlow += lightBest[j] * uLightColors[j];
    }
    vec3 color = acesFitted(diskAcc + lightGlow + glow + overlay) + trans * bg;
    color = toSrgb(clamp(color, 0.0, 1.0));
    // Dither only where disk light was added, to break up 8-bit banding.
    color += (hash12(gl_FragCoord.xy) - 0.5) * (1.0 - trans) / 255.0;
    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
