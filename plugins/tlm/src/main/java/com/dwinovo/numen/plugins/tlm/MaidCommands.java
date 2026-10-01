package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.EntityRef;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
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
 * {@code use_entity},交权限层裁决,放行了才调车万女仆的包({@link Maids})。和 {@code use block} 同一条规矩:不走路,
 * 够不着就失败并给出照抄就能走过去的那一行。
 *
 * <p>本类不碰车万女仆的类,只经 {@link Maids}:联动的防漂移测试只执行登记命令的这一段,那里没有车万女仆。
 */
final class MaidCommands {

    static final String MAIDS = "maids";
    static final String MAID = "maid";
    static final String TASK = "task";
    static final String CONFIG = "config";
    static final String OPEN = "open";

    private static final Gson GSON = new Gson();

    private static final Param<EntityRef> WHICH = Param.required("maid", ArgType.entity(), "The maid.")
            .values("her entity id, as tlm maids or scan entities lists it");
    private static final Param<ResourceLocation> WORK = Param.required("task", ArgType.id(), "The work mode.")
            .values("a task id as tlm maid lists it, e.g. touhou_little_maid:farm");
    private static final Param<EntityRef> FOR_MAID = Param.optional("maid", ArgType.entity(), "The maid.")
            .values("her entity id, as tlm maids or scan entities lists it")
            .whenOmitted("your maid within reach (the nearest one)");
    private static final Param<Boolean> HOME = Param.optional("home", ArgType.bool(),
            "Home mode: --home keeps her working and resting around her home or schedule points; --no-home has her "
                    + "follow you.")
            .whenOmitted("leave it as it is");
    private static final Param<Boolean> PICKUP = Param.optional("pickup", ArgType.bool(),
            "Whether she picks up items, experience and power points around her.")
            .whenOmitted("leave it as it is");
    private static final Param<Boolean> RIDE = Param.optional("ride", ArgType.bool(),
            "Whether she may ride things; --no-ride also gets her off what she rides now (not off a chair or a "
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

    static String line(String action) {
        return TlmCommands.line(action);
    }

    static void actions(CommandGroup tlm) {
        tlm.server(MAIDS, "The maids you keep: the ones here, the ones in unloaded chunks, and tombstones.",
                        MaidCommands::maids, Listing.PAGE)
                .example(line(MAIDS))
                .note("Read-only. One per line: a maid here gives her entity id, name, model, task, schedule, home "
                        + "mode, health, favorability level, whether she sits and how far away she is; a maid in an "
                        + "unloaded chunk gives where she was last; a tombstone gives where it stands.")
                .note("Your power points and how many maids TLM counts as yours are in your body state every "
                        + "turn.")
                .seeAlso(line(MAID), "scan entities");
        tlm.server(MAID, "One maid in full, and every work mode with what it needs.",
                        MaidCommands::maid, WHICH, Listing.PAGE)
                .example(line(MAID) + " 812")
                .note("Read-only, from any distance, any maid (someone else's too).")
                .note("One line per work mode: can_switch says whether TLM lets her switch to it now; to_enable lists "
                        + "what it waits for (true = met); works_with lists what the work uses (e.g. has_bow, "
                        + "has_arrow for ranged_attack), true = she has it.")
                .seeAlso(line(TASK), line(CONFIG));
        tlm.server(TASK, "Switch one of your maids to another work mode, like a click in her task list.",
                        MaidCommands::task, WORK, FOR_MAID)
                .example(line(TASK) + " touhou_little_maid:farm --maid 812")
                .example(line(TASK) + " touhou_little_maid:idle")
                .note("It does not travel: stand within about 7 blocks of her, the distance at which her GUI stays "
                        + "open. Farther away it fails and names the move goto line.")
                .note("TLM decides: only the owner may switch, and a mode may wait for something first (see "
                        + "can_switch in " + line(MAID) + "). The result reads her task back; unchanged means TLM "
                        + "did not take it, and it says what TLM's rules show.")
                .note("It is using your maid, so your owner's rules may ask them first; the call waits for the answer.")
                .seeAlso(line(MAID), line(CONFIG));
        tlm.server(CONFIG, "Change one of your maids' settings: home mode, picking up, riding, schedule.",
                        MaidCommands::config, WHICH, HOME, PICKUP, RIDE, SCHEDULE)
                .example(line(CONFIG) + " 812 --schedule night")
                .example(line(CONFIG) + " 812 --home --no-pickup")
                .note("Give only what you change; the rest stays. The same reach, owner rule and asking as "
                        + line(TASK) + ".")
                .note("TLM keeps home mode off when her schedule points are in another dimension or more than 32 "
                        + "blocks from her; turning it on with no points set makes where she stands her home.")
                .note("The result reads every setting back and says which of yours did not take.")
                .seeAlso(line(MAID), line(TASK));
        tlm.server(OPEN, "Open a page of one of your maids' GUI, then work it with use gui.",
                        MaidCommands::open, WHICH, TAB)
                .example(line(OPEN) + " 812")
                .example(line(OPEN) + " 812 --tab bauble")
                .note("Then `use gui` lists its slots, `use transfer` and `use shift` move items (armour, hand, "
                        + "backpack or bauble slots), `use close` closes it. It stays open while you stay within "
                        + "reach.")
                .note("The same reach, owner rule and asking as " + line(TASK) + ". A sleeping maid does not open.")
                .seeAlso("use gui", "use transfer", "use close");
    }

    // ---- 读 ----

    private static void maids(ServerSource src, CommandArgs args) {
        NumenPlayer her = src.companion();
        List<String> rows = new ArrayList<>();
        List<Entity> here = Maids.loaded(her);
        for (Entity maid : here) {
            rows.add(GSON.toJson(Maids.row(maid, her)));
        }
        List<Map<String, Object>> away = Maids.away(her);
        away.forEach(row -> rows.add(GSON.toJson(row)));
        List<Map<String, Object>> tombstones = Maids.tombstones(her);
        tombstones.forEach(row -> rows.add(GSON.toJson(row)));

        String head = here.isEmpty() && away.isEmpty() && tombstones.isEmpty()
                ? "You keep no maids. A wild maid is tamed with a cake: `use entity 812 --item minecraft:cake`, "
                        + "with her entity id from `scan entities`."
                : here.size() + " maid(s) here, " + away.size() + " in unloaded chunks, " + tombstones.size()
                        + " tombstone(s); one per line:";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("here", here.size());
        data.put("away", away.size());
        data.put("tombstones", tombstones.size());
        src.reply(new Listing(head, rows, "", line(MAIDS)).result(args, data).toJson());
    }

    private static void maid(ServerSource src, CommandArgs args) {
        Entity maid = named(src, args.get(WHICH));
        if (maid == null) {
            return;
        }
        List<String> rows = new ArrayList<>();
        for (Map<String, Object> task : Maids.tasks(maid)) {
            rows.add(GSON.toJson(task));
        }
        String head = GSON.toJson(Maids.detail(maid, src.companion())) + "\nWork modes, one per line:";
        src.reply(new Listing(head, rows, "", args.write(line(MAID), List.of(WHICH))).result(args).toJson());
    }

    // ---- 做 ----

    private static void task(ServerSource src, CommandArgs args) {
        ResourceLocation task = args.get(WORK);
        if (!Maids.taskExists(task)) {
            List<String> same = Maids.tasksNamed(task.getPath());
            src.reply(TaskResult.fail("TLM has no work mode " + task
                    + (same.isEmpty() ? "." : " — did you mean " + String.join(" or ", same) + "?")
                    + " `" + line(MAID) + " <maid>` lists them.").toJson());
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
            return TaskResult.fail(Maids.label(maid) + " still works as " + now + "; TLM did not switch her to "
                    + task + "." + whyNot(her, maid, task), data);
        });
    }

