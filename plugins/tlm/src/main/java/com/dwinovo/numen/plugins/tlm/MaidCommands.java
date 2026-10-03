package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.EntityRef;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code tlm} 组里管她的女仆的那几个动作:名下有哪些、一只的详情、切工作模式、改设置、打开界面的一页。
 *
 * <p>都在服务端:女仆是世界里的实体。读的两个当场回、不问主人;做的三个是人在女仆界面里按的按钮,每一个都是对这只女仆的
 * {@code use_entity},交权限层裁决,放行了才调车万女仆的包({@link Maids})。和 {@code numen.use.block} 同一条规矩:不走路,
 * 够不着就失败并给出照抄就能走过去的那一次调用。
 *
 * <p>本类不碰车万女仆的类,只经 {@link Maids}:联动的防漂移测试只执行登记命令的这一段,那里没有车万女仆。
 */
final class MaidCommands {

    static final String GROUP = "maid";
    static final String MAIDS = "list";
    static final String MAID = "info";
    static final String TASK = "task";
    static final String CONFIG = "config";
    static final String OPEN = "open";

    private static final Gson GSON = new Gson();

    /** 日程的三种,{@link #SCHEDULE} 收的、读回的都是它们。 */
    private static final ScriptType SCHEDULE_WORD = ScriptType.choice(List.of("day", "night", "all"));

    /** 一只加载着的女仆:{@code tlm.maid.list} 列的、{@code tlm.maid.info} 详述的都是它,由 {@link Maids#row} 写。 */
    static final ScriptType.Class MAID_CLASS = new ScriptType.Class("Maid",
            "A maid of Touhou Little Maid, loaded in the world: an Entity with her work and settings. Hand her on as "
                    + "she is: tlm.maid.info(m), numen.use.entity(m), numen.move.to(m).", Shapes.ENTITY.name(),
            List.of(ScriptType.field("model", ScriptType.STRING, "The model she wears."),
                    ScriptType.field("task", ScriptType.STRING, "Her work mode, touhou_little_maid:farm."),
                    ScriptType.field("schedule", SCHEDULE_WORD, null),
                    ScriptType.field("home", ScriptType.BOOLEAN, "Home mode."),
                    ScriptType.field("favorability_level", ScriptType.INTEGER, null),
                    ScriptType.field("sitting", ScriptType.BOOLEAN, null),
                    ScriptType.optional("dimension", ScriptType.STRING,
                            "When she is in another dimension than you (then there is no distance).")));

    /** 存档里记着的一只:待在没加载的区块里的女仆最后在哪,或一块墓碑在哪,由 {@link Maids#away} 与 {@link Maids#tombstones} 写。 */
    private static final ScriptType RECORD = ScriptType.table(
            ScriptType.field("name", ScriptType.STRING, null),
            ScriptType.field("pos", Shapes.POS.type(), null),
            ScriptType.field("dimension", ScriptType.STRING, null));

    /** 一个工作模式,由 {@link Maids#tasks} 写。 */
    private static final ScriptType WORK_MODE = ScriptType.table(
            ScriptType.field("task", ScriptType.STRING, null),
            ScriptType.optional("current", ScriptType.BOOLEAN, "true for the one she works as now."),
            ScriptType.field("can_switch", ScriptType.BOOLEAN, "Whether TLM lets her switch to it now."),
            ScriptType.optional("to_enable", new ScriptType.Simple("table<string, boolean>"),
                    "What it waits for, true = met."),
            ScriptType.optional("works_with", new ScriptType.Simple("table<string, boolean>"),
                    "What the work uses (has_bow, has_arrow for ranged_attack), true = she has it."));

