#version 310 es
precision highp float;

in vec2 vUv;
uniform sampler2D uPrevTex;
uniform sampler2D uCurrTex;
uniform vec2 uTexelSize;

out vec4 fragColor;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    float currCenter = luma(texture(uCurrTex, vUv).rgb);

    // 5-point diamond coarse search
    vec2 stepUv = uTexelSize * 3.0;
    vec2 offsets[5];
    offsets[0] = vec2(0.0, 0.0);
    offsets[1] = vec2(stepUv.x, 0.0);
    offsets[2] = vec2(-stepUv.x, 0.0);
    offsets[3] = vec2(0.0, stepUv.y);
    offsets[4] = vec2(0.0, -stepUv.y);

    vec2 bestOffset = vec2(0.0);
    float bestDiff = 10.0;
    for (int i = 0; i < 5; i++) {
        vec2 sampleUv = clamp(vUv + offsets[i], vec2(0.0), vec2(1.0));
        float prevL = luma(texture(uPrevTex, sampleUv).rgb);
        float diff = abs(currCenter - prevL);
        if (diff < bestDiff) {
            bestDiff = diff;
            bestOffset = offsets[i];
        }
    }

    // Fast Lucas-Kanade spatial gradient refinement
    float gx = luma(texture(uCurrTex, clamp(vUv + vec2(uTexelSize.x, 0.0), 0.0, 1.0)).rgb) -
               luma(texture(uCurrTex, clamp(vUv - vec2(uTexelSize.x, 0.0), 0.0, 1.0)).rgb);
    float gy = luma(texture(uCurrTex, clamp(vUv + vec2(0.0, uTexelSize.y), 0.0, 1.0)).rgb) -
               luma(texture(uCurrTex, clamp(vUv - vec2(0.0, uTexelSize.y), 0.0, 1.0)).rgb);
    float prevRef = luma(texture(uPrevTex, clamp(vUv + bestOffset, 0.0, 1.0)).rgb);
    float dt = currCenter - prevRef;

    float denom = gx * gx + gy * gy + 0.0025;
    vec2 gradStep = vec2(gx, gy) * (-dt / denom) * uTexelSize * 2.0;
    vec2 velocity = clamp(bestOffset + gradStep, vec2(-0.04), vec2(0.04));

    // Bidirectional SAD confidence & local edge magnitude
    float pWarp = luma(texture(uPrevTex, clamp(vUv + 0.5 * velocity, 0.0, 1.0)).rgb);
    float cWarp = luma(texture(uCurrTex, clamp(vUv - 0.5 * velocity, 0.0, 1.0)).rgb);
    float sad = abs(pWarp - cWarp);
    float edgeMag = sqrt(gx * gx + gy * gy);

    fragColor = vec4(
        clamp(velocity.x * 12.0 + 0.5, 0.0, 1.0),
        clamp(velocity.y * 12.0 + 0.5, 0.0, 1.0),
        clamp(sad * 4.0, 0.0, 1.0),
        clamp(edgeMag * 2.0, 0.0, 1.0)
    );
}