    private static void config(ServerSource src, CommandArgs args) {
        Boolean home = args.get(HOME);
        Boolean pickup = args.get(PICKUP);
        Boolean ride = args.get(RIDE);
        String schedule = args.get(SCHEDULE);
        if (home == null && pickup == null && ride == null && schedule == null) {
            src.reply(TaskResult.fail("nothing to change: give one or more of --home, --pickup, --ride, --schedule. "
                    + "`" + line(MAID) + " " + args.get(WHICH) + "` shows her settings now.").toJson());
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
            if (home != null && now.home() != home) refused.add(home ? "--home" : "--no-home");
            if (pickup != null && now.pickup() != pickup) refused.add(pickup ? "--pickup" : "--no-pickup");
            if (ride != null && now.ride() != ride) refused.add(ride ? "--ride" : "--no-ride");
            if (schedule != null && !now.schedule().equals(schedule)) refused.add("--schedule " + schedule);
            String settings = "home " + now.home() + ", pickup " + now.pickup() + ", ride " + now.ride()
                    + ", schedule " + now.schedule();
            if (refused.isEmpty()) {
                return TaskResult.ok(Maids.label(maid) + " is set: " + settings + ".", data);
            }
            return TaskResult.fail(Maids.label(maid) + " is set: " + settings + "; TLM did not take "
                    + String.join(", ", refused) + "." + whyNot(her, maid, null), data);
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
                return TaskResult.ok("Opened " + Maids.label(maid) + "'s GUI (" + menu + "). `use gui` lists its "
                        + "slots; `use transfer` and `use shift` move items; `use close` closes it.", data);
            }
            String why = Maids.asleep(maid) ? " She is asleep; a sleeping maid's GUI does not open."
                    : whyNot(her, maid, null);
            return TaskResult.fail("TLM did not open the " + tab.word() + " page of " + Maids.label(maid) + "."
                    + why, data);
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
     * @param action 动作名,和 {@code params} 一起写回这一行,权限层的回执与征询里点名的就是它
     */
    private static void act(ServerSource src, CommandArgs args, EntityRef which, String action, List<Param<?>> params,
                            Deed deed) {
        Entity maid = reached(src, which);
        if (maid == null) {
            return;
        }
        String what = args.write(line(action), params);
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
            src.reply(TaskResult.fail(Maids.label(maid) + " is " + String.format("%.1f", her.distanceTo(maid))
                    + " blocks away — too far for her GUI, and this does not travel. `move goto " + at.getX()
                    + " " + at.getY() + " " + at.getZ() + " --arrive near --near 2` first, then run this "
                    + "again.").toJson());
            return null;
        }
        return maid;
    }

