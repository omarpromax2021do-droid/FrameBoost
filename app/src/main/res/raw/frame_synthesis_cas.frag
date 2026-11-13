#version 310 es
precision highp float;

in vec2 vUv;
uniform sampler2D uPrevTex;
uniform sampler2D uCurrTex;
uniform sampler2D uMotionTex;
uniform vec2 uTexelSize;
uniform vec2 uMotionTexelSize;
uniform float uInterpolationAlpha;
uniform float uCasSharpness;
uniform int uHudMaskEnabled;
uniform vec4 uHudProtectedRects[4];

out vec4 fragColor;

bool isInsideProtectedHud(vec2 uv) {
    if (uHudMaskEnabled == 0) {
        return false;
    }
    for (int i = 0; i < 4; i++) {
        vec4 r = uHudProtectedRects[i];
        if (uv.x >= r.x && uv.x <= r.z && uv.y >= r.y && uv.y <= r.w) {
            return true;
        }
    }
    return false;
}

void main() {
    vec3 currRaw = texture(uCurrTex, vUv).rgb;
    vec3 baseColor = currRaw;
    vec2 casSampleUv = vUv;

    bool protectedZone = isInsideProtectedHud(vUv);
    float effectiveAlpha = protectedZone ? 0.0 : clamp(uInterpolationAlpha, 0.0, 1.0);

    if (effectiveAlpha > 0.01) {
        vec4 motionCenter = texture(uMotionTex, vUv);
        vec2 velocity = (motionCenter.rg - 0.5) / 12.0;

        // Motion divergence guard across neighboring motion texels
        vec2 mR = texture(uMotionTex, clamp(vUv + vec2(uMotionTexelSize.x, 0.0), 0.0, 1.0)).rg;
        vec2 mL = texture(uMotionTex, clamp(vUv - vec2(uMotionTexelSize.x, 0.0), 0.0, 1.0)).rg;
        vec2 mU = texture(uMotionTex, clamp(vUv + vec2(0.0, uMotionTexelSize.y), 0.0, 1.0)).rg;
        vec2 mD = texture(uMotionTex, clamp(vUv - vec2(0.0, uMotionTexelSize.y), 0.0, 1.0)).rg;
        float divergence = length(mR - mL) + length(mU - mD);

        vec2 prevUv = clamp(vUv + 0.5 * velocity, vec2(0.0), vec2(1.0));
        vec2 currUv = clamp(vUv - 0.5 * velocity, vec2(0.0), vec2(1.0));
        vec3 prevWarp = texture(uPrevTex, prevUv).rgb;
        vec3 currWarp = texture(uCurrTex, currUv).rgb;

        float sad = dot(abs(prevWarp - currWarp), vec3(0.3333));
        if (sad <= 0.18 && divergence <= 0.35 && motionCenter.b <= 0.72) {
            vec3 synth = mix(prevWarp, currWarp, 0.5);
            baseColor = mix(currRaw, synth, effectiveAlpha);
            casSampleUv = currUv;
        }
    }

    // Fused 5-tap AMD Contrast Adaptive Sharpening (CAS)
    float sharpness = clamp(uCasSharpness, 0.0, 1.0);
    if (sharpness > 0.005) {
        vec3 n = texture(uCurrTex, clamp(casSampleUv + vec2(0.0, -uTexelSize.y), 0.0, 1.0)).rgb;
        vec3 s = texture(uCurrTex, clamp(casSampleUv + vec2(0.0, uTexelSize.y), 0.0, 1.0)).rgb;
        vec3 e = texture(uCurrTex, clamp(casSampleUv + vec2(uTexelSize.x, 0.0), 0.0, 1.0)).rgb;
        vec3 w = texture(uCurrTex, clamp(casSampleUv - vec2(uTexelSize.x, 0.0), 0.0, 1.0)).rgb;

        vec3 mn = min(baseColor, min(min(n, s), min(e, w)));
        vec3 mx = max(baseColor, max(max(n, s), max(e, w)));
        vec3 amp = clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0);
        float peak = -mix(0.0, 0.20, sharpness);
        vec3 weight = peak * sqrt(amp);
        baseColor = clamp((baseColor + (n + s + e + w) * weight) / (1.0 + 4.0 * weight), 0.0, 1.0);
    }

    fragColor = vec4(baseColor, 1.0);
}
