package com.dwinovo.numen.client.skin;

import com.dwinovo.numen.Constants;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 皮肤库条目的预览纹理缓存:把 {@code config/numen/skins/<id>.png} 注册成
 * 动态纹理,供列表行画头像(PlayerFaceRenderer)。按条目 id 缓存,换图/删除
 * 时 {@link #evict} 作废。仅客户端渲染线程使用。
 */
public final class SkinTextures {

    private static final Map<String, ResourceLocation> CACHE = new HashMap<>();

    private SkinTextures() {}

    /** 条目原图的纹理位置;读不到图返回 null(调用方跳过头像)。 */
    public static ResourceLocation faceOf(String id, Path png) {
        if (CACHE.containsKey(id)) return CACHE.get(id);   // 含"读取失败"的 null 负缓存
        ResourceLocation rl = null;
        try (InputStream in = Files.newInputStream(png)) {
            NativeImage img = NativeImage.read(in);
            rl = new ResourceLocation(Constants.MOD_ID,
                    "skin_preview/" + id.toLowerCase(Locale.ROOT));
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(img));
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("[numen-skin] 预览纹理加载失败 {}: {}", id, e.toString());
        }
        CACHE.put(id, rl);
        return rl;
    }

    /** 换图/删除后作废(纹理管理器里的旧注册随之释放)。 */
    public static void evict(String id) {
        ResourceLocation rl = CACHE.remove(id);
        if (rl != null) {
            Minecraft.getInstance().getTextureManager().release(rl);
        }
    }
}