    private static final Param<EntityRef> WHICH = Param.required("maid", ArgType.entity(), "The maid.")
            .values("her entity id, as tlm.maid.list or numen.scan.entities lists it");
    private static final Param<ResourceLocation> WORK = Param.required("task", ArgType.id(), "The work mode.")
            .values("a task id as tlm.maid.info lists it, e.g. touhou_little_maid:farm");
    private static final Param<EntityRef> FOR_MAID = Param.optional("maid", ArgType.entity(), "The maid.")
            .values("her entity id, as tlm.maid.list or numen.scan.entities lists it")
            .whenOmitted("your maid within reach (the nearest one)");
    private static final Param<Boolean> HOME = Param.optional("home", ArgType.bool(),
            "Home mode: true keeps her working and resting around her home or schedule points; false has her "
                    + "follow you.")
            .whenOmitted("leave it as it is");
    private static final Param<Boolean> PICKUP = Param.optional("pickup", ArgType.bool(),
            "Whether she picks up items, experience and power points around her.")
            .whenOmitted("leave it as it is");
    private static final Param<Boolean> RIDE = Param.optional("ride", ArgType.bool(),
            "Whether she may ride things; false also gets her off what she rides now (not off a chair or a "
                    + "flying broom).")
            .whenOmitted("leave it as it is");
    /** 日程的三种:车万女仆 {@code MaidSchedule} 的名字,小写。 */
    private static final Param<String> SCHEDULE = Param.optional("schedule", ArgType.oneOf("day", "night", "all"),
            "When she works: day works by day and sleeps at night, night the other way round, all works "
                    + "round the clock.")
            .whenOmitted("leave it as it is");
    /** 能打开的页:{@link Maids.Tab} 的写法。 */
    private static final Param<String> TAB = Param.optional("tab", ArgType.oneOf("backpack", "bauble", "curios"),
            "Which page of her GUI: backpack = armour, hands, her own slots and her backpack; bauble = her "
                    + "bauble slots; curios = her Curios slots (with Curios installed).")
            .whenOmitted("open the backpack page");

    private MaidCommands() {}

    /** 回执与提示里提到一个动作时写它的函数:{@code tlm.maid.info}。 */
    static String line(String action) {
        return NumenTlm.NAMESPACE + "." + GROUP + "." + action;
    }

    /** 相关动作里点名一个动作:{@code maid info}。 */
    private static String path(String action) {
        return GROUP + " " + action;
    }

