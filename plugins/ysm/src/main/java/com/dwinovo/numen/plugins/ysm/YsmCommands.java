package com.dwinovo.numen.plugins.ysm;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.NumenCli;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code numen ysm}:现在穿什么、能换成什么、能做哪些动作;换一身;做一个动作。三个都在服务端,全走 YSM 自己的
 * 命令、命令补全与同伴的 NBT(见 {@link Ysm})。
 *
 * <p>都不提升成快捷工具:联动的动作是长尾,走 {@code numen} 这一个入口就够了。
 */
final class YsmCommands {

    static final String GROUP = "ysm";
    static final String OPTIONS = "options";
    static final String SWITCH = "switch";
    static final String EMOTE = "emote";

    /** YSM 自己的 {@code ysm play <玩家> stop} 就用这个词停下动作,这里照搬。 */
    private static final String STOP = "stop";

    private static final Param<String> MODEL = Param.required("model", ArgType.string(),
            "Model id exactly as " + line(OPTIONS) + " lists it, e.g. misc/1_alex.");
    private static final Param<String> TEXTURE = Param.optional("texture", ArgType.string(),
            "Texture id from the model's textures in " + line(OPTIONS) + "; without it, the model's first one.");
    private static final Param<String> ANIMATION = Param.required("animation", ArgType.string(),
            "Animation id from " + line(OPTIONS) + ", or " + STOP + " to go back to idle.");

    private final Ysm ysm;

    private YsmCommands(Ysm ysm) {
        this.ysm = ysm;
    }

    /** 回执里提到别的动作时写的那一行命令。 */
    static String line(String action) {
        return NumenCli.ROOT + " " + GROUP + " " + action;
    }

    static void install(NumenApi numen, Ysm ysm) {
        numen.registerCommands(GROUP, "Yes Steve Model looks: what you wear and can switch to, switching, emotes.",
                new YsmCommands(ysm)::actions);
    }

    private void actions(CommandGroup group) {
        group.server(OPTIONS, "Your model and texture now, the models you can switch to, and this model's "
                + "textures and emotes.", this::options);
        group.server(SWITCH, "Switch to another model. You can have exactly the models the owner is authorized for.",
                this::switchModel, MODEL, TEXTURE);
        group.server(EMOTE, "Play one of this model's emotes, or stop the one playing.",
                this::emote, ANIMATION);
    }

    /**
     * 现在穿什么、能换成什么、能做哪些动作——一次问清:三样本来就是同一个问题的三面。清单不写进帮助里:帮助跟着
     * 玩家装的模型变,查询就该是查询。
     */
    private void options(ServerSource src, CommandArgs args) {
        var server = src.companion().level().getServer();
        if (server == null) {
            src.reply(TaskResult.fail("身体不在服务端上").toJson());
            return;
        }
        String me = src.companion().getName().getString();
        var look = ysm.readLook(src.companion());
        var models = ysm.models(server, me);
        var textures = look == null ? List.<String>of() : ysm.textures(server, me, look.model());
        var emotes = ysm.emotes(server, me);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("current_model", look == null ? "(读不到,YSM 可能没装)" : look.model());
        data.put("current_texture", look == null ? "" : look.texture());
        data.put("available_models", models);
        data.put("textures", textures);   // 当前模型的贴图 id,switch 的 --texture 从这里挑
        data.put("emotes", emotes);

        String summary = look == null
                ? "读不到当前模型,YSM 可能没装"
                : "现在穿 " + look.model() + ",可换 " + models.size()
                  + " 个模型,当前模型有 " + emotes.size() + " 个动作";
        src.reply(TaskResult.ok(summary, data).toJson());
    }

    /**
     * 换一身模型:这里先把写不通的当场拒掉(YSM 不认的模型、定不了默认贴图),写得通的交给任务槽里的一次短任务
     * ({@link SwitchTask}),在那里执行 YSM 的命令、回读她身上穿的,成败以回读为准。
     *
     * <h2>能换成什么由 YSM 判,不由这里判</h2>
     * 命令刻意不传 {@code ignore_auth},YSM 会按同伴自己的授权表检查;而那张表由 {@link OwnerSync} 持续镜像成主人的。
     * 所以"主人没有的模型同伴也要不到"是 YSM 在拦——这里不写这个 if,也就不会有"我们的判断和 YSM 的判断不一致"。
     */
    private void switchModel(ServerSource src, CommandArgs args) {
        String model = args.get(MODEL);
        var server = src.companion().level().getServer();
        if (server == null) {
            src.reply(TaskResult.fail("身体不在服务端上").toJson());
            return;
        }
        String me = src.companion().getName().getString();
        // 贴图不给就用 YSM 给这个模型列的第一张——问的是它自己的补全,不猜文件格式
        String texture = args.get(TEXTURE);
        if (texture == null) {
            if (!ysm.models(server, me).contains(model)) {
                src.reply(TaskResult.fail(
                        "YSM 不认 '" + model + "' 这个模型。用 " + line(OPTIONS) + " 看清单里的 id").toJson());
                return;
            }
            var textures = ysm.textures(server, me, model);
            if (textures.isEmpty()) {
                src.reply(TaskResult.fail(
                        "YSM 没给 '" + model + "' 列出贴图,定不了默认贴图;用 --texture 指定一个").toJson());
                return;
            }
            texture = textures.get(0);
        }

        TaskDispatch.runSync(src.companion(), new SwitchRecord(src, new Ysm.Look(model, texture)), src::reply);
    }

    /**
     * 做一个动作。动作名不写死在这里:每个模型自带一套,清单问 YSM 自己的命令补全,跟着同伴现在穿的模型走。
     *
     * <p><b>音效不用我们管。</b> 模型作者可以把音效接在动画上(动画 JSON 里的 {@code sound_effects}),YSM 播动画时
     * 一并放。真机验过:同伴是服务端假玩家,但 YSM 照样给它放声音——播放路径没有区分真假玩家。所以这里只管发 play 命令。
     */
    private void emote(ServerSource src, CommandArgs args) {
        String animation = args.get(ANIMATION);
        var server = src.companion().level().getServer();
        if (server == null) {
            src.reply(TaskResult.fail("身体不在服务端上").toJson());
            return;
        }
        String me = src.companion().getName().getString();
        if (STOP.equalsIgnoreCase(animation)) {
            ysm.stopAnimation(server, me);
            src.reply(TaskResult.ok("停下了").toJson());
            return;
        }
        // YSM 的 play 命令是静默的:动作名不存在时它既不报错也不回执,所以先自己核一遍,
        // 否则模型会以为做了、其实什么都没发生。
        var known = ysm.emotes(server, me);
        if (!known.isEmpty() && !known.contains(animation)) {
            src.reply(TaskResult.fail(
                    "当前模型没有 '" + animation + "' 这个动作,用 " + line(OPTIONS) + " 看有哪些").toJson());
            return;
        }
        ysm.playAnimation(server, me, animation);
        src.reply(TaskResult.ok("做了 " + animation).toJson());
    }
}
