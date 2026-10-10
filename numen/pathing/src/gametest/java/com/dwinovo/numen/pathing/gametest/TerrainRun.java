package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.TerrainWorld;
import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.NavStatus;
import com.dwinovo.numen.pathing.api.Navigation;
import com.dwinovo.numen.pathing.api.Navigator;
import com.dwinovo.numen.pathing.api.Ports;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.slf4j.Logger;

/**
 * 在固定存档上走一条固定路线({@link TerrainRoutes.Route}),跑完记一行 JSON 到日志({@code [terrain-route] {...}}),
 * 这份记录是寻路优化的基线。成败只看到没到、超没超时;其余字段只记录、不断言。
 *
 * <p>搜索次数、重搜、走不下去、卡住这几项寻路不经 API 交出,只写进它的日志({@code NumenPathing}),这里挂一个日志附加器(接在根记录器上,只看 NumenPathing 的事件)按身体名数。
 */
final class TerrainRun {

    private static final Logger LOG = LogUtils.getLogger();
    /** 路线加载范围的半径(区块):沿路线每隔几个区块压一张票据,各罩住这么宽。 */
    private static final int TICKET_DISTANCE = 6;
    private static final Pattern KINDS = Pattern.compile("(?:到目标|半程) \\d+ 步 \\[([^\\]]*)\\]");
    /** 汇总表要等所有路线都跑完;每条跑完时登记一行。 */
    private static final List<JsonObject> DONE = new ArrayList<>();

    private enum Phase { LOADING, SPAWNED, WALKING, FINISHED }

    private final GameTestHelper helper;
    private final TerrainRoutes.Route route;
    private final ServerLevel level;
    private final Counter counter;
    private Phase phase = Phase.LOADING;
    private TestBody body;
    private Navigation navigation;
    private int waited;
    private int ticks;
    private float lowestHealth = Float.MAX_VALUE;

    private TerrainRun(GameTestHelper helper, TerrainRoutes.Route route) {
        this.helper = helper;
        this.route = route;
        this.level = helper.getLevel().getServer().getLevel(TerrainWorld.DIMENSION);
        this.counter = new Counter("terrain_" + route.name());
    }

    /** 开跑:核对路线与存档,压票据加载路线沿途的区块,之后每刻推一步。 */
    static void start(GameTestHelper helper, TerrainRoutes.Route route) {
        TerrainBlock block = TerrainBlock.load();
        block.requireInside(route);
        TerrainRun run = new TerrainRun(helper, route);
        block.requireFromSave(run.level);
        Worlds.settle(run.level);
        run.loadAlongRoute();
        helper.onEachTick(run::tick);
    }

    private void loadAlongRoute() {
        int steps = Math.max(1, (int) Math.ceil(route.distance() / (4 * 16)));
        for (int i = 0; i <= steps; i++) {
            int x = route.from().getX() + (route.to().getX() - route.from().getX()) * i / steps;
            int z = route.from().getZ() + (route.to().getZ() - route.from().getZ()) * i / steps;
            ChunkPos pos = new ChunkPos(new BlockPos(x, 0, z));
            level.getChunkSource().addRegionTicket(TicketType.FORCED, pos, TICKET_DISTANCE, pos);
        }
    }

