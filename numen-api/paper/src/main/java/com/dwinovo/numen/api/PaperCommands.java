package com.dwinovo.numen.api;

import com.dwinovo.numen.api.entity.NumenCommands;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.Commands;
import net.minecraft.commands.CommandSourceStack;

/**
 * Numen 的指令树是原版的 Brigadier 树({@code CommandDispatcher<CommandSourceStack>}),NeoForge 与 Fabric 把它直接交给
 * 原版的调度器。Paper 的注册器要的是它自己的"节点"(来源类型是 API 里的 {@code io.papermc.paper.command.brigadier.CommandSourceStack}),
 * 而 Paper 上原版的 {@code CommandSourceStack} 正是实现了那个接口的类:运行时节点收到的来源就是原版的,
 * 所以先在一个空调度器里把树照常建好,再把根下的节点原样交给注册器。
 *
 * <p>数据包重载时 Paper 重建指令并再发一次 {@code LifecycleEvents.COMMANDS},每次都重新建一棵(节点不跨次复用)。
 */
final class PaperCommands {

    private PaperCommands() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void register(Commands registrar) {
        CommandDispatcher<CommandSourceStack> tree = new CommandDispatcher<>();
        NumenCommands.register(tree);
        for (CommandNode<CommandSourceStack> root : tree.getRoot().getChildren()) {
            registrar.register((LiteralCommandNode) root);
        }
    }
}
