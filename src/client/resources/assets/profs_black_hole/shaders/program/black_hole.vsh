#version 150

// Black Hole - full-screen pass vertex shader.
//
// Emits one triangle that covers the whole viewport, generated purely from
// gl_VertexID (no vertex attributes). Draw with an empty VAO bound:
//     glDrawArrays(GL_TRIANGLES, 0, 3)
//
//   id 0 -> clip (-1,-1)  uv (0,0)
//   id 1 -> clip ( 3,-1)  uv (2,0)
//   id 2 -> clip (-1, 3)  uv (0,2)
//
// The visible part of the triangle interpolates texCoord over [0,1]^2.

out vec2 texCoord;

void main() {
    vec2 uv = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    texCoord = uv;
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
