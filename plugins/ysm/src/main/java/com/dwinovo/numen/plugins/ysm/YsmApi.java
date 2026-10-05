package com.dwinovo.numen.plugins.ysm;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.OnHer;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;

import java.util.List;
import java.util.Optional;

/**
 * {@code ysm.model}:现在穿什么、能换成什么;换一身;做一个动作。三个都在服务端,全走 YSM 自己的命令、命令补全与同伴的 NBT(见
 * {@link Ysm})。
 *
 * <p>三个都经 {@link ServerCall#onHer} 以服务器的权威执行 YSM 的命令:这几条命令要权限等级 2,她自己多半没有;作用对象写死为她
 * ({@link OnHer}),能换成什么仍由 YSM 按镜像来的主人授权判。
 */
public final class YsmApi {

    /** 这个联动在她的 API 里的名字空间。 */
    static final String NAMESPACE = "ysm";

    /** YSM 自己的 {@code ysm play <玩家> stop} 就用这个词停下动作,这里照搬。 */
    private static final String STOP = "stop";

    /** 读 NBT、跑命令的那一份;联动装上时给。 */
    private static Ysm ysm;

    private YsmApi() {}

    static void install(NumenApi numen, Ysm ysm) {
        YsmApi.ysm = ysm;
        numen.api("model", "Yes Steve Model looks: what you wear and can switch to, switching, emotes.",
                YsmApi.class);
    }

    /** 穿着的与能换的。 */
    @Doc("Your Yes Steve Model look and what you can switch to.")
    public record Options(@Doc("The model you wear now; none when it cannot be read (YSM may be missing).")
                          Optional<String> currentModel,
                          Optional<String> currentTexture,
                          @Doc("This model's textures: what ysm.model.switch takes as texture.") List<String> textures,
                          @Doc("Every model you can switch to, by the id ysm.model.switch takes.") List<String> models) {}

    /**
     * 现在穿什么、能换成什么、这身有哪几张贴图——一次问清:本来就是同一个问题的几面。清单不写进帮助里:帮助跟着玩家装的模型变,查询
     * 就该是查询。这身模型有哪些动作不在里面:YSM 不告诉服务器(见 {@link Ysm})。
     */
    @Fn("Your model and texture now, the models you can switch to, and this model's textures.")
    @Example("ysm.model.options()")
    @Example("for _, m in ipairs(ysm.model.options().models) do print(m) end")
    @Note("Read-only. Emotes are not listed: YSM does not tell the server which ones a model has.")
    @Note("It runs YSM's own commands with the server's authority, on you only.")
    @SeeAlso({"ysm.model.switch", "ysm.model.emote"})
    public static Options options(ServerCall call) {
        OnHer her = call.onHer();
        Ysm.Look look = ysm.readLook(call.her());
        return new Options(Optional.ofNullable(look).map(Ysm.Look::model),
                Optional.ofNullable(look).map(Ysm.Look::texture),
                look == null ? List.of() : ysm.textures(her, look.model()), ysm.models(her));
    }

    /** 换成哪身。 */
    public record Switch(@Doc("The model to switch to: a model id exactly as ysm.model.options lists it.") String model,
                         @Doc("Which of the model's textures to wear: a texture id from the textures "
                                 + "ysm.model.options lists.") @Omitted("use the model's first texture")
                         Optional<String> texture) {}

    /**
     * 换一身模型:这里先把写不通的当场拒掉(YSM 不认的模型、定不了默认贴图),写得通的交给一件短活({@link SwitchTask}),在那里执行
     * YSM 的命令、回读她身上穿的,成败以回读为准。
     *
     * <h2>能换成什么由 YSM 判,不由这里判</h2>
     * 命令刻意不传 {@code ignore_auth},YSM 会按同伴自己的授权表检查;而那张表由 {@link OwnerSync} 持续镜像成主人的。所以"主人没有的
     * 模型同伴也要不到"是 YSM 在拦——这里不写这个 if,也就不会有"我们的判断和 YSM 的判断不一致"。
     */
    @Fn(value = "Switch to another model.", name = "switch")
    @Example("ysm.model.switch(\"misc/1_alex\")")
    @Example("ysm.model.switch(\"抽象鸣潮 菲比.ysm\")")
    @Note("You can have exactly the models your owner is authorized for. A refusal comes from YSM, so don't retry the "
            + "same model.")
    @Note("Short, not background work: it comes back once your body shows the new look, or with what YSM said. It "
            + "does not ask your owner.")
    @SeeAlso("ysm.model.options")
    public static Pending<Void> switch_(ServerCall call, Switch args) {
        String model = args.model();
        OnHer her = call.onHer();
        // 贴图不给就用 YSM 给这个模型列的第一张——问的是它自己的补全,不猜文件格式
        String texture = args.texture().orElse(null);
        if (texture == null) {
            if (!ysm.models(her).contains(model)) {
                throw new ApiError(ErrorKind.NOT_FOUND, "YSM 不认 '" + model + "' 这个模型;看清单里的 id",
                        Call.of("ysm.model.options"));
            }
            List<String> textures = ysm.textures(her, model);
            if (textures.isEmpty()) {
                throw new ApiError(ErrorKind.FAILED, "YSM 没给 '" + model + "' 列出贴图,定不了默认贴图;用 {texture = ...} "
                        + "指定一个", null);
            }
            texture = textures.get(0);
        }
        return call.sync(new SwitchRecord(call, her, new Ysm.Look(model, texture)));
    }

    /** 做哪个动作。 */
    public record Emote(@Doc("The animation to play: an animation id of the model you wear, e.g. extra1 (YSM does not "
            + "tell the server which ones a model has), or " + STOP + " to go back to idle.") String animation) {}

    /**
     * 做一个动作。动作名不写死在这里:每个模型自带一套。
     *
     * <h2>只是发出</h2>
     * YSM 的 play 命令是静默的,动作名不存在时它既不报错也不回执;服务端又拿不到这身模型的动作清单(见 {@link Ysm})。所以这里核对不了她
     * 做没做成,不交回"做了";说明里照实写核对不了。
     *
     * <p><b>音效不用我们管。</b> 模型作者可以把音效接在动画上(动画 JSON 里的 {@code sound_effects}),YSM 播动画时一并放。真机验过:
     * 同伴是服务端假玩家,但 YSM 照样给它放声音——播放路径没有区分真假玩家。所以这里只管发 play 命令。
     */
    @Fn("Play one of this model's emotes, or stop the one playing.")
    @Example("ysm.model.emote(\"extra1\")")
    @Example("ysm.model.emote(\"" + STOP + "\")")
    @Note("It only sends the command and returns nothing: YSM does not tell the server which animations a model has, "
            + "so whether this one exists cannot be checked, and a missing one does nothing.")
    @SeeAlso("ysm.model.options")
    public static void emote(ServerCall call, Emote args) {
        String animation = args.animation();
        OnHer her = call.onHer();
        if (STOP.equalsIgnoreCase(animation)) {
            ysm.stopAnimation(her);
        } else {
            ysm.playAnimation(her, animation);
        }
    }
}
