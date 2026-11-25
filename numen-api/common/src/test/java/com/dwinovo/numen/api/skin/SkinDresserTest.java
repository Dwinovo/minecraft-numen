package com.dwinovo.numen.api.skin;

import com.dwinovo.numen.api.entity.MojangSkins;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.InsecurePublicKeyException;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.ProfileResult;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端穿皮肤的唯一入口:签名验过(Mojang 的服务公钥,这里换成一把测试密钥)才穿;签名不对、改过内容、没有公钥可验、没签名、引用不对,
 * 都拒绝并说明原因,一副也不穿。
 */
class SkinDresserTest {

    private static final UUID HER = UUID.randomUUID();
    private static final String VALUE = Base64.getEncoder().encodeToString(
            "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/a\"}}}".getBytes(StandardCharsets.UTF_8));
    private static final SkinRef REF = SkinRef.of(SkinFixtures.header(64, 64), "classic");

    private final KeyPair mojang = keyPair();
    private final List<String> worn = new ArrayList<>();

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mojang 用私钥签的 textures 数据:和 authlib 验的是同一种签名(SHA1 with RSA,对 value 的字节)。 */
    private static String sign(KeyPair key, String value) {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(key.getPrivate());
            signature.update(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 原版会话服务验属性签名的行为:没有签名是缺,公钥一把都对不上是无效(没有公钥也是一把都对不上)。 */
    private static MinecraftSessionService sessionsWith(PublicKey... keys) {
        return new MinecraftSessionService() {
            @Override
            public String getSecurePropertyValue(Property property) {
                if (!property.hasSignature()) {
                    throw new InsecurePublicKeyException.MissingException("Property is not signed");
                }
                for (PublicKey key : keys) {
                    if (property.isSignatureValid(key)) {
                        return property.value();
                    }
                }
                throw new InsecurePublicKeyException.InvalidException("Property has invalid signature");
            }

            @Override
            public void joinServer(UUID profileId, String authenticationToken, String serverId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ProfileResult hasJoinedServer(String profileName, String serverId, InetAddress address) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Property getPackedTextures(GameProfile profile) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MinecraftProfileTextures unpackTextures(Property packedTextures) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ProfileResult fetchProfile(UUID profileId, boolean requireSecure) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private SkinDresser dresser(MinecraftSessionService sessions) {
        return new SkinDresser(sessions, new SkinFolder(Path.of("none")), new SkinDresser.Dress() {
            @Override
            public boolean wear(UUID companion, SkinRef ref, MojangSkins.Skin skin) {
                worn.add(companion + " " + ref + " " + skin.value() + " " + skin.signature());
                return true;
            }

            @Override
            public void takeOff(UUID companion) {
                worn.add("off " + companion);
            }
        });
    }

    @Test
    void aSkinSignedByMojangsKeyIsWornAndItsDataAndReferenceAreRecorded() {
        String signature = sign(mojang, VALUE);

        dresser(sessionsWith(mojang.getPublic())).wear(HER, REF.toString(), VALUE, signature);

        assertEquals(List.of(HER + " " + REF + " " + VALUE + " " + signature), worn);
    }

    @Test
    void aSkinWhoseContentWasChangedAfterSigningIsRefused() {
        String signature = sign(mojang, VALUE);
        String tampered = Base64.getEncoder().encodeToString(
                "{\"textures\":{\"SKIN\":{\"url\":\"http://evil.example/a\"}}}".getBytes(StandardCharsets.UTF_8));

        SkinError refused = assertThrows(SkinError.class,
                () -> dresser(sessionsWith(mojang.getPublic())).wear(HER, REF.toString(), tampered, signature));

        assertEquals(SkinError.SIGNATURE_REJECTED, refused.code());
        assertTrue(worn.isEmpty(), "没验过就一点也不穿");
    }

    @Test
    void aSignatureFromAnotherKeyIsRefused() {
        String signature = sign(keyPair(), VALUE);

        assertEquals(SkinError.SIGNATURE_REJECTED, assertThrows(SkinError.class,
                () -> dresser(sessionsWith(mojang.getPublic())).wear(HER, REF.toString(), VALUE, signature)).code());
        assertTrue(worn.isEmpty());
    }

    @Test
    void withNoKeysToCheckAgainstNothingIsWorn() {
        String signature = sign(mojang, VALUE);

        SkinError refused = assertThrows(SkinError.class,
                () -> dresser(sessionsWith()).wear(HER, REF.toString(), VALUE, signature));

        assertEquals(SkinError.SIGNATURE_REJECTED, refused.code());
        assertTrue(worn.isEmpty(), "取不到公钥就验不了,验不了不穿");
    }

    @Test
    void anUnsignedSkinAndAMalformedReferenceAreRefused() {
        SkinDresser dresser = dresser(sessionsWith(mojang.getPublic()));
        String signature = sign(mojang, VALUE);

        assertEquals(SkinError.NOT_SIGNED, assertThrows(SkinError.class, () -> dresser.wear(HER, REF.toString(), VALUE, "")).code());
        assertEquals(SkinError.NOT_SIGNED, assertThrows(SkinError.class, () -> dresser.wear(HER, REF.toString(), "", signature)).code());
        assertEquals(SkinError.BAD_REF, assertThrows(SkinError.class, () -> dresser.wear(HER, "my:旧条目", VALUE, signature)).code());
        assertEquals(SkinError.BAD_REF, assertThrows(SkinError.class, () -> dresser.wear(HER, null, VALUE, signature)).code());
        assertTrue(worn.isEmpty());
    }

    @Test
    void takingTheSkinOffGoesThroughTheSameEntryWithoutAnyCheck() {
        dresser(sessionsWith()).takeOff(HER);

        assertEquals(List.of("off " + HER), worn);
    }
}
