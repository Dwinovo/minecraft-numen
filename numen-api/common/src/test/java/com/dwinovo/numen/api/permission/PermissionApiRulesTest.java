package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.FakeWorld;
import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.agent.scope.Handle;
import com.dwinovo.numen.permission.Verdict;

import net.minecraft.network.chat.Component;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** {@code numen.permission.rules()} 交给她读的三张表:硬规则、主人写的(带命中几次)、出厂的,每行照列表的写法;连同规则里能写的词。 */
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

    /** 规则里能写的词:自带的照规则里的写法列出来(带参数的带着例子),此刻登记着的接在后面,撤回了就不在了。 */
    @Test
    void sheReadsTheWordsARuleCanUseBuiltInAndRegistered() {
        List<String> builtIn = PermissionApi.rules(RuleBook.of(new PermissionStore(), true)).words();
        assertEquals(List.of("self", "owner", "other", "nature", "structure(#minecraft:village)",
                "block_entity", "named", "near(owner)"), builtIn.subList(0, 8));
        Handle word = Questions.RULE_WORD.register("gt_listed", new RuleWord("is listed", Component.literal("listed"),
                Set.of(), view -> true));
        try {
            assertTrue(PermissionApi.rules(RuleBook.of(new PermissionStore(), true)).words().contains("gt_listed"));
        } finally {
            word.close();
        }
        assertFalse(PermissionApi.rules(RuleBook.of(new PermissionStore(), true)).words().contains("gt_listed"));
    }
}
