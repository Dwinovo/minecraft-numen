package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.brain.CompanionBinding;
import com.dwinovo.numen.api.brain.OwnerInput;
import com.dwinovo.numen.api.brain.OwnerInputs;
import com.dwinovo.numen.api.brain.ServerSkins;
import com.dwinovo.numen.api.brain.Slot;
import com.dwinovo.numen.api.brain.Venue;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.skin.SkinError;
import com.dwinovo.numen.api.skin.SkinImage;
import com.dwinovo.numen.api.skin.SkinSigning;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 召唤卡上一并定下的几项,每一项都可以不写:三项库绑定({@link Slot})、模式、皮肤。没写的项服务端照不指定时的规矩办——库条目不绑,
 * 第一次出生是生存({@code CompanionFactory} 加入时定的),皮肤是原版按她的 UUID 挑的默认皮肤。
 *
 * <p>检查({@link #prepare})与落下({@link Prepared#apply})走的都是编辑她时的那几条路,没有召唤专用的一套:库条目是
 * {@link CompanionBinding#find} 与 {@link OwnerInput.Bind},模式是 {@link Companions#mayChooseCreative} 与
 * {@link Companions#applyGameMode},皮肤是皮肤文件夹与 {@link SkinImage#check} 的那道门和 {@code Wardrobe#wear}。
 * 有一项不过门就整次不召唤,告诉玩家是哪一项、为什么;都过了的在她到达的那一刻落下。
 *
 * @param entries  要绑的库条目({@code 命名空间:名字});没写的项不在里面
 * @param creative 模式:{@code true} 创造、{@code false} 生存;{@code null} 是没写
 * @param skin     皮肤;{@code null} 是没写
 */
public record SummonSetup(Map<Slot, String> entries, Boolean creative, Skin skin) {

    /** 什么都没写:命令与外接大脑的召唤只带名字。 */
    public static final SummonSetup NONE = new SummonSetup(Map.of(), null, null);

    public SummonSetup {
        EnumMap<Slot, String> copy = new EnumMap<>(Slot.class);
        entries.forEach((slot, entry) -> copy.put(Objects.requireNonNull(slot), Objects.requireNonNull(entry)));
        entries = java.util.Collections.unmodifiableMap(copy);
    }

    /**
     * 召唤卡上选的皮肤:服务器皮肤文件夹里叫 {@code name} 的那张({@code serverFile}),或主人这台机器的文件夹里那张的原图
     * ({@code png},{@code name} 是它的文件名,只用来在进度与失败里称呼它)。
     *
     * @param variant 手臂模型;{@code null} 是看图判断
     */
    public record Skin(String name, boolean serverFile, byte[] png, String variant) {

        public Skin {
            Objects.requireNonNull(name);
            png = png == null ? new byte[0] : png;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Skin other && name.equals(other.name) && serverFile == other.serverFile
                    && Arrays.equals(png, other.png) && Objects.equals(variant, other.variant);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, serverFile, Arrays.hashCode(png), variant);
        }
    }

    // ---- 线上的样子 ----

    private static final StreamCodec<ByteBuf, Slot> SLOT = ByteBufCodecs.STRING_UTF8.map(wire -> {
        Slot slot = Slot.ofWire(wire);
        if (slot == null) {
            throw new DecoderException("unknown binding slot: " + wire);
        }
        return slot;
    }, Slot::wire);

    private static final StreamCodec<ByteBuf, Skin> SKIN = StreamCodec.composite(
            Wire.TO_SERVER.text(), Skin::name,
            ByteBufCodecs.BOOL, Skin::serverFile,
            ByteBufCodecs.byteArray(Wire.MESSAGE_BYTES), Skin::png,
            ByteBufCodecs.optional(Wire.TO_SERVER.text()), s -> Optional.ofNullable(s.variant()),
            (name, serverFile, png, variant) -> new Skin(name, serverFile, png, variant.orElse(null)));

    /** 上行的编解码:什么都没写只占一个空表与两个"没有"的标记。不碰注册表,可以分片送。 */
    public static final StreamCodec<ByteBuf, SummonSetup> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.<ByteBuf, Slot, String, Map<Slot, String>>map(java.util.HashMap::new, SLOT,
                    Wire.TO_SERVER.text(), Slot.values().length),
            SummonSetup::entries,
            ByteBufCodecs.optional(ByteBufCodecs.BOOL), s -> Optional.ofNullable(s.creative()),
            ByteBufCodecs.optional(SKIN), s -> Optional.ofNullable(s.skin()),
            (entries, creative, skin) -> new SummonSetup(entries, creative.orElse(null), skin.orElse(null)));

    // ---- 检查与落下 ----

    /** 检查要问世界的几件事,与编辑时问的是同几处;单测换成假的。 */
    public interface Checks {

        /** 主人能绑这一条(看得到、没被他关掉)。 */
        boolean entryExists(Slot slot, String entry);

        /** 主人能让同伴进创造。 */
        boolean mayChooseCreative();

        /**
         * 这张皮肤的原图,检查过能当皮肤用。
         *
         * @throws SkinError 没有这张图、读不了、不能当皮肤用
         */
        byte[] skinImage(Skin skin);
    }

    /** 这位主人此刻的世界:库是他看得到的那些,模式门是他的权限,皮肤文件夹是服务器上的那个。 */
    public static Checks checks(ServerPlayer owner) {
        MinecraftServer server = owner.level().getServer();
        return new Checks() {
            @Override
            public boolean entryExists(Slot slot, String entry) {
                return CompanionBinding.find(server, owner.getUUID(), slot, entry) != null;
            }

            @Override
            public boolean mayChooseCreative() {
                return Companions.mayChooseCreative(owner);
            }

            @Override
            public byte[] skinImage(Skin skin) {
                if (skin.serverFile()) {
                    return ServerSkins.of(server).wardrobe().folder().read(skin.name());
                }
                SkinImage.check(skin.png());
                return skin.png();
            }
        };
    }

    /**
     * 召唤之前当场检查每一项,顺序是库条目(按 {@link Slot} 的次序)、模式、皮肤;第一项不过门的就是拒绝的原因。
     *
     * @param name 要召唤的名字,拒绝的话里说是哪一只
     */
    public Prepared prepare(Checks checks, String name) {
        for (Map.Entry<Slot, String> e : entries.entrySet()) {
            if (!checks.entryExists(e.getKey(), e.getValue())) {
                return Prepared.refused(Component.translatable(Keys.NOTICE_SUMMON_ENTRY_MISSING, name,
                        Component.translatable(e.getKey().label()), e.getValue()));
            }
        }
        if (Boolean.TRUE.equals(creative) && !checks.mayChooseCreative()) {
            return Prepared.refused(Component.translatable(Keys.NOTICE_SUMMON_CREATIVE_DENIED, name));
        }
        byte[] png = null;
        if (skin != null) {
            try {
                png = checks.skinImage(skin);
            } catch (SkinError unusable) {
                return Prepared.refused(Component.translatable(Keys.NOTICE_SUMMON_SKIN_REFUSED, name, skin.name(),
                        unusable.message()));
            }
        }
        return new Prepared(null, this, png);
    }

    /**
     * 检查的结果:不过门是 {@code refusal};过了的带着检查时读到的皮肤原图,落下时就用它。
     */
    public record Prepared(Component refusal, SummonSetup setup, byte[] skinPng) {

        static Prepared refused(Component why) {
            return new Prepared(why, null, null);
        }

        /**
         * 她到了:每一项经编辑时的那条路落下,在名册推给主人之前。检查与到达之间库变了(条目被删、皮肤签名排满)的,
         * 那一项没办成,如实告诉主人。
         */
        public void apply(ServerPlayer owner, NumenPlayer body) {
            MinecraftServer server = owner.level().getServer();
            for (Map.Entry<Slot, String> e : setup.entries().entrySet()) {
                OwnerInputs.Applied applied = OwnerInputs.handle(server, owner.getUUID(),
                        new OwnerInput.Bind(body.getUUID(), e.getKey(), e.getValue()), Venue.PANEL);
                if (applied.problem() != null) {
                    owner.sendSystemMessage(Component.translatable(Keys.COMMAND_BIND_FAILED, applied.problem()));
                }
            }
            if (setup.creative() != null) {
                Companions.applyGameMode(owner, body, setup.creative());
            }
            Skin skin = setup.skin();
            if (skin != null) {
                long ticket = ServerSkins.of(server).wardrobe().wear(owner.getUUID(), body.getUUID(), skin.name(),
                        new SkinSigning.Source.Image(skinPng), skin.variant(), new SkinSigning.Listener() {
                            @Override
                            public void done(SkinSigning.Outcome outcome) {
                                if (!outcome.ok()) {
                                    owner.sendSystemMessage(Component.translatable(Keys.COMMAND_SKIN_FAILED,
                                            skin.name(), outcome.problem().message()));
                                }
                            }
                        });
                if (ticket < 0) {
                    owner.sendSystemMessage(Component.translatable(Keys.COMMAND_SKIN_FULL,
                            SkinSigning.MAX_PENDING_PER_OWNER));
                }
            }
        }
    }
}
