package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.blueprint.BlueprintStore;
import com.dwinovo.numen.core.build.Design;
import com.dwinovo.numen.core.build.Designs;
import com.dwinovo.numen.core.build.Layout;
import com.dwinovo.numen.core.build.Primitive;
import com.dwinovo.numen.core.build.Slice;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设计库这一半:新建、往末尾加一步、换一步、插一步、删一步、删掉整份、列出库里有什么、展示一份。都不动世界一格,当场回。
 *
 * <p>改设计文件不是动世界的动作,不经权限层;只有设计所属主人的同伴能改、能删,别人的设计可以看、可以照着盖。
 * 每次改完整份重读重画一遍({@link Design#withSteps}),画不出来就不存,回执说清是哪一步。
 */
public final class DesignOps {

    private DesignOps() {}

    /** 新建一份空设计。名字不能和已有的设计或蓝图文件撞:{@code build at} 按名字找,只能指一样东西。 */
    public static String create(NumenPlayer her, String name) {
        MinecraftServer server = her.getServer();
        Design.checkedName(name);
        if (Designs.exists(server, name)) {
            return TaskResult.fail("there is already a design named " + name + "; build show " + name
                    + " shows it").toJson();
        }
        if (BlueprintStore.list(server).contains(name)) {
            return TaskResult.fail("a blueprint file is already named " + name + "; pick another name").toJson();
        }
        Design design = Design.fresh(name, her.getOwnerUuid(), her.ownerName(), her.getGameProfile().getName(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        Designs.save(server, design);
        return TaskResult.ok("made an empty design " + name + "; add steps with a primitive and --into " + name
                + ", for example build layer 0 0 0 ##### --block stone_bricks --into " + name).toJson();
    }

    /** 往设计末尾加一步:原语与读好的参数写回一行({@link Design.Step#line}),和当场执行是同一行字。 */
    public static String append(NumenPlayer her, Primitive primitive, CommandArgs args) {
        String name = args.get(Primitive.Params.INTO);
        Design design = Designs.load(her.getServer(), name);
        String refused = refusal(her, design);
        if (refused != null) {
            return refused;
        }
        List<String> steps = new ArrayList<>(design.steps());
        steps.add(new Design.Step(primitive, args).line());
        return saved(her, design.withSteps(steps), "added step " + steps.size());
    }

    /** 把第 {@code n} 步换成 {@code primitive}(写法同 {@code build} 之后的那一截,如 {@code layer 0 0 0 ###})。 */
    public static String replace(NumenPlayer her, String name, int n, String primitive) {
        Design design = Designs.load(her.getServer(), name);
        String refused = refusal(her, design);
        if (refused != null) {
            return refused;
        }
        checkStep(design, n, design.steps().size());
        List<String> steps = new ArrayList<>(design.steps());
        steps.set(n - 1, stepLine(primitive));
        return saved(her, design.withSteps(steps), "replaced step " + n);
    }

    /** 在第 {@code n} 步前插一步;{@code n} 比步数多一就是接在末尾。 */
    public static String insert(NumenPlayer her, String name, int n, String primitive) {
        Design design = Designs.load(her.getServer(), name);
        String refused = refusal(her, design);
        if (refused != null) {
            return refused;
        }
        checkStep(design, n, design.steps().size() + 1);
        List<String> steps = new ArrayList<>(design.steps());
        steps.add(n - 1, stepLine(primitive));
        return saved(her, design.withSteps(steps), "inserted step " + n);
    }

    /** 删掉第 {@code n} 步,后面的步往前挪。 */
    public static String drop(NumenPlayer her, String name, int n) {
        Design design = Designs.load(her.getServer(), name);
        String refused = refusal(her, design);
        if (refused != null) {
            return refused;
        }
        checkStep(design, n, design.steps().size());
        List<String> steps = new ArrayList<>(design.steps());
        steps.remove(n - 1);
        return saved(her, design.withSteps(steps), "dropped step " + n);
    }

    /** 删掉整份设计。已建成的房子不受影响——它们的格子记在自己的记录里,只是不能再照这份设计改。 */
    public static String delete(NumenPlayer her, String name) {
        Design design = Designs.load(her.getServer(), name);
        String refused = refusal(her, design);
        if (refused != null) {
            return refused;
        }
        Designs.delete(her.getServer(), name);
        return TaskResult.ok("deleted design " + name + "; what was built from it stays standing, and build built "
                + "still lists it").toJson();
    }

    /** 设计库:她们写的设计,和 {@code schematics/} 里的蓝图文件,都能 {@code build at}。 */
    public static String library(MinecraftServer server, CommandArgs args) {
        List<String> rows = new ArrayList<>();
        for (String name : Designs.names(server)) {
            String row;
            try {
                Design d = Designs.load(server, name);
                Vec3i size = Layout.of(d.drawn().targets(), 0).size();
                row = name + " — design, " + d.steps().size() + " step(s), " + size.getX() + "x" + size.getY() + "x"
                        + size.getZ() + (d.ownerName().isEmpty() ? "" : ", " + d.ownerName() + "'s");
            } catch (IllegalArgumentException e) {
                row = name + " — design that does not read: " + e.getMessage().replace('\n', ' ');
            }
            rows.add("  " + row);
        }
        for (String name : BlueprintStore.list(server)) {
            String size;
            try {
                Vec3i s = BlueprintStore.peekSize(server, name);
                size = s.getX() + "x" + s.getY() + "x" + s.getZ();
            } catch (RuntimeException e) {
                size = "size unreadable: " + e.getMessage();
            }
            rows.add("  " + name + " — blueprint file, " + size);
        }
        String head = rows.isEmpty()
                ? "No designs or blueprint files yet. build new starts a design; blueprint files (.litematic, .schem, "
                        + ".nbt, .snbt) go into the server's schematics folder."
                : "Designs and blueprint files, each buildable with build at:";
        return new Listing(head, rows, "", "build designs").result(args).toJson();
    }

    /**
     * 展示一份:设计按步分页,列出每一步;蓝图文件报尺寸、格数、用料与按层分布。给了 {@code layer},改为把那一层的最终样子
     * 俯视画成字符图({@link Slice}),设计与蓝图文件都行。
     *
     * @param layer 画哪一层;没给是 null,列步骤或报价
     * @param again 这一行本身(不带 {@code --page}):翻页时写它
     */
    public static String show(NumenPlayer her, String name, Integer layer, CommandArgs args, String again) {
        MinecraftServer server = her.getServer();
        boolean design = Designs.kindOf(server, name) == Designs.Kind.DESIGN;
        if (layer != null) {
            return design
                    ? Slice.of("design " + name, Designs.load(server, name).drawn().targets(), layer, again)
                            .result(args).toJson()
                    : Slice.of("blueprint file " + name + " (0 0 0 is its lowest north-west corner)",
                            BlueprintStore.load(her.serverLevel(), name, BlockPos.ZERO, 0).targets(), layer, again)
                            .result(args).toJson();
        }
        return design ? showDesign(her, Designs.load(server, name), args, again) : showFile(her, name, args, again);
    }

    /**
     * 一份设计:抬头是整份的尺寸、范围、格数与料;每一步一条(那一行命令,加它占的范围、格数与料),按输出预算分页;结尾说
     * 这份设计是谁的、她缺不缺料。{@code data} 是整份的小结(尺寸、格数、全量料单、还缺多少),不随页变。
     */
    private static String showDesign(NumenPlayer her, Design design, CommandArgs args, String again) {
        List<BuildTaskRecord.Target> all = design.drawn().targets();
        Map<Item, Integer> cost = BuildBill.cost(all, Set.of());
        Map<String, Object> data = new LinkedHashMap<>();
        String head;
        if (design.steps().isEmpty()) {
            head = design.name() + ": no steps yet; add one with a primitive and --into " + design.name();
        } else {
            Vec3i size = Layout.of(all, 0).size();
            head = design.name() + ": " + design.steps().size() + " step(s), " + size.getX() + "x" + size.getY() + "x"
                    + size.getZ() + " " + span(all) + ", " + all.size() + " cells — " + BuildBill.topLine(cost);
            data.put("size", size.getX() + "x" + size.getY() + "x" + size.getZ());
        }
        List<String> steps = new ArrayList<>();
        List<List<BuildTaskRecord.Target>> drawn = design.drawn().steps();
        for (int i = 0; i < design.steps().size(); i++) {
            List<BuildTaskRecord.Target> cells = drawn.get(i);
            String kind = Design.step(design.steps().get(i)).primitive().action;
            steps.add((i + 1) + ". " + design.steps().get(i) + "\n   " + kind + " " + span(cells) + ", "
                    + cells.size() + " cells: " + BuildBill.topLine(BuildBill.cost(cells, Set.of())));
        }
        data.put("cells", all.size());
        data.put("materials", BuildBill.summarize(cost));
        String foot = ownership(her, design) + (design.author().isEmpty() ? "" : " Written by " + design.author() + ".")
                + "\n" + shortOf(her, cost, data);
        return new Listing(head, steps, foot, again).result(args, data).toJson();
    }

    /**
     * 读一张图纸:尺寸、用料、按层分布。按组件全等收料的那些格(旗帜的花纹)与摆设身上带的东西要单独点名:报价说一句
     * "white_banner x3" 而实际要的是三面绣好花纹的旗,玩家按报价备齐了照样一格都放不下去。判据严到哪里,报价就得说到哪里。
     * 它不随图纸变长(料按种类、分布按层),只有一页。
     */
    private static String showFile(NumenPlayer her, String file, CommandArgs args, String again) {
        Layout loaded = BlueprintStore.load(her.serverLevel(), file, BlockPos.ZERO, 0);
        Map<String, Integer> extra = new LinkedHashMap<>();
        Map<String, Integer> exact = new LinkedHashMap<>();
        for (var list : loaded.cellNeeds().values()) {
            for (var need : list) {
                (need.exact() ? exact : extra).merge(need.stack().getHoverName().getString(), 1, Integer::sum);
            }
        }
        for (var spawn : loaded.entities()) {
            for (var stack : spawn.payload(her.serverLevel().registryAccess())) {
                exact.merge(stack.getHoverName().getString(), 1, Integer::sum);
            }
        }
        Map<Item, Integer> cost = BuildBill.cost(loaded.targets(), loaded.cellNeeds().keySet());
        long clears = loaded.targets().stream().filter(t -> !t.costsMaterial()).count();

        Map<String, Object> data = new LinkedHashMap<>();
        Vec3i size = loaded.size();
        data.put("size", size.getX() + "x" + size.getY() + "x" + size.getZ());
        data.put("cells", loaded.targets().size());
        data.put("cells_costing_materials", BuildBill.sum(cost));
        if (clears > 0) {
            data.put("cells_that_only_clear", clears);
        }
        if (loaded.dropped() > 0) {
            // 掉格要在报价这一步就说清:她正是在这里决定要不要建、缺什么料
            data.put("cells_dropped", loaded.dropped()
                    + " (liquids, or blocks with no item to pay with — she will not build these)");
        }
        data.put("materials", BuildBill.summarize(cost));
        // 这两项和 materials 一样出 map 而不是拼好的字符串:她要拿它们做算术(还差几件、够不够)
        if (!extra.isEmpty()) {
            data.put("materials_for_multi_item_cells", extra);
        }
        if (!exact.isEmpty()) {
            data.put("materials_needing_an_exact_match", exact);
            data.put("exact_match_means", "same patterns / enchantments / contents, not just the same kind of item");
        }
        data.put("layer_profile", BuildBill.layerProfile(loaded.targets()));
        String head = file + ": blueprint file, " + size.getX() + "x" + size.getY() + "x" + size.getZ() + ", "
                + loaded.targets().size() + " cells, needs " + BuildBill.sum(cost) + " items across " + cost.size()
                + " kinds — " + BuildBill.topLine(cost);
        return new Listing(head, List.of(), shortOf(her, cost, data), again).result(args, data).toJson();
    }

    /** 免耗材的画像说一句不花料;生存画像报她手上还缺多少(整份都盖要的,不减已经立着的),也记进 {@code data}。 */
    private static String shortOf(NumenPlayer her, Map<Item, Integer> cost, Map<String, Object> data) {
        if (WorkProfile.of(her).freeMaterials()) {
            return "She builds free of charge in this mode.";
        }
        Map<Item, Integer> shortOf = BuildBill.shortOf(her, cost);
        data.put("short_of", BuildBill.summarize(shortOf));
        return shortOf.isEmpty() ? "She carries enough for all of it."
                : "For all of it she is still short " + BuildBill.topLine(shortOf) + ".";
    }

    /** 这些格占的范围:{@code x 0..8, y 0..4, z 0..6}。 */
    private static String span(List<BuildTaskRecord.Target> cells) {
        if (cells.isEmpty()) {
            return "(nothing)";
        }
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (BuildTaskRecord.Target t : cells) {
            int[] at = {t.pos().getX(), t.pos().getY(), t.pos().getZ()};
            for (int i = 0; i < 3; i++) {
                lo[i] = Math.min(lo[i], at[i]);
                hi[i] = Math.max(hi[i], at[i]);
            }
        }
        return "at x " + range(lo[0], hi[0]) + ", y " + range(lo[1], hi[1]) + ", z " + range(lo[2], hi[2]);
    }

    private static String range(int lo, int hi) {
        return lo == hi ? Integer.toString(lo) : lo + ".." + hi;
    }

    /** 写来的一步({@code build} 之后的那一截)读成原语,写回规范的一行;读不通就说清。 */
    private static String stepLine(String primitive) {
        return Design.step(Design.GROUP + " " + primitive).line();
    }

    private static void checkStep(Design design, int n, int max) {
        if (n < 1 || n > max) {
            throw new IllegalArgumentException(design.name() + " has " + design.steps().size() + " step(s); step "
                    + n + " is not one of them");
        }
    }

    /** 这份设计是谁的、她能不能改,一句话。 */
    private static String ownership(NumenPlayer her, Design design) {
        if (design.owner() == null) {
            return "It names no owner, so it can be built but not changed from here.";
        }
        if (design.owner().equals(her.getOwnerUuid())) {
            return "It is your owner's design; you can change it.";
        }
        return "It belongs to " + (design.ownerName().isEmpty() ? "another player" : design.ownerName())
                + "'s companions; you can build it, not change it.";
    }

    /** 这份设计她改不了的理由;改得了是 null。 */
    private static String refusal(NumenPlayer her, Design design) {
        if (design.owner() == null) {
            return TaskResult.fail("design " + design.name() + " names no owner, so it can be shown and built but not "
                    + "changed from here").toJson();
        }
        if (!design.owner().equals(her.getOwnerUuid())) {
            return TaskResult.fail("design " + design.name() + " belongs to "
                    + (design.ownerName().isEmpty() ? "another player" : design.ownerName())
                    + "'s companions: you can show it and build it, not change or delete it").toJson();
        }
        return null;
    }

    /** 存下改过的设计,回执说这一步做了什么、整份现在多大。 */
    private static String saved(NumenPlayer her, Design design, String what) {
        Designs.save(her.getServer(), design);
        List<BuildTaskRecord.Target> all = design.drawn().targets();
        return TaskResult.ok(design.name() + ": " + what + "; it now has " + design.steps().size() + " step(s), "
                + all.size() + " cells. build show " + design.name() + " lists them.").toJson();
    }
}
