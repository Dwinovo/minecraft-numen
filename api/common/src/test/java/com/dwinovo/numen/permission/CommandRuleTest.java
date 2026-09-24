package com.dwinovo.numen.permission;

import com.dwinovo.numen.data.ModLanguageData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.minecraft.network.chat.Component;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 执行游戏指令这个动作:{@code command(根名)} 的写法与匹配,别名同指一个节点就同认,裁决照三张表走、出厂层放行
 * Numen 自己的与只读、只说话的指令、其余没有规则就问,主人层压过出厂层,"允许并记住"钉上她打的那个根名、只写进
 * 主人层,征询清单上给主人看的是整行,动词文案两种语言都有。指令树用一棵小 Brigadier 树
 * 代替服务器的——别名的认法只看节点的重定向。
 */
@Tag("mc")
class CommandRuleTest {

    private static boolean booted;
    private static RootCommandNode<Object> tree;

    @BeforeAll
    static void boot() {
        booted = FakeWorld.boot();
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        LiteralCommandNode<Object> teleport = dispatcher.register(LiteralArgumentBuilder.literal("teleport")
                .then(RequiredArgumentBuilder.argument("where", StringArgumentType.greedyString()).executes(c -> 1)));
        dispatcher.register(LiteralArgumentBuilder.literal("tp").redirect(teleport));
        LiteralCommandNode<Object> msg = dispatcher.register(LiteralArgumentBuilder.literal("msg")
                .then(RequiredArgumentBuilder.argument("text", StringArgumentType.greedyString()).executes(c -> 1)));
        dispatcher.register(LiteralArgumentBuilder.literal("tell").redirect(msg));
        dispatcher.register(LiteralArgumentBuilder.literal("w").redirect(msg));
        LiteralCommandNode<Object> teammsg = dispatcher.register(LiteralArgumentBuilder.literal("teammsg")
                .then(RequiredArgumentBuilder.argument("text", StringArgumentType.greedyString()).executes(c -> 1)));
        dispatcher.register(LiteralArgumentBuilder.literal("tm").redirect(teammsg));
        dispatcher.register(LiteralArgumentBuilder.literal("setblock")
                .then(RequiredArgumentBuilder.argument("rest", StringArgumentType.greedyString()).executes(c -> 1)));
        tree = dispatcher.getRoot();
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过指令规则钉桩");
    }

    private static final Facts NO_WORLD = new Facts(null, null, null, null);

    private static Action run(String line) {
        return Action.command(line, tree);
    }

    private static Gate gate(Mode mode, List<String> deny, List<String> ask, List<String> allow) {
        RuleSet owner = new RuleSet(deny.stream().map(Rule::parse).toList(), ask.stream().map(Rule::parse).toList(),
                allow.stream().map(Rule::parse).toList());
        return new Gate(null, mode, owner, RuleSet.factory(), new PlacedBlocks(), List.of());
    }

    // ==================== 动作 ====================

    @Test
    void theActionCarriesTheLineAndTheNamesItAnswersTo() {
        Action tp = run("tp 1 64 1");
        assertEquals(Action.Kind.COMMAND, tp.kind());
        assertEquals("tp 1 64 1", tp.command().line());
        assertEquals("tp", tp.command().root());
        assertEquals(Set.of("tp", "teleport"), tp.command().names(), "别名与它重定向去的那个同认");
        assertEquals(Set.of("teleport", "tp"), run("teleport 0 0 0").command().names(), "反过来也一样");
        assertEquals(Set.of("msg", "tell", "w"), run("w Steve hi").command().names(), "几个别名指同一个节点");
        assertEquals(Set.of("setblock"), run("setblock 0 64 0 stone").command().names());
        assertEquals(Set.of("nosuch"), run("nosuch thing").command().names(), "树上没有的根只认它自己");
        assertEquals("command /setblock 0 64 0 stone", run("setblock 0 64 0 stone").describe());
    }

    // ==================== 写法 ====================

