package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.FakeWorld;
import com.dwinovo.numen.permission.Verdict;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** {@code numen.permission.rules()} 交给她读的三张表:硬规则、主人写的(带命中几次)、出厂的,每行照列表的写法。 */
@Tag("mc")
class PermissionApiRulesTest {

    private static boolean booted;

    @BeforeAll
    static void boot() {
        booted = FakeWorld.boot();
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过规则列表钉桩");
    }

    /** 主人写了一行 deny、从没命中过:她读到的三张表里,硬规则有不碰别人的、主人那张只有这一行并注明没命中过、出厂的有问主人。 */
    @Test
    void sheReadsTheHardTheOwnersAndTheFactoryRows() {
        PermissionStore store = new PermissionStore();
        store.add(Verdict.Kind.DENY, new PermissionStore.Row(Groups.parse("break(minecraft:tnt)"),
                PermissionStore.Source.OWNER, ""));
        PermissionApi.Rules rules = PermissionApi.rules(RuleBook.of(store, true));
        assertTrue(rules.hard().contains("deny break(other)"), rules.hard().toString());
        assertEquals(1, rules.yours().size(), rules.yours().toString());
        assertTrue(rules.yours().get(0).startsWith("deny break(minecraft:tnt)")
                && rules.yours().get(0).contains("never fired"), rules.yours().get(0));
        assertTrue(rules.factory().contains("ask break(owner)"), rules.factory().toString());
    }
}
