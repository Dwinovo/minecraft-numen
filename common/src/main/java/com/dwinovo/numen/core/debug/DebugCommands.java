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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 寻路调试命令树,并入 {@code /numen} 根:
 * <pre>
 *   /numen debug                          翻转调试模式(路径粒子渲染 + UI 文本不过滤直出)
 *   /numen goto &lt;name&gt; &lt;y&gt;                该同伴走到目标高度
 *   /numen goto &lt;name&gt; &lt;x&gt; &lt;z&gt;            该同伴走到该水平位置(Y 自动落地表)
 *   /numen goto &lt;name&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;        该同伴走到精确格
 *   /numen thisway &lt;name&gt; &lt;distance&gt;      沿该同伴当前朝向前方 distance 格
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
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .executes(DebugCommands::gotoCmd))))
                .then(Commands.literal("thisway")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("distance", IntegerArgumentType.integer(1))
                                        .executes(DebugCommands::thisWay))))
                .then(Commands.literal("mine")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("blocks", StringArgumentType.greedyString())
                                        .executes(DebugCommands::mine))))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(DebugCommands::cancel))));
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

    // ==================== goto / thisway ====================

    /**
     * 参数形态与工具一致:1 个数字=高度,2 个=水平位置,3 个=精确格;
     * 单个非数字 token=方块 id(走到最近的一个旁边)。
     */
    private static int gotoCmd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NumenPlayer companion = requireCompanion(ctx);
        if (companion == null) {
            return 0;
        }
        String[] tokens = StringArgumentType.getString(ctx, "args").trim().split("\\s+");
        boolean allNumeric = tokens.length > 0;
        for (String t : tokens) {
            if (!t.matches("-?\\d+")) {
                allNumeric = false;
                break;
            }
        }
        if (allNumeric) {
            return switch (tokens.length) {
                case 1 -> dispatchMoveTo(ctx, companion,
                        null, Double.parseDouble(tokens[0]), null, null);
                case 2 -> dispatchMoveTo(ctx, companion,
                        Double.parseDouble(tokens[0]), null, Double.parseDouble(tokens[1]), null);
                case 3 -> dispatchMoveTo(ctx, companion,
                        Double.parseDouble(tokens[0]), Double.parseDouble(tokens[1]),
                        Double.parseDouble(tokens[2]), null);
                default -> {
                    ctx.getSource().sendFailure(Component.literal(
                            "用法: goto <名> <y> | <x> <z> | <x> <y> <z> | <方块id>"));
                    yield 0;
                }
            };
        }
        if (tokens.length == 1) {
            return dispatchMoveTo(ctx, companion, null, null, null, tokens[0]);
        }
        ctx.getSource().sendFailure(Component.literal(
                "用法: goto <名> <y> | <x> <z> | <x> <y> <z> | <方块id>"));
        return 0;
    }

    private static int thisWay(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NumenPlayer companion = requireCompanion(ctx);
        if (companion == null) {
            return 0;
        }
        int distance = IntegerArgumentType.getInteger(ctx, "distance");
        // 从同伴当前朝向推前方落点(yaw 的 MC 约定:0=+Z,90=-X)
        double theta = Math.toRadians(companion.getYHeadRot());
        double x = companion.getX() - Math.sin(theta) * distance;
        double z = companion.getZ() + Math.cos(theta) * distance;
        return dispatchMoveTo(ctx, companion, Math.floor(x), null, Math.floor(z), null);
    }

    private static int dispatchMoveTo(CommandContext<CommandSourceStack> ctx, NumenPlayer companion,
                                      Double x, Double y, Double z, String block) {
        TaskRecord record;
        try {
            record = (TaskRecord) MOVEMENT_TOOLS.moveTo(x, y, z, 1.0, block, null,
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
            record = BLOCK_TOOLS.autoMine(blockIds, count, null, null,
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
