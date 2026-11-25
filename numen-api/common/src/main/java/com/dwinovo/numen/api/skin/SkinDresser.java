package com.dwinovo.numen.api.skin;

import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.MojangSkins;
import com.mojang.authlib.minecraft.InsecurePublicKeyException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * 服务端给同伴穿皮肤的唯一入口:<b>(她, 引用, 签好的 textures 数据与签名) → 验签 → 换身体、名册里记数据与引用</b>。
 * 签名是挑皮肤的人的客户端签好交来的,服务端只信 Mojang:用原版服务器启动时就取好的服务公钥(authlib 的 profile property 公钥,
 * 经会话服务验 {@link MinecraftSessionService#getSecurePropertyValue})验过才穿;签名不对、或没有公钥可验,拒绝并说明原因,
 * 不穿未签名的数据。脱掉走同一个入口。引用只给主人的界面对"她穿的是不是这一张",服务端不拿它找任何东西。
 * 谁能改她的皮肤由调用方按她的主人规则先判。
 *
 * <p>另管服务器上的皮肤文件夹("服务器提供"):列文件、按名字给原图。
 *
 * <p>线程:服务端主线程。
 */
public final class SkinDresser {

    /** 把一副皮肤穿到一只同伴身上,或脱掉。 */
    public interface Dress {

        /** 穿上并记下引用;她已不在名册上(被遣散了)是 {@code false}。 */
        boolean wear(UUID companion, SkinRef ref, MojangSkins.Skin skin);

        /** 脱掉,回到默认皮肤。 */
        void takeOff(UUID companion);
    }

    private final MinecraftSessionService sessions;
    private final SkinFolder folder;
    private final Dress dress;

    public SkinDresser(MinecraftSessionService sessions, SkinFolder folder, Dress dress) {
        this.sessions = sessions;
        this.folder = folder;
        this.dress = dress;
    }

    /** 这台服务器上的那一份:原版的会话服务验签,{@link Companions#applySkin} 换身体。 */
    public static SkinDresser of(MinecraftServer server) {
        return of(server, server.getSessionService());
    }

    /** 同上,验签的会话服务由调用方给(GameTest 换成认另一把公钥的)。 */
    public static SkinDresser of(MinecraftServer server, MinecraftSessionService sessions) {
        return new SkinDresser(sessions, SkinFolder.here(), new Dress() {
            @Override
            public boolean wear(UUID companion, SkinRef ref, MojangSkins.Skin skin) {
                if (CompanionRegistry.get(server).find(companion) == null) {
                    return false;
                }
                Companions.applySkin(server, companion, skin, ref.toString());
                return true;
            }

            @Override
            public void takeOff(UUID companion) {
                Companions.applySkin(server, companion, null, null);
            }
        });
    }

    /** 服务器上的皮肤文件夹。 */
    public SkinFolder folder() {
        return folder;
    }

    /**
     * 验过签名就给她穿上。
     *
     * @throws SkinError 服务端不肯穿:码见 {@link SkinError}({@link SkinError#BAD_REF}、{@link SkinError#NOT_SIGNED}、
     *                   {@link SkinError#SIGNATURE_REJECTED}、{@link SkinError#GONE}),第一条参数是给日志的英文诊断
     */
    public void wear(UUID companion, String ref, String value, String signature) {
        SkinRef parsed = SkinRef.parse(ref);
        if (parsed == null) {
            throw SkinError.of(SkinError.BAD_REF, "the skin reference is not <hash>-<arms>");
        }
        if (value == null || value.isBlank() || signature == null || signature.isBlank()) {
            throw SkinError.of(SkinError.NOT_SIGNED, "the skin carries no signature");
        }
        try {
            sessions.getSecurePropertyValue(new Property("textures", value, signature));
        } catch (InsecurePublicKeyException unverified) {
            throw SkinError.of(unverified, SkinError.SIGNATURE_REJECTED,
                    "not valid under Mojang's services keys, or no keys to check against: " + unverified.getMessage());
        }
        if (!dress.wear(companion, parsed, new MojangSkins.Skin(value, signature))) {
            throw SkinError.of(SkinError.GONE, "she is no longer on the roster");
        }
    }

    /** 脱掉她的皮肤。 */
    public void takeOff(UUID companion) {
        dress.takeOff(companion);
    }
}
