package com.dwinovo.numen.pathing.gametest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.NavStatus;
import com.dwinovo.numen.pathing.api.Navigation;
import com.dwinovo.numen.pathing.api.Navigator;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.api.PlanQuery;
import com.dwinovo.numen.pathing.api.PlanResult;
import com.dwinovo.numen.pathing.api.Planning;
import com.dwinovo.numen.pathing.api.Ports;
import com.dwinovo.numen.pathing.api.Report;
import com.dwinovo.numen.pathing.body.Effector;
import com.dwinovo.numen.pathing.body.PlayerHands;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一条寻路用例:在空场地里用代码搭场景,拉起一具普通假玩家({@link TestBody}),交给 {@link Navigator#drive} 真走,
 * 每刻推一次导航,收场时断言。每条用例都断言三件事:
 * <ul>
 *   <li>在时限内到达,或按预期收场、结局对;</li>
 *   <li>规格不许改地形时,实际账里没有挖、没有放;</li>
 *   <li>实际账与世界的变化一致:场地里变了的格,每一格都在账上,账上每一格的最后样子就是世界里的样子。</li>
 * </ul>
 * 用例自己中途改世界(关门、堆方块)走 {@link #change},不算进导航的账。
 *
 * <p>坐标都相对场地原点:模板的第一层,地板铺在 y = 0,身体站在 y = 1。
 */
final class Trial {

    /** 原版玩家进世界之后的出生无敌刻数({@code ServerPlayer.spawnInvulnerableTime})。 */
    static final int SPAWN_INVULNERABILITY = 60;

    /** 空场地模板。 */
    static final String ARENA = "pathing_arena";
    /** 长条空场地模板(长途)。 */
    static final String LONG = "pathing_long";
    /** 高场地模板(按高度爬高)。 */
    static final String TALL = "pathing_tall";

    final GameTestHelper helper;
    final ServerLevel level;
    final BlockPos origin;
    private final BlockPos extent;
    private final Map<BlockPos, BlockState> baseline = new HashMap<>();
    private final List<Run> runs = new ArrayList<>();
    /** 每刻要做的事(推导航、等规划);在刻里新加的从下一刻起做。 */
    private final List<Runnable> tickers = new ArrayList<>();
    private boolean recorded;

    Materials materials = Materials.NONE;
    TerrainPolicy terrain = TerrainPolicy.ALLOW_ALL;
    Threats threats = Threats.NONE;
    /** 包在身体的原版两只手外面的一层;默认不包。 */
    java.util.function.UnaryOperator<Effector> hands = h -> h;

    Trial(GameTestHelper helper) {
        this.helper = helper;
        this.level = helper.getLevel();
        this.origin = helper.absolutePos(BlockPos.ZERO).above();
        var bounds = helper.getBounds();
        this.extent = new BlockPos((int) bounds.getXsize(), (int) bounds.getYsize(), (int) bounds.getZsize());
        // 只在这里向 GameTest 登记一次:原版在刻里遍历它的登记表,刻里再登记会撞上那次遍历
        helper.onEachTick(() -> List.copyOf(tickers).forEach(Runnable::run));
    }

    // ==================== 场地 ====================

    BlockPos at(int x, int y, int z) {
        return origin.offset(x, y, z);
    }

    Trial set(int x, int y, int z, BlockState state) {
        level.setBlock(at(x, y, z), state, 3);
        return this;
    }

    Trial set(int x, int y, int z, Block block) {
        return set(x, y, z, block.defaultBlockState());
    }

    /** 把 {@code (x0, y0, z0)} 到 {@code (x1, y1, z1)} 的长方体填成 {@code state}。 */
    Trial fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    set(x, y, z, state);
                }
            }
        }
        return this;
    }

    Trial fill(int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
        return fill(x0, y0, z0, x1, y1, z1, block.defaultBlockState());
    }

    /** 整个场地铺一层石头地板(y = 0)。 */
    Trial floor() {
        return fill(0, 0, 0, extent.getX() - 1, 0, extent.getZ() - 1, Blocks.STONE);
    }

    BlockState state(int x, int y, int z) {
        return level.getBlockState(at(x, y, z));
    }

    /** 用例中途改世界:不算进导航的账。 */
    void change(int x, int y, int z, BlockState state) {
        BlockPos pos = at(x, y, z);
        level.setBlock(pos, state, 3);
        baseline.put(pos, level.getBlockState(pos));
    }

    // ==================== 身体与端口 ====================

    /** 在 {@code (x, y, z)} 这一格的中心拉起一具身体,吃饱、满血。 */
    TestBody body(int x, int y, int z) {
        BlockPos pos = at(x, y, z);
        TestBody body = TestBody.spawn(level, "pathing_" + helper.getTick() + "_" + x + "_" + z,
                pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        body.getFoodData().setFoodLevel(20);
        return body;
    }

    /** 背包里放上这些东西(依次放进空格)。 */
    static void give(TestBody body, ItemStack... stacks) {
        for (ItemStack stack : stacks) {
            body.getInventory().add(stack);
        }
    }

    /** 垫路料:按清单先后,身上(主背包或副手)有哪种就用哪种;创造模式清单第一种。 */
    static Materials carried(TestBody body, Block... list) {
        return () -> {
            for (Block block : list) {
                Item item = block.asItem();
                if (body.getInventory().contains(new ItemStack(item)) || body.getOffhandItem().is(item)) {
                    return Optional.of(block);
                }
            }
            return body.gameMode.isCreative() && list.length > 0 ? Optional.of(list[0]) : Optional.empty();
        };
    }

    // ==================== 走 ====================

    /** 开走:从下一刻起每刻推一次导航,收场时断言。 */
    Run go(TestBody body, Goal goal, RouteSpec spec) {
        return go(body, NavRequest.to(goal, spec));
    }

    Run go(TestBody body, NavRequest request) {
        Navigator navigator = navigator(body);
        Run run = new Run(this, body, navigator, navigator.drive(request), request.spec());
        runs.add(run);
        return run;
    }

    /**
     * 只搜不走:从这具身体脚下规划,有了结论交给 {@code then}(在世界线程上,那一刻)。规划期间身体原地不动,{@code then}
     * 可以接着 {@link #go} 开走,或自己断言后 {@code helper.succeed()}。
     */
    void plan(TestBody body, PlanQuery query, Consumer<PlanResult> then) {
        if (!recorded) {
            recorded = true;
            record();
        }
        Planning planning = navigator(body).plan(query);
        boolean[] done = {false};
        tickers.add(() -> {
            if (done[0]) {
                return;
            }
            PlanResult result = planning.poll();
            if (result != null) {
                done[0] = true;
                then.accept(result);
            }
        });
    }

    /** {@code ticks} 刻之后做 {@code action}(可以在里面开走)。 */
    void later(int ticks, Runnable action) {
        int[] left = {ticks};
        tickers.add(() -> {
            if (left[0]-- == 0) {
                action.run();
            }
        });
    }

    /** 这具身体加这条用例的端口组成的门面。 */
    Navigator navigator(TestBody body) {
        return Navigator.of(body, new Ports(hands.apply(new PlayerHands(body)), terrain, materials, threats));
    }

    /** 一次导航通过了;全部都通过,这条用例才通过。 */
    private void passed() {
        for (Run run : runs) {
            if (!run.passed) {
                return;
            }
        }
        helper.succeed();
    }

    /** 记下场地此刻的样子,收场时与实际账对照:在第一次规划或导航开走的那一刻记,搭好的场景(水流开、脚手架定下稳定度)已经自己停当。 */
    private void record() {
        baseline.clear();
        BlockPos.betweenClosed(origin, origin.offset(extent.getX() - 1, extent.getY() - 1, extent.getZ() - 1))
                .forEach(pos -> baseline.put(pos.immutable(), level.getBlockState(pos)));
    }

    /** 场地与第一次规划或开走那一刻一样,一格没变。 */
    void untouched() {
        audit(List.of(), RouteSpec.defaults(), (before, now) -> false, java.util.Set.of());
    }

    /**
     * 同一个场地里别的身体的实际账上有的格。一个用例里几具身体同时走时,一具收场对账的那一刻,另一具可能正好放下或挖掉了一格:
     * 那一格记在它自己的账上,由它收场时对。
     */
    private java.util.Set<BlockPos> othersCells(Run self) {
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        for (Run run : runs) {
            if (run == self) {
                continue;
            }
            run.navigation.report().ledger().entries().forEach(e -> out.add(e.pos()));
        }
        return out;
    }

    /**
     * 实际账与世界的变化一致;规格不许改地形时账里没有挖、没有放。不一致就抛断言异常。{@code passive} 认得出的变化不是
     * 导航动的手,而是原版在身体经过时自己做的(冰霜行者冻住的水面),不算进来;{@code others} 是同一场地里别的身体账上的格,
     * 由它们各自对。
     */
    void audit(List<EditLedger.Entry> entries, RouteSpec spec,
               java.util.function.BiPredicate<BlockState, BlockState> passive, java.util.Set<BlockPos> others) {
        Map<BlockPos, BlockState> last = new HashMap<>();
        for (EditLedger.Entry e : entries) {
            if (!spec.changes() && !(e instanceof EditLedger.Toggled)) {
                throw new GameTestAssertException("不许挖也不许放,却在 " + rel(e.pos()) + " 改了地形:" + e);
            }
            last.put(e.pos(), switch (e) {
                case EditLedger.Dug d -> null;
                case EditLedger.Placed p -> p.after();
                case EditLedger.Toggled t -> t.after();
            });
        }
        List<String> problems = new ArrayList<>();
        baseline.forEach((pos, before) -> {
            BlockState now = level.getBlockState(pos);
            boolean onLedger = last.containsKey(pos);
            if (now != before && !onLedger && !others.contains(pos) && !passive.test(before, now)) {
                problems.add(rel(pos) + " 从 " + before + " 变成 " + now + ",账上没有");
            }
            if (onLedger) {
                BlockState expected = last.get(pos);
                if (expected == null ? !now.getCollisionShape(level, pos).isEmpty() && now.getBlock() != Blocks.WATER
                        && now.getBlock() != Blocks.LAVA : now != expected) {
                    problems.add(rel(pos) + " 账上是 " + expected + ",世界里是 " + now);
                }
            }
        });
        if (!problems.isEmpty()) {
            throw new GameTestAssertException("实际账与世界不符:" + String.join(";", problems));
        }
    }

    String rel(BlockPos pos) {
        BlockPos r = pos.subtract(origin);
        return "(" + r.getX() + "," + r.getY() + "," + r.getZ() + ")";
    }

    // ==================== 一次在走的导航 ====================

    /** 一次在走的导航与它收场时要断言的事。 */
    static final class Run {

        private final Trial trial;
        final TestBody body;
        final Navigator navigator;
        final Navigation navigation;
        private final RouteSpec spec;
        private NavStatus.State expected = NavStatus.State.ARRIVED;
        private Consumer<Outcome> outcomeCheck = o -> {};
        private final List<Consumer<Run>> finals = new ArrayList<>();
        private final List<Consumer<Run>> everyTick = new ArrayList<>();
        private java.util.function.BiPredicate<BlockState, BlockState> passive = (before, now) -> false;
        private boolean finished;
        private boolean passed;
        /** 这么多刻内要收场(从开走算起);用例的 GameTest 时限要比它加上 {@link #delay} 长。 */
        private int limit = 400;
        /**
         * 拉起身体之后等这么多刻才开走:原版玩家进世界后有 60 刻的出生无敌,这期间摔落不掉血,掉没掉血就看不出来。
         */
        private int delay = SPAWN_INVULNERABILITY;
        private int age;
        NavStatus status;
        Report report;
        int ticks;
        /** 这次导航途中身体最少时剩几点血(原版会回血,只看收场时的血量看不出摔没摔)。 */
        float lowestHealth = Float.MAX_VALUE;
        /** 途中身体往上的速度最大到过多少:起跳一下是 0.33 以上,迈步上坎是瞬间抬上去的,不留速度。 */
        double highestRise;
        /** 途中身体的脚最高到过哪一格(相对场地)。 */
        double highestFeet = Double.NEGATIVE_INFINITY;

        Run(Trial trial, TestBody body, Navigator navigator, Navigation navigation, RouteSpec spec) {
            this.trial = trial;
            this.body = body;
            this.navigator = navigator;
            this.navigation = navigation;
            this.spec = spec;
            trial.tickers.add(this::tick);
        }

        /** 拉起身体之后等 {@code ticks} 刻才开走。 */
        Run after(int ticks) {
            delay = ticks;
            return this;
        }

        /** 要在 {@code ticks} 刻内收场。 */
        Run within(int ticks) {
            limit = ticks;
            return this;
        }

        /** 预期被叫停。 */
        Run stops() {
            expected = NavStatus.State.STOPPED;
            return this;
        }

        /** 预期到达。 */
        Run arrives() {
            expected = NavStatus.State.ARRIVED;
            return this;
        }

        /** 预期以 {@code kind} 收场,并对结局做 {@code check}。 */
        <T extends Outcome> Run fails(Class<T> kind, Consumer<T> check) {
            expected = NavStatus.State.FAILED;
            outcomeCheck = o -> {
                if (!kind.isInstance(o)) {
                    throw new GameTestAssertException("结局应是 " + kind.getSimpleName() + ",却是 " + o);
                }
                check.accept(kind.cast(o));
            };
            return this;
        }

        <T extends Outcome> Run fails(Class<T> kind) {
            return fails(kind, o -> {});
        }

        /** 世界里这样的变化是原版在身体经过时自己做的,不是导航动的手:对账时不算。 */
        Run passive(java.util.function.BiPredicate<BlockState, BlockState> change) {
            passive = change;
            return this;
        }

        /** 收场之后再断言。 */
        Run then(Consumer<Run> check) {
            finals.add(check);
            return this;
        }

        /**
         * 每刻至少占 {@code millis} 毫秒墙钟。测试服务器的刻不等墙钟(一刻做完就接着下一刻),而搜索在工作线程上按墙钟跑:
         * 一次要几百毫秒的搜索在这里会占去上千刻,期限与"提前搜下一段来得及"就都失去了意义。搜索吃重的用例按它定个节奏,
         * 让刻与搜索的快慢比接近真实服务器(真实服务器一刻五十毫秒)。
         */
        Run paced(long millis) {
            everyTick.add(r -> {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            return this;
        }

        /** 每刻在推导航之前做(用例中途改世界、推身体)。 */
        Run during(Consumer<Run> action) {
            everyTick.add(action);
            return this;
        }

        private void tick() {
            if (finished || ++age <= delay) {
                return;
            }
            if (ticks == 0 && !trial.recorded) {
                trial.recorded = true;
                trial.record();
            }
            try {
                ticks++;
                for (Consumer<Run> action : everyTick) {
                    action.accept(this);
                }
                lowestHealth = Math.min(lowestHealth, body.getHealth());
                status = navigation.tick();
                lowestHealth = Math.min(lowestHealth, body.getHealth());
                highestRise = Math.max(highestRise, body.getDeltaMovement().y);
                highestFeet = Math.max(highestFeet, body.getY() - trial.origin.getY());
                if (status.running()) {
                    overdue();
                    return;
                }
                report = navigation.report();
                if (status.state() != expected) {
                    throw new GameTestAssertException("应当 " + expected + ",却是 " + status.state() + " " + status.outcome()
                            + ",身体在 " + trial.rel(body.blockPosition()) + " " + navigation);
                }
                if (expected == NavStatus.State.FAILED) {
                    outcomeCheck.accept(status.outcome());
                }
                trial.audit(report.ledger().entries(), spec, passive, trial.othersCells(this));
                for (Consumer<Run> check : finals) {
                    check.accept(this);
                }
                pass();
            } catch (RuntimeException e) {
                finished = true;
                navigation.stop();
                body.leave();
                throw e instanceof GameTestAssertException assertion ? assertion
                        : new GameTestAssertException("用例抛出了 " + e);
            }
        }

        private void overdue() {
            if (ticks >= limit) {
                throw new GameTestAssertException("时限内没收场:身体在 " + trial.rel(body.blockPosition())
                        + " (" + fmt(body.getX()) + "," + fmt(body.getY()) + "," + fmt(body.getZ()) + ") "
                        + navigation);
            }
        }

        private void pass() {
            finished = true;
            body.leave();
            passed = true;
            trial.passed();
        }

        private static String fmt(double v) {
            return String.format("%.2f", v);
        }
    }
}
