package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.dwinovo.numen.api.entity.BodyAction;
import com.dwinovo.numen.api.entity.BodyCompat;
import com.dwinovo.numen.api.entity.Faces;
import com.dwinovo.numen.api.entity.MenuOps;
import com.dwinovo.numen.api.entity.Mouse;
import com.dwinovo.numen.api.entity.Replaceable;
import com.dwinovo.numen.api.entity.Sight;
import com.dwinovo.numen.api.knowledge.RecipeProbe;
import com.dwinovo.numen.pathing.plan.CraftPlan;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.Tools;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 挖一格之前先把"适格工具"备好:没有就做一件。执行层的一小段状态机,每刻推进一步,由 {@link Work} 在
 * {@link Edit.Dig} 之前调,给 {@link Rig#kit} 用(一次导航一份,跨步保留"做不做得出来"的配方索引)。
 *
 * <p><b>判定顺序</b>:这一格 {@link Tools#needsTool} 且不是创造模式 → 背包 36 格(副手的那件换到主手)里有没有
 * {@link Tools#harvests} 的 → 没有就按 {@link Tools#candidates} 由便宜到贵找一条做得出的
 * ({@link CraftPlan#plan});原版工具都是 3×3,需要工作台:附近有就开,身上有就放,都没有就先做一张。一件都不成、
 * 或做到一半做不下去(没有空格放工作台、权限不许、界面被占、背包满、打开的不是合成格)就 {@link Status#GIVE_UP}:
 * 调用方照旧拿现有最快的工具挖,接受慢/无掉落,不阻断这一步。
 *
 * <p>做出来的东西经 {@link BodyAction.Crafted} 记进回执,放下的工作台经 {@link Work} 记进实际账
 * ({@link EditLedger});回合结束时 {@link #abandon()} 把格里的料收回去、关掉自己开的界面,不给下一次导航留一个开着的菜单。
 */
final class Kit {

    /** 这一刻的结果:备好了(或不需要)、还在做、做不出来。 */
    enum Status {
        READY,
        WORKING,
        GIVE_UP
    }

    private enum Phase {
        IDLE,
        CHECK,
        CRAFT_BEFORE,
        PLACE_TABLE,
        OPEN_TABLE,
        CRAFT_MAIN,
        CLOSE,
        DONE,
        GIVE_UP
    }

    /** 一次备工具最多花几刻:够原木→木板→木棍→工作台→工具,再久就不是"顺手备一件"了。 */
    private static final int MAX_PREP_TICKS = 200;
    /** 摆好料之后结果槽有几刻还没算出来就不等了(把重算推到下一 tick 的模组)。 */
    private static final int MAX_RESULT_RETRIES = 5;
    /** 右键工作台最多试几次。 */
    private static final int MAX_OPEN_TRIES = 3;
    /** 找附近工作台的水平半径;竖直上下各一格。 */
    private static final int TABLE_SCAN = 4;

    private final Rig rig;

    private Phase phase = Phase.IDLE;
    private Edit.Dig dig;
    private BlockPos stepTo;
    private MoveKind move = MoveKind.WALK;
    private int ticks;
    private boolean worked;

    /** 工作台本身要做的几步(2×2),做完才放/开它。 */
    private List<CraftPlan.Step> before = List.of();
    private int beforeIndex;
    /** 工具的计划:料与工具本身;需要工作台时在 3×3 里做。 */
    private List<CraftPlan.Step> main = List.of();
    private int mainIndex;
    private int resultRetries = 0;

    /** 要用的工作台那一格(附近找到的,或刚放下的)。 */
    private BlockPos table;
    /** 工作台还没放下:手上有一张(或刚做出一张)。 */
    private boolean placeTable;
    /** 这次是自己打开的工作台:收场时要关掉。 */
    private boolean tableOpened;
    private int openTries;

    private Map<Item, List<CraftPlan.Shape>> index;

    Kit(Rig rig) {
        this.rig = rig;
    }

    /** 换到一件新改动:从判定开始;在做的上一件先收干净。 */
    void begin(Edit.Dig dig, BlockPos stepTo, MoveKind move) {
        abandon();
        this.dig = dig;
        this.stepTo = stepTo;
        this.move = move;
        this.phase = Phase.CHECK;
        this.ticks = 0;
        this.worked = false;
        this.before = List.of();
        this.beforeIndex = 0;
        this.main = List.of();
        this.mainIndex = 0;
        this.resultRetries = 0;
        this.table = null;
        this.placeTable = false;
        this.tableOpened = false;
        this.openTries = 0;
    }

    /** 这一刻做一步;{@link Status#READY} 之后调用方可以换工具、开挖。 */
    Status tick(Edit.Dig dig) {
        if (dig != this.dig) {
            // 不该发生:Work 换件时会 begin;兜底当成新的一件
            begin(dig, stepTo, move);
        }
        worked = false;
        if (++ticks > MAX_PREP_TICKS) {
            giveUp("preparing a tool took too long");
        }
        switch (phase) {
            case CHECK -> check();
            case CRAFT_BEFORE -> craftBefore();
            case PLACE_TABLE -> placeTable();
            case OPEN_TABLE -> openTable();
            case CRAFT_MAIN -> craftMain();
            case CLOSE -> closeAndDone();
            case DONE, GIVE_UP, IDLE -> {
            }
        }
        return phase == Phase.DONE ? Status.READY : phase == Phase.GIVE_UP ? Status.GIVE_UP : Status.WORKING;
    }

    /** 这一刻真做了事(放了、开了、合了):调用方据此报"在推进",别让脱困反射把这一次准备当成卡住。 */
    boolean worked() {
        return worked;
    }

    // ==================== 判定 ====================

    private void check() {
        ServerLevel level = rig.entity.serverLevel();
        BlockState state = level.getBlockState(dig.pos());
        if (!Tools.needsTool(state) || rig.snapshot().creative()) {
            phase = Phase.DONE;
            return;
        }
        if (hasSuitableTool(state)) {
            phase = Phase.DONE;
            return;
        }
        if (Tools.harvests(rig.entity.getOffhandItem(), state)) {
            // 适格的那件在副手:换到主手再挖(F 键交换,记进身体动作),别让 ToolChoice 拿主背包里别的凑合
            rig.act(rig.hotbar.hold(com.dwinovo.numen.api.entity.Hotbar.OFFHAND).orElse(null));
            phase = Phase.DONE;
            return;
        }
        if (rig.entity.containerMenu != rig.entity.inventoryMenu) {
            giveUp("another window is open, so I can't lay out a recipe");
            return;
        }
        Map<Item, Integer> pool = carried();
        Item chosen = null;
        CraftPlan.Plan plan = null;
        for (Item candidate : Tools.candidates(state)) {
            Optional<CraftPlan.Plan> made = CraftPlan.plan(candidate, recipes(), pool);
            if (made.isPresent()) {
                chosen = candidate;
                plan = made.get();
                break;
            }
        }
        if (plan == null) {
            giveUp("nothing I carry can be made into a tool that harvests " + blockName(state));
            return;
        }
        boolean needsTable = plan.steps().stream().anyMatch(step -> step.shape().grid() >= 3);
        if (!needsTable) {
            main = plan.steps();
            mainIndex = 0;
            phase = Phase.CRAFT_MAIN;
            return;
        }
        if (findTableNearby()) {
            main = plan.steps();
            mainIndex = 0;
            phase = Phase.OPEN_TABLE;
            return;
        }
        if (!rig.mayPlace) {
            giveUp("a crafting table would have to be placed and this walk may not place blocks");
            return;
        }
        if (carries(Items.CRAFTING_TABLE)) {
            main = plan.steps();
            mainIndex = 0;
            placeTable = true;
            phase = Phase.PLACE_TABLE;
            return;
        }
        Optional<CraftPlan.Plan> tablePlan = CraftPlan.plan(Items.CRAFTING_TABLE, recipes(), pool);
        if (tablePlan.isEmpty()) {
            giveUp("a tool that harvests " + blockName(state) + " needs a crafting table and I can't make one");
            return;
        }
        Optional<CraftPlan.Plan> toolPlan = CraftPlan.plan(chosen, recipes(), tablePlan.get().left());
        if (toolPlan.isEmpty()) {
            giveUp("making the crafting table would use the materials the tool needs");
            return;
        }
        before = tablePlan.get().steps();
        beforeIndex = 0;
        main = toolPlan.get().steps();
        mainIndex = 0;
        placeTable = true;
        phase = Phase.CRAFT_BEFORE;
    }

    /** 主背包 36 格里有没有挖得动这一格的。 */
    private boolean hasSuitableTool(BlockState state) {
        Inventory inventory = rig.entity.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (Tools.harvests(inventory.getItem(slot), state)) {
                return true;
            }
        }
        return false;
    }

    /** 主背包里此刻的件数。 */
    private Map<Item, Integer> carried() {
        Map<Item, Integer> pool = new HashMap<>();
        Inventory inventory = rig.entity.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                pool.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return pool;
    }

    private boolean carries(Item item) {
        Inventory inventory = rig.entity.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    /** 物品 → 做它的配方。整张表读一次(坏配方只丢它自己,不抛给调用方)。 */
    private Map<Item, List<CraftPlan.Shape>> recipes() {
        if (index != null) {
            return index;
        }
        ServerLevel level = rig.entity.serverLevel();
        Map<Item, List<CraftPlan.Shape>> built = new HashMap<>();
        for (CraftingRecipe recipe : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            try {
                if (recipe.isSpecial() || !RecipeProbe.usableIngredients(recipe)) {
                    continue;
                }
                ItemStack result = RecipeProbe.resultOf(recipe, level.registryAccess());
                Optional<CraftPlan.Shape> shape = CraftPlan.shape(recipe, recipe.getId(), result);
                shape.ifPresent(s -> built.computeIfAbsent(s.result(), key -> new ArrayList<>()).add(s));
            } catch (RuntimeException broken) {
                if (PathLog.debugging()) {
                    PathLog.debug("{} 配方 {} 读不出来,跳过: {}", rig.who, recipe.getId(), broken.toString());
                }
            }
        }
        built.values().forEach(shapes -> shapes.sort(Comparator.comparing(shape -> shape.id().toString())));
        index = built;
        return index;
    }

    // ==================== 做(2×2 与 3×3 同一套) ====================

    private void craftBefore() {
        if (beforeIndex >= before.size()) {
            phase = placeTable ? Phase.PLACE_TABLE : Phase.OPEN_TABLE;
            return;
        }
        if (craftOne(before.get(beforeIndex))) {
            beforeIndex++;
        }
    }

    private void craftMain() {
        if (mainIndex >= main.size()) {
            phase = tableOpened ? Phase.CLOSE : Phase.DONE;
            return;
        }
        if (craftOne(main.get(mainIndex))) {
            mainIndex++;
        }
    }

    /** 做一步;做成返回 true,还在等结果/做不下去返回 false。 */
    private boolean craftOne(CraftPlan.Step step) {
        AbstractContainerMenu menu = rig.entity.containerMenu;
        Grid grid = findGrid(menu);
        if (grid == null) {
            giveUp("there is no crafting grid open");
            return false;
        }
        if (step.shape().grid() >= 3 && (grid.w() < 3 || grid.h() < 3)) {
            giveUp("the open grid is too small for " + stackName(step.shape().result()));
            return false;
        }
        sweep(grid);
        if (!layOut(step, grid)) {
            sweep(grid);
            giveUp("couldn't lay " + stackName(step.shape().result()) + " out in the grid");
            return false;
        }
        menu.slotsChanged(grid.container());
        if (menu.slots.get(grid.result()).getItem().isEmpty()) {
            if (++resultRetries > MAX_RESULT_RETRIES) {
                sweep(grid);
                giveUp("the laid-out grid doesn't form " + stackName(step.shape().result()));
                return false;
            }
            return false;
        }
        resultRetries = 0;
        int had = count(step.shape().result());
        menu.clicked(grid.result(), 0, ClickType.QUICK_MOVE, rig.entity);
        sweep(grid);
        int made = count(step.shape().result()) - had;
        if (made <= 0) {
            giveUp("crafted nothing — my inventory is full");
            return false;
        }
        worked = true;
        rig.act(new BodyAction.Crafted(step.shape().result(), made));
        if (PathLog.debugging()) {
            PathLog.debug("{} 做出 {} 件 {}", rig.who, made, stackName(step.shape().result()));
        }
        return true;
    }

    /** 把一步的料放进格子的对应位置:有序按宽高摆,无序按先后填。 */
    private boolean layOut(CraftPlan.Step step, Grid grid) {
        AbstractContainerMenu menu = rig.entity.containerMenu;
        CraftPlan.Shape shape = step.shape();
        int next = 0;
        for (int cell = 0; cell < shape.cells().size(); cell++) {
            Item item = step.items().get(cell);
            if (item == null) {
                continue;
            }
            int at;
            if (shape.shapeless()) {
                at = next++;
            } else {
                int row = cell / shape.width();
                int column = cell % shape.width();
                if (row >= grid.h() || column >= grid.w()) {
                    return false;
                }
                at = row * grid.w() + column;
            }
            if (at < 0 || at >= grid.cells().length || grid.cells()[at] < 0) {
                return false;
            }
            if (!putOne(menu, item, grid.cells()[at])) {
                return false;
            }
        }
        return true;
    }

    /** 从背包里拿一个 {@code item} 放进 {@code target};xyz 那一格确实放上了一个为真。 */
    private boolean putOne(AbstractContainerMenu menu, Item item, int target) {
        for (Slot slot : menu.slots) {
            if (slot.container != rig.entity.getInventory() || slot.getContainerSlot() >= 36) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !stack.is(item)) {
                continue;
            }
            MenuOps.dripInto(menu, rig.entity, slot.index, target, 1);
            return menu.slots.get(target).getItem().is(item) && !menu.slots.get(target).getItem().isEmpty();
        }
        return false;
    }

    /** 把格子里剩下的东西收回背包。 */
    private void sweep(Grid grid) {
        AbstractContainerMenu menu = rig.entity.containerMenu;
        for (int slot : grid.cells()) {
            if (slot >= 0 && slot < menu.slots.size() && !menu.slots.get(slot).getItem().isEmpty()) {
                menu.clicked(slot, 0, ClickType.QUICK_MOVE, rig.entity);
            }
        }
    }

    /** 背包 36 格里 {@code item} 一共几件。 */
    private int count(Item item) {
        Inventory inventory = rig.entity.getInventory();
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    // ==================== 工作台 ====================

    /** 附近 4 格内看得见、够得着的一张工作台:找到就记下它,返回 true。 */
    private boolean findTableNearby() {
        ServerLevel level = rig.entity.serverLevel();
        BlockPos feet = rig.entity.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -TABLE_SCAN; dx <= TABLE_SCAN; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -TABLE_SCAN; dz <= TABLE_SCAN; dz++) {
                    BlockPos at = feet.offset(dx, dy, dz);
                    if (!level.isLoaded(at) || !level.getBlockState(at).is(Blocks.CRAFTING_TABLE)) {
                        continue;
                    }
                    if (rig.look.use(at) == null) {
                        continue;
                    }
                    double distance = at.distSqr(feet);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = at;
                    }
                }
            }
        }
        if (best == null) {
            return false;
        }
        table = best;
        return true;
    }

    /** 把身上的工作台放到脚边:不是要挖的那一格、不是这一步的落点,优先背离要去的地方。 */
    private void placeTable() {
        if (table == null) {
            BlockPos cell = tableCell();
            if (cell == null) {
                giveUp("no free, supported cell next to me to put a crafting table down on");
                return;
            }
            Edit.Place placing = new Edit.Place(cell, rig.world().getBlockState(cell), Blocks.CRAFTING_TABLE, Permit.ALLOW);
            Beat beat = Work.placeCore(rig, placing, move);
            if (beat instanceof Beat.Blocked blocked) {
                giveUp("couldn't put a crafting table down: " + blocked.blockage().hitch());
                return;
            }
            if (beat instanceof Beat.Denied) {
                giveUp("placing a crafting table was refused");
                return;
            }
            if (beat instanceof Beat.Going going && going.worked()) {
                table = cell;
                worked = true;
            }
            return;
        }
        phase = Phase.OPEN_TABLE;
        openTries = 0;
    }

    /** 脚边能放工作台的一格;没有为 null。 */
    private BlockPos tableCell() {
        ServerLevel level = rig.entity.serverLevel();
        BlockPos feet = rig.entity.blockPosition();
        BlockPos target = stepTo != null ? stepTo : dig.pos();
        double ux = target.getX() + 0.5 - rig.entity.getX();
        double uz = target.getZ() + 0.5 - rig.entity.getZ();
        double length = Math.sqrt(ux * ux + uz * uz);
        final double tx = length > 1e-6 ? ux / length : 0;
        final double tz = length > 1e-6 ? uz / length : 0;
        List<BlockPos> cells = new ArrayList<>();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos cell = feet.relative(side);
            if (cell.equals(dig.pos()) || (stepTo != null && cell.equals(stepTo))) {
                continue;
            }
            BlockState state = level.getBlockState(cell);
            if (!Replaceable.replaceableBy(state, Blocks.CRAFTING_TABLE)) {
                continue;
            }
            if (Faces.inSight(level, rig.entity.getEyePosition(), BodyCompat.blockReach(rig.entity), cell,
                    Blocks.CRAFTING_TABLE) == null) {
                continue;
            }
            cells.add(cell);
        }
        if (cells.isEmpty()) {
            return null;
        }
        // 背离要去的地方那一格先:放的表不挡视线、不挡下一步
        cells.sort(Comparator.comparingDouble(cell -> {
            double dx = cell.getX() - feet.getX();
            double dz = cell.getZ() - feet.getZ();
            return dx * tx + dz * tz;
        }));
        return cells.get(0);
    }

    /** 右键打开工作台;自己已经开着的 3×3 直接用。 */
    private void openTable() {
        AbstractContainerMenu menu = rig.entity.containerMenu;
        if (menu != rig.entity.inventoryMenu) {
            Grid open = findGrid(menu);
            if (open != null && open.w() >= 3 && open.h() >= 3) {
                tableOpened = true;
                phase = Phase.CRAFT_MAIN;
                return;
            }
            giveUp("another window is open, so I can't open the crafting table");
            return;
        }
        if (table == null) {
            giveUp("no crafting table to open");
            return;
        }
        // 右键工作台时手上别拿着方块:拿着方块点它会先往那儿再放一块,而不是打开界面
        rig.act(rig.hotbar.hold(-1).orElse(null));
        Sight.Trace trace = rig.look.use(table);
        if (trace == null) {
            giveUp("I can't see the crafting table from where I stand");
            return;
        }
        rig.look.at(trace.point());
        if (rig.mouse.on(table) == null) {
            if (++openTries > MAX_OPEN_TRIES) {
                giveUp("the crosshair wouldn't land on the crafting table");
            }
            return;
        }
        Mouse.Use use = rig.mouse.use();
        if (use instanceof Mouse.Use.Pressed pressed) {
            worked = true;
            Grid open = findGrid(rig.entity.containerMenu);
            if (pressed.opened() && open != null && open.w() >= 3 && open.h() >= 3) {
                tableOpened = true;
                phase = Phase.CRAFT_MAIN;
                return;
            }
            if (++openTries > MAX_OPEN_TRIES) {
                giveUp("right-clicking the crafting table didn't open a 3x3 grid");
            }
        } else if (use instanceof Mouse.Use.Refused refused) {
            giveUp("using the crafting table was refused: " + refused.reason());
        }
    }

    private void closeAndDone() {
        closeTable();
        phase = Phase.DONE;
    }

    /** 开着的合成格(自己的 2×2 或打开的工作台)里剩下的料收回背包。 */
    private void sweepOpenGrid() {
        Grid grid = findGrid(rig.entity.containerMenu);
        if (grid != null) {
            sweep(grid);
        }
    }

    /** 自己开的工作台:先把格子里的料收回去,再关。 */
    private void closeTable() {
        if (!tableOpened) {
            return;
        }
        Grid grid = findGrid(rig.entity.containerMenu);
        if (grid != null) {
            sweep(grid);
        }
        rig.entity.closeContainer();
        tableOpened = false;
    }

    // ==================== 收场 ====================

    /** 这一趟结束/换了一件:关掉自己开的界面,状态清空。 */
    void abandon() {
        if (phase == Phase.IDLE && dig == null) {
            return;
        }
        sweepOpenGrid();
        closeTable();
        phase = Phase.IDLE;
        dig = null;
        stepTo = null;
        before = List.of();
        main = List.of();
        beforeIndex = 0;
        mainIndex = 0;
        resultRetries = 0;
        table = null;
        placeTable = false;
        openTries = 0;
        ticks = 0;
        worked = false;
    }

    private void giveUp(String why) {
        if (phase == Phase.GIVE_UP) {
            return;
        }
        sweepOpenGrid();
        closeTable();
        phase = Phase.GIVE_UP;
        PathLog.info("{} 备工具放弃:{};照现有工具挖 {}", rig.who, why, stackName(dig == null ? null : dig.state().getBlock().asItem()));
    }

    // ==================== 菜单 ====================

    /** 开着的合成格:格子的宽高、每格在菜单里的槽位、结果槽与那个容器。 */
    private record Grid(int w, int h, int[] cells, int result, Container container) {}

    private static Grid findGrid(AbstractContainerMenu menu) {
        if (menu == null) {
            return null;
        }
        int w = 0;
        int h = 0;
        int result = -1;
        int[] cells = null;
        Container container = null;
        for (Slot slot : menu.slots) {
            if (slot instanceof ResultSlot) {
                result = slot.index;
                continue;
            }
            if (slot.container instanceof CraftingContainer craft) {
                if (cells == null) {
                    w = craft.getWidth();
                    h = craft.getHeight();
                    cells = new int[w * h];
                    Arrays.fill(cells, -1);
                    container = craft;
                }
                int pos = slot.getContainerSlot();
                if (pos >= 0 && pos < cells.length) {
                    cells[pos] = slot.index;
                }
            }
        }
        return cells == null || result < 0 ? null : new Grid(w, h, cells, result, container);
    }

    private static String blockName(BlockState state) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    private static String stackName(Item item) {
        if (item == null) {
            return "?";
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
