package com.dwinovo.numen.core.debug;

import java.util.Arrays;
import java.util.List;

import com.dwinovo.numen.core.tools.BlockActionTools;
import com.dwinovo.numen.core.tools.MovementTools;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskRecord;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 寻路调试命令树,并入 {@code /numen} 根:
 * <pre>
 *   /numen debug                          翻转调试模式(路径粒子渲染 + UI 文本不过滤直出)
 *   /numen goto &lt;name&gt; &lt;y&gt;                该同伴走到目标高度
 *   /numen goto &lt;name&gt; &lt;x&gt; &lt;z&gt;            该同伴走到该水平位置(Y 自动落地表)
 *   /numen goto &lt;name&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;        该同伴走到精确格
 *   /numen mine &lt;name&gt; [count] &lt;block...&gt; 采集指定方块(空格分隔多个 id)
 *   /numen cancel &lt;name&gt;                  叫停该同伴当前任务
 * </pre>
 * 任务经与 LLM 工具相同的任务队列下发,占用/拒绝口径一致。
 */
public final class DebugCommands {

    private static final MovementTools MOVEMENT_TOOLS = new MovementTools();
    private static final BlockActionTools BLOCK_TOOLS = new BlockActionTools();
    /** mine 未给数量时的默认目标件数。 */
    private static final int DEFAULT_MINE_COUNT = 64;

