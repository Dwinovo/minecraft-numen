package com.dwinovo.numen.client.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.20.1 shim for the GUI sprite-atlas API ({@code GuiGraphics.blitSprite}), which only exists on
 * 1.20.2+. The mod's UI declares sprites as {@code numen_api:<name>} ids backed by
 * {@code textures/gui/sprites/<name>.png} (+ an optional {@code .png.mcmeta} scaling descriptor),
 * exactly the post-1.20.2 sprite layout.
 *
 * <p>We resolve those by hand: read the PNG's pixel size from its header and the
 * {@code nine_slice} border (uniform int, or a {@code {left,top,right,bottom}} object) from the
 * mcmeta. {@code nine_slice} sprites are drawn as nine STRETCHED {@code blit} regions — matching the
 * 1.20.2+ {@code nine_slice} semantics — rather than 1.20.1's {@code blitNineSliced}, which TILES the
 * edges and so smears small frame borders into a dashed seam. {@code stretch} (or unmetad) sprites
 * are a single stretched blit. Metadata is cached per sprite. Call sites use
 * {@code GuiCompat.blitSprite(g, SPRITE, x, y, w, h)}.
 */
public final class GuiCompat {

    private GuiCompat() {}

    private record SpriteMeta(int texW, int texH, int bl, int bt, int br, int bb, boolean nineSlice) {}

    private static final Map<ResourceLocation, SpriteMeta> CACHE = new ConcurrentHashMap<>();

    public static void blitSprite(GuiGraphics g, ResourceLocation spriteId, int x, int y, int w, int h) {
        ResourceLocation tex = new ResourceLocation(spriteId.getNamespace(),
                "textures/gui/sprites/" + spriteId.getPath() + ".png");
        SpriteMeta m = CACHE.computeIfAbsent(spriteId, id -> load(tex));
        if (!m.nineSlice) {
            region(g, tex, x, y, w, h, 0, 0, m.texW, m.texH, m.texW, m.texH);   // single stretch
            return;
        }
        int tw = m.texW;
        int th = m.texH;
        int bl = m.bl;
        int bt = m.bt;
        int br = m.br;
        int bb = m.bb;
        int innerW = w - bl - br;            // destination inner span
        int innerH = h - bt - bb;
        int srcInnerW = tw - bl - br;        // source inner span
        int srcInnerH = th - bt - bb;
        // corners (drawn 1:1)
        region(g, tex, x, y, bl, bt, 0, 0, bl, bt, tw, th);                                   // TL
        region(g, tex, x + w - br, y, br, bt, tw - br, 0, br, bt, tw, th);                    // TR
        region(g, tex, x, y + h - bb, bl, bb, 0, th - bb, bl, bb, tw, th);                    // BL
        region(g, tex, x + w - br, y + h - bb, br, bb, tw - br, th - bb, br, bb, tw, th);     // BR
        // edges (stretched along the run)
        region(g, tex, x + bl, y, innerW, bt, bl, 0, srcInnerW, bt, tw, th);                  // top
        region(g, tex, x + bl, y + h - bb, innerW, bb, bl, th - bb, srcInnerW, bb, tw, th);   // bottom
        region(g, tex, x, y + bt, bl, innerH, 0, bt, bl, srcInnerH, tw, th);                  // left
        region(g, tex, x + w - br, y + bt, br, innerH, tw - br, bt, br, srcInnerH, tw, th);   // right
        // center (stretched both ways)
        region(g, tex, x + bl, y + bt, innerW, innerH, bl, bt, srcInnerW, srcInnerH, tw, th);
    }

    /** Stretch the {@code (u,v,srcW,srcH)} texture region into the {@code (dx,dy,dw,dh)} dest box. */
    private static void region(GuiGraphics g, ResourceLocation tex,
                               int dx, int dy, int dw, int dh,
                               int u, int v, int srcW, int srcH, int texW, int texH) {
        if (dw <= 0 || dh <= 0 || srcW <= 0 || srcH <= 0) {
            return;
        }
        g.blit(tex, dx, dy, dw, dh, (float) u, (float) v, srcW, srcH, texW, texH);
    }

    /**
     * Draw one icon out of the vanilla HUD atlas by UV — the pre-sprite-id way, and the way vanilla
     * itself still draws hearts / hunger shanks on this game version. Callers pass the same
     * {@code (u, v)} the vanilla HUD code uses, so the pixels are literally vanilla's: no copy of a
     * vanilla texture is shipped in this mod's assets.
     */
    public static void blitHudIcon(GuiGraphics g, int x, int y, int u, int v, int w, int h) {
        g.blit(com.dwinovo.numen.mixin.GuiIconsAccessor.numen$iconsAtlas(), x, y, u, v, w, h);
    }

    private static SpriteMeta load(ResourceLocation tex) {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        int texW = 16;
        int texH = 16;
        try (InputStream in = rm.open(tex)) {
            int[] dim = pngSize(in);
            texW = dim[0];
            texH = dim[1];
        } catch (Exception ignored) {
            // missing/unreadable texture → fall back to 16×16; the blit is wrong-sized, not a crash
        }

        boolean nine = false;
        int bl = 0;
        int bt = 0;
        int br = 0;
        int bb = 0;
        ResourceLocation mcmeta = new ResourceLocation(tex.getNamespace(), tex.getPath() + ".mcmeta");
        try (InputStream in = rm.open(mcmeta)) {
            JsonObject scaling = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("gui").getAsJsonObject("scaling");
            if ("nine_slice".equals(scaling.get("type").getAsString())) {
                nine = true;
                // The mcmeta width/height is the sprite's logical (source) size, authoritative for the
                // 9-slice math (equals the PNG size for our standalone sprites).
                if (scaling.has("width")) texW = scaling.get("width").getAsInt();
                if (scaling.has("height")) texH = scaling.get("height").getAsInt();
                JsonElement border = scaling.get("border");
                if (border.isJsonObject()) {
                    JsonObject b = border.getAsJsonObject();
                    bl = b.get("left").getAsInt();
                    bt = b.get("top").getAsInt();
                    br = b.get("right").getAsInt();
                    bb = b.get("bottom").getAsInt();
                } else {
                    bl = bt = br = bb = border.getAsInt();   // uniform
                }
            }
        } catch (Exception ignored) {
            // no mcmeta (or type != nine_slice) → single stretch blit
        }
        return new SpriteMeta(texW, texH, bl, bt, br, bb, nine);
    }

    /** Read width/height from a PNG's IHDR header (first 24 bytes) without decoding the image. */
    private static int[] pngSize(InputStream in) throws IOException {
        byte[] b = in.readNBytes(24);
        if (b.length < 24) {
            throw new IOException("short PNG header");
        }
        int w = ((b[16] & 0xFF) << 24) | ((b[17] & 0xFF) << 16) | ((b[18] & 0xFF) << 8) | (b[19] & 0xFF);
        int h = ((b[20] & 0xFF) << 24) | ((b[21] & 0xFF) << 16) | ((b[22] & 0xFF) << 8) | (b[23] & 0xFF);
        return new int[]{w, h};
    }
}
