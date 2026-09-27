package com.dwinovo.numen.plugins.ysm;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.core.WrittenCommandsLint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;

import static com.dwinovo.numen.core.WrittenCommandsLint.assertReads;

/**
 * YSM 联动写着的命令不走样:随它发的技能文档与 {@code ysm} 组的说明,按命令树(core 的组、本组、原版的指令)读一遍。
 * 本组经 {@link NumenPlugins} 那扇门、用联动自己登记它的那一段装上,和 {@link NumenYsm#install} 里的一样;存法取 NeoForge
 * 那一种,说明文字与存法无关。
 */
class YsmWrittenCommandsTest {

    @BeforeAll
    static void install() {
        WrittenCommandsLint.install();
        Ysm ysm = new Ysm(Ysm.Storage.NEOFORGE);
        NumenPlugins.register(numen -> YsmCommands.install(numen, ysm));
    }

    @Test
    void theBundledSkillsWriteCommandsThatRead() throws IOException, URISyntaxException {
        assertReads(WrittenCommandsLint.documents("plugins/ysm/skills"), 5);
    }

    @Test
    void theGroupsDescriptionsWriteCommandsThatRead() {
        assertReads(WrittenCommandsLint.registeredUnder(YsmCommands.GROUP), 5);
    }
}
