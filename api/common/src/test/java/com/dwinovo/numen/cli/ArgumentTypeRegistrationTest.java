package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.dwinovo.numen.cli.CliFixture.door;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 她在 MC 指令树上的节点里,每一种参数类型都在 MC 的指令参数类型注册表里有着落:要么是原版已经登记的 Brigadier
 * 自带类型,要么是 Numen 经平台服务登记的那几种({@link NumenCli#registerArgumentTypes})。有一种没着落,服务器给她
 * 发指令树时连包都造不出来。真登记之后她的指令树造得出包,在 GameTest 里对着真服务器验。
 */
class ArgumentTypeRegistrationTest {

    /** 原版在指令参数类型注册表里登记过的 Brigadier 自带类型({@code brigadier:integer} 等)。 */
    private static final Set<Class<?>> BRIGADIER = Set.of(
            IntegerArgumentType.class, BoolArgumentType.class, StringArgumentType.class);

    @Test
    void everyArgumentTypeOnHerNodesIsRegistered() {
        door().registerCommands("gt_arg_types", "One action with every kind of argument.", g ->
                g.server("all", "Takes one of each.", (src, args) -> src.reply(TaskResult.ok("ok").toJson()),
                                Param.required("n", ArgType.integer(1, 9), "A number."),
                                Param.required("where", ArgType.integer(), "A coordinate."),
                                Param.required("which", ArgType.id(), "An id."),
                                Param.required("name", ArgType.string(), "A name."),
                                Param.required("word", ArgType.word(), "A word."),
                                Param.required("yes", ArgType.bool(), "A yes or no."),
                                Param.optional("flag", ArgType.bool(), "A flag, read inside the flags node."))
                        .example("numen gt_arg_types all 1 2 stone name word true --flag false"));
        Set<Class<?>> found = new HashSet<>();
        for (var node : NumenCli.herNodes()) {
            collect(node.build(), found);
        }
        Set<Class<?>> registered = new HashSet<>(BRIGADIER);
        registered.addAll(NumenCli.OWN_ARGUMENT_TYPES);
        assertTrue(registered.containsAll(found), "not registered: " + found.stream()
                .filter(c -> !registered.contains(c)).toList());
        assertTrue(found.containsAll(NumenCli.OWN_ARGUMENT_TYPES), "Numen's own types are on her nodes: " + found);
        assertTrue(found.containsAll(List.of(IntegerArgumentType.class, BoolArgumentType.class,
                StringArgumentType.class)), "so are Brigadier's: " + found);
    }

    private static void collect(CommandNode<CommandSourceStack> node, Set<Class<?>> found) {
        if (node instanceof ArgumentCommandNode<CommandSourceStack, ?> argument) {
            found.add(argument.getType().getClass());
        }
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            collect(child, found);
        }
    }
}
