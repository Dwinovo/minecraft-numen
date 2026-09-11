package com.dwinovo.numen.plugins.ysm;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 本插件对接 YSM 的那一面——<b>唯一</b>知道 YSM 存在的地方。
 *
 * <h2>为什么只走命令与 NBT,不碰 YSM 的类</h2>
 * YSM 是闭源且混淆的:2.6.5 里模型选择界面叫
 * {@code com.elfmcys.yesstevemodel.O0o0Oo0Oo0Ooo0oO000o0OOO},下一个版本就换名字。
 * 引用它等于把本插件绑死在某一个 YSM 版本上。
 *
 * <p>这里只用两样混淆改不动的东西:
 * <ul>
 *   <li><b>命令</b>——{@code ysm model set} / {@code ysm play} / {@code ysm auth} 是 YSM
 *       对外的公开面,三个加载器上一字不差,而且目标参数用的是原版的
 *       {@code EntityArgument.players()},所以同伴(服务端假玩家)在玩家列表里就打得中。
 *       已在 1.21.1 + YSM 2.6.5 真机验过。</li>
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

    // ---- 读:玩家现在穿什么 ----

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

    // ---- 写:走命令 ----

    /**
     * 给一个玩家换模型。<b>刻意不传 ignore_auth</b>——省略时 YSM 默认按授权检查,
     * 同伴要不到主人没有的模型是 YSM 在拦,不是本插件写 if 拦。
     *
     * <p>贴图必须是真实的贴图 id({@link YsmCatalog#textures}),这里不替调用方补占位符:
     * YSM 2.6.5 起认 {@code -} 为"用默认贴图",2.4.1 却把它当贴图名原样存下,模型渲染成
     * 紫黑格——1.21 只有 2.4.1 可用,真机撞见过。
     */
    public void setModel(MinecraftServer server, String playerName, Look look) {
        run(server, "ysm model set " + arg(playerName) + " " + arg(look.model()) + " " + arg(look.texture()));
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
     * YSM 的命令要权限等级 2。这里用等级 4 的服务器源执行:调用方是插件而不是玩家,
     * 越权与否已经在上层按"主人的授权集合"判过了。
     */
    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withPermission(4), command);
    }

    /** 模型 id 带斜杠(misc/1_alex),名字可能带空格——交给 Brigadier 自己决定要不要加引号。 */
    private static String arg(String raw) {
        return StringArgumentType.escapeIfRequired(raw);
    }

    /** 一个玩家的外观:模型 + 贴图。 */
    public record Look(String model, String texture) {}
}
