package com.dwinovo.numen.api.client.skin;

import com.dwinovo.numen.api.Constants;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 皮肤库条目的预览纹理缓存:把条目的原图(服务端推来的 png 字节)注册成动态纹理,供列表行画头像
 * (PlayerFaceRenderer)。按条目的 key 缓存,同一个 key 来了不同的图(重签换了图)就换掉旧纹理。仅客户端渲染线程使用。
 */
public final class SkinTextures {

    private record Cached(int hash, ResourceLocation texture) {}

    private static final Map<String, Cached> CACHE = new HashMap<>();

    private SkinTextures() {}

    /** 这张图的纹理位置;读不到图返回 null(调用方跳过头像)。 */
    public static ResourceLocation faceOf(String key, byte[] png) {
        if (png.length == 0) {
            return null;
        }
        int hash = Arrays.hashCode(png);
        Cached cached = CACHE.get(key);
        if (cached != null && cached.hash() == hash) {
            return cached.texture();   // 含"读取失败"的 null 负缓存
        }
        if (cached != null && cached.texture() != null) {
            Minecraft.getInstance().getTextureManager().release(cached.texture());
        }
        ResourceLocation rl = null;
        try (ByteArrayInputStream in = new ByteArrayInputStream(png)) {
            NativeImage img = NativeImage.read(in);
            rl = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID,
                    "skin_preview/" + Integer.toHexString(key.toLowerCase(Locale.ROOT).hashCode()) + "_"
                            + Integer.toHexString(hash));
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(img));
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("[numen-skin] 预览纹理加载失败 {}: {}", key, e.toString());
        }
        CACHE.put(key, new Cached(hash, rl));
        return rl;
    }
}
