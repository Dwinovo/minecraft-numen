package com.dwinovo.numen.entity;

import com.dwinovo.numen.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.PermissionStore;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.RuleSet;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.platform.Services;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

/**
 * The unified server-side {@code /numen} command tree — one root with
 * per-companion verbs. Lives entirely on the server so it never collides with
 * the client command dispatcher; the two inherently client-local verbs
 * ({@code settings}, {@code reset}) act on the caller's own client by firing a
 * {@link ClientUiActionPayload} back at them.
 *
 * <pre>
 *   /numen player summon &lt;name&gt;    summon the named companion (idempotent — reuses an existing one)
 *   /numen player despawn &lt;name&gt;   permanently dismiss the named companion (gone for good)
 *   /numen settings                  open the settings GUI on the caller's client
 *   /numen reset                     clear the caller's conversation loops
 *
 *   /numen permission mode &lt;name&gt; [ask|bypass|observe]     show or set a companion's permission mode
 *   /numen permission rules list                          the caller's rows, then the factory rows
 *   /numen permission rules add &lt;deny|ask|allow&gt; &lt;rule&gt;  append a row, e.g. ask take(*)
 *   /numen permission rules remove &lt;deny|ask|allow&gt; &lt;n&gt;   remove row n (1-based, as listed)
 *   /numen permission rules reset                         clear the caller's three tables
 *   /numen consent &lt;allow|remember|deny&gt; &lt;id&gt; [note]      answer a pending consent request
 * </pre>
 *
 * <h2>权限命令是底层接口</h2>
 * 卡片、面板与以后聊天里的可点击按钮都落到同一组公开接口:模式经 {@link Permission},规则经
 * {@link PermissionStore},答复只经 {@link ConsentDesk#reply}(与卡片的网络载荷同一个入口)。这里只解析参数、
 * 回话,不复制任何判断。规则写在调用者自己名下,模式只能设自己的同伴,答复只认主人——都是主人专用。
 */
@com.dwinovo.numen.api.Internal
public final class NumenCommands {

    private NumenCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("numen")
                .then(Commands.literal("player")
                        .then(Commands.literal("summon")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("despawn")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> despawn(ctx, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("settings")
                        .executes(ctx -> clientAction(ctx, ClientUiActionPayload.Action.OPEN_SETTINGS)))
                .then(Commands.literal("reset")
                        .executes(ctx -> clientAction(ctx, ClientUiActionPayload.Action.RESET_LOOPS)))
                .then(Commands.literal("permission")
                        .then(modeCommand())
                        .then(Commands.literal("rules")
                                .then(Commands.literal("list").executes(NumenCommands::listRules))
                                .then(tableCommand("add", (literal, table) -> literal.then(
                                        Commands.argument("rule", StringArgumentType.greedyString())
                                                .executes(ctx -> addRule(ctx, table)))))
                                .then(tableCommand("remove", (literal, table) -> literal.then(
                                        Commands.argument("row", IntegerArgumentType.integer(1))
                                                .executes(ctx -> removeRule(ctx, table)))))
                                .then(Commands.literal("reset").executes(NumenCommands::resetRules))))
                .then(consentCommand()));
    }

