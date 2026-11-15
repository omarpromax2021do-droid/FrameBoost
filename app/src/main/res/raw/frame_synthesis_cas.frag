#version 310 es
precision highp float;

in vec2 vUv;
uniform sampler2D uPrevTex;
uniform sampler2D uCurrTex;
uniform sampler2D uMotionTex;
uniform vec2 uTexelSize;
uniform vec2 uOutputTexelSize;
uniform vec2 uMotionTexelSize;
uniform float uInterpolationAlpha;
uniform float uCasSharpness;
uniform int uFsrUpscaleActive;
uniform int uHudMaskEnabled;
uniform vec4 uHudProtectedRects[4];

out vec4 fragColor;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

float insideProtectedRect(vec2 uv, vec4 rect) {
    vec2 geMin = step(rect.xy, uv);
    vec2 leMax = step(uv, rect.zw);
    return geMin.x * geMin.y * leMax.x * leMax.y;
}

float computeHudMask(vec2 uv) {
    float hit = 0.0;
    hit = max(hit, insideProtectedRect(uv, uHudProtectedRects[0]));
    hit = max(hit, insideProtectedRect(uv, uHudProtectedRects[1]));
    hit = max(hit, insideProtectedRect(uv, uHudProtectedRects[2]));
    hit = max(hit, insideProtectedRect(uv, uHudProtectedRects[3]));
    return hit * float(uHudMaskEnabled);
}

void main() {
    vec4 currSample = texture(uCurrTex, vUv);
    vec3 currRaw = currSample.rgb;
    vec3 prevRaw = texture(uPrevTex, vUv).rgb;

    vec3 rawN = texture(uCurrTex, clamp(vUv + vec2(0.0, -uTexelSize.y), 0.0, 1.0)).rgb;
    vec3 rawS = texture(uCurrTex, clamp(vUv + vec2(0.0, uTexelSize.y), 0.0, 1.0)).rgb;
    vec3 rawE = texture(uCurrTex, clamp(vUv + vec2(uTexelSize.x, 0.0), 0.0, 1.0)).rgb;
    vec3 rawW = texture(uCurrTex, clamp(vUv - vec2(uTexelSize.x, 0.0), 0.0, 1.0)).rgb;

    float lumaCurr = luma(currRaw);
    float lumaPrev = luma(prevRaw);
    float gradX = luma(rawE) - luma(rawW);
    float gradY = luma(rawS) - luma(rawN);
    float localEdgeGrad = abs(gradX) + abs(gradY);

    vec4 motionCenter = texture(uMotionTex, vUv);
    vec2 velocity = (motionCenter.rg - 0.5) / 12.0;

    vec2 mR = texture(uMotionTex, clamp(vUv + vec2(uMotionTexelSize.x, 0.0), 0.0, 1.0)).rg;
    vec2 mL = texture(uMotionTex, clamp(vUv - vec2(uMotionTexelSize.x, 0.0), 0.0, 1.0)).rg;
    vec2 mU = texture(uMotionTex, clamp(vUv + vec2(0.0, uMotionTexelSize.y), 0.0, 1.0)).rg;
    vec2 mD = texture(uMotionTex, clamp(vUv - vec2(0.0, uMotionTexelSize.y), 0.0, 1.0)).rg;
    float divergence = length(mR - mL) + length(mU - mD);

    float neighborMotionMag =
        length((mR - 0.5) / 12.0) +
        length((mL - 0.5) / 12.0) +
        length((mU - 0.5) / 12.0) +
        length((mD - 0.5) / 12.0);

    float isStaticPixel = 1.0 - step(0.012, abs(lumaCurr - lumaPrev));
    float isHighContrastEdge = step(0.10, localEdgeGrad);
    float hasNeighborMotion = step(0.001, neighborMotionMag);
    float autoStaticUiMask = isStaticPixel * isHighContrastEdge * hasNeighborMotion;
    float guardMask = max(computeHudMask(vUv), autoStaticUiMask);

    float effectiveAlpha = clamp(uInterpolationAlpha, 0.0, 1.0) * (1.0 - guardMask);

    vec2 prevUv = clamp(vUv + 0.5 * velocity, vec2(0.0), vec2(1.0));
    vec2 currUv = clamp(vUv - 0.5 * velocity, vec2(0.0), vec2(1.0));
    vec3 prevWarp = texture(uPrevTex, prevUv).rgb;
    vec3 currWarp = texture(uCurrTex, currUv).rgb;

    float sad = dot(abs(prevWarp - currWarp), vec3(0.3333));
    float motionValid = step(sad, 0.18) * step(divergence, 0.35) * step(motionCenter.b, 0.72);
    float synthWeight = effectiveAlpha * motionValid;

    vec3 synth = mix(prevWarp, currWarp, 0.5);
    vec3 baseColor = mix(currRaw, synth, synthWeight);
    vec2 casSampleUv = mix(vUv, currUv, synthWeight);

    vec3 n = texture(uCurrTex, clamp(casSampleUv + vec2(0.0, -uTexelSize.y), 0.0, 1.0)).rgb;
    vec3 s = texture(uCurrTex, clamp(casSampleUv + vec2(0.0, uTexelSize.y), 0.0, 1.0)).rgb;
    vec3 e = texture(uCurrTex, clamp(casSampleUv + vec2(uTexelSize.x, 0.0), 0.0, 1.0)).rgb;
    vec3 w = texture(uCurrTex, clamp(casSampleUv - vec2(uTexelSize.x, 0.0), 0.0, 1.0)).rgb;

    // FSR 1.0 edge-adaptive directional reconstruction when upscaling 75% capture to 100% panel
    vec2 edgeDir = vec2(-gradY, gradX);
    float edgeDirLen = max(length(edgeDir), 0.0001);
    vec2 tangentStep = (edgeDir / edgeDirLen) * (uOutputTexelSize * 0.75);
    vec3 dirPos = texture(uCurrTex, clamp(casSampleUv + tangentStep, 0.0, 1.0)).rgb;
    vec3 dirNeg = texture(uCurrTex, clamp(casSampleUv - tangentStep, 0.0, 1.0)).rgb;
    vec3 fsrDirectional = mix(baseColor, 0.5 * baseColor + 0.25 * (dirPos + dirNeg), clamp(localEdgeGrad * 2.0, 0.0, 0.45));
    vec3 fsrColor = mix(baseColor, fsrDirectional, float(uFsrUpscaleActive));

    // Fused 5-tap AMD CAS with internal weight capped to 0.35 (peak <= 0.175) to avoid ringing/halos
    vec3 mn = min(fsrColor, min(min(n, s), min(e, w)));
    vec3 mx = max(fsrColor, max(max(n, s), max(e, w)));
    vec3 amp = clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0);
    float peak = -clamp(uCasSharpness, 0.0, 0.35) * 0.5;
    vec3 weight = peak * sqrt(amp);
    vec3 sharpened = (fsrColor + (n + s + e + w) * weight) / max(1.0 + 4.0 * weight, vec3(0.25));
    vec3 finalColor = clamp(sharpened, mn, mx);

    fragColor = vec4(finalColor, currSample.a);
}
