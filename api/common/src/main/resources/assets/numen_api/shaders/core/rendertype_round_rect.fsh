#version 150

// Signed-distance-field rounded rectangle (standard sdRoundedBox by Inigo Quilez).
// u_Rect = (centerX, centerY, halfWidth, halfHeight) in the same space as Position.

uniform vec4 u_Rect;
uniform float u_Radius;
uniform vec4 ColorModulator;

in vec2 guiPos;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec2 p = guiPos - u_Rect.xy;
    vec2 q = abs(p) - u_Rect.zw + u_Radius;
    float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - u_Radius;

    float aa = fwidth(dist);
    float alpha = 1.0 - smoothstep(-aa, 0.0, dist);

    vec4 color = vertexColor * ColorModulator;
    color.a *= alpha;
    if (color.a < 0.002) {
        discard;
    }
    fragColor = color;
}