    @Test
    void commandRulesNameRootsAndTeachTheSlashMistake() {
        Rule rule = Rule.parse("command(setblock)");
        assertEquals(Action.Kind.COMMAND, rule.kind());
        assertEquals("runs /setblock", rule.describe());
        assertEquals("command(!msg & !trigger)", Rule.parse("command(!msg & !trigger)").toString());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Rule.parse("command(/tp)"))
                .getMessage().contains("without the slash"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Rule.parse("chew(tp)"))
                .getMessage().contains("command"), "认得的动词里列出 command");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Rule.parse("break(tp)"))
                .getMessage().contains("unknown signal 'tp'"), "别的动词里的裸词仍是信号");
    }

    // ==================== 匹配 ====================

    @Test
    void aRootNameMatchesTheCommandAndItsAliases() {
        assertTrue(Rule.parse("command(tp)").matches(run("teleport 0 64 0"), NO_WORLD), "写别名盖得住本名");
        assertTrue(Rule.parse("command(teleport)").matches(run("tp 0 64 0"), NO_WORLD), "写本名盖得住别名");
        assertTrue(Rule.parse("command(msg)").matches(run("tell Steve hi"), NO_WORLD));
        assertFalse(Rule.parse("command(msg)").matches(run("setblock 0 64 0 stone"), NO_WORLD));
        assertTrue(Rule.parse("command(!msg)").matches(run("setblock 0 64 0 stone"), NO_WORLD), "取反");
        assertTrue(Rule.parse("command(*)").matches(run("msg Steve hi"), NO_WORLD));
        assertTrue(Rule.parse("*(*)").matches(run("msg Steve hi"), NO_WORLD), "任何动作包括指令");
        assertFalse(Rule.parse("drop(*)").matches(run("msg Steve hi"), NO_WORLD), "别的动词不认指令");
        assertFalse(Rule.parse("*(placed)").matches(run("setblock 0 64 0 stone"), NO_WORLD),
                "信号说的是方块与实体,一条指令没有它们");
    }

    // ==================== 裁决 ====================

    @Test
    void noRuleMeansAskAndTheOwnersRowsDecide() {
        Verdict factory = gate(Mode.ASK, List.of(), List.of(), List.of()).judge(run("give @s diamond"), null);
        assertTrue(factory.asks(), "give 没有一行规则说到:问");
        assertNull(factory.rule());

        Gate owner = gate(Mode.ASK, List.of("command(tp)"), List.of("command(setblock)"),
                List.of("command(msg)", "command(trigger)"));
        assertTrue(owner.judge(run("tell Steve on my way"), null).allowed(), "允许 msg,别名 tell 同样放行");
        Verdict denied = owner.judge(run("teleport ~ ~10 ~"), null);
        assertEquals(Verdict.Kind.DENY, denied.kind(), "拒绝 tp,换成本名 teleport 也绕不过去");
        assertTrue(denied.reason().contains("denied by rule command(tp)"), denied.reason());
        Verdict asked = owner.judge(run("setblock 0 64 0 stone"), null);
        assertEquals("command(setblock)", asked.rule().toString());
        assertTrue(asked.reason().contains("runs /setblock"), asked.reason());

        assertTrue(gate(Mode.BYPASS, List.of(), List.of(), List.of()).judge(run("setblock 0 64 0 stone"), null)
                .allowed());
        assertEquals(Verdict.Kind.DENY, gate(Mode.OBSERVE, List.of(), List.of(), List.of("command(*)"))
                .judge(run("msg Steve hi"), null).kind(), "observe 连允许过的指令也不执行");
    }

    @Test
    void theFactoryRowsAreCommandRulesLikeAnyOther() {
        List<Rule> commands = RuleSet.factory().allow().stream()
                .filter(r -> r.kind() == Action.Kind.COMMAND).toList();
        assertEquals(List.of("command(numen)", "command(help)", "command(list)", "command(me)", "command(msg)",
                "command(teammsg)", "command(seed)", "command(random)"),
                commands.stream().map(Rule::toString).toList(), "出厂指令行与别的规则同一种写法、同一个解析");
        assertTrue(RuleSet.factory().ask().stream().noneMatch(r -> r.kind() == Action.Kind.COMMAND),
                "出厂 ask 表不写指令:没说到的本来就问");
    }

    @Test
    void theFactoryLetsHerOwnAndReadOnlyCommandsRun() {
        Gate bare = gate(Mode.ASK, List.of(), List.of(), List.of());
        for (String line : List.of("numen goto 10 64 10", "help", "help give", "list", "me waves",
                "msg Steve hi", "tell Steve hi", "w Steve hi", "teammsg regroup", "tm regroup", "seed",
                "random value 1..6")) {
            assertTrue(bare.judge(run(line), null).allowed(), "出厂放行 /" + line);
        }
        for (String line : List.of("setblock 0 64 0 stone", "give @s diamond", "tp 0 64 0")) {
            Verdict verdict = bare.judge(run(line), null);
            assertTrue(verdict.asks(), "没有规则说到的照旧问 /" + line);
            assertNull(verdict.rule(), "问它的不是哪一行出厂规则 /" + line);
        }
    }

    @Test
    void theOwnersRowsOverrideTheFactoryOnes() {
        Gate strict = gate(Mode.ASK, List.of("command(msg)"), List.of("command(help)"), List.of());
        Verdict msg = strict.judge(run("msg Steve hi"), null);
        assertEquals(Verdict.Kind.DENY, msg.kind(), "主人拒绝 msg 压过出厂放行");
        assertTrue(msg.reason().contains("denied by rule command(msg)"), msg.reason());
        assertEquals(Verdict.Kind.DENY, strict.judge(run("w Steve hi"), null).kind(), "别名 w 也绕不过去");
        assertEquals("command(help)", strict.judge(run("help"), null).rule().toString(),
                "主人的 ask 行压过出厂 allow 行");
        assertTrue(strict.judge(run("list"), null).allowed(), "主人没说到的出厂行照旧放行");
    }

    @Test
    void rememberingWritesTheOwnersLayerOnly() {
        Gate bare = gate(Mode.ASK, List.of(), List.of(), List.of());
        Action setblock = run("setblock 0 64 0 stone");
        ConsentItem item = bare.consentItem(setblock, bare.judge(setblock, null), null);
        PermissionStore store = new PermissionStore();
        store.remember(List.of(item.remember()));

        assertEquals(List.of(Rule.parse("command(setblock)")), store.rules().allow(), "以后都允许写进主人层");
        assertEquals(RuleSet.FACTORY_ALLOW, RuleSet.factory().allow().stream().map(Rule::toString).toList(),
                "出厂层不跟着变");
        Gate after = new Gate(null, Mode.ASK, store.rules(), RuleSet.factory(), new PlacedBlocks(), List.of());
        assertTrue(after.judge(run("setblock 1 64 1 dirt"), null).allowed(), "记住以后 setblock 不再问");
        assertTrue(after.judge(run("help"), null).allowed(), "出厂行照旧放行");
        assertTrue(after.judge(run("give @s diamond"), null).asks(), "别的照旧问");
    }

    // ==================== 征询与记住 ====================

    @Test
    void theConsentItemShowsTheWholeLineAndRemembersTheRoot() {
        Gate bare = gate(Mode.ASK, List.of(), List.of(), List.of());
        Action setblock = run("setblock 0 64 0 stone");
        ConsentItem item = bare.consentItem(setblock, bare.judge(setblock, null), null);
        assertEquals(Action.Kind.COMMAND, item.kind());
        assertNull(item.pos());
        assertNull(item.icon(), "指令没有图标,给主人看名字");
        assertEquals(Component.literal("/setblock 0 64 0 stone"), item.name());
        assertEquals("command(setblock)", item.remember().toString(), "记住的是根名:以后的 setblock 都不再问");
        assertTrue(item.remember().matches(setblock, NO_WORLD), "记下的规则盖得住这次问的动作");
        assertEquals("command 1 /setblock 0 64 0 stone: " + Verdict.UNCOVERED,
                ConsentItem.listingText(List.of(item)));

        // 主人写的 ask 行已经点了名,只留着它;带取反的条件原样留着
        Gate named = gate(Mode.ASK, List.of(), List.of("command(tp)"), List.of());
        Action teleport = run("teleport ~ ~10 ~");
        assertEquals("command(tp & teleport)",
                named.consentItem(teleport, named.judge(teleport, null), null).remember().toString());
        Gate broad = gate(Mode.ASK, List.of(), List.of("command(!msg)"), List.of());
        assertEquals("command(!msg & setblock)",
                broad.consentItem(setblock, broad.judge(setblock, null), null).remember().toString());
    }

    @Test
    void aGrantCoversTheSameLineOnly() {
        Gate ungranted = gate(Mode.ASK, List.of(), List.of(), List.of());
        Action first = run("setblock 0 64 0 stone");
        ConsentItem grant = ungranted.consentItem(first, ungranted.judge(first, null), null);
        Gate granted = new Gate(null, Mode.ASK, RuleSet.EMPTY, RuleSet.factory(), new PlacedBlocks(),
                List.of(grant));
        assertTrue(granted.judge(first, null).allowed());
        assertTrue(granted.judge(run("setblock 0 65 0 stone"), null).asks(), "答应的是这一整行,换一行另问");
    }

    // ==================== 显示文案 ====================

    @Test
    void everyVerbHasItsWordInBothLanguages() {
        for (String locale : List.of("en_us", "zh_cn")) {
            Map<String, String> lang = new HashMap<>();
            ModLanguageData.addTranslations(locale, lang::put);
            for (Action.Kind kind : Action.Kind.values()) {
                assertTrue(lang.containsKey(ModLanguageData.Keys.CONSENT_VERB_PREFIX + kind.verb()),
                        locale + " has no word for " + kind.verb());
            }
        }
        Map<String, String> en = new HashMap<>();
        ModLanguageData.addTranslations("en_us", en::put);
        Map<String, String> zh = new HashMap<>();
        ModLanguageData.addTranslations("zh_cn", zh::put);
        assertEquals("run", en.get(ModLanguageData.Keys.CONSENT_VERB_PREFIX + "command"));
        assertEquals("执行", zh.get(ModLanguageData.Keys.CONSENT_VERB_PREFIX + "command"));
    }
}