    static void actions(CommandGroup tlm) {
        tlm.declare(MAID_CLASS);
        tlm.server(MAIDS, "The maids you keep: the ones here, the ones in unloaded chunks, and tombstones.",
                        MaidCommands::maids, Listing.PAGE)
                .returns(ScriptType.table(
                        ScriptType.field("here", ScriptType.listOf(MAID_CLASS.type()), "Loaded now, nearest first."),
                        ScriptType.field("away", ScriptType.listOf(RECORD), "In unloaded chunks: where each was last."),
                        ScriptType.field("tombstones", ScriptType.listOf(RECORD), "Where each tombstone stands.")))
                .example(line(MAIDS) + "()")
                .example("for _, m in ipairs(" + line(MAIDS) + "().here) do print(m.id, m.task, m.distance) end")
                .note("Read-only. A maid here is a Maid: her entity id, name, model, task, schedule, home mode, "
                        + "health, favorability level, whether she sits and how far away she is; a maid in an "
                        + "unloaded chunk gives where she was last; a tombstone gives where it stands.")
                .note("Your power points and how many maids TLM counts as yours are in your body state every "
                        + "turn.")
                .seeAlso(path(MAID), "numen scan entities");
        tlm.server(MAID, "One maid in full, and every work mode with what it needs.",
                        MaidCommands::maid, WHICH, Listing.PAGE)
                .returns(ScriptType.table(
                        ScriptType.field("maid", MAID_CLASS.type(), "Her, as tlm.maid.list lists her."),
                        ScriptType.field("pickup", ScriptType.BOOLEAN, null),
                        ScriptType.field("ride", ScriptType.BOOLEAN, null),
                        ScriptType.field("favorability", ScriptType.INTEGER, null),
                        ScriptType.field("favorability_to_next_level", ScriptType.INTEGER, null),
                        ScriptType.field("backpack", ScriptType.STRING, null),
                        ScriptType.optional("home_center", Shapes.POS.type(), "With home mode on."),
                        ScriptType.optional("home_radius", ScriptType.NUMBER, "With home mode on."),
                        ScriptType.optional("schedule_points", ScriptType.table(
                                ScriptType.field("work", Shapes.POS.type(), null),
                                ScriptType.field("idle", Shapes.POS.type(), null),
                                ScriptType.field("sleep", Shapes.POS.type(), null),
                                ScriptType.field("dimension", ScriptType.STRING, null)), "When they are set."),
                        ScriptType.field("tasks", ScriptType.listOf(WORK_MODE), "Every work mode in her task list.")))
                .example(line(MAID) + "(812)")
                .example("for _, t in ipairs(" + line(MAID) + "(812).tasks) do print(t.task, t.can_switch) end")
                .note("Read-only, from any distance, any maid (someone else's too).")
                .note("Every work mode: can_switch says whether TLM lets her switch to it now; to_enable lists "
                        + "what it waits for (true = met); works_with lists what the work uses (e.g. has_bow, "
                        + "has_arrow for ranged_attack), true = she has it.")
                .seeAlso(path(TASK), path(CONFIG));
        tlm.server(TASK, "Switch one of your maids to another work mode, like a click in her task list.",
                        MaidCommands::task, WORK, FOR_MAID)
                .returns(ScriptType.table(ScriptType.field("maid", ScriptType.INTEGER, "Her entity id."),
                        ScriptType.field("task", ScriptType.STRING, "Her work mode, read back.")))
                .example(line(TASK) + "(\"touhou_little_maid:farm\", {maid = 812})")
                .example(line(TASK) + "(\"touhou_little_maid:idle\")")
                .note("It does not travel: stand within about 7 blocks of her, the distance at which her GUI stays "
                        + "open. Farther away it fails with out_of_reach, and its hint is the numen.move.to call to copy.")
                .note("TLM decides: only the owner may switch, and a mode may wait for something first (see "
                        + "can_switch in " + line(MAID) + "). The result reads her task back; unchanged means TLM "
                        + "did not take it, and it says what TLM's rules show.")
                .note("It is using your maid, so your owner's rules may ask them first; the call waits for the answer.")
                .seeAlso(path(MAID), path(CONFIG));
        tlm.server(CONFIG, "Change one of your maids' settings: home mode, picking up, riding, schedule.",
                        MaidCommands::config, WHICH, HOME, PICKUP, RIDE, SCHEDULE)
                .returns(ScriptType.table(ScriptType.field("maid", ScriptType.INTEGER, "Her entity id."),
                        ScriptType.field("home", ScriptType.BOOLEAN, null),
                        ScriptType.field("pickup", ScriptType.BOOLEAN, null),
                        ScriptType.field("ride", ScriptType.BOOLEAN, null),
                        ScriptType.field("schedule", SCHEDULE_WORD, null)))
                .example(line(CONFIG) + "(812, {schedule = \"night\"})")
                .example(line(CONFIG) + "(812, {home = true, pickup = false})")
                .note("Give only what you change; the rest stays. The same reach, owner rule and asking as "
                        + line(TASK) + ".")
                .note("TLM keeps home mode off when her schedule points are in another dimension or more than 32 "
                        + "blocks from her; turning it on with no points set makes where she stands her home.")
                .note("The result reads every setting back and says which of yours did not take.")
                .seeAlso(path(MAID), path(TASK));
        tlm.server(OPEN, "Open a page of one of your maids' GUI, then work it with numen.gui.view.",
                        MaidCommands::open, WHICH, TAB)
                .returns(ScriptType.table(ScriptType.field("maid", ScriptType.INTEGER, "Her entity id."),
                        ScriptType.field("menu", ScriptType.STRING, "The menu now open.")))
                .example(line(OPEN) + "(812)")
                .example(line(OPEN) + "(812, {tab = \"bauble\"})")
                .note("Then `numen.gui.view()` lists its slots, `numen.gui.move` and `numen.gui.quick` move items (armour, hand, "
                        + "backpack or bauble slots), `numen.gui.close()` closes it. It stays open while you stay within "
                        + "reach.")
                .note("The same reach, owner rule and asking as " + line(TASK) + ". A sleeping maid does not open.")
                .seeAlso("numen gui view", "numen gui move", "numen gui close");
    }

    // ---- 读 ----

