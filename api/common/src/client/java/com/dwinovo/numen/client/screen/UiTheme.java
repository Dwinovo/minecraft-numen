package com.dwinovo.numen.client.screen;

import java.util.List;

/**
 * A BlockFrame (neobrutalist) colour theme for the Numen GUI — thick borders +
 * hard offset shadows + square corners + a dot-grid ground, drawn procedurally so
 * the whole palette swaps instantly. Five presets, picked by the player in Settings.
 *
 * <p>Design rule: a FILLED background (with border + hard shadow) marks something
 * <em>clickable</em> — buttons, the input field, tabs. Status displays (tool calls,
 * plan items, messages) are plain text with a coloured glyph, never a filled pill.
 *
 * <p>All colours are ARGB. Grounds are mid-tone (never stark white); borders double
 * as the hard-shadow colour (warm-dark for the cozy themes, near-black for the MC ones).
 */
public record UiTheme(
        String id, String label,
        int ground, int dot, int band, int onBand, int border,
        int text, int textDim, int field,
        int cta, int onCta,
        int reply, int ok, int run, int fail) {

    // grounds + bands sourced from real MC palettes / cozy references (see tools/ui-textures).
    public static final UiTheme VANILLA = new UiTheme("vanilla", "Vanilla",
            0xFFC6C6C6, 0x18000000, 0xFF565656, 0xFFFFFFFF, 0xFF161616,
            0xFF16181B, 0xFF55585C, 0xFFB3B3B3,
            0xFFFFAA00, 0xFF111111,
            0xFF1C8E9C, 0xFF2E9E3A, 0xFFB07A12, 0xFFC0392B);

    public static final UiTheme FORMAT = new UiTheme("format", "Classic",
            0xFFAAAAAA, 0x1A000000, 0xFF00AAAA, 0xFF06363A, 0xFF000000,
            0xFF121212, 0xFF4A4A4A, 0xFF9A9A9A,
            0xFFFFAA00, 0xFF111111,
            0xFF128A98, 0xFF2A9E36, 0xFFA8730E, 0xFFC0392B);

    public static final UiTheme DIAMOND = new UiTheme("diamond", "Diamond",
            0xFFA9B4BC, 0x1A0B1014, 0xFF2C7E86, 0xFFEAFBFF, 0xFF13171B,
            0xFF15191D, 0xFF4C545C, 0xFF97A4AD,
            0xFFFFAA00, 0xFF111111,
            0xFF1C7C86, 0xFF2E8E3E, 0xFFA8730E, 0xFFB23A2C);

    public static final UiTheme COZY = new UiTheme("cozy", "Cozy",
            0xFFEAD3A8, 0x22332A18, 0xFF8B6D9C, 0xFFFBF5EF, 0xFF2C2540,
            0xFF2C2540, 0xFF6A6276, 0xFFDFC79A,
            0xFFE0A53A, 0xFF2C2540,
            0xFF5B7BA6, 0xFF5E8C46, 0xFFA8741E, 0xFFB05A50);

    public static final UiTheme WARM = new UiTheme("warm", "Cottage",
            0xFFCBA87B, 0x2A2A2012, 0xFF6E8F66, 0xFFF6EFD9, 0xFF352818,
            0xFF352818, 0xFF6E5E48, 0xFFBE9C70,
            0xFFE3A23A, 0xFF2A2012,
            0xFF4E7480, 0xFF577E3C, 0xFFA8731E, 0xFFA8533A);

    // 标准双主题:亮·黑字 / 暗·白字——干净高对比,对齐新版设置面板的观感;
    // 上面五个带色相的老主题是"风味款",玩家自选。
    public static final UiTheme LIGHT = new UiTheme("light", "Light",
            0xFFEDEDF0, 0x12000000, 0xFF2A2A32, 0xFFF5F5F8, 0xFF3A3A44,
            0xFF17171C, 0xFF4A4A55, 0xFFDFDFE4,
            0xFF2F6FE0, 0xFFFFFFFF,
            0xFF1C7C9C, 0xFF2E8E3E, 0xFFA8730E, 0xFFC0392B);

    public static final UiTheme DARK = new UiTheme("dark", "Dark",
            0xFF1E1F26, 0x30000000, 0xFF15161B, 0xFFEDEDEF, 0xFF0C0C10,
            0xFFF0F1F4, 0xFFA9ADBB, 0xFF2A2C34,
            0xFF5B9CFF, 0xFF10131A,
            0xFF3FB6C6, 0xFF57AB5A, 0xFFC79432, 0xFFE5534B);

    public static final List<UiTheme> ALL = List.of(LIGHT, DARK, VANILLA, FORMAT, DIAMOND, COZY, WARM);

    private static UiTheme current = LIGHT;

    public static UiTheme current() { return current; }

    public static void set(String id) {
        for (UiTheme t : ALL) {
            if (t.id().equals(id)) { current = t; return; }
        }
        current = LIGHT;
    }

    // ---- derived tints: computed from the base palette so every preset gets the full
    // chat-app family (bubbles/chips/cards) without hand-tuning 5×10 extra colours.
    // The mix ratios were fitted to reproduce WARM's original hand-picked values. ----

    /** 暗色地面判定:派生公式原按亮地面拟合,暗主题下混色方向要反过来。 */
    private boolean isDark() {
        int r = (ground >> 16) & 0xFF, g = (ground >> 8) & 0xFF, b = ground & 0xFF;
        return (r * 3 + g * 6 + b) / 10 < 96;
    }

    /** Faintest text tier (placeholders, pending items, empty-state hints). */
    public int faint() { return mix(textDim, field, 0.5f); }
    /** Muted on-band text (persona name after the companion name in the header). */
    public int onBandFaint() { return mix(onBand, band, 0.5f); }
    /** Companion bubble: pale card lifting off the ground. */
    public int aiFill() { return isDark() ? mix(ground, 0xFFFFFFFF, 0.12f) : mix(ground, 0xFFFFFFFF, 0.7f); }
    public int aiBorder() { return isDark() ? mix(ground, 0xFFFFFFFF, 0.25f) : mix(ground, border, 0.22f); }
    /** Content surface: a page one step lighter than the ground — text sits on THIS,
     *  never on the raw dotted ground (the dots stay as ambient frame texture). */
    public int surface() { return isDark() ? mix(ground, 0xFFFFFFFF, 0.06f) : mix(ground, 0xFFFFFFFF, 0.38f); }
    public int surfaceBorder() { return isDark() ? mix(ground, 0xFFFFFFFF, 0.18f) : mix(ground, border, 0.14f); }
    /** Owner bubble: the CTA warmth, desaturated for body text. */
    public int ownFill() { return isDark() ? mix(cta, 0xFF000000, 0.35f) : mix(cta, 0xFFFFFFFF, 0.4f); }
    public int ownBorder() { return isDark() ? mix(cta, 0xFF000000, 0.55f) : mix(cta, border, 0.15f); }
    /** Queued prompt: a half-present owner bubble. */
    public int queuedFill() { return (ownFill() & 0xFFFFFF) | 0x80000000; }
    public int queuedBorder() { return (ownBorder() & 0xFFFFFF) | 0x80000000; }
    /** Tool chip: translucent wash — status, not a message(暗主题下用亮色洗)。 */
    public int chipFill() { return isDark() ? 0x28FFFFFF : (border & 0xFFFFFF) | 0x22000000; }
    /** Sidebar card (plan panel): a fainter wash of the same tone. */
    public int cardFill() { return isDark() ? 0x14FFFFFF : (border & 0xFFFFFF) | 0x16000000; }

    /** Per-channel RGB mix of {@code a} toward {@code b} by {@code t}; alpha forced opaque. */
    public static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int gr = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (gr << 8) | bl;
    }

    // ---- persistence: config/numen/ui.json {"theme": "<id>", "talkHint": bool} ----

    private static java.nio.file.Path file;
    /** 快捷对话提醒:准星指着同伴时浮「按 [键] 对话」。默认开,设置里可关。 */
    private static boolean talkHint = true;

    public static boolean talkHintEnabled() {
        return talkHint;
    }

    /** 设置入口:切换快捷对话提醒并落盘。 */
    public static void setTalkHint(boolean enabled) {
        talkHint = enabled;
        persist();
    }

    /** Load the saved pick (client init). Missing/broken file keeps the default. */
    public static void init(java.nio.file.Path numenConfigDir) {
        file = numenConfigDir.resolve("ui.json");
        try {
            if (java.nio.file.Files.exists(file)) {
                var o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file))
                        .getAsJsonObject();
                if (o.has("theme")) set(o.get("theme").getAsString());
                if (o.has("talkHint")) talkHint = o.get("talkHint").getAsBoolean();
            }
        } catch (Exception e) {
            com.dwinovo.numen.Constants.LOG.warn("ui.json unreadable — using default theme", e);
        }
    }

    /** The picker's entry point: switch AND save. */
    public static void select(String id) {
        set(id);
        persist();
    }

    private static void persist() {
        if (file == null) return;
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            var o = new com.google.gson.JsonObject();
            o.addProperty("theme", current.id());
            o.addProperty("talkHint", talkHint);
            java.nio.file.Files.writeString(file, o.toString());
        } catch (Exception e) {
            com.dwinovo.numen.Constants.LOG.warn("ui.json write failed — prefs not persisted", e);
        }
    }
}
