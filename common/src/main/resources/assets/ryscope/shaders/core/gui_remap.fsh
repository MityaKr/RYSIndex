#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform vec4 C0;
uniform vec4 C1;
uniform vec4 C2;
uniform vec4 C3;
uniform vec4 C4;

in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec4 c = texture(Sampler0, texCoord0);
    if (c.a < 0.01) discard;
    float top = max(c.r, max(c.g, c.b));
    float low = min(c.r, min(c.g, c.b));
    // серая текстура, окрашенная тинтом (вода, жидкости, цветные шкалы), цветная: её не перекрашиваем
    vec4 tint = ColorModulator;
    float tintTop = max(tint.r, max(tint.g, tint.b));
    float tintLow = min(tint.r, min(tint.g, tint.b));
    if (top - low < 0.09 && tintTop - tintLow < 0.09) {
        float l = (c.r + c.g + c.b) / 3.0;
        vec4 m;
        if (l < 0.216) m = mix(C0, C1, l / 0.216);
        else if (l < 0.545) m = mix(C1, C2, (l - 0.216) / 0.329);
        else if (l < 0.776) m = mix(C2, C3, (l - 0.545) / 0.231);
        else m = mix(C3, C4, (l - 0.776) / 0.224);
        c = vec4(m.rgb, c.a * m.a);
    }
    fragColor = c * tint;
}