    private static void maids(ServerSource src, CommandArgs args) {
        NumenPlayer her = src.companion();
        List<JsonObject> here = new ArrayList<>();
        Maids.loaded(her).forEach(maid -> here.add(Maids.row(maid, her)));
        List<JsonObject> away = Maids.away(her);
        List<JsonObject> tombstones = Maids.tombstones(her);
        // 话里三份并成一张清单,一行一只,kind 说它是哪一份的;数据里三份各是一个列表,不用 kind
        List<String> rows = new ArrayList<>();
        here.forEach(row -> rows.add(kinded("maid", row)));
        away.forEach(row -> rows.add(kinded("maid_away", row)));
        tombstones.forEach(row -> rows.add(kinded("tombstone", row)));

        String head = here.isEmpty() && away.isEmpty() && tombstones.isEmpty()
                ? "You keep no maids. A wild maid is tamed with a cake: `numen.use.entity(812, {item = \"minecraft:cake\"})`, "
                        + "with her entity id from `numen.scan.entities`."
                : here.size() + " maid(s) here, " + away.size() + " in unloaded chunks, " + tombstones.size()
                        + " tombstone(s); one per line:";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("here", array(here));
        data.put("away", array(away));
        data.put("tombstones", array(tombstones));
        src.reply(new Listing(head, rows, "").result(args, data).toJson());
    }

    /** 清单里的一行:{@code kind} 打头,接着是那一只的数据。 */
    private static String kinded(String kind, JsonObject row) {
        JsonObject line = new JsonObject();
        line.addProperty("kind", kind);
        row.entrySet().forEach(e -> line.add(e.getKey(), e.getValue()));
        return GSON.toJson(line);
    }

    private static JsonArray array(List<JsonObject> rows) {
        JsonArray out = new JsonArray();
        rows.forEach(out::add);
        return out;
    }

    private static void maid(ServerSource src, CommandArgs args) {
        Entity maid = named(src, args.get(WHICH));
        if (maid == null) {
            return;
        }
        List<Map<String, Object>> tasks = Maids.tasks(maid);
        List<String> rows = new ArrayList<>();
        for (Map<String, Object> task : tasks) {
            rows.add(GSON.toJson(task));
        }
        JsonObject detail = Maids.detail(maid, src.companion());
        String head = GSON.toJson(detail) + "\nWork modes, one per line:";
        Map<String, Object> data = new LinkedHashMap<>();
        detail.entrySet().forEach(e -> data.put(e.getKey(), e.getValue()));
        data.put("tasks", GSON.toJsonTree(tasks));
        src.reply(new Listing(head, rows, "").result(args, data).toJson());
    }

    // ---- 做 ----

    private static void task(ServerSource src, CommandArgs args) {
        ResourceLocation task = args.get(WORK);
        if (!Maids.taskExists(task)) {
            List<String> same = Maids.tasksNamed(task.getPath());
            EntityRef named = args.get(FOR_MAID);
            src.reply(TaskResult.fail(ErrorKind.NOT_FOUND, "TLM has no work mode " + task
                    + (same.isEmpty() ? "" : " — did you mean " + String.join(" or ", same) + "?")
                    + (named == null ? "; " + line(MAID) + " lists a maid's work modes" : ""),
                    named == null || named.id() == null ? null : line(MAID) + "(" + named.id() + ")").toJson());
            return;
        }
        EntityRef which = args.get(FOR_MAID) != null ? args.get(FOR_MAID) : yoursWithinReach(src);
        if (which == null) {
            return;
        }
        act(src, args.with(FOR_MAID, which), which, TASK, List.of(WORK, FOR_MAID), (her, maid) -> {
            String was = Maids.task(maid);
            Maids.switchTask(her, maid, task);
            String now = Maids.task(maid);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("maid", maid.getId());
            data.put("task", now);
            if (now.equals(task.toString())) {
                return TaskResult.ok(Maids.label(maid) + (was.equals(now) ? " already works as " : " now works as ")
                        + now + ".", data);
            }
            return refused(her, maid, task, Maids.label(maid) + " still works as " + now
                    + "; TLM did not switch her to " + task + ".", data);
        });
    }

