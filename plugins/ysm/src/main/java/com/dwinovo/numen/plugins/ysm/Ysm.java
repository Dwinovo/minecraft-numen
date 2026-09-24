package com.dwinovo.numen.plugins.ysm;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestion;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本插件对接 YSM 的那一面——<b>唯一</b>知道 YSM 存在的地方。
 *
 * <h2>为什么只走命令与 NBT,不碰 YSM 的类</h2>
 * YSM 是闭源且混淆的:2.6.5 里模型选择界面叫
 * {@code com.elfmcys.yesstevemodel.O0o0Oo0Oo0Ooo0oO000o0OOO},下一个版本就换名字。
 * 引用它等于把本插件绑死在某一个 YSM 版本上。
 *
 * <p>这里只用三样混淆改不动的东西:
 * <ul>
 *   <li><b>命令</b>——{@code ysm model set} / {@code ysm play} / {@code ysm auth} 是 YSM
 *       对外的公开面,三个加载器上一字不差,而且目标参数用的是原版的
 *       {@code EntityArgument.players()},所以同伴(服务端假玩家)在玩家列表里就打得中。
 *       已在 1.21.1 + YSM 2.6.5 真机验过。</li>
 *   <li><b>命令补全</b>——有哪些模型、某个模型有哪些贴图、当前模型有哪些动作,都问命令树的
 *       补全({@code ysm model set <玩家> <Tab>})。这是 YSM 自己维护的清单:目录里的、
 *       {@code .ysm} 打包的、zip 里的模型全在,格式和目录规则一概不用我们认,
 *       2.4.1 与 2.6.5 都挂了补全提供者。</li>
 *   <li><b>NBT 键名</b>——它们是源码里的字符串字面量,混淆器不改字符串。存在哪一层
 *       随加载器而异,见 {@link Storage}。</li>
 * </ul>
 */
public final class Ysm {

    /**
     * YSM 在各加载器上把玩家数据存在 NBT 的哪里。
     *
     * <p>三个加载器的持久化机制不同,YSM 各用各的:NeoForge 是 data attachment,Forge 是
     * capability,Fabric 没有这类机制、YSM 自己往实体 NBT 里写。外层键、以及内层键带不带
     * 命名空间前缀,都随之而异。本加载器是哪一种由宿主说({@link YsmHost#storage()})。
     */
    public enum Storage {
        /** 真机 dump 确认(1.21.1,YSM 2.6.5)。 */
        NEOFORGE("neoforge:attachments", "yes_steve_model:"),
        /** 真机确认(1.20.1,YSM 2.6.5):换装后回读到了新模型。 */
        FORGE("ForgeCaps", "yes_steve_model:"),
        /** 从 YSM 2.6.5 Fabric 版的字节码读出:实体存档时 {@code tag.put("ysm", …)},内层按 capability 名直接放,不带前缀。 */
        FABRIC("ysm", "");

        private final String root;
        private final String prefix;

        Storage(String root, String prefix) {
            this.root = root;
            this.prefix = prefix;
        }
    }

    private static final String MODEL_INFO = "model_id";

    /**
     * 主人被授权的模型集合。
     *
     * <p><b>这一条尚未真机确认</b>:同一层里还有 {@code star_models},测试时两者都是空的
     * (用的模型不需要授权),分不出哪个是授权表、哪个是收藏夹。判据很简单——给自己授权
     * 一个模型,再 {@code /data get entity @s} 看哪个列表多了东西。
     * 认错了也不会放行越权:{@link #setModel} 不传 ignore_auth,YSM 自己会拦。
     */
    private static final String AUTH_MODELS = "own_models";

    private static final String KEY_MODEL = "model_id";
    private static final String KEY_TEXTURE = "select_texture";

    private final Storage storage;

    public Ysm(Storage storage) {
        this.storage = storage;
    }

    // ---- 读:玩家现在穿什么(NBT) ----

    /** 一个玩家的 NBT 里 YSM 那一块;YSM 没给这个玩家写过时是空的。 */
    private CompoundTag data(ServerPlayer player) {
        CompoundTag all = new CompoundTag();
        player.saveWithoutId(all);
        return all.getCompound(storage.root);
    }

    /** 一个玩家当前的模型与贴图;YSM 没给这个玩家写过时返回 null。 */
    public Look readLook(ServerPlayer player) {
        CompoundTag info = data(player).getCompound(storage.prefix + MODEL_INFO);
        if (info.isEmpty()) return null;
        String model = info.getString(KEY_MODEL);
        return model.isEmpty() ? null : new Look(model, info.getString(KEY_TEXTURE));
    }

