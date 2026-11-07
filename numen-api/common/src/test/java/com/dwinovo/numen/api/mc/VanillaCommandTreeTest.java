package com.dwinovo.numen.api.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.BuiltInExceptionProvider;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原版的指令树(专用服那一套,注册表取原版的)上,她能用哪些、写不通时怎么说、{@code help} 挖出什么:等级 0 是没有 OP,等级 2 是
 * 服务器把她记进 OP 表。真服务器上的树还多出模组的节点,{@code /numen} 不给她用由 GameTest 守。
 */
@Tag("mc")
class VanillaCommandTreeTest {

    private static CommandDispatcher<CommandSourceStack> vanilla;

    private static BuiltInExceptionProvider brigadierWords;

    @BeforeAll
    static void boot() {
        brigadierWords = CommandSyntaxException.BUILT_IN_EXCEPTIONS;
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        vanilla = new Commands(Commands.CommandSelection.DEDICATED,
                Commands.createValidationContext(VanillaRegistries.createLookup())).getDispatcher();
    }

    /** 引导时 Brigadier 的内置报错被换成了 MC 的说法;换回去,同一个 JVM 里别的命令测试读的是 Brigadier 的原话。 */
    @AfterAll
    static void restoreBrigadierWords() {
        CommandSyntaxException.BUILT_IN_EXCEPTIONS = brigadierWords;
    }

    /** 她的来源;没有服务器,补全里的在线玩家名是空的(选择器照常补)。 */
    private static CommandSourceStack her(int level) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, level, "her",
                Component.literal("her"), null, null) {
            @Override
            public java.util.Collection<String> getOnlinePlayerNames() {
                return List.of();
            }
        };
    }

    /** {@code numen.mc.run("help …")} 交回的话:原版那几行用法,带了指令时接上挖出的几行。 */
    private static String help(String command, int level) {
        List<String> out = new ArrayList<>(BrigadierHelp.usage(vanilla, command, her(level)));
        if (!command.isEmpty()) {
            BrigadierHelp.mine(vanilla, command, her(level)).lines().filter(l -> !l.isBlank()).forEach(out::add);
        }
        return String.join("\n", out);
    }

    /** 没有 OP 时 give 写得通也用不了:说清是服务器不让,指给她 help。 */
    @Test
    void withoutOpGiveIsRefusedAsTheServersRefusal() {
        String problem = McApi.problem(vanilla, "give @s minecraft:diamond", her(0));
        assertTrue(problem != null && problem.contains("the server does not let you use /give"), problem);
    }

    /** {@code help} 按她的来源过滤:没有 OP 时有 msg、没有 give;有 OP 后 give 也在。 */
    @Test
    void helpListsOnlyWhatTheServerLetsHerRun() {
        String guest = help("", 0);
        assertTrue(guest.contains("/msg <targets> <message>") && !guest.contains("/give "), guest);
        String op = help("", 2);
        assertTrue(op.contains("/give <targets> <item> [<count>]"), op);
    }

    /**
     * {@code help give}:原版那一行用法在前,后面是参数类型与类型自带的例子,再是此刻接下来能写的(她自己、选择器);写了半截的
     * 物品 id 只列以它开头的。
     */
    @Test
    void helpGiveMinesTypesExamplesAndCandidates() {
        String said = help("give", 2);
        assertTrue(said.startsWith("/give <targets> <item> [<count>]\n"), said);
        assertTrue(said.contains("\n  <targets> minecraft:entity (amount multiple, type players) — e.g. Player, ")
                && said.contains("\n  <item> minecraft:item_stack — e.g. stick, minecraft:stick")
                && said.contains("\n  <count> brigadier:integer (min 1"), said);
        assertTrue(said.contains("\nCan go next") && said.contains("@s"), said);

        String items = help("give @s minecraft:diamond_", 2);
        String next = items.substring(items.indexOf("\nCan go next") + 1);
        assertTrue(next.contains("minecraft:diamond_axe") && Arrays.stream(next.substring(next.indexOf(": ") + 2).split(", "))
                .allMatch(id -> id.startsWith("minecraft:diamond_")), items);
    }
}
