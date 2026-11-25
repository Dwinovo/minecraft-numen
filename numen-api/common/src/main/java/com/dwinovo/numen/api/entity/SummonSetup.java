package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * 召唤卡上一并定下的、服务端要办的一项,可以不写:模式。没写的服务端照不指定时的规矩办——第一次出生是生存({@code CompanionFactory} 加入时定的)。
 * 皮肤与库绑定不在这里:它们在主人的客户端上办,她到了以后由客户端穿上、绑上。
 *
 * <p>检查({@link #prepare})与落下({@link Prepared#apply})走的是编辑她时的那条路,没有召唤专用的一套:模式是 {@link Companions#mayChooseCreative} 与
 * {@link Companions#applyGameMode}。不过门就整次不召唤,告诉玩家为什么;过了的在她到达的那一刻落下。
 *
 * @param creative 模式:{@code true} 创造、{@code false} 生存;{@code null} 是没写
 */
public record SummonSetup(Boolean creative) {

    /** 什么都没写:命令与外接大脑的召唤只带名字。 */
    public static final SummonSetup NONE = new SummonSetup(null);

    // ---- 线上的样子 ----

    /** 上行的编解码:什么都没写只占一个"没有"的标记。不碰注册表,可以分片送。 */
    public static final StreamCodec<ByteBuf, SummonSetup> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(ByteBufCodecs.BOOL), s -> Optional.ofNullable(s.creative()),
            creative -> new SummonSetup(creative.orElse(null)));

    // ---- 检查与落下 ----

    /** 检查要问世界的事,与编辑时问的是同一处;单测换成假的。 */
    public interface Checks {

        /** 主人能让同伴进创造。 */
        boolean mayChooseCreative();
    }

    /** 这位主人此刻的世界:模式门是他的权限。 */
    public static Checks checks(ServerPlayer owner) {
        return () -> Companions.mayChooseCreative(owner);
    }

    /**
     * 召唤之前当场检查;不过门的就是拒绝的原因。
     *
     * @param name 要召唤的名字,拒绝的话里说是哪一只
     */
    public Prepared prepare(Checks checks, String name) {
        if (Boolean.TRUE.equals(creative) && !checks.mayChooseCreative()) {
            return Prepared.refused(Component.translatable(Keys.NOTICE_SUMMON_CREATIVE_DENIED, name));
        }
        return new Prepared(null, this);
    }

    /** 检查的结果:不过门是 {@code refusal};过了的在她到达时落下。 */
    public record Prepared(Component refusal, SummonSetup setup) {

        static Prepared refused(Component why) {
            return new Prepared(why, null);
        }

        /** 她到了:模式经编辑时的那条路落下,在名册推给主人之前。 */
        public void apply(ServerPlayer owner, NumenPlayer body) {
            if (setup.creative() != null) {
                Companions.applyGameMode(owner, body, setup.creative());
            }
        }
    }
}
