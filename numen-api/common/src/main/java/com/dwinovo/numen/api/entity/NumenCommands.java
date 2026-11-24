package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.agent.scope.Handle;
import com.dwinovo.numen.api.brain.Presence;
import com.dwinovo.numen.api.brain.Sharing;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.api.permission.ConsentAnswer;
import com.dwinovo.numen.api.permission.ConsentDesk;
import com.dwinovo.numen.api.permission.Permission;
import com.dwinovo.numen.api.permission.PermissionStore;
import com.dwinovo.numen.permission.Edit;
import com.dwinovo.numen.permission.Preset;
import com.dwinovo.numen.permission.Settings;
import com.dwinovo.numen.permission.Tier;
import com.dwinovo.numen.permission.Verb;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The server-side {@code /numen} command tree: the players' verbs that manage companions. Her own functions are
 * not here — they are the Lua API ({@code sdk.ApiRegistry}), off the MC command tree. The two
 * inherently client-local player verbs ({@code settings}, {@code reset}) act on the caller's own client by firing
 * a {@link ClientUiActionPayload} back at them.
 *
 * <pre>
 *   /numen player summon &lt;name&gt;    summon the named companion (idempotent — reuses an existing one)
 *   /numen player despawn &lt;name&gt;   take the named companion offline (she logs out; her things stay, summon brings her back)
 *   /numen player delete &lt;name&gt;    permanently delete the named companion (gone for good)
 *   /numen player share &lt;name&gt; &lt;player&gt;    (her creator) let that player use her too; he may be offline
 *   /numen player unshare &lt;name&gt; &lt;player&gt;  (her creator) take that back
 *   /numen player leave &lt;name&gt;     give back a companion that was shared with the caller
 *   /numen settings                  open the settings GUI on the caller's client
 *   /numen reset                     clear the caller's conversation loops
 *   /numen debug                     toggle debug mode (the caller's chat UI shows text unfiltered)
 *   /numen drive &lt;companion&gt; &lt;program&gt; (op) run a Lua program as her, through the same entry as her own;
 *                                    a name with spaces or non-ASCII letters goes in quotes
 *
 *   /numen permission show &lt;name&gt;                          a companion's eight tiers and her never-ask lists
 *   /numen permission set &lt;name&gt; &lt;verb&gt; &lt;allow|ask|deny&gt;   set one tier, e.g. set Aria drop allow
 *   /numen permission preset &lt;name&gt; &lt;observe|bypass|factory&gt;  all eight tiers at once (factory also clears the lists)
 *   /numen permission list add|remove &lt;name&gt; &lt;verb&gt; &lt;entry&gt;   put a name on a verb's never-ask list, or take it off
 *   /numen consent &lt;allow|remember|deny&gt; &lt;id&gt; [note]           answer a pending consent request
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
 * 卡片、面板与以后聊天里的可点击按钮都落到同一组公开接口:设置经
 * {@link PermissionStore},答复只经 {@link ConsentDesk#reply}(与卡片的网络载荷同一个入口)。这里只解析参数、
 * 回话,不复制任何判断。设置只能改自己创建的同伴的,答复认她的主人表上的人。
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
                                .executes(ctx -> despawn(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> delete(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("share")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(playerArgument().executes(ctx -> share(ctx, true)))))
                .then(Commands.literal("unshare")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(playerArgument().executes(ctx -> share(ctx, false)))))
                .then(Commands.literal("leave")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(NumenCommands::leave))));
        graft(dispatcher, Commands.literal("settings")
                .executes(ctx -> clientAction(ctx, ClientUiActionPayload.Action.OPEN_SETTINGS)));
        graft(dispatcher, Commands.literal("reset").executes(NumenCommands::resetBrains));
        graft(dispatcher, Commands.literal("debug").executes(NumenCommands::toggleDebugText));
        graft(dispatcher, permissionCommand());
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
        Companions.Summoned summoned = Companions.summonFor(source.getPlayerOrException(), name, SummonSetup.NONE);
        if (summoned.problem() != null) {
            source.sendFailure(summoned.problem());
            return 0;
        }
        source.sendSuccess(() ->
                Component.translatable(Keys.COMMAND_SUMMONED, name, summoned.body().getUUID().toString()), false);
        return 1;
    }

    private static int despawn(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        int offlined = Companions.offlineByName(owner.level().getServer(), owner.getUUID(), name);
        if (offlined == 0) {
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_NO_SUCH_COMPANION, name));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(Keys.COMMAND_OFFLINED, name), false);
        return offlined;
    }

    private static int delete(CommandContext<CommandSourceStack> ctx, String name)
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

    /** 指令里"一位玩家"这个参数:写名字,补全列在线的玩家(同伴不算);不在线的也认,见 {@link NumenPlayer#profileNamed}。 */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> playerArgument() {
        return Commands.argument("player", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getServer().getPlayerList()
                        .getPlayers().stream().filter(player -> !(player instanceof NumenPlayer))
                        .map(player -> player.getGameProfile().getName()), builder));
    }

    /**
     * {@code /numen player share|unshare <同伴> <玩家>}:创建者把自己名下叫这个名字的同伴分享给一位玩家,或收回。玩家写名字,
     * 不在线的也行。改表与它的后果都在 {@link Sharing}。
     */
    private static int share(CommandContext<CommandSourceStack> ctx, boolean add) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        MinecraftServer server = owner.getServer();
        String name = StringArgumentType.getString(ctx, "name");
        List<java.util.UUID> hers = Sharing.named(server, owner.getUUID(), name, true);
        if (hers.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_NO_SUCH_COMPANION, name));
            return 0;
        }
        com.mojang.authlib.GameProfile profile = NumenPlayer.profileNamed(server, StringArgumentType.getString(ctx, "player"));
        if (profile == null) {
            ctx.getSource().sendFailure(Component.translatable("argument.player.unknown"));
            return 0;
        }
        int changed = 0;
        for (java.util.UUID companion : hers) {
            Sharing.Outcome outcome = add ? Sharing.share(server, owner.getUUID(), companion, profile.getId())
                    : Sharing.unshare(server, owner.getUUID(), companion, profile.getId());
            Component told = Sharing.told(outcome, add ? Sharing.Change.SHARE : Sharing.Change.UNSHARE, name,
                    profile.getName());
            if (outcome == Sharing.Outcome.DONE) {
                changed++;
                ctx.getSource().sendSuccess(() -> told, false);
            } else {
                ctx.getSource().sendFailure(told);
            }
        }
        return changed;
    }

    /** {@code /numen player leave <同伴>}:被分享的人把自己从她的主人表上移出去。 */
    private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer caller = ctx.getSource().getPlayerOrException();
        MinecraftServer server = caller.getServer();
        String name = StringArgumentType.getString(ctx, "name");
        int left = 0;
        for (java.util.UUID companion : Sharing.named(server, caller.getUUID(), name, false)) {
            if (Sharing.unshare(server, caller.getUUID(), companion, caller.getUUID()) == Sharing.Outcome.DONE) {
                left++;
            }
        }
        if (left == 0) {
            ctx.getSource().sendFailure(Sharing.told(Sharing.Outcome.UNCHANGED, Sharing.Change.LEAVE, name, ""));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Sharing.told(Sharing.Outcome.DONE, Sharing.Change.LEAVE, name, ""), false);
        return left;
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

    // ==================== 权限:设置 ====================

    private static LiteralArgumentBuilder<CommandSourceStack> permissionCommand() {
        return Commands.literal("permission")
                .then(Commands.literal("show").then(companionArgument().executes(NumenCommands::showSettings)))
                .then(Commands.literal("set").then(companionArgument().then(
                        wordArgument("verb", verbWords()).then(wordArgument("tier", tierWords())
                                .executes(NumenCommands::setTier)))))
                .then(Commands.literal("preset").then(companionArgument().then(
                        wordArgument("preset", presetWords()).executes(NumenCommands::applyPreset))))
                .then(Commands.literal("list")
                        .then(Commands.literal("add").then(companionArgument().then(wordArgument("verb", verbWords())
                                .then(entryArgument().executes(ctx -> editList(ctx, true))))))
                        .then(Commands.literal("remove").then(companionArgument().then(wordArgument("verb", verbWords())
                                .then(entryArgument().executes(ctx -> editList(ctx, false)))))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> companionArgument() {
        return Commands.argument("name", StringArgumentType.word());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> entryArgument() {
        return Commands.argument("entry", StringArgumentType.greedyString());
    }

    /** 一个词参数,补全列出认得的那几个。 */
    private static RequiredArgumentBuilder<CommandSourceStack, String> wordArgument(String name, List<String> words) {
        return Commands.argument(name, StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(words, builder));
    }

    private static List<String> verbWords() {
        return Arrays.stream(Verb.values()).map(Verb::word).toList();
    }

    private static List<String> tierWords() {
        return Arrays.stream(Tier.values()).map(Tier::word).toList();
    }

    private static List<String> presetWords() {
        return Arrays.stream(Preset.values()).map(Preset::word).toList();
    }

    /** 调用者名下的一只在场同伴(主人在线时他的同伴都在场);没有就说一声、交回 null。 */
    private static NumenPlayer ownedCompanion(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        for (ServerPlayer player : owner.getServer().getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer body && body.isOwnedByPlayer(owner.getUUID())
                    && body.getName().getString().equals(name)) {
                return body;
            }
        }
        ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_YOURS_NOT_HERE, name));
        return null;
    }

    /** 参数里的一个词认成 {@code parse} 的结果;认不出就说一声认得哪几个、交回 null。 */
    private static <T> T word(CommandContext<CommandSourceStack> ctx, String argument, List<String> words,
                              Function<String, T> parse) {
        String given = StringArgumentType.getString(ctx, argument);
        T parsed = parse.apply(given);
        if (parsed == null) {
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_PERMISSION_BAD_WORD, given,
                    String.join(", ", words)));
        }
        return parsed;
    }

    private static PermissionStore storeOf(ServerPlayer owner) {
        return PermissionStore.of(owner.getServer(), owner.getUUID());
    }

    /** 看一只同伴的八个档位与每种动作的"不用问"名单。 */
    private static int showSettings(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NumenPlayer companion = ownedCompanion(ctx);
        if (companion == null) {
            return 0;
        }
        Settings settings = Permission.settingsOf(companion);
        MutableComponent text = Component.translatable(Keys.COMMAND_SETTINGS_HEAD, companion.getName());
        for (Verb verb : Verb.values()) {
            text.append("\n  " + verb.word() + ": " + settings.tier(verb).word());
            if (!settings.names(verb).isEmpty()) {
                text.append(" · ").append(Component.translatable(Keys.COMMAND_SETTINGS_NEVER,
                        String.join(", ", settings.names(verb))));
            }
        }
        ctx.getSource().sendSuccess(() -> text, false);
        return 1;
    }

    private static int setTier(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NumenPlayer companion = ownedCompanion(ctx);
        Verb verb = companion == null ? null : word(ctx, "verb", verbWords(), Verb::byWord);
        Tier tier = verb == null ? null : word(ctx, "tier", tierWords(), Tier::byWord);
        if (tier == null) {
            return 0;
        }
        storeOf(ctx.getSource().getPlayerOrException()).edit(companion.getUUID(), new Edit.SetTier(verb, tier));
        ctx.getSource().sendSuccess(() -> Component.translatable(Keys.COMMAND_SETTINGS_SET, companion.getName(),
                verb.word(), tier.word()), false);
        return 1;
    }

    private static int applyPreset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NumenPlayer companion = ownedCompanion(ctx);
        Preset preset = companion == null ? null : word(ctx, "preset", presetWords(), Preset::byWord);
        if (preset == null) {
            return 0;
        }
        storeOf(ctx.getSource().getPlayerOrException()).preset(companion.getUUID(), preset);
        ctx.getSource().sendSuccess(() -> Component.translatable(Keys.COMMAND_SETTINGS_PRESET, companion.getName(),
                preset.word()), false);
        return 1;
    }

    /** 往一种动作的"不用问"名单里记一项,或删一项。 */
    private static int editList(CommandContext<CommandSourceStack> ctx, boolean add) throws CommandSyntaxException {
        NumenPlayer companion = ownedCompanion(ctx);
        Verb verb = companion == null ? null : word(ctx, "verb", verbWords(), Verb::byWord);
        if (verb == null) {
            return 0;
        }
        String entry = StringArgumentType.getString(ctx, "entry").strip();
        PermissionStore store = storeOf(ctx.getSource().getPlayerOrException());
        boolean listed = store.settingsOf(companion.getUUID()).listed(verb, entry);
        if (add == listed || entry.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable(add ? Keys.COMMAND_LIST_HAS : Keys.COMMAND_LIST_HAS_NOT,
                    companion.getName(), verb.word(), entry));
            return 0;
        }
        store.edit(companion.getUUID(), add ? new Edit.Add(verb, entry) : new Edit.Remove(verb, entry));
        ctx.getSource().sendSuccess(() -> Component.translatable(add ? Keys.COMMAND_LIST_ADDED : Keys.COMMAND_LIST_REMOVED,
                companion.getName(), verb.word(), entry), false);
        return 1;
    }

    // ==================== 征询答复 ====================

    private static LiteralArgumentBuilder<CommandSourceStack> consentCommand() {
        return Commands.literal("consent")
                .then(Commands.literal("allow").then(consentId(ConsentAnswer.Decision.ALLOW_ONCE)))
                .then(Commands.literal("remember").then(consentId(ConsentAnswer.Decision.ALLOW_REMEMBER)))
                .then(Commands.literal("deny").then(consentId(ConsentAnswer.Decision.DENY)));
    }

    /** 请求号,后面可以跟一句附言:允许与拒绝都能带,原话交给模型。 */
    private static RequiredArgumentBuilder<CommandSourceStack, Long> consentId(ConsentAnswer.Decision decision) {
        return Commands.argument("id", LongArgumentType.longArg(1)).executes(ctx -> consent(ctx, decision, ""))
                .then(Commands.argument("note", StringArgumentType.greedyString())
                        .executes(ctx -> consent(ctx, decision, StringArgumentType.getString(ctx, "note"))));
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
