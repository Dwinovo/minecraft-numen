package com.dwinovo.numen.entity;

import com.dwinovo.numen.Constants;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 借正版玩家皮肤——Carpet 假人({@code EntityPlayerMPFake.createFake})同款路径,
 * 零第三方、零自写 HTTP,全部走服务端内置的 Mojang 服务栈:
 * <ol>
 *   <li>名字 → UUID:{@link MinecraftServer#getProfileCache()}(usercache.json
 *       两级缓存,未命中才查 Mojang,离线服照常工作);</li>
 *   <li>UUID → 完整档案:{@code SessionService.fetchProfile(uuid, true)},返回的
 *       GameProfile 自带 Mojang 签名的 textures 属性(头颅方块渲染玩家头的同一套)。</li>
 * </ol>
 * 全程后台线程,任何一步失败归结为 {@code null}(调用方回落原版默认皮肤),
 * 绝不抛到调用线程。
 */
public final class MojangSkins {

    /** Mojang 签名的 textures 属性对。{@code signature} 可为空串(理论上不该发生)。 */
    public record Skin(String value, String signature) {}

    private MojangSkins() {}

    /** 合法的正版玩家名:3~16 位字母/数字/下划线。 */
    public static boolean validName(String s) {
        return s != null && s.matches("[A-Za-z0-9_]{3,16}");
    }

    /** 异步取 {@code playerName} 的签名皮肤;查无此人/网络失败 → {@code null}。 */
    public static CompletableFuture<Skin> fetch(MinecraftServer server, String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            var cache = server.getProfileCache();
            Optional<GameProfile> byName = cache == null ? Optional.empty() : cache.get(playerName);
            if (byName.isEmpty()) {
                Constants.LOG.info("[numen-skin] {} 不是正版玩家名,用默认皮肤", playerName);
                return null;
            }
            ProfileResult result = server.getSessionService().fetchProfile(byName.get().getId(), true);
            if (result == null) {
                Constants.LOG.warn("[numen-skin] {} 的档案获取失败(会话服务器无响应),用默认皮肤", playerName);
                return null;
            }
            for (Property p : result.profile().getProperties().get("textures")) {
                Constants.LOG.info("[numen-skin] 借到 {} 的皮肤", playerName);
                return new Skin(p.value(), p.signature() == null ? "" : p.signature());
            }
            return null;
        }, Util.backgroundExecutor()).exceptionally(e -> {
            Constants.LOG.warn("[numen-skin] 皮肤获取异常 {}: {}", playerName, String.valueOf(e));
            return null;
        });
    }
}