    /** 她自己的、够得着的女仆里最近的那一只(按 UUID 点名,重启后认的还是她);一只都没有时回执已经写好,返回 null。 */
    private static EntityRef yoursWithinReach(ServerSource src) {
        NumenPlayer her = src.companion();
        Entity nearest = null;
        for (Entity maid : Maids.loaded(her)) {
            if (Maids.ownedBy(maid, her) && Maids.inReach(her, maid)
                    && (nearest == null || her.distanceToSqr(maid) < her.distanceToSqr(nearest))) {
                nearest = maid;
            }
        }
        if (nearest == null) {
            src.reply(TaskResult.fail("none of your maids is within reach — `" + line(MAIDS) + "` lists them; name "
                    + "one with --maid, or `move goto <x y z> --arrive near --near 2` to her first.").toJson());
            return null;
        }
        return EntityRef.id(nearest.getId());
    }

    /** 点名的那只女仆;不在或不是女仆的话回执已经写好,返回 null。 */
    private static Entity named(ServerSource src, EntityRef ref) {
        Entity entity = ref.in(src.companion().serverLevel());
        if (entity == null) {
            src.reply(TaskResult.fail("no entity " + ref + " is here — `" + line(MAIDS) + "` or `scan entities` "
                    + "gives the ids, and they do not survive restarts.").toJson());
            return null;
        }
        if (!Maids.is(entity)) {
            src.reply(TaskResult.fail(ref + " is " + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                    + ", not a maid.").toJson());
            return null;
        }
        return entity;
    }

    /**
     * 车万女仆没照做时,它自己的规矩此刻怎么说:不是主人;这个工作模式还没开,开它要什么。只读、只说,判断仍在车万女仆的包里。
     *
     * @param task 切工作模式时是要切的那个;别的动作为 null
     */
    private static String whyNot(NumenPlayer her, Entity maid, ResourceLocation task) {
        if (!Maids.ownedBy(maid, her)) {
            String owner = Maids.owner(maid);
            return owner == null ? " She is wild: tame her first with a cake (`use entity " + maid.getId()
                    + " --item minecraft:cake`)." : " She is not yours: TLM lets only her owner (" + owner
                    + ") do this.";
        }
        if (task != null) {
            Map<String, Boolean> missing = Maids.notEnabled(maid, task);
            if (missing != null) {
                return " TLM has not enabled " + task + " for her"
                        + (missing.isEmpty() ? " (another mod holds it back)." : "; it waits for: " + missing + ".");
            }
        }
        return "";
    }
}