    private DebugCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("numen")
                .then(Commands.literal("debug")
                        .executes(DebugCommands::toggleDebug))
                .then(Commands.literal("goto")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(DebugCommands::suggestCompanions)
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(DebugCommands::gotoPos))
                                .then(Commands.argument("column", ColumnPosArgument.columnPos())
                                        .executes(DebugCommands::gotoColumn))
                                .then(Commands.argument("level", IntegerArgumentType.integer())
                                        .executes(DebugCommands::gotoLevel))
                                .then(Commands.argument("block", StringArgumentType.word())
                                        .suggests(DebugCommands::suggestBlocks)
                                        .executes(DebugCommands::gotoNearestBlock))))
                .then(Commands.literal("mine")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(DebugCommands::suggestCompanions)
                                .then(Commands.argument("blocks", StringArgumentType.greedyString())
                                        .suggests(DebugCommands::suggestBlocksGreedy)
                                        .executes(DebugCommands::mine))))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(DebugCommands::suggestCompanions)
                                .executes(DebugCommands::cancel))));
    }

    // ==================== 补全 ====================

    /** 调用者名下同伴的名字。 */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestCompanions(CommandContext<CommandSourceStack> ctx,
                              com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        ServerPlayer caller = ctx.getSource().getPlayer();
        if (caller != null) {
            for (ServerPlayer p : caller.level().getServer().getPlayerList().getPlayers()) {
                if (p instanceof NumenPlayer np && np.isOwnedByPlayer(caller.getUUID())) {
                    builder.suggest(p.getName().getString());
                }
            }
        }
        return builder.buildFuture();
    }

    /** 全部方块 id(单 token 参数用)。 */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestBlocks(CommandContext<CommandSourceStack> ctx,
                          com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet(), builder);
    }

    /** 贪婪参数里补全最后一个 token(mine 的多方块清单用)。 */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestBlocksGreedy(CommandContext<CommandSourceStack> ctx,
                                com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        var offset = builder.createOffset(builder.getInput().lastIndexOf(' ') + 1);
        return SharedSuggestionProvider.suggestResource(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet(), offset);
    }

    // ==================== debug 开关 ====================

    private static int toggleDebug(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer caller = ctx.getSource().getPlayerOrException();
        boolean on = PathDebug.toggle(caller.getUUID());
        Services.NETWORK.sendToPlayer(caller, new ClientUiActionPayload(on
                ? ClientUiActionPayload.Action.DEBUG_TEXT_ON
                : ClientUiActionPayload.Action.DEBUG_TEXT_OFF));
        ctx.getSource().sendSuccess(() -> Component.literal(
                on ? "调试模式已开:路径粒子渲染 + UI 文本不过滤直出"
                   : "调试模式已关"), false);
        return 1;
    }

    // ==================== goto ====================

    /** goto <名> <x> <y> <z>(BlockPos 参数,支持 ~ 相对与准星坐标补全)。 */
    private static int gotoPos(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NumenPlayer companion = requireCompanion(ctx);
        if (companion == null) {
            return 0;
        }
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        return dispatchMoveTo(ctx, companion,
                (double) pos.getX(), (double) pos.getY(), (double) pos.getZ(), null);
    }

    /** goto <名> <x> <z>(列坐标,支持 ~;Y 自动落地表)。 */
    private static int gotoColumn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NumenPlayer companion = requireCompanion(ctx);
        if (companion == null) {
            return 0;
        }
        var col = ColumnPosArgument.getColumnPos(ctx, "column");
        return dispatchMoveTo(ctx, companion, (double) col.x(), null, (double) col.z(), null);
    }

    /** goto <名> <y>(仅高度)。 */
    private static int gotoLevel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NumenPlayer companion = requireCompanion(ctx);
        if (companion == null) {
            return 0;
        }
        int y = IntegerArgumentType.getInteger(ctx, "level");
        return dispatchMoveTo(ctx, companion, null, (double) y, null, null);
    }

    /** goto <名> <方块id>(走到最近的一个旁边,带注册表补全)。 */
    private static int gotoNearestBlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NumenPlayer companion = requireCompanion(ctx);
        if (companion == null) {
            return 0;
        }
        return dispatchMoveTo(ctx, companion, null, null, null,
                StringArgumentType.getString(ctx, "block"));
    }

    private static int dispatchMoveTo(CommandContext<CommandSourceStack> ctx, NumenPlayer companion,
                                      Double x, Double y, Double z, String block) {
        TaskRecord record;
        try {
            record = (TaskRecord) MOVEMENT_TOOLS.moveTo(x, y, z, block,
                    TaskDispatch.ctx("debug-goto", companion));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        TaskDispatch.enqueue(companion, record, reply ->
                ctx.getSource().sendFailure(Component.literal(reply)));
        ctx.getSource().sendSuccess(() -> Component.literal(
                companion.getName().getString() + " ← " + record.describe()), false);
        return 1;
    }

    // ==================== mine / cancel ====================

    private static int mine(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NumenPlayer companion = requireCompanion(ctx);
        if (companion == null) {
            return 0;
        }
        // 参数形态:[count] <block id...>——首 token 是数字则作数量
        List<String> tokens = Arrays.asList(
                StringArgumentType.getString(ctx, "blocks").trim().split("\\s+"));
        int count = DEFAULT_MINE_COUNT;
        List<String> blockIds = tokens;
        if (!tokens.isEmpty() && tokens.get(0).matches("\\d+")) {
            count = Integer.parseInt(tokens.get(0));
            blockIds = tokens.subList(1, tokens.size());
        }
        if (blockIds.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("至少给一个方块 id"));
            return 0;
        }
        TaskRecord record;
        try {
            record = BLOCK_TOOLS.autoMine(blockIds, count,
                    TaskDispatch.ctx("debug-mine", companion));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        TaskDispatch.dispatchAsync(companion, record, reply ->
                ctx.getSource().sendSuccess(() -> Component.literal(reply), false));
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NumenPlayer companion = requireCompanion(ctx);
        if (companion == null) {
            return 0;
        }
        CompanionTickDispatcher.stopActive(companion, "stopped by command");
        ctx.getSource().sendSuccess(() -> Component.literal(
                companion.getName().getString() + " 的当前任务已叫停"), false);
        return 1;
    }

    // ==================== 同伴定位 ====================

    /** 按名字找调用者拥有的同伴;找不到发失败提示并返回 null。 */
    private static NumenPlayer requireCompanion(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "name");
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        for (ServerPlayer p : owner.level().getServer().getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer np && np.isOwnedByPlayer(owner.getUUID())
                    && np.getName().getString().equals(name)) {
                return np;
            }
        }
        ctx.getSource().sendFailure(Component.literal("没有名为 '" + name + "' 的同伴"));
        return null;
    }
}
