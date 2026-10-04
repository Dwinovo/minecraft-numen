package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.ClientCall;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.SeeAlso;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code tlm.skin}:她自己穿哪套女仆模型({@code list}、{@code wear}、{@code remove})。她养的女仆是另一组 {@code tlm.maid}
 * (见 {@link MaidApi})。
 *
 * <h2>为什么穿模型的三个在主人客户端</h2>
 * 模型包只有客户端知道({@code CustomPackLoader} 是客户端类),穿什么也记在主人这边({@link Wardrobe}),发去服务端问,服务端也答
 * 不上来。两侧都登记(帮助要它),函数只在客户端跑:第一个参数是 {@link ClientCall}。女仆是世界里的实体,管女仆的那几个在服务端。
 */
public final class SkinApi {

    private static final String ABSENT = "这里没装车万女仆,换不了模型";

    private SkinApi() {}

    static void install(NumenApi numen) {
        numen.api("skin", "Touhou Little Maid looks: the maid model you wear yourself.", SkinApi.class);
        numen.api("maid", "Touhou Little Maid: the maids you keep.", MaidApi.class);
    }

    /** 一个模型。 */
    @Doc("A maid model installed on your owner's client.")
    public record Model(@Doc("What tlm.skin.wear takes.") String id, String name, String pack) {}

    /** 一个模型包。 */
    @Doc("A model pack: its name, how many models, a few of their names.")
    public record Pack(String pack, int count, List<String> examples) {}

    /** 穿着的与装着的。 */
    @Doc("Which maid model you wear, and which are installed.")
    public record Looks(@Doc("The model you wear now; none when you wear your own look.")
                           Optional<String> currentModel,
                           Optional<String> currentName,
                           @Doc("Without search: how many models in all.") Optional<Integer> total,
                           @Doc("Without search: every pack.") Optional<List<Pack>> packs,
                           @Doc("With search: every model found.") Optional<List<Model>> models) {}

    /** 找什么。 */
    public record Search(@Doc("Character name, pack name or id to look for.")
                         @Omitted("get one entry per pack instead of single models") Optional<String> search) {}

    /**
     * 不带关键词只给包级摘要,带关键词才展开具体条目——这台机器上有两百多个模型,全量倒出去一次吃掉两万多 token,而且给的是一堆哈希
     * id,模型拿到了也讲不清哪个是哪个(理由见 {@link MaidCatalog})。
     */
    @Fn("Your own look: which maid model you wear now, and which are installed.")
    @Example("tlm.skin.list()")
    @Example("tlm.skin.list({search = \"灵梦\"})")
    @Note("Read-only. Runs on your owner's client, where the model packs are.")
    @Note("Without search every pack, with search every model found.")
    @SeeAlso("tlm.skin.wear")
    public static Looks list(ClientCall call, Search args) {
        present();
        String worn = Wardrobe.worn(call.companion());
        Optional<String> current = Optional.ofNullable(worn);
        Optional<String> name = current.map(MaidCatalog::nameOf);
        String q = args.search().map(String::trim).orElse("");
        if (q.isEmpty()) {
            List<Pack> packs = MaidCatalog.summary();
            int total = packs.stream().mapToInt(Pack::count).sum();
            return new Looks(current, name, Optional.of(total), Optional.of(packs), Optional.empty());
        }
        return new Looks(current, name, Optional.empty(), Optional.empty(), Optional.of(MaidCatalog.search(q)));
    }

    /** 穿哪一个。 */
    public record Wear(@Doc("The maid model to wear: a model id exactly as tlm.skin.list({search = ...}) lists it.")
                       ResourceLocation model) {}

    /** 穿上的。 */
    @Doc("The maid model you now wear.")
    public record Worn(String currentModel, String currentName) {}

    /**
     * 只认清单里真实存在的 id。模型不存在时直接失败并指回清单——比默默换成一个空模型好:她会知道自己刚才那句没生效,下一轮能自己改口。
     */
    @Fn("Your own look: put on a maid model.")
    @Example("tlm.skin.wear(\"touhou_little_maid:hakurei_reimu\")")
    @Note("It covers your whole body: a YSM model or your own skin stops showing until you take it off.")
    @Note("It does not ask your owner; tell them what you changed into.")
    @SeeAlso({"tlm.skin.list", "tlm.skin.remove"})
    public static Worn wear(ClientCall call, Wear args) {
        present();
        String model = args.model().toString();
        if (!Tlm.exists(model)) {
            // 不把全量清单塞回去(两百多个,一次两万 token),指回清单去搜
            throw new ApiError(ErrorKind.NOT_FOUND, "没有叫 " + model + " 的模型;搜一下正确的 id",
                    Call.of("tlm.skin.list", Map.of("search", args.model().getPath())));
        }
        Wardrobe.wear(call.companion(), model);
        return new Worn(model, MaidCatalog.nameOf(model));
    }

    @Fn("Your own look: take the maid model off; your other look shows again.")
    @Example("tlm.skin.remove()")
    @SeeAlso("tlm.skin.wear")
    public static void remove(ClientCall call) {
        present();
        Wardrobe.wear(call.companion(), null);
    }

    private static void present() {
        if (!Tlm.present()) {
            throw new ApiError(ErrorKind.FAILED, ABSENT, null);
        }
    }
}
