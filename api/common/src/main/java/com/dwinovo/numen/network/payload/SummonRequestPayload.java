package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.Companions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server: the owner asked to summon a companion by name from the panel's
 * "+" button. Mirrors the {@code /numen player summon} command — summon is
 * idempotent per (owner, name), so re-summoning an existing name just wakes it.
 *
 * <p>名字限定 Minecraft 官方命名规则(3~16 位英文/数字/下划线)。皮肤来源二选一:
 * {@code skinValue} 非空 = 客户端皮肤库里 MineSkin 代签好的 Mojang 签名数据,直接
 * 采用(签名自验证,客户端伪造不了);为空 = <b>名字就是皮肤来源</b>,服务端异步查
 * 同名正版玩家,查到穿其皮肤,查不到静默回落默认皮肤(日志可查,不打扰玩家)。
 */
public record SummonRequestPayload(String name, String skinValue, String skinSig, boolean creative)
        implements CustomPacketPayload {

    public static final int MAX_NAME = 16;
    /** Mojang 签名 textures 的尺寸上限:value 是带皮肤/披风 URL 的 base64 JSON,
     *  实测 1KB 上下,8KB 已是十倍余量;signature 固定 ~700B。 */
    public static final int MAX_SKIN_VALUE = 8192;
    public static final int MAX_SKIN_SIG = 2048;

    public static final ResourceLocation ID =
            new ResourceLocation(Constants.MOD_ID, "summon_request");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(name, MAX_NAME);
        buf.writeUtf(skinValue, MAX_SKIN_VALUE);
        buf.writeUtf(skinSig, MAX_SKIN_SIG);
        buf.writeBoolean(creative);
    }

    public static SummonRequestPayload read(FriendlyByteBuf buf) {
        return new SummonRequestPayload(buf.readUtf(MAX_NAME), buf.readUtf(MAX_SKIN_VALUE),
                buf.readUtf(MAX_SKIN_SIG), buf.readBoolean());
    }

    /** 正在异步召唤中的 owner/name 键——皮肤查询窗口内吃掉重复请求,防双击造重。 */
    private static final java.util.Set<String> SPAWNING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Server main thread. */
    public static void handle(SummonRequestPayload p, ServerPlayer owner) {
        String name = p.name() == null ? "" : p.name().trim();
        if (!com.dwinovo.numen.entity.MojangSkins.validName(name)) return;   // 服务端权威校验
        var server = owner.level().getServer();
        // 重名闸:同名玩家已在线(真人/别的主人的同伴)一律拒绝——
        // 例外是自己的同名同伴(那是幂等唤醒/换肤,summon 内部处理)。
        var online = server.getPlayerList().getPlayerByName(name);
        boolean ownSameName = online instanceof com.dwinovo.numen.entity.NumenPlayer np
                && np.isOwnedByPlayer(owner.getUUID());
        if (online != null && !ownSameName) {
            owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[Numen] 名字「" + name + "」已被在线玩家占用,换一个吧"));
            return;
        }
        // 登录中闸:异步皮肤查询窗口内(几秒)重复点击不许再召。
        String spawnKey = owner.getUUID() + "/" + name;
        if (!SPAWNING.add(spawnKey)) return;
        // 皮肤一律由客户端备好(自定义库条目或它在本机查到的正版档案),服务端
        // 不再查询:那条路吃 JVM 默认网络、绕开玩家的代理,国内经常静默超时。
        // 签名数据是 Mojang 自验证的,伪造不了,所以照单收下是安全的。
        String value = p.skinValue() == null ? "" : p.skinValue();
        var skin = value.isBlank() ? null
                : new com.dwinovo.numen.entity.MojangSkins.Skin(value,
                        p.skinSig() == null ? "" : p.skinSig());
        try {
            ServerLevel level = (ServerLevel) owner.level();
            var body = Companions.summon(server, owner.getUUID(), name, level, owner.position(), skin);
            Companions.applyGameMode(owner, body, p.creative());
            Companions.syncRosterToOwner(server, owner);   // push the new roster to the owner
        } finally {
            SPAWNING.remove(spawnKey);
        }
    }
}