    /** 一个玩家被授权的模型集合。读不到就是空集——空集意味着"什么都不镜像",不是"放行一切"。 */
    public Set<String> readAuthorized(ServerPlayer player) {
        ListTag list = data(player).getList(storage.prefix + AUTH_MODELS, Tag.TAG_STRING);
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) out.add(list.getString(i));
        return out;
    }

    // ---- 读:YSM 认哪些 id(命令补全) ----

    /** YSM 认的全部模型 id。 */
    public List<String> models(MinecraftServer server, String playerName) {
        return suggestions(server, "ysm model set " + arg(playerName) + " ");
    }

    /**
     * 某个模型的贴图 id。补全按字母排,第一张未必是作者在模型里定的默认;
     * YSM 认 {@code -}(用默认贴图)的版本会把它也列出来,它排在最前。
     */
    public List<String> textures(MinecraftServer server, String playerName, String model) {
        return suggestions(server, "ysm model set " + arg(playerName) + " " + arg(model) + " ");
    }

    /** 一个玩家现在这身模型能做的动作名。 */
    public List<String> emotes(MinecraftServer server, String playerName) {
        return suggestions(server, "ysm play " + arg(playerName) + " ");
    }

    /** 命令行敲到这里、按 Tab 会列出什么。补全提供者是同步的,在服务端线程上直接取。 */
    private static List<String> suggestions(MinecraftServer server, String input) {
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        ParseResults<CommandSourceStack> parse = dispatcher.parse(input, source(server));
        return dispatcher.getCompletionSuggestions(parse).join().getList().stream()
                .map(Suggestion::getText).map(Ysm::unquote).toList();
    }

    /** 补全给的是命令行里的写法,带空格的 id 会带引号;还原成 id 本身。 */
    private static String unquote(String token) {
        if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
            return token.substring(1, token.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return token;
    }

    // ---- 写:走命令 ----

    /**
     * 给一个玩家换模型。<b>刻意不传 ignore_auth</b>——省略时 YSM 默认按授权检查,
     * 同伴要不到主人没有的模型是 YSM 在拦,不是本插件写 if 拦。
     *
     * <p>贴图由调用方从 {@link #textures} 里挑好再传,这里不补占位符:{@code -} 只有 2.6 起
     * 才认,2.4.1 会把它当贴图名原样存下、模型渲染成紫黑格——1.21 只有 2.4.1 可用,真机撞见过。
     *
     * @return YSM 对这条命令说的话(换好了、要授权、没有这个模型……);它在几种情况下什么都不说
     */
    public List<String> setModel(MinecraftServer server, String playerName, Look look) {
        return run(server, "ysm model set " + arg(playerName) + " " + arg(look.model()) + " " + arg(look.texture()));
    }

    public void playAnimation(MinecraftServer server, String playerName, String animation) {
        run(server, "ysm play " + arg(playerName) + " " + arg(animation));
    }

    public void stopAnimation(MinecraftServer server, String playerName) {
        run(server, "ysm play " + arg(playerName) + " stop");
    }

    public void authClear(MinecraftServer server, String playerName) {
        run(server, "ysm auth " + arg(playerName) + " clear");
    }

    public void authAdd(MinecraftServer server, String playerName, String modelId) {
        run(server, "ysm auth " + arg(playerName) + " add " + arg(modelId));
    }

    /**
     * 以服务器的身份执行一条 YSM 命令,返回它对执行者说的每一句话(去掉颜色码)。
     *
     * <p>命令什么时候真正执行,看调用方在哪:不在任何一条指令的执行当中(服务器刻里跑的任务、网络包送来的调用)
     * 时,这里返回前它已经执行完;在另一条指令的执行当中(控制台、{@code /numen debug}、{@code /test} 调进来的)时,
     * 原版的指令队列把它排到那条指令之后,这里返回时它还没跑,也就还什么都没说。要以执行结果为准的事
     * (换模型要回读身上穿的)因此放在任务里做,见 {@link SwitchTask}。
     */
    private static List<String> run(MinecraftServer server, String command) {
        Heard heard = new Heard(server);
        server.getCommands().performPrefixedCommand(source(server).withSource(heard), command);
        return heard.lines;
    }

    /**
     * 命令回话的去处:YSM 对执行者说的每一句都收下。要不要知会管理员、记进服务器日志,照服务器自己的来,
     * 和直接以服务器身份执行时一样。
     */
    private static final class Heard implements CommandSource {

        private final MinecraftServer server;
        private final List<String> lines = new ArrayList<>();

        Heard(MinecraftServer server) {
            this.server = server;
        }

        @Override
        public void sendSystemMessage(Component message) {
            lines.add(ChatFormatting.stripFormatting(message.getString()));
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return server.shouldInformAdmins();
        }
    }

    /**
     * YSM 的命令要权限等级 2。这里用等级 4 的服务器源:调用方是插件而不是玩家,
     * 越权与否已经在上层按"主人的授权集合"判过了。补全也用它,否则权限不够的节点不会被列出。
     */
    private static CommandSourceStack source(MinecraftServer server) {
        return server.createCommandSourceStack().withPermission(4);
    }

    /** 模型 id 带斜杠(misc/1_alex),名字可能带空格——交给 Brigadier 自己决定要不要加引号。 */
    private static String arg(String raw) {
        return StringArgumentType.escapeIfRequired(raw);
    }

    /** 一个玩家的外观:模型 + 贴图。 */
    public record Look(String model, String texture) {}
}
