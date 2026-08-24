#version 150

// 1.21.6+ UBO convention (mirrors vanilla dynamictransforms/projection includes;
// copied inline like vanilla core/gui does).
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec4 Color;
in vec2 UV0;   // local offset from rect centre, gui px (interpolates linearly)
in ivec2 UV1;  // (halfWidth, halfHeight) * 16 — same on all 4 vertices
in ivec2 UV2;  // (radius * 16, unused)

out vec4 vertexColor;
out vec2 vLocal;
flat out vec2 vHalf;
flat out float vRadius;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    vLocal = UV0;
    vHalf = vec2(UV1) / 16.0;
    vRadius = float(UV2.x) / 16.0;
}
