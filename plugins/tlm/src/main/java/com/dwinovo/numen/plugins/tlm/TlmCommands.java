package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.ClientSource;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code tlm}:现在穿哪套女仆模型、这里装了哪些、换上一套、脱下来。
 *
 * <h2>为什么都在主人客户端</h2>
 * 模型包只有客户端知道({@code CustomPackLoader} 是客户端类),穿什么也记在主人这边({@link Wardrobe}),
 * 发去服务端问,服务端也答不上来。命令树两侧都登记(帮助要它),处理函数只在客户端跑。
 *
 * <p>都不提升成快捷工具:联动的动作是长尾,走 {@code numen} 这一个入口就够了。
 */
final class TlmCommands {

    static final String GROUP = "tlm";
    static final String MODELS = "models";
    static final String WEAR = "wear";
    static final String REMOVE = "remove";

    private static final String ABSENT = "这里没装车万女仆,换不了模型";

    private static final Param<String> SEARCH = Param.optional("search", ArgType.string(),
            "Character name, pack name or id to look for.")
            .whenOmitted("get one line per pack instead of single models");
    private static final Param<ResourceLocation> MODEL = Param.required("model", ArgType.id(),
            "The maid model to wear.")
            .values("a model id exactly as " + line(MODELS) + " --search lists it");

    private TlmCommands() {}

    /** 回执与状态片段里提到别的动作时写的那一行命令。 */
    static String line(String action) {
        return GROUP + " " + action;
    }

    static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Touhou Little Maid models you can wear: list, wear, take off.",
                TlmCommands::actions);
    }

    private static void actions(CommandGroup tlm) {
        tlm.client(MODELS, "Which maid model you wear now, and which are installed.",
                TlmCommands::models, SEARCH)
                .example(line(MODELS))
                .example(line(MODELS) + " --search 灵梦")
                .note("Read-only. Runs on your owner's client, where the model packs are.")
                .seeAlso(line(WEAR));
        tlm.client(WEAR, "Put on a maid model.",
                TlmCommands::wear, MODEL)
                .example(line(WEAR) + " touhou_little_maid:hakurei_reimu")
                .note("It covers your whole body: a YSM model or your own skin stops showing until you take it off.")
                .note("It does not ask your owner; tell them what you changed into.")
                .seeAlso(line(MODELS), line(REMOVE));
        tlm.client(REMOVE, "Take the maid model off; your other look shows again.",
                TlmCommands::remove)
                .example(line(REMOVE))
                .seeAlso(line(WEAR));
    }

    /**
     * 不带关键词只给包级摘要,带关键词才展开具体条目——这台机器上有两百多个模型,全量倒出去一次吃掉两万多 token,
     * 而且给的是一堆哈希 id,模型拿到了也讲不清哪个是哪个。理由与封顶细节见 {@link MaidCatalog}。
     */
    private static void models(ClientSource src, CommandArgs args) {
        if (!Tlm.present()) {
            src.reply(TaskResult.fail(ABSENT).toJson());
            return;
        }
        String q = args.get(SEARCH) == null ? "" : args.get(SEARCH).trim();

        String wornId = Wardrobe.worn(src.companion());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("current_model", wornId);
        data.put("current_name", wornId == null ? null : MaidCatalog.nameOf(wornId));

        String worn = wornId == null ? "现在是本来的样子" : "现在穿 " + MaidCatalog.nameOf(wornId);

        if (q.isEmpty()) {
            Map<String, Object> packs = MaidCatalog.summary();
            int total = packs.values().stream()
                    .mapToInt(v -> (int) ((Map<?, ?>) v).get("count")).sum();
            data.put("packs", packs);
            data.put("total", total);
            src.reply(TaskResult.ok(worn + ";一共 " + total + " 个模型,分在 " + packs.size()
                    + " 个包里。想找具体哪个,用 --search 搜角色名或包名", data).toJson());
            return;
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (MaidCatalog.Entry e : MaidCatalog.search(q)) {
            Map<String, String> r = new LinkedHashMap<>();
            r.put("id", e.id());
            r.put("name", e.name());
            r.put("pack", e.pack());
            rows.add(r);
        }
        data.put("matches", rows);
        src.reply(TaskResult.ok(worn + ";搜「" + q + "」找到 " + rows.size() + " 个", data).toJson());
    }

    /**
     * 只认清单里真实存在的 id。模型不存在时直接失败并指回清单——比默默换成一个空模型好:同伴会知道自己刚才那句
     * 没生效,下一轮能自己改口。
     */
    private static void wear(ClientSource src, CommandArgs args) {
        if (!Tlm.present()) {
            src.reply(TaskResult.fail(ABSENT).toJson());
            return;
        }
        String model = args.get(MODEL).toString();
        if (!Tlm.exists(model)) {
            // 不把全量清单塞回去(两百多个,一次两万 token),指回清单去搜
            src.reply(TaskResult.fail("没有叫 " + model + " 的模型;用 " + line(MODELS)
                    + " --search 搜一下正确的 id").toJson());
            return;
        }
        Wardrobe.wear(src.companion(), model);
        String name = MaidCatalog.nameOf(model);
        src.reply(TaskResult.ok("换上了 " + name,
                Map.of("current_model", model, "current_name", name)).toJson());
    }

    private static void remove(ClientSource src, CommandArgs args) {
        if (!Tlm.present()) {
            src.reply(TaskResult.fail(ABSENT).toJson());
            return;
        }
        Wardrobe.wear(src.companion(), null);
        src.reply(TaskResult.ok("脱下了,身体交还给别的外观", Map.of()).toJson());
    }
}