    private static int summon(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) owner.level();
        NumenPlayer body = Companions.summon(
                level.getServer(), owner.getUUID(), name, level, owner.position());
        // Push the updated roster so the owner's G panel can reach the new companion.
        Companions.syncRosterToOwner(level.getServer(), owner);
        ctx.getSource().sendSuccess(() ->
                Component.literal("Summoned companion '" + name + "' (" + body.getUUID() + ")"), false);
        return 1;
    }

    private static int despawn(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        var server = owner.level().getServer();
        // Permanent dismissal: removes the live body AND its registry entry (and any same-name
        // duplicates), so it does NOT come back on the next login. NOT dormancy.
        int dismissed = Companions.dismissByName(server, owner.getUUID(), name);
        if (dismissed == 0) {
            ctx.getSource().sendFailure(
                    Component.literal("No companion of yours named '" + name + "'"));
            return 0;
        }
        Companions.syncRosterToOwner(server, owner);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Dismissed companion '" + name + "' — gone for good"
                        + (dismissed > 1 ? " (cleaned up " + dismissed + " duplicates)" : "")), false);
        return dismissed;
    }

    private static int clientAction(CommandContext<CommandSourceStack> ctx,
                                    ClientUiActionPayload.Action action)
            throws CommandSyntaxException {
        ServerPlayer caller = ctx.getSource().getPlayerOrException();
        Services.NETWORK.sendToPlayer(caller, new ClientUiActionPayload(action));
        return 1;
    }

    // ==================== 权限:模式 ====================

    private static LiteralArgumentBuilder<CommandSourceStack> modeCommand() {
        var name = Commands.argument("name", StringArgumentType.word()).executes(ctx -> mode(ctx, null));
        for (Mode mode : Mode.values()) {
            name.then(Commands.literal(mode.name().toLowerCase(Locale.ROOT)).executes(ctx -> mode(ctx, mode)));
        }
        return Commands.literal("mode").then(name);
    }

    /**
     * 看或设调用者名下一只在场同伴的模式——主人在线时他的同伴都在场。
     *
     * @param mode 要设的模式;null = 只看
     */
    private static int mode(CommandContext<CommandSourceStack> ctx, Mode mode) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        NumenPlayer companion = null;
        for (ServerPlayer player : owner.getServer().getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer body && body.isOwnedByPlayer(owner.getUUID())
                    && body.getName().getString().equals(name)) {
                companion = body;
            }
        }
        if (companion == null) {
            ctx.getSource().sendFailure(Component.literal("No companion of yours named '" + name + "' is here"));
            return 0;
        }
        if (mode != null) {
            Permission.setMode(companion, mode);
        }
        String now = Permission.modeOf(companion).name().toLowerCase(Locale.ROOT);
        ctx.getSource().sendSuccess(() -> Component.literal(
                name + (mode == null ? ": permission mode " : ": permission mode set to ") + now), false);
        return 1;
    }

    // ==================== 权限:规则 ====================

    @FunctionalInterface
    private interface TableBranch {
        LiteralArgumentBuilder<CommandSourceStack> attach(LiteralArgumentBuilder<CommandSourceStack> literal,
                                                          Verdict.Kind table);
    }

    /** {@code <verb> <deny|ask|allow> …}:三张表各一个字面量分支,后面接什么由 {@code branch} 定。 */
    private static LiteralArgumentBuilder<CommandSourceStack> tableCommand(String verb, TableBranch branch) {
        var root = Commands.literal(verb);
        for (Verdict.Kind table : List.of(Verdict.Kind.DENY, Verdict.Kind.ASK, Verdict.Kind.ALLOW)) {
            root.then(branch.attach(Commands.literal(tableName(table)), table));
        }
        return root;
    }

    private static int listRules(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        RuleSet mine = PermissionStore.of(owner.getServer(), owner.getUUID()).rules();
        StringBuilder sb = new StringBuilder("Your rules (checked first, deny → allow → ask):");
        appendLayer(sb, mine, true);
        sb.append("\nFactory rules (checked after yours, allow → ask; not editable):");
        appendLayer(sb, RuleSet.factory(), false);
        sb.append("\nNothing matched → ask.");
        String text = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static void appendLayer(StringBuilder sb, RuleSet layer, boolean numbered) {
        for (Verdict.Kind table : List.of(Verdict.Kind.DENY, Verdict.Kind.ALLOW, Verdict.Kind.ASK)) {
            List<Rule> rows = layer.table(table);
            sb.append("\n  ").append(tableName(table)).append(':');
            if (rows.isEmpty()) {
                sb.append(" (none)");
            }
            for (int i = 0; i < rows.size(); i++) {
                sb.append("\n    ").append(numbered ? (i + 1) + ". " : "- ").append(rows.get(i));
            }
        }
    }

    private static int addRule(CommandContext<CommandSourceStack> ctx, Verdict.Kind table)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        Rule rule;
        try {
            rule = Rule.parse(StringArgumentType.getString(ctx, "rule"));
        } catch (IllegalArgumentException mistake) {
            ctx.getSource().sendFailure(Component.literal(mistake.getMessage()));
            return 0;
        }
        boolean added = PermissionStore.of(owner.getServer(), owner.getUUID()).add(table, rule);
        String text = added ? "Added to " + tableName(table) + ": " + rule
                : tableName(table) + " already has: " + rule;
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return added ? 1 : 0;
    }

    private static int removeRule(CommandContext<CommandSourceStack> ctx, Verdict.Kind table)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        PermissionStore store = PermissionStore.of(owner.getServer(), owner.getUUID());
        int row = IntegerArgumentType.getInteger(ctx, "row");
        int size = store.rules().table(table).size();
        if (row > size) {
            ctx.getSource().sendFailure(Component.literal(tableName(table) + " has no row " + row + " (it has "
                    + size + "); /numen permission rules list shows the numbers"));
            return 0;
        }
        Rule removed = store.remove(table, row - 1);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Removed from " + tableName(table) + ": " + removed), false);
        return 1;
    }

    private static int resetRules(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        PermissionStore.of(owner.getServer(), owner.getUUID()).reset();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Cleared your deny, ask and allow tables; the factory rules still apply"), false);
        return 1;
    }

    private static String tableName(Verdict.Kind table) {
        return table.name().toLowerCase(Locale.ROOT);
    }

    // ==================== 征询答复 ====================

    private static LiteralArgumentBuilder<CommandSourceStack> consentCommand() {
        var root = Commands.literal("consent");
        consentBranch(root, "allow", ConsentAnswer.Decision.ALLOW_ONCE);
        consentBranch(root, "remember", ConsentAnswer.Decision.ALLOW_REMEMBER);
        consentBranch(root, "deny", ConsentAnswer.Decision.DENY);
        return root;
    }

    private static void consentBranch(LiteralArgumentBuilder<CommandSourceStack> root, String word,
                                      ConsentAnswer.Decision decision) {
        root.then(Commands.literal(word)
                .then(Commands.argument("id", LongArgumentType.longArg(1))
                        .executes(ctx -> consent(ctx, decision, ""))
                        .then(Commands.argument("note", StringArgumentType.greedyString())
                                .executes(ctx -> consent(ctx, decision, StringArgumentType.getString(ctx, "note"))))));
    }

    private static int consent(CommandContext<CommandSourceStack> ctx, ConsentAnswer.Decision decision, String note)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        long id = LongArgumentType.getLong(ctx, "id");
        NumenPlayer companion = ConsentDesk.pendingAt(owner.getServer(), id);
        ConsentDesk.Reply reply = companion == null ? ConsentDesk.Reply.NOT_PENDING
                : ConsentDesk.reply(owner, companion, id, decision, note);
        switch (reply) {
            case ANSWERED -> ctx.getSource().sendSuccess(() -> Component.literal("Answered consent request #" + id
                    + " for " + companion.getName().getString() + ": " + decision.name().toLowerCase(Locale.ROOT)),
                    false);
            case NOT_OWNER -> ctx.getSource().sendFailure(Component.literal(
                    "Consent request #" + id + " belongs to a companion that is not yours"));
            case NOT_PENDING -> ctx.getSource().sendFailure(Component.literal(
                    "No pending consent request #" + id + " (already answered, expired or replaced)"));
        }
        return reply == ConsentDesk.Reply.ANSWERED ? 1 : 0;
    }
}