    private static void config(ServerSource src, CommandArgs args) {
        Boolean home = args.get(HOME);
        Boolean pickup = args.get(PICKUP);
        Boolean ride = args.get(RIDE);
        String schedule = args.get(SCHEDULE);
        if (home == null && pickup == null && ride == null && schedule == null) {
            EntityRef which = args.get(WHICH);
            src.reply(TaskResult.fail(ErrorKind.BAD_ARGUMENT, "nothing to change: give one or more of home, pickup, "
                    + "ride, schedule; " + line(MAID) + " shows her settings now",
                    which.id() == null ? null : line(MAID) + "(" + which.id() + ")").toJson());
            return;
        }
        act(src, args, args.get(WHICH), CONFIG, List.of(WHICH, HOME, PICKUP, RIDE, SCHEDULE), (her, maid) -> {
            Maids.Settings was = Maids.settings(maid);
            Maids.configure(her, maid, new Maids.Settings(home != null ? home : was.home(),
                    pickup != null ? pickup : was.pickup(), ride != null ? ride : was.ride(),
                    schedule != null ? schedule : was.schedule()));
            Maids.Settings now = Maids.settings(maid);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("maid", maid.getId());
            data.put("home", now.home());
            data.put("pickup", now.pickup());
            data.put("ride", now.ride());
            data.put("schedule", now.schedule());
            List<String> refused = new ArrayList<>();
            if (home != null && now.home() != home) refused.add("home = " + home);
            if (pickup != null && now.pickup() != pickup) refused.add("pickup = " + pickup);
            if (ride != null && now.ride() != ride) refused.add("ride = " + ride);
            if (schedule != null && !now.schedule().equals(schedule)) refused.add("schedule = \"" + schedule + "\"");
            String settings = "home " + now.home() + ", pickup " + now.pickup() + ", ride " + now.ride()
                    + ", schedule " + now.schedule();
            if (refused.isEmpty()) {
                return TaskResult.ok(Maids.label(maid) + " is set: " + settings + ".", data);
            }
            return refused(her, maid, null, Maids.label(maid) + " is set: " + settings + "; TLM did not take "
                    + String.join(", ", refused) + ".", data);
        });
    }

    private static void open(ServerSource src, CommandArgs args) {
        Maids.Tab tab = Maids.Tab.byWord(args.get(TAB) == null ? "backpack" : args.get(TAB));
        act(src, args, args.get(WHICH), OPEN, List.of(WHICH, TAB), (her, maid) -> {
            Maids.open(her, maid, tab);
            String menu = Maids.showing(her, maid);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("maid", maid.getId());
            if (menu != null) {
                data.put("menu", menu);
                return TaskResult.ok("Opened " + Maids.label(maid) + "'s GUI (" + menu + "). `numen.gui.view()` lists its "
                        + "slots; `numen.gui.move` and `numen.gui.quick` move items; `numen.gui.close()` closes it.", data);
            }
            String said = "TLM did not open the " + tab.word() + " page of " + Maids.label(maid) + ".";
            if (Maids.asleep(maid)) {
                return TaskResult.fail(ErrorKind.FAILED, said + " She is asleep; a sleeping maid's GUI does not open.",
                        null, data);
            }
            return refused(her, maid, null, said, data);
        });
    }

    /** 做一件事的那一步:动手的是 {@link Maids} 的调用,回执以调完读回的为准。 */
    @FunctionalInterface
    private interface Deed {
        TaskResult on(NumenPlayer her, Entity maid);
    }

    /**
     * 做的三个动作共用的那一段:认出女仆、够不够得着、交权限层、动手。主人要问时这次调用挂着等答复,答复回来的时候女仆
     * 可能走开了,所以放行之后按同一个编号再认一次、再量一次。
     *
     * @param which  点名的那只
     * @param action 动作名,和 {@code params} 一起写回这次调用,权限层的回执与征询里点名的就是它
     */
    private static void act(ServerSource src, CommandArgs args, EntityRef which, String action, List<Param<?>> params,
                            Deed deed) {
        Entity maid = reached(src, which);
        if (maid == null) {
            return;
        }
        String what = args.call(src.actionPath(), params);
        src.authorize(Action.useEntity(maid), what, allowed -> {
            Entity still = reached(allowed, which);
            if (still != null) {
                allowed.reply(deed.on(allowed.companion(), still).toJson());
            }
        });
    }

