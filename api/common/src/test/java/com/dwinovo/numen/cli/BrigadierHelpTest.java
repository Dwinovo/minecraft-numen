package com.dwinovo.numen.cli;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.BuiltInExceptionProvider;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code help <指令>} 从 Brigadier 挖出的几项:参数类型的称呼(注册表名加这一格的设定)、类型自带的例子、接下来能写什么
 * (有上限,给总数,写半截就缩小);以及写错时的"你是不是要写",原版与模组的指令({@link CommandRunner#problem})和
 * Numen 的命令是同一个函数。类型的称呼要 MC 的指令参数类型注册表,所以先引导 MC;真服务器上的 {@code help give} 与
 * {@code give @s minecraft:dimond} 在 GameTest 里验。
 */
@Tag("mc")
class BrigadierHelpTest {

    private static boolean booted;

    /** 十五个名字:多过一屏的上限,又好数。 */
    private static final List<String> NAMES = IntStream.rangeClosed(1, 15)
            .mapToObj(i -> (i < 10 ? "alex0" : "alex") + i).toList();

    /** 引导之前 Brigadier 内置报错的说法。 */
    private static BuiltInExceptionProvider brigadierWords;

    @BeforeAll
    static void boot() {
        brigadierWords = CommandSyntaxException.BUILT_IN_EXCEPTIONS;
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    /**
     * 引导 MC 时 {@code SharedConstants} 把 Brigadier 的内置报错换成了 MC 的说法("Unknown or incomplete command…")。
     * 换回引导之前的那一份:同一个 JVM 里别的命令测试读的是 Brigadier 的原话,不能随这个类先跑还是后跑而变。
     */
    @AfterAll
    static void restoreBrigadierWords() {
        CommandSyntaxException.BUILT_IN_EXCEPTIONS = brigadierWords;
    }

    @BeforeEach
    void needMinecraft() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过要注册表的测试");
    }

    /**
     * 一棵小树:{@code give <targets> <item> [<count>]}(targets 的候选是十五个名字),与字面分支的 {@code gamemode}——
     * 其中 {@code spectator} 她用不了。
     */
    private static CommandDispatcher<Object> tree() {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.literal("give")
                .then(RequiredArgumentBuilder.argument("targets", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(NAMES, builder))
                        .then(RequiredArgumentBuilder.argument("item", ResourceLocationArgument.id())
                                .executes(ctx -> 1)
                                .then(RequiredArgumentBuilder.argument("count", IntegerArgumentType.integer(1))
                                        .executes(ctx -> 1)))));
        dispatcher.register(LiteralArgumentBuilder.literal("gamemode")
                .then(LiteralArgumentBuilder.literal("survival").executes(ctx -> 1))
                .then(LiteralArgumentBuilder.literal("creative").executes(ctx -> 1))
                .then(LiteralArgumentBuilder.literal("spectator").requires(source -> false).executes(ctx -> 1)));
        return dispatcher;
    }

    @Test
    void aTypeIsNamedByTheRegistryWithItsSettings() {
        assertEquals("brigadier:integer (min 1, max 64)", BrigadierHelp.typeName(IntegerArgumentType.integer(1, 64)));
        assertEquals("brigadier:integer", BrigadierHelp.typeName(IntegerArgumentType.integer()));
        assertEquals("brigadier:string (type word)", BrigadierHelp.typeName(StringArgumentType.word()));
        assertEquals("minecraft:entity (amount single, type players)", BrigadierHelp.typeName(EntityArgument.player()));
        assertEquals("minecraft:entity (amount multiple, type entities)",
                BrigadierHelp.typeName(EntityArgument.entities()));
        assertEquals("minecraft:resource_location", BrigadierHelp.typeName(ResourceLocationArgument.id()));
    }

    /** 用法里出现的参数各一行,带类型与例子;接下来能写的列前十个,说一共几个。 */
    @Test
    void helpGiveMinesTheArgumentsOfItsUsageAndTheNextCandidates() {
        String mined = BrigadierHelp.mine(tree(), "give", new Object());
        assertEquals("""

                Arguments:
                  <targets> brigadier:string (type word) — e.g. word, words_with_underscores
                  <item> minecraft:resource_location — e.g. foo, foo:bar, 012
                  <count> brigadier:integer (min 1) — e.g. 0, 123, -123
                Can go next (10 of 15): alex01, alex02, alex03, alex04, alex05, alex06, alex07, alex08, alex09, \
                alex10""", mined);
    }

    /** 最后一截写了一半还读不通,只留以它开头的;多写一截,从那一格往下挖。 */
    @Test
    void writingMoreNarrowsTheCandidatesAndDigsDeeper() {
        assertEquals("\nCan go next: survival", BrigadierHelp.mine(tree(), "gamemode sur", new Object()));
        String deeper = BrigadierHelp.mine(tree(), "give alex01", new Object());
        assertTrue(deeper.startsWith("\nArguments:\n  <item> minecraft:resource_location"), deeper);
        assertFalse(deeper.contains("<targets>"), "已经写了的那格不再列: " + deeper);
    }

    /** 字面的分支就是接下来能写的;她用不了的那一格不出现。 */
    @Test
    void literalsAreCandidatesAndUnusableNodesAreLeftOut() {
        assertEquals("\nCan go next: creative, survival", BrigadierHelp.mine(tree(), "gamemode", new Object()));
        assertEquals("\nDid you mean: survival?", BrigadierHelp.mine(tree(), "gamemode survivl", new Object()),
                "写了半截却对不上任何候选,就是写错了");
    }

    /**
     * 原版与模组的指令写错了:Brigadier 的原话、位置、这条的用法,候选接在最后——和 Numen 命令同一个函数。她用不了的
     * 指令不指给她。
     */
    @Test
    void aMistypedCommandEndsWithTheNearestCandidates() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(Commands.literal("give").requires(source -> source.hasPermission(2))
                .then(Commands.argument("targets", StringArgumentType.word())
                        .then(Commands.argument("item", new KnownIds(List.of("minecraft:diamond", "minecraft:dirt")))
                                .executes(ctx -> 1))));
        dispatcher.register(Commands.literal("tell").then(Commands.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> 1)));

        String item = CommandRunner.problem(dispatcher, "give her minecraft:dimond", her(2));
        assertEquals("""
                unknown id minecraft:dimond at position 9: give her <--[HERE]
                Usage: /give <targets> <item>
                Did you mean: minecraft:diamond?""", item);

        assertEquals("there is no /gvie command on this server. help lists the commands you can run.\n"
                + "Did you mean: give?", CommandRunner.problem(dispatcher, "gvie her minecraft:diamond", her(2)));
        assertEquals("there is no /gvie command on this server. help lists the commands you can run.",
                CommandRunner.problem(dispatcher, "gvie her minecraft:diamond", her(0)),
                "没有 OP 时她用不了 give,就不指给她");
    }

    private static CommandSourceStack her(int level) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, level, "her",
                Component.literal("her"), null, null);
    }

    /** 像物品参数那样认一张 id 表:不认的报错(位置在这个 id 开头),补全列出整张表。 */
    private record KnownIds(List<String> ids) implements ArgumentType<String> {

        private static final DynamicCommandExceptionType UNKNOWN = new DynamicCommandExceptionType(
                id -> new LiteralMessage("unknown id " + id));

        @Override
        public String parse(StringReader reader) throws CommandSyntaxException {
            int start = reader.getCursor();
            String id = reader.readUnquotedString() + (reader.canRead() && reader.peek() == ':' ? readRest(reader) : "");
            if (!ids.contains(id)) {
                reader.setCursor(start);
                throw UNKNOWN.createWithContext(reader, id);
            }
            return id;
        }

        private static String readRest(StringReader reader) {
            reader.skip();
            return ":" + reader.readUnquotedString();
        }

        @Override
        public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
            return SharedSuggestionProvider.suggest(ids, builder);
        }
    }
}
