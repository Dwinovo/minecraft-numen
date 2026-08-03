package com.dwinovo.numen.client.voice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link VoiceLibrary} 声线库的存取与每同伴绑定,headless(直接以临时路径构造,
 * 不经 {@code instance()},不碰 Minecraft)。GUI 部分(语音 tab / 试听 / 召唤下拉)
 * 需真机验证。
 */
class VoiceLibraryTest {

    @TempDir
    Path dir;

    @org.junit.jupiter.api.BeforeEach
    void homeInTemp() {
        com.dwinovo.numen.client.agent.CompanionHome.overrideRoot(dir);
    }

    @org.junit.jupiter.api.AfterEach
    void homeRestore() {
        com.dwinovo.numen.client.agent.CompanionHome.overrideRoot(null);
    }

    private VoiceLibrary fresh() {
        return new VoiceLibrary(dir.resolve("voice.json"));
    }

    private static VoiceLibrary.Entry openai(VoiceLibrary lib, String name) {
        return lib.create(name, "openai", "https://api.siliconflow.cn", "sk-test", "",
                "CosyVoice2", "CosyVoice2:alex", "", "", "", 1.0f);
    }

    // ---- entries: create / get / update / remove + 持久化 ----

    @Test
    void createAssignsIdAndPersists() {
        VoiceLibrary lib = fresh();
        VoiceLibrary.Entry e = openai(lib, "测试声线");
        assertNotNull(e.id());
        assertFalse(e.id().isBlank());
        assertTrue(Files.exists(dir.resolve("voice.json")));

        VoiceLibrary reloaded = fresh();   // 同一文件重新加载
        assertEquals(1, reloaded.list().size());
        VoiceLibrary.Entry r = reloaded.get(e.id());
        assertEquals("测试声线", r.name());
        assertEquals("openai", r.backend());
        assertEquals("sk-test", r.apiKey());
        assertEquals(1.0f, r.volume());
    }

    @Test
    void sovitsFieldsRoundTrip() {
        VoiceLibrary lib = fresh();
        VoiceLibrary.Entry e = lib.create("派蒙", "gpt_sovits", "http://127.0.0.1:9880", "", "",
                "", "", "D:/voices/ref.wav", "参考音频文本", "zh", 1.2f);
        VoiceLibrary.Entry r = fresh().get(e.id());
        assertTrue(r.isSovits());
        assertEquals("D:/voices/ref.wav", r.refAudio());
        assertEquals("参考音频文本", r.promptText());
        assertEquals("zh", r.textLang());
        assertEquals(1.2f, r.volume());
    }

    @Test
    void minimaxAndFishFieldsRoundTrip() {
        VoiceLibrary lib = fresh();
        VoiceLibrary.Entry mm = lib.create("MM", "minimax", "https://api.minimax.io", "eyJtest",
                "1234567890", "speech-02-turbo", "male-qn-qingse", "", "", "", 1.0f);
        VoiceLibrary.Entry fish = lib.create("Fish", "fish_audio", "https://api.fish.audio", "fk-test",
                "", "s1", "802e3bc2b27e49c2995d23ef70e6ac89", "", "", "", 1.0f);

        VoiceLibrary reloaded = fresh();
        VoiceLibrary.Entry m = reloaded.get(mm.id());
        assertTrue(m.isMiniMax());
        assertEquals("1234567890", m.groupId());
        assertEquals("male-qn-qingse", m.voice());
        VoiceLibrary.Entry f = reloaded.get(fish.id());
        assertTrue(f.isFishAudio());
        assertEquals("802e3bc2b27e49c2995d23ef70e6ac89", f.voice());
        assertEquals("s1", f.model());
        assertEquals("", f.groupId());   // 未填 group_id 不落盘,读回空串
    }

    @Test
    void updateReplacesInPlace() {
        VoiceLibrary lib = fresh();
        VoiceLibrary.Entry e = openai(lib, "旧名");
        lib.update(new VoiceLibrary.Entry(e.id(), "新名", "openai", e.url(), e.apiKey(), "",
                "NewModel", e.voice(), "", "", "", 0.5f));
        VoiceLibrary.Entry r = fresh().get(e.id());
        assertEquals("新名", r.name());
        assertEquals("NewModel", r.model());
        assertEquals(0.5f, r.volume());
        assertEquals(1, fresh().list().size());
    }

    @Test
    void updateUnknownIdIsNoop() {
        VoiceLibrary lib = fresh();
        lib.update(new VoiceLibrary.Entry("ghost", "x", "openai", "", "", "", "", "", "", "", "", 1f));
        assertTrue(lib.list().isEmpty());
    }