    /** 点名的那只女仆,且在她够得着的地方;不是的话回执已经写好,返回 null。 */
    private static Entity reached(ServerSource src, EntityRef ref) {
        Entity maid = named(src, ref);
        if (maid == null) {
            return null;
        }
        NumenPlayer her = src.companion();
        if (!Maids.inReach(her, maid)) {
            BlockPos at = maid.blockPosition();
            src.reply(TaskResult.fail(ErrorKind.OUT_OF_REACH, Maids.label(maid) + " is "
                    + String.format("%.1f", her.distanceTo(maid)) + " blocks away — too far for her GUI, and this "
                    + "does not travel; walk to her first, then call this again", goNear(at),
                    Map.of("pos", Shapes.pos(at))).toJson());
            return null;
        }
        return maid;
    }

    /** 走到一格两格之内的那一次调用,够不着的失败把它当下一步。 */
    private static String goNear(BlockPos at) {
        return "numen.move.to(" + Shapes.literal(at) + ", {arrive = \"near\", range = 2})";
    }

    /** 她自己的、够得着的女仆里最近的那一只(按 UUID 点名,重启后认的还是她);一只都没有时回执已经写好,返回 null。 */
    private static EntityRef yoursWithinReach(ServerSource src) {
        NumenPlayer her = src.companion();
        Entity nearest = null;
        boolean any = false;
        for (Entity maid : Maids.loaded(her)) {
            if (!Maids.ownedBy(maid, her)) {
                continue;
            }
            any = true;
            if (Maids.inReach(her, maid)
                    && (nearest == null || her.distanceToSqr(maid) < her.distanceToSqr(nearest))) {
                nearest = maid;
            }
        }
        if (nearest == null) {
            src.reply(TaskResult.fail(any ? ErrorKind.OUT_OF_REACH : ErrorKind.NOT_FOUND, "none of your maids is "
                    + "within reach — name one with {maid = <id>}, or walk to her first", line(MAIDS) + "()")
                    .toJson());
            return null;
        }
        return EntityRef.id(nearest.getId());
    }

    /** 点名的那只女仆;不在或不是女仆的话回执已经写好,返回 null。 */
    private static Entity named(ServerSource src, EntityRef ref) {
        Entity entity = ref.in(src.companion().serverLevel());
        if (entity == null) {
            src.reply(TaskResult.fail(ErrorKind.NOT_FOUND, "no entity " + ref + " is here — ids do not survive "
                    + "restarts", line(MAIDS) + "()").toJson());
            return null;
        }
        if (!Maids.is(entity)) {
            src.reply(TaskResult.fail(ErrorKind.BAD_ARGUMENT, ref + " is "
                    + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + ", not a maid", line(MAIDS) + "()")
                    .toJson());
            return null;
        }
        return entity;
    }

    /**
     * 车万女仆没照做时的那条失败:话是 {@code said} 接上它自己的规矩此刻怎么说——不是主人(拒绝,野生的给出驯服那一下);
     * 这个工作模式还没开,开它要什么。只读、只说,判断仍在车万女仆的包里。
     *
     * @param task 切工作模式时是要切的那个;别的动作为 null
     */
    private static TaskResult refused(NumenPlayer her, Entity maid, ResourceLocation task, String said,
                                      Map<String, Object> data) {
        if (!Maids.ownedBy(maid, her)) {
            String owner = Maids.owner(maid);
            if (owner == null) {
                return TaskResult.fail(ErrorKind.DENIED, said + " She is wild: tame her first with a cake.",
                        "numen.use.entity(" + maid.getId() + ", {item = \"minecraft:cake\"})", data);
            }
            return TaskResult.fail(ErrorKind.DENIED, said + " She is not yours: TLM lets only her owner (" + owner
                    + ") do this.", null, data);
        }
        if (task != null) {
            Map<String, Boolean> missing = Maids.notEnabled(maid, task);
            if (missing != null) {
                return TaskResult.fail(ErrorKind.FAILED, said + " TLM has not enabled " + task + " for her"
                        + (missing.isEmpty() ? " (another mod holds it back)." : "; it waits for: " + missing + "."),
                        null, data);
            }
        }
        return TaskResult.fail(ErrorKind.FAILED, said, null, data);
    }
}
