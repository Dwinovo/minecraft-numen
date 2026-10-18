package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.client.agent.CompanionLooks.Looks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 声线与皮肤的选择跟着同伴记在这台机器上:重启读得回来,她不在了就忘掉,旧版的绑定文件接得过来。 */
class CompanionLooksTest {

    private static final UUID HER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @AfterEach
    void release() {
        CompanionLooks.init(null, null);
    }

    @Test
    void whatWasChosenIsReadBackAfterARestart(@TempDir Path dir) {
        CompanionLooks.init(dir, dir);
        CompanionLooks.set(HER, CompanionLooks.of(HER).withVoice("v1").withSkin("s1"));

        CompanionLooks.init(dir, dir);

        assertEquals(new Looks("v1", "s1"), CompanionLooks.of(HER));
        assertTrue(CompanionLooks.of(OTHER).isEmpty());
    }

    @Test
    void clearingBothChoicesForgetsHer(@TempDir Path dir) {
        CompanionLooks.init(dir, dir);
        CompanionLooks.set(HER, new Looks("v1", null));
        CompanionLooks.set(HER, CompanionLooks.of(HER).withVoice(null));

        CompanionLooks.init(dir, dir);

        assertTrue(CompanionLooks.of(HER).isEmpty());
    }

    @Test
    void forgettingOneLeavesTheOthers(@TempDir Path dir) {
        CompanionLooks.init(dir, dir);
        CompanionLooks.set(HER, new Looks("v1", null));
        CompanionLooks.set(OTHER, new Looks(null, "s2"));

        CompanionLooks.forget(HER);
        CompanionLooks.init(dir, dir);

        assertTrue(CompanionLooks.of(HER).isEmpty());
        assertEquals("s2", CompanionLooks.of(OTHER).skinId());
    }

    @Test
    void theOldPerCompanionBindingsAreTakenOverOnTheFirstRead(@TempDir Path dir) throws IOException {
        Path home = dir.resolve("companions").resolve(HER.toString());
        Files.createDirectories(home);
        Files.writeString(home.resolve("binding.json"),
                "{\"v\":1,\"provider\":\"p\",\"voice\":\"v9\",\"skin\":\"s9\"}", StandardCharsets.UTF_8);

        CompanionLooks.init(dir, dir);

        assertEquals(new Looks("v9", "s9"), CompanionLooks.of(HER));
        assertTrue(Files.isRegularFile(dir.resolve("looks.json")), "接过来的落在新文件里");
        assertTrue(Files.isRegularFile(home.resolve("binding.json")), "旧文件原样留着");
        assertTrue(CompanionLooks.of(OTHER).isEmpty());
    }
}
