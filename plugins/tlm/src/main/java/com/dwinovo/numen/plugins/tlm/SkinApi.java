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
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code tlm.skin}:她自己穿哪套女仆模型({@code list}、{@code wear}、{@code remove})。她养的女仆是另一组 {@code tlm.maid}
 * (见 {@link MaidApi})。
 *
 * <h2>穿什么在服务端,查名册在客户端</h2>
 * 穿什么是她身体的属性({@link Outfit}):{@code wear}、{@code remove} 在服务端跑,按服务端的模型登记表核对 id,
 * 存在身体上、同步给每个看到她的客户端。{@code list} 在主人客户端跑:模型的显示名是翻译键,中文名搜索都要客户端的语言表,
 * 服务端答不上来。她现在穿的是哪套,由 {@link MaidLook} 随时写进提示词。两侧都登记(帮助要它),函数在哪一侧跑看第一个参数。
 */
public final class SkinApi {

    private static final String ABSENT = "这里没装车万女仆,换不了模型";

    private SkinApi() {}

    static void install(NumenApi numen) {
        numen.api("skin", "Touhou Little Maid looks: the maid model you wear yourself.", SkinApi.class);
        numen.api("maid", "Touhou Little Maid: the maids you keep.", MaidApi.class);
        numen.api("altar", "Touhou Little Maid: what the altar can craft.", AltarApi.class);
    }

    /** 一个模型。 */
    @Doc("A maid model installed on your owner's client.")
    public record Model(@Doc("What tlm.skin.wear takes.") String id, String name, String pack) {}

    /** 一个模型包。 */
    @Doc("A model pack: its name, how many models, a few of their names.")
    public record Pack(String pack, int count, List<String> examples) {}

    /** 装着的。 */
    @Doc("Which maid models are installed.")
    public record Looks(@Doc("Without search: how many models in all.") Optional<Integer> total,
                           @Doc("Without search: every pack.") Optional<List<Pack>> packs,
                           @Doc("With search: every model found.") Optional<List<Model>> models) {}

    /** 找什么。 */
    public record Search(@Doc("Character name, pack name or id to look for.")
                         @Omitted("get one entry per pack instead of single models") Optional<String> search) {}

    /**
     * 不带关键词只给包级摘要,带关键词才展开具体条目——这台机器上有两百多个模型,全量倒出去一次吃掉两万多 token,而且给的是一堆哈希
     * id,模型拿到了也讲不清哪个是哪个(理由见 {@link MaidCatalog})。
     */
    @Fn("Your own look: which maid models are installed.")
    @Example("tlm.skin.list()")
    @Example("tlm.skin.list({search = \"灵梦\"})")
    @Note("Read-only. Runs on your owner's client, where the models' names are.")
    @Note("Without search every pack, with search every model found.")
    @SeeAlso({"tlm.skin.wear", "tlm.skin.remove"})
    public static Looks list(ClientCall call, Search args) {
        present();
        String q = args.search().map(String::trim).orElse("");
        if (q.isEmpty()) {
            List<Pack> packs = MaidCatalog.summary();
            int total = packs.stream().mapToInt(Pack::count).sum();
            return new Looks(Optional.of(total), Optional.of(packs), Optional.empty());
        }
        return new Looks(Optional.empty(), Optional.empty(), Optional.of(MaidCatalog.search(q)));
    }

    /** 穿哪一个。 */
    public record Wear(@Doc("The maid model to wear: a model id exactly as tlm.skin.list({search = ...}) lists it.")
                       ResourceLocation model) {}

    /** 穿上的。 */
    @Doc("The maid model you now wear.")
    public record Worn(String currentModel) {}

    /**
     * 只认清单里真实存在的 id。模型不存在时直接失败并指回清单——比默默换成一个空模型好:她会知道自己刚才那句没生效,下一轮能自己改口。
     */
    @Fn("Your own look: put on a maid model.")
    @Example("tlm.skin.wear(\"touhou_little_maid:hakurei_reimu\")")
    @Note("It covers your whole body: a YSM model or your own skin stops showing until you take it off. Everyone who "
            + "sees you sees the model, as long as their game has its model pack.")
    @Note("It does not ask your owner; tell them what you changed into.")
    @SeeAlso({"tlm.skin.list", "tlm.skin.remove"})
    public static Worn wear(ServerCall call, Wear args) {
        String model = args.model().toString();
        if (!Maids.hasModel(model)) {
            // 不把全量清单塞回去(两百多个,一次两万 token),指回清单去搜
            throw new ApiError(ErrorKind.NOT_FOUND, "没有叫 " + model + " 的模型;搜一下正确的 id",
                    Call.of("tlm.skin.list", Map.of("search", args.model().getPath())));
        }
        Outfit.wear(call.her(), model);
        return new Worn(model);
    }

    @Fn("Your own look: take the maid model off; your other look shows again.")
    @Example("tlm.skin.remove()")
    @SeeAlso("tlm.skin.wear")
    public static void remove(ServerCall call) {
        Outfit.wear(call.her(), null);
    }

    private static void present() {
        if (!Tlm.present()) {
            throw new ApiError(ErrorKind.FAILED, ABSENT, null);
        }
    }
}
