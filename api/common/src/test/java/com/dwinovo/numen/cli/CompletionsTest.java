package com.dwinovo.numen.cli;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 补全引擎的候选与"你是不是要写":候选只来自她用得了的节点,写了半截的只留以它开头的;最接近的按编辑距离挑,
 * 换位算一步,五个字符以内一步、更长的两步,只列最近的那一档,至多三个;不带命名空间的写法按原版的规矩比
 * {@code minecraft:} 下的 id。
 */
class CompletionsTest {

    @Test
    void anAdjacentSwapIsOneStep() {
        assertEquals(1, Completions.distance("swtich", "switch"));
        assertEquals(1, Completions.distance("dimond", "diamond"));
        assertEquals(1, Completions.distance("tke", "take"));
        assertEquals(3, Completions.distance("opshuns", "options"));
    }

    @Test
    void theNearestAreTheClosestTierWithinTheLimit() {
        assertEquals(List.of("switch"), Completions.nearest("swtich", List.of("--help", "emote", "options", "switch")));
        assertEquals(List.of("give"), Completions.nearest("gvie", List.of("gamemode", "give", "tell")));
        assertEquals(List.of("options"), Completions.nearest("opitns", List.of("options")),
                "六个字符以上,两步之内都算近");
        assertEquals(List.of(), Completions.nearest("tkea", List.of("take")), "五个字符以内只容一步");
        assertEquals(List.of(), Completions.nearest("opshuns", List.of("options")), "三步太远");
        assertEquals(List.of("linger"), Completions.nearest("lingre", List.of("lingerz", "linger")),
                "一步的在,两步的就不列");
        assertEquals(List.of("bat", "hat", "mat"), Completions.nearest("cat", List.of("bat", "hat", "mat", "rat")),
                "并列的按原来的顺序,至多三个");
        assertEquals(List.of("gave"), Completions.nearest("give", List.of("give", "gave")), "一模一样的不算纠正");
        assertEquals(List.of(), Completions.nearest("", List.of("give")));
    }

    @Test
    void aBareIdIsComparedWithTheVanillaPath() {
        List<String> items = List.of("minecraft:diamond", "minecraft:dirt", "othermod:dimond_ore");
        assertEquals(List.of("minecraft:diamond"), Completions.nearest("minecraft:dimond", items));
        assertEquals(List.of("minecraft:diamond"), Completions.nearest("dimond", items));
    }

    /** 她用不了的节点不给候选;写了半截的只留以它开头的。 */
    @Test
    void candidatesComeOnlyFromNodesSheCanUse() {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.literal("give").requires(source -> false)
                .then(RequiredArgumentBuilder.argument("x", StringArgumentType.word()).executes(ctx -> 1)));
        dispatcher.register(LiteralArgumentBuilder.literal("gamemode").executes(ctx -> 1));
        dispatcher.register(LiteralArgumentBuilder.literal("tell").executes(ctx -> 1));

        assertEquals(List.of("gamemode", "tell"),
                Completions.texts(Completions.at(dispatcher.parse("", new Object()), 0)));
        assertEquals(List.of("gamemode"), Completions.texts(Completions.at(dispatcher.parse("ga", new Object()), 2)));
        assertEquals("", Completions.didYouMean(dispatcher.parse("gvie x", new Object())),
                "她用不了 give,就不指给她");

        dispatcher.register(LiteralArgumentBuilder.literal("gift").executes(ctx -> 1));
        assertEquals("\nDid you mean: gift?", Completions.didYouMean(dispatcher.parse("gfit", new Object())));
    }
}
