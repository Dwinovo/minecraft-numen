#version 150

// Signed-distance-field rounded rectangle (standard sdRoundedBox by Inigo Quilez).
// SDF params arrive as flat varyings (no custom uniforms on 1.21.6+ pipelines).

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
};

in vec4 vertexColor;
in vec2 vLocal;
flat in vec2 vHalf;
flat in float vRadius;

out vec4 fragColor;

void main() {
    vec2 q = abs(vLocal) - vHalf + vRadius;
    float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - vRadius;

    float aa = fwidth(dist);
    float alpha = 1.0 - smoothstep(-aa, 0.0, dist);

    vec4 color = vertexColor * ColorModulator;
    color.a *= alpha;
    if (color.a < 0.002) {
        discard;
    }
    fragColor = color;
}
