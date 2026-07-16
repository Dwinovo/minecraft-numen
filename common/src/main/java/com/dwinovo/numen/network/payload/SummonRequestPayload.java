package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.Companions;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
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
public record SummonRequestPayload(String name, String skinValue, String skinSig)
        implements CustomPacketPayload {

    public static final int MAX_NAME = 16;
    /** Mojang 签名 textures 的尺寸上限:value 是带皮肤/披风 URL 的 base64 JSON,
     *  实测 1KB 上下,8KB 已是十倍余量;signature 固定 ~700B。 */
    public static final int MAX_SKIN_VALUE = 8192;
    public static final int MAX_SKIN_SIG = 2048;

    public static final Type<SummonRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "summon_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SummonRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(MAX_NAME), SummonRequestPayload::name,
                    ByteBufCodecs.stringUtf8(MAX_SKIN_VALUE), SummonRequestPayload::skinValue,
                    ByteBufCodecs.stringUtf8(MAX_SKIN_SIG), SummonRequestPayload::skinSig,
                    SummonRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
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
        String value = p.skinValue() == null ? "" : p.skinValue();
        if (!value.isBlank()) {
            // 自定义皮肤:签名数据现成,直接召唤,零网络。
            com.dwinovo.numen.Constants.LOG.info("[numen-skin] 召唤 {} 携带自定义皮肤数据,直接入册", name);
            try {
                ServerLevel level = (ServerLevel) owner.level();
                Companions.summon(server, owner.getUUID(), name, level, owner.position(),
                        new com.dwinovo.numen.entity.MojangSkins.Skin(value,
                                p.skinSig() == null ? "" : p.skinSig()));
                Companions.syncRosterToOwner(server, owner);
            } finally {
                SPAWNING.remove(spawnKey);
            }
            return;
        }
        // 按名字借皮肤:查询在后台线程,绝不阻塞主线程;
        // 取到(或确认没有)后蹦回主线程再召唤。
        com.dwinovo.numen.entity.MojangSkins.fetch(server, name).thenAccept(skin -> server.execute(() -> {
            try {
                if (owner.hasDisconnected()) return;
                ServerLevel level = (ServerLevel) owner.level();
                Companions.summon(server, owner.getUUID(), name, level, owner.position(), skin);
                Companions.syncRosterToOwner(server, owner);   // push the new roster to the owner
            } finally {
                SPAWNING.remove(spawnKey);
            }
        }));
    }
}
