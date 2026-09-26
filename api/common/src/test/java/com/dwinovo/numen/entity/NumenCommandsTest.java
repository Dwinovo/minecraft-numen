package com.dwinovo.numen.entity;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.task.TaskResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code /numen} 这棵树的形状与观众:玩家的管理指令(权限、征询、drive……)每条都解析到有执行体的节点、参数取得到,
 * 写错的解析不通;整个根只给不是她的来源,她一条都解析不通。她的命令组不在 MC 的指令树上。执行与鉴权(只认主人)、
 * 真服务器发给玩家的指令树,在 GameTest 里钉。
 */
@Tag("mc")
class NumenCommandsTest {

    private static final Param<Integer> COUNT = Param.required("count", ArgType.integer(1, 64), "How many.");
    private static final Param<String> FROM = Param.optional("from", ArgType.word(), "Where from.");

    private static boolean booted;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    /** 控制台那样的来源:不是她。 */
    private CommandSourceStack console;
    /** 她:来源实体是一具 {@link NumenPlayer}。 */
    private CommandSourceStack her;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
        if (booted) {
            NumenPlugins.register(numen -> numen.registerCommands("gt_tree", "A group that stays off the MC tree.", g -> {
                g.server("take", "Take some.", (src, args) -> src.reply(TaskResult.ok("took").toJson()), COUNT, FROM)
                        .example("gt_tree take 3 --from chest");
                g.client("jot", "Jot on the owner's client.", (src, args) -> src.reply(TaskResult.ok("jot").toJson()),
                        COUNT).example("gt_tree jot 2");
            }));
        }
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        assumeTrue(booted, "Minecraft 引导不可用,跳过命令树钉桩");
        dispatcher = new CommandDispatcher<>();
        NumenCommands.register(dispatcher);
        console = source(null);
        her = source(body());
    }

    private static CommandSourceStack source(NumenPlayer entity) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, 4, "test",
                Component.literal("test"), null, entity);
    }

    /**
     * 一具不经构造的 {@link NumenPlayer}:它的构造要一台服务器,而这里只让 {@code requires} 看"来源实体是不是她"。
     */
    private static NumenPlayer body() throws ReflectiveOperationException {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (NumenPlayer) ((sun.misc.Unsafe) field.get(null)).allocateInstance(NumenPlayer.class);
    }

    /** 整行解析到底、落在有执行体的节点上,返回参数表。 */
    private Map<String, ParsedArgument<CommandSourceStack, ?>> runs(String command, CommandSourceStack source) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, source);
        assertFalse(parsed.getReader().canRead(), "not fully parsed: " + command + " " + parsed.getExceptions());
        CommandContextBuilder<CommandSourceStack> context = parsed.getContext();
        while (context.getChild() != null) {
            context = context.getChild();
        }
        assertNotNull(context.getCommand(), "no command at the end of: " + command);
        return context.getArguments();
    }

    private Map<String, ParsedArgument<CommandSourceStack, ?>> runs(String command) {
        return runs(command, console);
    }

    private boolean fails(String command, CommandSourceStack source) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, source);
        CommandContextBuilder<CommandSourceStack> context = parsed.getContext();
        return parsed.getReader().canRead() || !parsed.getExceptions().isEmpty() || context.getCommand() == null;
    }

    private boolean fails(String command) {
        return fails(command, console);
    }

    @Test
    void modeShowsOrSets() {
        assertEquals("Aria", runs("numen permission mode Aria").get("name").getResult());
        runs("numen permission mode Aria ask");
        runs("numen permission mode Aria bypass");
        runs("numen permission mode Aria observe");
        assertTrue(fails("numen permission mode Aria chaos"));
    }

    @Test
    void rulesListAddRemoveReset() {
        runs("numen permission rules list");
        assertEquals("break(placed & minecraft:cobblestone)",
                runs("numen permission rules add allow break(placed & minecraft:cobblestone)").get("rule").getResult());
        runs("numen permission rules add deny attack(villager)");
        runs("numen permission rules add ask take(*)");
        assertEquals(2, runs("numen permission rules remove ask 2").get("row").getResult());
        runs("numen permission rules reset");
        assertTrue(fails("numen permission rules add maybe take(*)"), "only deny, ask and allow are tables");
        assertTrue(fails("numen permission rules remove ask 0"), "rows are numbered from 1");
    }

    @Test
    void consentTakesAnIdAndADenyTakesAnOptionalNote() {
        Map<String, ParsedArgument<CommandSourceStack, ?>> bare = runs("numen consent allow 42");
        assertEquals(42L, bare.get("id").getResult());
        Map<String, ParsedArgument<CommandSourceStack, ?>> noted = runs("numen consent deny 7 那是我的柱子 别动");
        assertEquals("那是我的柱子 别动", noted.get("note").getResult());
        runs("numen consent remember 9");
        runs("numen consent deny 9");
        assertTrue(fails("numen consent allow 42 小心点"), "a note only goes with a deny");
        assertTrue(fails("numen consent remember 9 小心点"), "a note only goes with a deny");
        assertTrue(fails("numen consent allow"), "a request id is required");
        assertTrue(fails("numen consent maybe 3"));
    }

    @Test
    void driveTakesACompanionAndTheRestOfTheLine() {
        assertEquals("give @s minecraft:diamond 2",
                runs("numen drive Aria give @s minecraft:diamond 2").get("line").getResult());
        assertTrue(fails("numen drive Aria"), "a line is required");
        assertTrue(fails("numen drive Aria task status", source(null).withPermission(0)), "drive is for ops");
    }

    /** 她的命令组不在 MC 的指令树上:谁都解析不到,谁的用法里都没有。 */
    @Test
    void herGroupsAreNotOnTheMcTree() {
        for (CommandSourceStack source : new CommandSourceStack[]{console, her}) {
            assertTrue(fails("numen gt_tree take 3 --from chest", source));
            assertTrue(fails("numen help", source));
            assertTrue(fails("gt_tree take 3", source));
            assertTrue(dispatcher.getSmartUsage(dispatcher.getRoot(), source).values().stream()
                    .noneMatch(usage -> usage.contains("gt_tree")), "a usage lists her group");
        }
        assertTrue(dispatcher.getRoot().getChild("gt_tree") == null, "her group is a root of the MC tree");
    }

    /** 管理同伴的指令只给玩家:她召唤不了同伴、改不了权限、答不了征询、drive 不了别人。 */
    @Test
    void thePlayersVerbsAreNotHers() {
        for (String line : new String[]{"numen player summon Aria", "numen permission rules list",
                "numen consent allow 42", "numen settings", "numen drive Aria help"}) {
            runs(line, console);
            assertTrue(fails(line, her), "she can reach " + line);
        }
        assertTrue(!dispatcher.getRoot().getChild(NumenCommands.ROOT).canUse(her), "she can use the /numen root");
        assertTrue(!dispatcher.getSmartUsage(dispatcher.getRoot(), her).containsKey(
                dispatcher.getRoot().getChild(NumenCommands.ROOT)), "her usage lists /numen");
    }

    /** 同名的一格挂两次:Brigadier 会悄悄并成一格,留下先来那一格的观众,所以当场抛出。 */
    @Test
    void aNameUnderNumenIsGraftedOnce() {
        assertThrows(IllegalStateException.class, () -> NumenCommands.graft(dispatcher,
                net.minecraft.commands.Commands.literal("permission")));
    }
}
