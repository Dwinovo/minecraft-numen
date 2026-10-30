package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.agent.scope.Handle;
import com.dwinovo.numen.api.brain.Presence;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.api.permission.ConsentAnswer;
import com.dwinovo.numen.api.permission.ConsentDesk;
import com.dwinovo.numen.api.permission.Groups;
import com.dwinovo.numen.api.permission.PermissionConfig;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.api.permission.Permission;
import com.dwinovo.numen.api.permission.PermissionStore;
import com.dwinovo.numen.api.permission.RuleBook;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verdict;
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
import com.dwinovo.numen.api.network.NumenNetwork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The server-side {@code /numen} command tree: the players' verbs that manage companions. Her own functions are
 * not here — they are the Lua API ({@code sdk.ApiRegistry}), off the MC command tree. The two
 * inherently client-local player verbs ({@code settings}, {@code reset}) act on the caller's own client by firing
 * a {@link ClientUiActionPayload} back at them.
 *
 * <pre>
 *   /numen player summon &lt;name&gt;    summon the named companion (idempotent — reuses an existing one)
 *   /numen player despawn &lt;name&gt;   permanently dismiss the named companion (gone for good)
 *   /numen settings                  open the settings GUI on the caller's client
 *   /numen reset                     clear the caller's conversation loops
 *   /numen debug                     toggle debug mode (the caller's chat UI shows text unfiltered)
 *   /numen drive &lt;companion&gt; &lt;program&gt; (op) run a Lua program as her, through the same entry as her own;
 *                                    a name with spaces or non-ASCII letters goes in quotes
 *
 *   /numen permission mode &lt;name&gt; [ask|bypass|observe]     show or set a companion's permission mode
 *   /numen permission rules list                          the caller's rows, then the factory rows
 *   /numen permission rules add &lt;deny|ask|allow&gt; &lt;rule&gt;  append a row, e.g. ask take(*)
 *   /numen permission rules remove &lt;deny|ask|allow&gt; &lt;n&gt;   remove row n (1-based, as listed)
 *   /numen permission rules reset                         clear the caller's three tables
 *   /numen consent &lt;allow|remember&gt; &lt;id&gt; | deny &lt;id&gt; [note]   answer a pending consent request
 *
 *   /numen group invite|remove|rename|dissolve …          the panel's conversation actions (see ConversationCommands)
 *   /numen chat [&lt;conversation&gt; [&lt;text&gt;]]                  route the caller's chat bar into a conversation, or say one line
 *   /numen token create|list|revoke                       the remote endpoint's tokens (see {@code TokenCommands})
 *   /numen companion &lt;her&gt; profile|persona|voice [&lt;entry&gt;]  bind one of her library entries (see BindingCommands)
 *   /numen companion &lt;her&gt; skin [player &lt;name&gt;|url &lt;address&gt;|server &lt;file&gt;]  give her a skin, signed in the server's queue when needed (see SkinCommand)
 *   /numen key &lt;service&gt; &lt;key&gt;                            store a key in the caller's own keychain (see KeyCommand)
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

    /** 谁开了"UI 文本不过滤直出"的调试模式。 */
    private static final java.util.Set<java.util.UUID> DEBUG_TEXT = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 插件经 {@link com.dwinovo.numen.api.NumenApi#command} 挂的指令:{@code /numen} 的根每次被读都按这张表现算,撤回即时生效。 */
    private static final List<LiteralArgumentBuilder<CommandSourceStack>> PLUGGED =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 现行的 {@code /numen} 根(服务器每建一棵指令树换一个);插件登记时用它查有没有撞名。 */
    private static volatile Root live;

    private NumenCommands() {}

    /**
     * 登记一格插件的指令。返回撤回它的句柄:撤掉后这一格从 {@code /numen} 下消失(服务器重发指令树之后玩家的补全也跟着变,见 {@link #resend})。
     *
     * @throws IllegalStateException 和 {@code /numen} 下现有的一格或另一个插件的一格撞名
     */
    public static Handle plug(LiteralArgumentBuilder<CommandSourceStack> verb) {
        synchronized (PLUGGED) {
            Root root = live;
            if (root != null) {
                root.requireFree(verb.getLiteral());
            }
            PLUGGED.add(verb);
        }
        return Handle.once(() -> PLUGGED.remove(verb));
    }

    /**
     * 登记变了之后,把现行的指令树重发给在线的每位玩家(客户端的补全是服务器发的那一份拷贝)。树本身不用重建:{@link Root} 每次被读
     * 都按 {@link #PLUGGED} 现算。
     */
    public static void resend(net.minecraft.server.MinecraftServer server) {
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(player);
        }
    }

    /**
     * {@code /numen} 这个根:固定的几格照常挂在它下面,插件的几格不挂,每次被读时从 {@link #PLUGGED} 现建(同一个登记同一个节点,
     * 缓存着)——Brigadier 的树只能加子节点、不能删,撤回要即时生效,所以插件的那几格不放进树里,放在这张表里。
     */
    private static final class Root extends com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> {

        private final java.util.Map<LiteralArgumentBuilder<CommandSourceStack>, CommandNode<CommandSourceStack>> built =
                new java.util.IdentityHashMap<>();

        Root() {
            super(ROOT, null, FOR_PLAYERS, null, null, false);
        }

        private synchronized List<CommandNode<CommandSourceStack>> plugged() {
            List<CommandNode<CommandSourceStack>> out = new java.util.ArrayList<>();
            built.keySet().retainAll(PLUGGED);
            for (LiteralArgumentBuilder<CommandSourceStack> verb : PLUGGED) {
                out.add(built.computeIfAbsent(verb, LiteralArgumentBuilder::build));
            }
            return out;
        }

        /** 固定的一格;没有是 null。 */
        CommandNode<CommandSourceStack> fixed(String literal) {
            return super.getChild(literal);
        }

        void requireFree(String literal) {
            if (fixed(literal) != null || PLUGGED.stream().anyMatch(v -> v.getLiteral().equals(literal))) {
                throw new IllegalStateException("/" + ROOT + " " + literal + " is registered twice");
            }
        }

        @Override
        public java.util.Collection<CommandNode<CommandSourceStack>> getChildren() {
            List<CommandNode<CommandSourceStack>> out = new java.util.ArrayList<>(super.getChildren());
            out.addAll(plugged());
            return out;
        }

        @Override
        public CommandNode<CommandSourceStack> getChild(String name) {
            CommandNode<CommandSourceStack> own = super.getChild(name);
            return own != null ? own : plugged().stream().filter(n -> n.getName().equals(name)).findFirst().orElse(null);
        }

        @Override
        public java.util.Collection<? extends CommandNode<CommandSourceStack>> getRelevantNodes(
                com.mojang.brigadier.StringReader input) {
            String text = input.getRemaining();
            int space = text.indexOf(' ');
            String word = space < 0 ? text : text.substring(0, space);
            CommandNode<CommandSourceStack> node = getChild(word);
            return node == null ? List.of() : List.of(node);
        }
    }

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
        graft(dispatcher, Commands.literal("reset").executes(NumenCommands::resetBrains));
        graft(dispatcher, Commands.literal("debug").executes(NumenCommands::toggleDebugText));
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
        graft(dispatcher, com.dwinovo.numen.api.brain.ConversationCommands.group());
        graft(dispatcher, com.dwinovo.numen.api.brain.ConversationCommands.chat());
        graft(dispatcher, com.dwinovo.numen.api.mcp.server.TokenCommands.node());
        graft(dispatcher, com.dwinovo.numen.api.brain.BindingCommands.node());
        graft(dispatcher, com.dwinovo.numen.api.brain.KeyCommand.node());
        graft(dispatcher, Commands.literal("drive").requires(source -> com.dwinovo.numen.api.platform.Services.permissions().isGameMaster(source))
                .then(Commands.argument("companion", StringArgumentType.string())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(companionsHere(ctx.getSource())
                                .map(body -> StringArgumentType.escapeIfRequired(body.getName().getString())), builder))
                        .then(Commands.argument("program", StringArgumentType.greedyString())
                                .executes(NumenCommands::drive))));
        // 插件的几格不在这里挂,见 Root;建树时就撞名的照旧在这里说清
        Root root = (Root) dispatcher.getRoot().getChild(ROOT);
        synchronized (PLUGGED) {
            for (LiteralArgumentBuilder<CommandSourceStack> verb : PLUGGED) {
                if (root.fixed(verb.getLiteral()) != null) {
                    throw new IllegalStateException("/" + ROOT + " " + verb.getLiteral() + " is registered twice");
                }
            }
            live = root;
        }
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
            root = new Root();
            dispatcher.getRoot().addChild(root);
        }
        if (root.getChild(node.getLiteral()) != null) {
            throw new IllegalStateException("/" + ROOT + " " + node.getLiteral() + " is registered twice");
        }
        root.addChild(node.build());
    }

    /**
     * {@code /numen drive <同伴> <Lua 程序>}:管理员以她的身份在服务端跑一段 Lua 程序,和她自己写的程序同一个入口
     * ({@link com.dwinovo.numen.api.program.ServerPrograms})——读参数、权限层、执行、等她派的活收尾、回执都一样,回执说给发指令的人听。
     *
     * <p>这条 drive 自己正在执行:原版把一条指令执行当中调起的另一条排到它之后,程序要是在这里执行,它里面的原版指令要等这条
     * drive 跑完才跑。所以交给服务器的任务队列,等这条 drive 执行完再跑。
     */
    private static int drive(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack caller = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "companion");
        List<NumenPlayer> named = companionsHere(caller).filter(body -> body.getName().getString().equals(name)).toList();
        if (named.size() != 1) {
            caller.sendFailure(named.isEmpty() ? Component.translatable(Keys.COMMAND_NO_COMPANION_HERE, name)
                    : Component.translatable(Keys.COMMAND_SEVERAL_NAMED, named.size(), name));
            return 0;
        }
        NumenPlayer her = named.get(0);
        String program = StringArgumentType.getString(ctx, "program");
        MinecraftServer server = caller.getServer();
        server.tell(new TickTask(server.getTickCount(), () -> com.dwinovo.numen.api.program.ServerPrograms.launch(her,
                "drive", program, com.dwinovo.numen.api.program.CallObserver.NONE, receipt -> report(caller, her, receipt))));
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

    /** 那段程序的回执,说给发 drive 的人听。 */
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
        CommandSourceStack source = ctx.getSource();
        // 结果可能在以后的某一刻才到(平台的接纳是异步的):话说给发指令的人,指令本身的返回值只说受没受理
        boolean accepted = Companions.summonFor(source.getPlayerOrException(), name, null, summoned -> {
            if (summoned.problem() != null) {
                source.sendFailure(summoned.problem());
            } else if (summoned.body() != null) {
                source.sendSuccess(() ->
                        Component.translatable(Keys.COMMAND_SUMMONED, name, summoned.body().getUUID().toString()), false);
            }
        });
        return accepted ? 1 : 0;
    }

    private static int despawn(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        var server = owner.level().getServer();
        // Permanent dismissal: removes the live body AND its registry entry (and any same-name
        // duplicates), so it does NOT come back on the next login. NOT dormancy. The dismissal pushes
        // the roster itself.
        int dismissed = Companions.dismissByName(server, owner.getUUID(), name);
        if (dismissed == 0) {
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_NO_SUCH_COMPANION, name));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> dismissed > 1
                ? Component.translatable(Keys.COMMAND_DISMISSED_DUPLICATES, name, dismissed)
                : Component.translatable(Keys.COMMAND_DISMISSED, name), false);
        return dismissed;
    }

    /** {@code /numen debug}:翻转调试模式,主人的客户端聊天 UI 切到不过滤直出。 */
    private static int toggleDebugText(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer caller = ctx.getSource().getPlayerOrException();
        if (needsClient(ctx, caller)) {
            return 0;
        }
        boolean on = DEBUG_TEXT.add(caller.getUUID());
        if (!on) {
            DEBUG_TEXT.remove(caller.getUUID());
        }
        NumenNetwork.sendToPlayer(caller, new ClientUiActionPayload(on
                ? ClientUiActionPayload.Action.DEBUG_TEXT_ON
                : ClientUiActionPayload.Action.DEBUG_TEXT_OFF));
        ctx.getSource().sendSuccess(() -> Component.translatable(on ? Keys.DEBUG_ON : Keys.DEBUG_OFF), false);
        return 1;
    }

    /** 调试:把调用者名下各同伴的大脑放掉重建,从她们的家里重新读起;在飞的回合作废。 */
    private static int resetBrains(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer caller = ctx.getSource().getPlayerOrException();
        com.dwinovo.numen.api.brain.CompanionBrains.reset(caller.server, caller.getUUID());
        return 1;
    }

    private static int clientAction(CommandContext<CommandSourceStack> ctx,
                                    ClientUiActionPayload.Action action)
            throws CommandSyntaxException {
        ServerPlayer caller = ctx.getSource().getPlayerOrException();
        if (needsClient(ctx, caller)) {
            return 0;
        }
        NumenNetwork.sendToPlayer(caller, new ClientUiActionPayload(action));
        return 1;
    }

    /** 这条指令作用在调用者自己的客户端上:他没装(原版客户端)就说一声,不是悄悄什么都没发生。 */
    private static boolean needsClient(CommandContext<CommandSourceStack> ctx, ServerPlayer caller) {
        if (Presence.hasClient(caller.getUUID())) {
            return false;
        }
        ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_NEEDS_CLIENT));
        return true;
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
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_YOURS_NOT_HERE, name));
            return 0;
        }
        if (mode != null) {
            Permission.setMode(companion, mode);
        }
        String now = Permission.modeOf(companion).name().toLowerCase(Locale.ROOT);
        ctx.getSource().sendSuccess(() -> Component.translatable(mode == null ? Keys.COMMAND_MODE : Keys.COMMAND_MODE_SET,
                name, now), false);
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

    /**
     * 规则列表:主人层(带序号、谁写的、命中几次)、硬规则、出厂层。表名与规则行照命令里的写法,只有说明的话随主人的语言。
     * 每一行后面是它的来源(她加的带她说的理由)和命中账,主人一眼看出哪条在起作用、哪条从没用过。
     */
    private static int listRules(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        RuleBook book = RuleBook.of(PermissionStore.of(owner.getServer(), owner.getUUID()),
                PermissionConfig.of(owner.getServer()).hardRules());
        MutableComponent text = Component.translatable(Keys.COMMAND_RULES_YOURS);
        for (Verdict.Kind table : List.of(Verdict.Kind.DENY, Verdict.Kind.ALLOW, Verdict.Kind.ASK)) {
            List<RuleBook.Line> rows = book.owner().get(table);
            text.append("\n  " + tableName(table) + ":");
            if (rows.isEmpty()) {
                text.append(" ").append(Component.translatable(Keys.COMMAND_RULES_NONE));
            }
            for (int i = 0; i < rows.size(); i++) {
                RuleBook.Line row = rows.get(i);
                text.append("\n    " + (i + 1) + ". " + row.rule()).append(sourceNote(row)).append(hitNote(row.hit()));
            }
        }
        text.append("\n").append(Component.translatable(book.hardOn() ? Keys.COMMAND_RULES_HARD : Keys.COMMAND_RULES_HARD_OFF));
        for (RuleBook.Line line : book.hard()) {
            text.append("\n    - deny " + line.rule()).append(hitNote(line.hit()));
        }
        text.append("\n").append(Component.translatable(Keys.COMMAND_RULES_FACTORY));
        for (Verdict.Kind table : List.of(Verdict.Kind.ALLOW, Verdict.Kind.ASK)) {
            text.append("\n  " + tableName(table) + ":");
            for (RuleBook.Line line : book.factory().get(table)) {
                text.append("\n    - " + line.rule()).append(hitNote(line.hit()));
            }
        }
        text.append("\n").append(Component.translatable(Keys.COMMAND_RULES_UNMATCHED));
        ctx.getSource().sendSuccess(() -> text, false);
        return 1;
    }

    /** 她加的行后面注明,带她说的理由;主人写的不注。 */
    private static Component sourceNote(RuleBook.Line row) {
        if (!row.byHer()) {
            return Component.empty();
        }
        return Component.literal(" · ").append(row.note().isEmpty() ? Component.translatable(Keys.COMMAND_RULES_HER)
                : Component.translatable(Keys.COMMAND_RULES_HER_WHY, row.note()));
    }

    /** 命中账:几次、最近一次多久以前;没命中过就说没有。 */
    private static Component hitNote(PermissionStore.Hit hit) {
        if (hit.count() == 0) {
            return Component.literal(" · ").append(Component.translatable(Keys.COMMAND_RULES_NEVER));
        }
        return Component.literal(" · ").append(Component.translatable(Keys.COMMAND_RULES_HITS, hit.count(),
                Component.translatable(Keys.COMMAND_RULES_AGO, RuleBook.ago(hit.lastMillis(), System.currentTimeMillis()))));
    }

    /** 主人加一行规则。 */
    private static int addRule(CommandContext<CommandSourceStack> ctx, Verdict.Kind table)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        Rule rule;
        try {
            rule = Groups.parse(StringArgumentType.getString(ctx, "rule"));
        } catch (Rule.Mistake mistake) {
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_RULE_MISTAKE_PREFIX + mistake.code(),
                    mistake.args().toArray()));
            return 0;
        }
        boolean added = PermissionStore.of(owner.getServer(), owner.getUUID()).add(table, rule);
        Component text = Component.translatable(added ? Keys.COMMAND_RULE_ADDED : Keys.COMMAND_RULE_EXISTS,
                tableName(table), rule.toString());
        ctx.getSource().sendSuccess(() -> text, false);
        return added ? 1 : 0;
    }

    private static int removeRule(CommandContext<CommandSourceStack> ctx, Verdict.Kind table)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        PermissionStore store = PermissionStore.of(owner.getServer(), owner.getUUID());
        int row = IntegerArgumentType.getInteger(ctx, "row");
        int size = store.rules().table(table).size();
        if (row > size) {
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_RULE_NO_ROW, tableName(table), row, size));
            return 0;
        }
        Rule removed = store.remove(table, row - 1);
        ctx.getSource().sendSuccess(() -> Component.translatable(Keys.COMMAND_RULE_REMOVED, tableName(table),
                removed.toString()), false);
        return 1;
    }

    private static int resetRules(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        PermissionStore.of(owner.getServer(), owner.getUUID()).reset();
        ctx.getSource().sendSuccess(() -> Component.translatable(Keys.COMMAND_RULES_RESET), false);
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
            case ANSWERED -> ctx.getSource().sendSuccess(() -> Component.translatable(Keys.COMMAND_CONSENT_ANSWERED,
                    id, companion.getName(), decision.name().toLowerCase(Locale.ROOT)), false);
            case NOT_OWNER -> ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_CONSENT_NOT_OWNER, id));
            case NOT_PENDING -> ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_CONSENT_NOT_PENDING, id));
        }
        return reply == ConsentDesk.Reply.ANSWERED ? 1 : 0;
    }
}