    @Test
    void removeDeletesEntryButKeepsOtherState() {
        VoiceLibrary lib = fresh();
        VoiceLibrary.Entry a = openai(lib, "A");
        VoiceLibrary.Entry b = openai(lib, "B");
        lib.remove(a.id());
        VoiceLibrary reloaded = fresh();
        assertNull(reloaded.get(a.id()));
        assertNotNull(reloaded.get(b.id()));
    }

    @Test
    void volumeIsClampedOnCreateAndLoad() {
        VoiceLibrary lib = fresh();
        VoiceLibrary.Entry e = lib.create("loud", "openai", "", "", "", "", "", "", "", "", 9.0f);
        assertEquals(2.0f, e.volume());
        assertEquals(2.0f, fresh().get(e.id()).volume());
        assertEquals(0f, VoiceLibrary.clampVolume(-3f));
        assertEquals(1f, VoiceLibrary.clampVolume(Float.NaN));
    }

    // ---- global switch + per-companion binding + resolve ----

    @Test
    void enabledFlagPersists() {
        VoiceLibrary lib = fresh();
        assertTrue(lib.enabled());   // 缺省开:玩家配好声线就该出声,关闭是显式选择
        lib.setEnabled(false);
        assertFalse(fresh().enabled());
    }

    // 绑定不再住在库里(它跟着同伴走,见 CompanionHome),所以这几条验的是
    // "库按外部给的绑定解析"——总开关闸门、悬空绑定回落。

    @Test
    void resolveGatesOnEnabled() {
        VoiceLibrary lib = fresh();
        UUID u = UUID.randomUUID();
        VoiceLibrary.Entry e = openai(lib, "A");
        com.dwinovo.numen.client.agent.CompanionHome.bind(u,
                com.dwinovo.numen.client.agent.CompanionHome.Binding.EMPTY.withVoice(e.id()));

        assertEquals(e.id(), lib.resolve(u).id());   // 缺省开 → 绑定即出声
        lib.setEnabled(false);
        assertNull(lib.resolve(u));                  // 总开关关闭 → 静音

        lib.setEnabled(true);
        assertEquals(e.id(), fresh().resolve(u).id(), "开关持久化,绑定在同伴那边不受重载影响");
    }

    @Test
    void unboundIsSilent() {
        VoiceLibrary lib = fresh();
        lib.setEnabled(true);
        UUID u = UUID.randomUUID();
        assertNull(lib.resolve(u), "没绑过 = 静音");
    }

    @Test
    void danglingBindingResolvesNull() {
        VoiceLibrary lib = fresh();
        lib.setEnabled(true);
        UUID u = UUID.randomUUID();
        VoiceLibrary.Entry e = openai(lib, "A");
        com.dwinovo.numen.client.agent.CompanionHome.bind(u,
                com.dwinovo.numen.client.agent.CompanionHome.Binding.EMPTY.withVoice(e.id()));
        lib.remove(e.id());                   // 条目删了,绑定悬空 → 回落静音
        assertNull(lib.resolve(u));
    }

    @Test
    void resolveNullCompanionIsNull() {
        VoiceLibrary lib = fresh();
        lib.setEnabled(true);
        assertNull(lib.resolve(null));
        assertNull(lib.resolve(null), "null 同伴:静音,不抛");
    }

    // ---- pending summon (name-keyed, applied when the roster snapshot arrives) ----

    @Test
    void pendingSummonTakeOnce() {
        VoiceLibrary.pendSummon("小玖", "voice_x");
        assertEquals("voice_x", VoiceLibrary.takePendingSummon("小玖"));
        assertNull(VoiceLibrary.takePendingSummon("小玖"));   // 取走即清
        assertNull(VoiceLibrary.takePendingSummon("路人"));
        VoiceLibrary.pendSummon("甲", null);                   // null 不入表
        assertNull(VoiceLibrary.takePendingSummon("甲"));
    }

    // ---- degrade on bad file ----

    @Test
    void corruptedFileStartsEmpty() throws Exception {
        Files.writeString(dir.resolve("voice.json"), "{not valid json");
        VoiceLibrary lib = fresh();
        assertTrue(lib.list().isEmpty());
        assertFalse(lib.enabled());
    }

    @Test
    void missingFileStartsEmpty() {
        VoiceLibrary lib = fresh();
        assertTrue(lib.list().isEmpty());
        assertTrue(lib.enabled());   // 全新安装缺省开(坏文件才保守置关,见上一条)
        assertNull(lib.resolve(UUID.randomUUID()));   // 无绑定 → 静音
    }
}
