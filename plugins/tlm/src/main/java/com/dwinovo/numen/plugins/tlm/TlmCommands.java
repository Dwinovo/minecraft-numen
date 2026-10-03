package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.ClientSource;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code tlm}:两件事住在同一个组里——她自己穿哪套女仆模型({@code models}、{@code wear}、{@code remove}),和她养的女仆
 * ({@code maids}、{@code maid}、{@code task}、{@code config}、{@code open},见 {@link MaidCommands})。
 *
 * <h2>为什么穿模型的三个在主人客户端</h2>
 * 模型包只有客户端知道({@code CustomPackLoader} 是客户端类),穿什么也记在主人这边({@link Wardrobe}),
 * 发去服务端问,服务端也答不上来。命令树两侧都登记(帮助要它),处理函数只在客户端跑。女仆是世界里的实体,管女仆的
 * 那几个在服务端。
 *
 * <p>每个动作就是脚本里的一个函数({@code tlm.wear("…")}),和别的动作同一个入口。
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
            .values("a model id exactly as " + line(MODELS) + "({search = ...}) lists it");

    private TlmCommands() {}

    /** 回执与状态片段里提到别的动作时写它的函数:{@code tlm.wear}。 */
    static String line(String action) {
        return GROUP + "." + action;
    }

    /** 相关动作里点名一个动作:{@code tlm wear}。 */
    static String path(String action) {
        return GROUP + " " + action;
    }

    static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Touhou Little Maid: the maid model you wear yourself, and the maids you keep.",
                TlmCommands::actions);
    }

    private static void actions(CommandGroup tlm) {
        tlm.client(MODELS, "Your own look: which maid model you wear now, and which are installed.",
                TlmCommands::models, SEARCH, Listing.PAGE)
                .returns(ScriptType.table(
                        ScriptType.optional("current_model", ScriptType.STRING, "The model you wear now; none when "
                                + "you wear your own look."),
                        ScriptType.optional("current_name", ScriptType.STRING, null),
                        ScriptType.optional("total", ScriptType.INTEGER, "Without search: how many models in all."),
                        ScriptType.optional("packs", ScriptType.listOf(ScriptType.table(
                                ScriptType.field("pack", ScriptType.STRING, null),
                                ScriptType.field("count", ScriptType.INTEGER, null),
                                ScriptType.field("examples", ScriptType.listOf(ScriptType.STRING), "A few names."))),
                                "Without search: every pack."),
                        ScriptType.optional("models", ScriptType.listOf(ScriptType.table(
                                ScriptType.field("id", ScriptType.STRING, "What " + line(WEAR) + " takes."),
                                ScriptType.field("name", ScriptType.STRING, null),
                                ScriptType.field("pack", ScriptType.STRING, null))),
                                "With search: every model found.")))
                .example(line(MODELS) + "()")
                .example(line(MODELS) + "({search = \"灵梦\"})")
                .note("Read-only. Runs on your owner's client, where the model packs are.")
                .note("Without search every pack, with search every model found; the reply lists one per line and a "
                        + "long list comes a page at a time.")
                .seeAlso(path(WEAR));
        tlm.client(WEAR, "Your own look: put on a maid model.",
                TlmCommands::wear, MODEL)
                .returns(ScriptType.table(ScriptType.field("current_model", ScriptType.STRING, null),
                        ScriptType.field("current_name", ScriptType.STRING, null)))
                .example(line(WEAR) + "(\"touhou_little_maid:hakurei_reimu\")")
                .note("It covers your whole body: a YSM model or your own skin stops showing until you take it off.")
                .note("It does not ask your owner; tell them what you changed into.")
                .seeAlso(path(MODELS), path(REMOVE));
        tlm.client(REMOVE, "Your own look: take the maid model off; your other look shows again.",
                TlmCommands::remove)
                .returns(ScriptType.NOTHING)
                .example(line(REMOVE) + "()")
                .seeAlso(path(WEAR));
        MaidCommands.actions(tlm);
    }

    /**
     * 不带关键词只给包级摘要,带关键词才展开具体条目——这台机器上有两百多个模型,全量倒出去一次吃掉两万多 token,
     * 而且给的是一堆哈希 id,模型拿到了也讲不清哪个是哪个(理由见 {@link MaidCatalog})。两样都一行一条,按输出预算分页。
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
            List<MaidCatalog.Pack> packs = MaidCatalog.summary();
            int total = packs.stream().mapToInt(MaidCatalog.Pack::count).sum();
            List<String> rows = new ArrayList<>();
            List<Map<String, Object>> listed = new ArrayList<>();
            for (MaidCatalog.Pack p : packs) {
                rows.add("  " + p.pack() + " — " + p.count() + " 个,比如 " + String.join("、", p.examples()));
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("pack", p.pack());
                one.put("count", p.count());
                one.put("examples", p.examples());
                listed.add(one);
            }
            data.put("total", total);
            data.put("packs", listed);
            src.reply(new Listing(worn + ";一共 " + total + " 个模型,分在 " + packs.size()
                    + " 个包里。想找具体哪个,用 {search = ...} 搜角色名或包名:", rows, "").result(args, data)
                    .toJson());
            return;
        }

        List<String> rows = new ArrayList<>();
        List<Map<String, Object>> found = new ArrayList<>();
        for (MaidCatalog.Entry e : MaidCatalog.search(q)) {
            rows.add("  " + e.id() + " — " + e.name() + "(" + e.pack() + ")");
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", e.id());
            one.put("name", e.name());
            one.put("pack", e.pack());
            found.add(one);
        }
        data.put("models", found);
        src.reply(new Listing(worn + ";搜「" + q + "」找到 " + rows.size() + " 个:", rows, "")
                .result(args, data).toJson());
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
            src.reply(TaskResult.fail(ErrorKind.NOT_FOUND, "没有叫 " + model + " 的模型;搜一下正确的 id",
                    line(MODELS) + "({search = \"" + args.get(MODEL).getPath() + "\"})").toJson());
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
