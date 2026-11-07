package com.dwinovo.numen.api.voice;

import com.dwinovo.numen.api.skin.SkinProfile;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 声线条目与皮肤条目的文件格式:读写一圈不变,缺的取默认,key 不在条目里。 */
class VoiceProfileTest {

    @Test
    void aVoiceSurvivesWritingAndReading() {
        VoiceProfile voice = new VoiceProfile("siliconflow", "openai", "https://api.siliconflow.cn", "g", "CosyVoice2",
                "alex", "D:/ref.wav", "参考句", "zh", 1.4f);

        assertEquals(voice, VoiceProfile.read(voice.write()));
    }

    @Test
    void unknownBackendsAreOpenAiAndTheVolumeIsClamped() {
        VoiceProfile odd = VoiceProfile.read("{\"backend\":\"mystery\",\"volume\":9}");

        assertEquals(VoiceProfile.BACKEND_OPENAI, odd.backend());
        assertEquals(2f, odd.volume());
        assertEquals(1f, VoiceProfile.read("{}").volume(), "没写音量是 1");
        assertEquals(0f, VoiceProfile.clampVolume(-3f));
        assertEquals(1f, VoiceProfile.clampVolume(Float.NaN));
    }

    @Test
    void theKeyIsKeptUnderTheServiceTheEntryNamesOrElseItsBackend() {
        assertEquals("doubao", VoiceProfile.read("{\"backend\":\"doubao\"}").keyService());
        assertEquals("my-relay", VoiceProfile.read("{\"backend\":\"openai\",\"service\":\"my-relay\"}").keyService());
    }

    @Test
    void anEntryNeverCarriesAKey() {
        String written = VoiceProfile.read("{\"backend\":\"openai\",\"api_key\":\"sk-secret\"}").write();

        assertFalse(written.contains("sk-secret"));
    }

    @Test
    void aSkinKeepsItsImageAndTellsWhetherItIsSigned() {
        SkinProfile skin = new SkinProfile("slim", "value", "sig", new byte[] {1, 2, 3});
        SkinProfile back = SkinProfile.read(skin.write());

        assertEquals("slim", back.variant());
        assertEquals("value", back.value());
        assertTrue(back.signed());
        assertEquals(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}), Base64.getEncoder().encodeToString(back.png()));
        assertFalse(SkinProfile.read("{\"variant\":\"weird\"}").signed());
        assertEquals("classic", SkinProfile.read("{\"variant\":\"weird\"}").variant());
    }

    @Test
    void theArmModelAndPictureAddressAreReadOutOfMojangsSignedValue() {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc\","
                + "\"metadata\":{\"model\":\"slim\"}}}}";
        SkinProfile.Textures textures = SkinProfile.texturesOf(Base64.getEncoder().encodeToString(json.getBytes()));

        assertEquals("slim", textures.variant());
        assertEquals("http://textures.minecraft.net/texture/abc", textures.url());
        assertEquals(null, SkinProfile.texturesOf("not base64 json"));
    }
}
