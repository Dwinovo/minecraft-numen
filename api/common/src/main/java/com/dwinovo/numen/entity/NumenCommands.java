package com.dwinovo.numen.entity;

import com.dwinovo.numen.cli.CommandRunner;
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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The server-side {@code /numen} command tree: the players' verbs that manage companions. Her own commands are
 * not here — they live in Numen's own command layer ({@code cli.NumenCli}), off the MC command tree. The two
 * inherently client-local player verbs ({@code settings}, {@code reset}) act on the caller's own client by firing
 * a {@link ClientUiActionPayload} back at them.
 *
 * <pre>
 *   /numen player summon &lt;name&gt;    summon the named companion (idempotent — reuses an existing one)
 *   /numen player despawn &lt;name&gt;   permanently dismiss the named companion (gone for good)
 *   /numen settings                  open the settings GUI on the caller's client
 *   /numen reset                     clear the caller's conversation loops
 *   /numen drive &lt;companion&gt; &lt;line&gt;   (op) run one line through her command entry, as her command tool does;
 *                                    a name with spaces or non-ASCII letters goes in quotes
 *
 *   /numen permission mode &lt;name&gt; [ask|bypass|observe]     show or set a companion's permission mode
 *   /numen permission rules list                          the caller's rows, then the factory rows
 *   /numen permission rules add &lt;deny|ask|allow&gt; &lt;rule&gt;  append a row, e.g. ask take(*)
 *   /numen permission rules remove &lt;deny|ask|allow&gt; &lt;n&gt;   remove row n (1-based, as listed)
 *   /numen permission rules reset                         clear the caller's three tables
 *   /numen consent &lt;allow|remember&gt; &lt;id&gt; | deny &lt;id&gt; [note]   answer a pending consent request
 * </pre>
 *
 * <h2>只给玩家</h2>
 * 她作为一个玩家也在 MC 的指令树上,行首带 {@code /} 的一行就是以她的身份在这棵树上执行。{@code /numen} 这个根只给
 * 不是她的来源({@link #FOR_PLAYERS}),一处定下,挂在它下面的每一格都随之(见 {@link #graft}):原版的补全、
 * {@code help}、解析都按 {@code requires} 过滤,她看不见、用不了召唤、设置、权限、征询、drive 这些——"她能不能经指令
 * 召唤同伴"从结构上就不存在,{@code /numen …} 对她就是"服务器不让你用"。{@code /execute as 她 run numen …} 也进不来:
 * Brigadier 解析时按发指令的人查 {@code requires}。
 *
 * <h2>权限命令是底层接口</h2>
 * 卡片、面板与以后聊天里的可点击按钮都落到同一组公开接口:模式经 {@link Permission},规则经
 * {@link PermissionStore},答复只经 {@link ConsentDesk#reply}(与卡片的网络载荷同一个入口)。这里只解析参数、
 * 回话,不复制任何判断。规则写在调用者自己名下,模式只能设自己的同伴,答复只认主人——都是主人专用。
 */
@com.dwinovo.numen.api.Internal
public final class NumenCommands {

    /** {@code /numen} 这个根。 */
    public static final String ROOT = "numen";
    /** 来源不是她(玩家、控制台、命令方块):管理同伴的指令只给他们。 */
    private static final Predicate<CommandSourceStack> FOR_PLAYERS =
            source -> !(source.getEntity() instanceof NumenPlayer);

    private NumenCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        graft(dispatcher, Commands.literal("player")
                .then(Commands.literal("summon")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("despawn")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> despawn(ctx, StringArgumentType.getString(ctx, "name"))))));
        graft(dispatcher, Commands.literal("settings")
                .executes(ctx -> clientAction(ctx, ClientUiActionPayload.Action.OPEN_SETTINGS)));
        graft(dispatcher, Commands.literal("reset")
                .executes(ctx -> clientAction(ctx, ClientUiActionPayload.Action.RESET_LOOPS)));
        graft(dispatcher, Commands.literal("permission")
                .then(modeCommand())
                .then(Commands.literal("rules")
                        .then(Commands.literal("list").executes(NumenCommands::listRules))
                        .then(tableCommand("add", (literal, table) -> literal.then(
                                Commands.argument("rule", StringArgumentType.greedyString())
                                        .executes(ctx -> addRule(ctx, table)))))
                        .then(tableCommand("remove", (literal, table) -> literal.then(
                                Commands.argument("row", IntegerArgumentType.integer(1))
                                        .executes(ctx -> removeRule(ctx, table)))))
                        .then(Commands.literal("reset").executes(NumenCommands::resetRules))));
        graft(dispatcher, consentCommand());
        graft(dispatcher, Commands.literal("drive").requires(source -> source.hasPermission(2))
                .then(Commands.argument("companion", StringArgumentType.string())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(companionsHere(ctx.getSource())
                                .map(body -> StringArgumentType.escapeIfRequired(body.getName().getString())), builder))
                        .then(Commands.argument("line", StringArgumentType.greedyString())
                                .executes(NumenCommands::drive))));
    }

    /**
     * 往 {@code /numen} 下挂一格。根由第一次挂的这一刻建出来,带着它的观众({@link #FOR_PLAYERS}):Brigadier 合并同名节点
     * 时留下先来的那一个的 {@code requires},所以根只在这里建。同名的一格已经在了就抛出:Brigadier 会把同名的两格悄悄
     * 并成一格,哪条管理指令与谁撞了,在服务器建指令树时就说清。
     */
    public static void graft(CommandDispatcher<CommandSourceStack> dispatcher,
                             LiteralArgumentBuilder<CommandSourceStack> node) {
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild(ROOT);
        if (root == null) {
            root = dispatcher.register(Commands.literal(ROOT).requires(FOR_PLAYERS));
        }
        if (root.getChild(node.getLiteral()) != null) {
            throw new IllegalStateException("/" + ROOT + " " + node.getLiteral() + " is registered twice");
        }
        root.addChild(node.build());
    }

    /**
     * {@code /numen drive <同伴> <一行指令>}:把这一行交给她的执行入口,和 {@code command} 工具是同一个入口——解析、
     * 权限层、执行、回执都一样,回执说给发指令的人听。
     *
     * <p>这条 drive 自己正在执行:原版把一条指令执行当中调起的另一条排到它之后,她的那一行要是在这里执行,入口返回时它
     * 还没跑,回显无从收起。所以交给服务器的任务队列,等这条 drive 执行完再以她的身份执行。
     */
    private static int drive(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack caller = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "companion");
        List<NumenPlayer> named = companionsHere(caller).filter(body -> body.getName().getString().equals(name)).toList();
        if (named.size() != 1) {
            caller.sendFailure(Component.literal(named.isEmpty() ? "No companion named '" + name + "' is here"
                    : named.size() + " companions here are named '" + name + "'"));
            return 0;
        }
        NumenPlayer her = named.get(0);
        String line = StringArgumentType.getString(ctx, "line");
        MinecraftServer server = caller.getServer();
        String callId = "drive-" + UUID.randomUUID();
        server.tell(new TickTask(server.getTickCount(),
                () -> CommandRunner.run(her, callId, line, result -> report(caller, her, result))));
        return 1;
    }

    /**
     * 此刻在场的同伴,不论主人是谁:drive 是服主的调试入口。名字用字符串参数(中文名加引号),不用原版的玩家参数——
     * 那一个只认 16 个字符以内的英文名。
     */
    private static java.util.stream.Stream<NumenPlayer> companionsHere(CommandSourceStack source) {
        return source.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> player instanceof NumenPlayer)
                .map(player -> (NumenPlayer) player);
    }

    /** 她那一行的回执,说给发 drive 的人听。 */
    private static void report(CommandSourceStack caller, NumenPlayer her, String resultJson) {
        JsonObject result = JsonParser.parseString(resultJson).getAsJsonObject();
        Component said = Component.literal(her.getName().getString() + ": " + result.get("message").getAsString());
        if (result.get("success").getAsBoolean()) {
            caller.sendSuccess(() -> said, false);
        } else {
            caller.sendFailure(said);
        }
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
        // 附言只随拒绝:主人要她换个做法才会说
        return Commands.literal("consent")
                .then(Commands.literal("allow").then(consentId(ConsentAnswer.Decision.ALLOW_ONCE)))
                .then(Commands.literal("remember").then(consentId(ConsentAnswer.Decision.ALLOW_REMEMBER)))
                .then(Commands.literal("deny").then(consentId(ConsentAnswer.Decision.DENY)
                        .then(Commands.argument("note", StringArgumentType.greedyString())
                                .executes(ctx -> consent(ctx, ConsentAnswer.Decision.DENY,
                                        StringArgumentType.getString(ctx, "note"))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Long> consentId(
            ConsentAnswer.Decision decision) {
        return Commands.argument("id", LongArgumentType.longArg(1)).executes(ctx -> consent(ctx, decision, ""));
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
