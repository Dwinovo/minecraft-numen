package com.dwinovo.numen.plugins;

import com.dwinovo.numen.NumenApiFixture;
import com.dwinovo.numen.api.NumenPlugins;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 联动的闸门:目标模组没装,联动不装,也不算这台服务器上的插件。 */
class GateTest {

    /** 联动的目标模组没装:闸门不装它,花名册里没有这一行。 */
    @Test
    void aLinkWhoseTargetIsNotInstalledIsNotAPlugin() {
        NumenApiFixture.install();
        boolean[] installed = {false};

        new Gate(mod -> false, path -> null).open("gametest_absent_mod", "gt_absent", () -> () -> installed[0] = true);

        assertFalse(installed[0], "the link was installed without its target");
        assertTrue(NumenPlugins.roster().plugins().stream().noneMatch(p -> p.id().equals("gt_absent")),
                "a link without its target is on the plugin list");
    }
}