    private boolean loaded() {
        int steps = Math.max(1, (int) Math.ceil(route.distance() / (4 * 16)));
        for (int i = 0; i <= steps; i++) {
            int x = route.from().getX() + (route.to().getX() - route.from().getX()) * i / steps;
            int z = route.from().getZ() + (route.to().getZ() - route.from().getZ()) * i / steps;
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                return false;
            }
        }
        return true;
    }

    private void tick() {
        switch (phase) {
            case LOADING -> {
                if (loaded()) {
                    BlockPos from = route.from();
                    if (level.getBlockState(from.below()).isAir()) {
                        helper.fail("路线常量过期:起点 " + from.toShortString() + " 脚下是空气");
                        phase = Phase.FINISHED;
                        return;
                    }
                    body = TestBody.spawn(level, counter.who, from.getX() + 0.5, from.getY(), from.getZ() + 0.5);
                    body.getFoodData().setFoodLevel(20);
                    phase = Phase.SPAWNED;
                }
            }
            case SPAWNED -> {
                // 出生无敌期里摔落不掉血,等过了再走,记的最低血量才有意义
                if (++waited > Trial.SPAWN_INVULNERABILITY) {
                    Navigator navigator = Navigator.of(body, new Ports(Trial.ALLOW_ALL, Trial.NO_MATERIALS, Threats.NONE));
                    navigation = navigator.drive(NavRequest.to(Goals.at(route.to()), RouteSpec.defaults()));
                    phase = Phase.WALKING;
                }
            }
            case WALKING -> walk();
            case FINISHED -> {
            }
        }
    }

    private void walk() {
        ticks++;
        NavStatus status = navigation.tick();
        while (navigation.waiting()) {
            nap();
            status = navigation.tick();
        }
        lowestHealth = Math.min(lowestHealth, body.getHealth());
        boolean overdue = status.running() && ticks >= route.limit();
        if (status.running() && !overdue) {
            return;
        }
        phase = Phase.FINISHED;
        if (overdue) {
            navigation.stop();
        }
        JsonObject record = record(status, overdue);
        LOG.info("[terrain-route] {}", record);
        body.leave();
        counter.close();
        summarize(record);
        if (status.state() == NavStatus.State.ARRIVED) {
            helper.succeed();
        } else {
            helper.fail((overdue ? "超时 " + route.limit() + " 刻没走到:" : "没走到:") + status.state() + " " + status.outcome()
                    + ",身体在 " + body.blockPosition().toShortString());
        }
    }

    private JsonObject record(NavStatus status, boolean overdue) {
        int dug = 0;
        int placed = 0;
        int toggled = 0;
        for (EditLedger.Entry e : navigation.report().ledger().entries()) {
            switch (e) {
                case EditLedger.Dug d -> dug++;
                case EditLedger.Placed p -> placed++;
                case EditLedger.Toggled t -> toggled++;
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("route", route.name());
        o.addProperty("kind", route.kind());
        o.addProperty("from", route.from().toShortString());
        o.addProperty("to", route.to().toShortString());
        o.addProperty("distance", Math.round(route.distance() * 10) / 10.0);
        o.addProperty("rise", route.rise());
        o.addProperty("arrived", status.state() == NavStatus.State.ARRIVED);
        o.addProperty("state", overdue ? "TIMEOUT" : status.state().name());
        o.addProperty("outcome", status.outcome() == null ? "" : status.outcome().toString());
        o.addProperty("ticks", ticks);
        o.addProperty("limit", route.limit());
        o.addProperty("searches", counter.searches.get());
        o.addProperty("budgetStops", counter.budget.get());
        o.addProperty("replans", counter.replans.get());
        o.addProperty("blocked", counter.blocked.get());
        o.addProperty("stuck", counter.stuck.get());
        o.addProperty("steps", navigation.report().ledger().steps());
        o.addProperty("firstPlan", counter.firstPlan.get());
        o.addProperty("dug", dug);
        o.addProperty("placed", placed);
        o.addProperty("toggled", toggled);
        o.addProperty("lowestHealth", lowestHealth);
        return o;
    }

    /** 每条跑完登记一行,全部路线都登记了就打一张汇总表。 */
    private static void summarize(JsonObject record) {
        synchronized (DONE) {
            DONE.add(record);
            if (DONE.size() < TerrainRoutes.ALL.size()) {
                return;
            }
            LOG.info("[terrain-table] {}", String.format("%-14s %-8s %6s %5s %5s %6s %6s %6s %7s %7s %6s %5s %5s", "route", "arrived",
                    "dist", "rise", "limit", "ticks", "search", "budget", "replan", "blocked", "stuck", "steps", "hp"));
            for (JsonObject o : DONE) {
                LOG.info("[terrain-table] {}", String.format("%-14s %-8s %6.1f %5d %5d %6d %6d %6d %7d %7d %6d %5d %5.1f",
                        o.get("route").getAsString(), o.get("state").getAsString(), o.get("distance").getAsDouble(),
                        o.get("rise").getAsInt(), o.get("limit").getAsInt(), o.get("ticks").getAsInt(),
                        o.get("searches").getAsInt(), o.get("budgetStops").getAsInt(), o.get("replans").getAsInt(), o.get("blocked").getAsInt(),
                        o.get("stuck").getAsInt(), o.get("steps").getAsInt(), o.get("lowestHealth").getAsFloat()));
            }
            DONE.clear();
        }
    }

    private static void nap() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 按身体名数寻路日志里的几类事件。 */
    private static final class Counter extends AbstractAppender {

        final String who;
        final AtomicInteger searches = new AtomicInteger();
        /** 搜索因节点预算用完而停(交出半程路线,接着再搜)的次数。 */
        final AtomicInteger budget = new AtomicInteger();
        final AtomicInteger replans = new AtomicInteger();
        final AtomicInteger blocked = new AtomicInteger();
        final AtomicInteger stuck = new AtomicInteger();
        /** 第一次搜索交出的路线按动作种类的步数,如 {@code WALK×56 ASCEND×27}。 */
        final AtomicReference<String> firstPlan = new AtomicReference<>("");
        private final LoggerConfig root =
                ((LoggerContext) LogManager.getContext(false)).getConfiguration().getRootLogger();

        Counter(String who) {
            super("terrain-counter-" + who, null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
            this.who = who;
            start();
            root.addAppender(this, null, null);
        }

        @Override
        public void append(LogEvent event) {
            if (!"NumenPathing".equals(event.getLoggerName())) {
                return;
            }
            String message = event.getMessage().getFormattedMessage();
            if (!message.contains(" " + who + "#")) {
                return;
            }
            if (message.contains(" 搜索 ") && message.contains(" 展开 ")) {
                if (message.contains(" 停因 BUDGET ")) {
                    budget.incrementAndGet();
                }
                if (searches.getAndIncrement() == 0) {
                    Matcher m = KINDS.matcher(message);
                    firstPlan.set(m.find() ? m.group(1) : "");
                }
            } else if (message.contains(" 重搜:")) {
                replans.incrementAndGet();
            } else if (message.contains(" 走不下去 ")) {
                blocked.incrementAndGet();
            } else if (message.contains(" 卡住 ")) {
                stuck.incrementAndGet();
            }
        }

        void close() {
            root.removeAppender(getName());
            stop();
        }
    }
}
