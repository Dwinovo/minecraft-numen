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
 *       对外的公开面,而且目标参数用的是原版的 {@code EntityArgument.players()},
 *       所以同伴(服务端假玩家)在玩家列表里就打得中。已在 1.21.1 + YSM 2.6.5 真机验过。</li>
 *   <li><b>NBT 键名</b>——它们是源码里的字符串字面量,混淆器不改字符串。存储位置是
 *       NeoForge 的 data attachment,外层键是 ResourceLocation,同样不混淆。</li>
 * </ul>
 */
public final class Ysm {

    /** 玩家 NBT 里 YSM 那块 attachment 的位置。真机 dump 确认过。 */
    private static final String ATTACHMENTS = "neoforge:attachments";
    private static final String MODEL_INFO = "yes_steve_model:model_id";

    /**
     * 主人被授权的模型集合。
     *
     * <p><b>这一条尚未真机确认</b>:同一层 attachment 里还有 {@code yes_steve_model:star_models},
     * 测试时两者都是空的(用的模型不需要授权),分不出哪个是授权表、哪个是收藏夹。
     * 判据很简单——给自己授权一个模型,再 {@code /data get entity @s} 看哪个列表多了东西。
     * 认错了也不会放行越权:{@link #setModel} 不传 ignore_auth,YSM 自己会拦。
     */
    private static final String AUTH_MODELS = "yes_steve_model:own_models";

    private static final String KEY_MODEL = "model_id";
    private static final String KEY_TEXTURE = "select_texture";

    /** 没有贴图时 YSM 认这个占位符。 */
    public static final String NO_TEXTURE = "-";

    private Ysm() {}

    // ---- 读:玩家现在穿什么 ----

    /**
     * 一个玩家当前的模型与贴图;YSM 没给这个玩家建过 attachment 时返回 null。
     *
     * <p>26.x 起实体存档换成了 {@code ValueOutput},而且 {@code CompoundTag} 的取值
     * 一律返回 {@code Optional}——所以这段和低版本分支长得不一样,不是抄漏了。
     */
    public static Look readLook(ServerPlayer player) {
        CompoundTag all = snapshot(player);
        if (all == null) return null;
        var info = all.getCompound(ATTACHMENTS).flatMap(a -> a.getCompound(MODEL_INFO));
        if (info.isEmpty()) return null;
        String model = info.get().getString(KEY_MODEL).orElse("");
        return model.isEmpty() ? null
                : new Look(model, info.get().getString(KEY_TEXTURE).orElse(NO_TEXTURE));
    }

    /** 一个玩家被授权的模型集合。读不到就是空集——空集意味着"什么都不镜像",不是"放行一切"。 */
    public static Set<String> readAuthorized(ServerPlayer player) {
        Set<String> out = new LinkedHashSet<>();
        CompoundTag all = snapshot(player);
        if (all == null) return out;
        all.getCompound(ATTACHMENTS)
           .flatMap(a -> a.getList(AUTH_MODELS))
           .ifPresent(list -> {
               for (int i = 0; i < list.size(); i++) list.getString(i).ifPresent(out::add);
           });
        return out;
    }

    /** 把玩家存成一份 CompoundTag 来读它的 attachment。26.x 起要经 ValueOutput 中转。 */
    private static CompoundTag snapshot(ServerPlayer player) {
        try {
            var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                    net.minecraft.util.ProblemReporter.DISCARDING, player.registryAccess());
            player.saveWithoutId(out);
            return out.buildResult();
        } catch (Throwable ignored) {
            return null;   // YSM 没装/存档形状变了:当作没穿,不报错
        }
    }

    // ---- 写:走命令 ----

    /**
     * 给一个玩家换模型。<b>刻意不传 ignore_auth</b>——省略时 YSM 默认按授权检查,
     * 同伴要不到主人没有的模型是 YSM 在拦,不是本插件写 if 拦。
     */
    public static void setModel(MinecraftServer server, String playerName, Look look) {
        String texture = (look.texture() == null || look.texture().isBlank()) ? NO_TEXTURE : look.texture();
        run(server, "ysm model set " + arg(playerName) + " " + arg(look.model()) + " " + arg(texture));
    }

    public static void playAnimation(MinecraftServer server, String playerName, String animation) {
        run(server, "ysm play " + arg(playerName) + " " + arg(animation));
    }

    public static void stopAnimation(MinecraftServer server, String playerName) {
        run(server, "ysm play " + arg(playerName) + " stop");
    }

    public static void authClear(MinecraftServer server, String playerName) {
        run(server, "ysm auth " + arg(playerName) + " clear");
    }

    public static void authAdd(MinecraftServer server, String playerName, String modelId) {
        run(server, "ysm auth " + arg(playerName) + " add " + arg(modelId));
    }

    /**
     * YSM 的命令要权限等级 2。这里用等级 4 的服务器源执行:调用方是插件而不是玩家,
     * 越权与否已经在上层按"主人的授权集合"判过了。
     */
    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withPermission(
                        // 26.x 起权限从整数等级换成了 PermissionSet;OWNER 对应原来的 4
                        net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER), command);
    }

    /** 模型 id 带斜杠(misc/1_alex),名字可能带空格——交给 Brigadier 自己决定要不要加引号。 */
    private static String arg(String raw) {
        return StringArgumentType.escapeIfRequired(raw);
    }

    /** 一个玩家的外观:模型 + 贴图。 */
    public record Look(String model, String texture) {}
}
