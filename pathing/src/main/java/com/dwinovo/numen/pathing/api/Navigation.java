package com.dwinovo.numen.pathing.api;

import com.dwinovo.numen.pathing.drive.Driver;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.drive.Halt;
import com.dwinovo.numen.pathing.drive.PathLog;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Pending;
import com.dwinovo.numen.pathing.search.Searches;

import net.minecraft.server.level.ServerPlayer;

/**
 * 一次在走的导航。宿主每个服务端刻调一次 {@link #tick}(在世界所在的线程上),直到它不再是 {@code RUNNING};
 * 半路不要了就 {@link #stop}。到了、收场、被叫停,都交出实际账({@link Report})。
 *
 * <p>段状态机说"搜索没交出路"时,为什么没路在工作线程上诊断(见 {@link Diagnosis}),这几刻身体站着不动,
 * 状态仍是 {@code RUNNING}。
 */
public final class Navigation {

    private final Driver driver;
    private final ServerPlayer entity;
    private final String who;
    /** 出发时的游戏刻与墙钟:结局里对比用了几刻、几秒,看得出刻速。 */
    private final long startTick;
    private final long startNanos;
    private Pending<Outcome> diagnosis;
    private NavStatus status = NavStatus.RUNNING;

    Navigation(Driver driver, ServerPlayer entity) {
        this.driver = driver;
        this.entity = entity;
        this.who = PathLog.who(entity);
        this.startTick = entity.level().getGameTime();
        this.startNanos = System.nanoTime();
    }

    public NavStatus tick() {
        if (!status.running()) {
            return status;
        }
        if (diagnosis != null) {
            Outcome outcome = diagnosis.poll();
            if (outcome != null) {
                long spent = diagnosis.ranNanos();
                diagnosis = null;
                status = NavStatus.failed(outcome);
                log("结局", " 诊断 " + PathLog.ms(spent));
            }
            return status;
        }
        switch (driver.tick()) {
            case RUNNING -> {
            }
            case ARRIVED -> {
                status = NavStatus.ARRIVED;
                log("结局", "");
            }
            case HALTED -> conclude(driver.halt());
        }
        return status;
    }

    private void conclude(Halt halt) {
        switch (halt) {
            case Halt.Searched searched -> {
                PathLog.info("{} 搜索没交出能走的路(停因 {}),在同一份快照上诊断为什么", who, searched.stop());
                diagnosis = Searches.submit(cancelled -> Diagnosis.of(searched.stop(), searched.search(), cancelled));
                return;
            }
            case Halt.Blocked blocked -> status = NavStatus.failed(new Outcome.Blocked(blocked.blockage()));
            case Halt.Denied denied -> status = NavStatus.failed(new Outcome.Denied(denied.cell(), denied.reason()));
            case Halt.NoSight sight -> status = NavStatus.failed(new Outcome.NoLineOfSight(sight.target()));
            case Halt.Stranded stranded -> status = NavStatus.failed(
                    new Outcome.Stranded(stranded.cell(), stranded.block()));
        }
        log("结局", "");
    }

    /**
     * 记一行收场:结局、走了几步、实际改了几格、身体动作几个、用了几刻与几秒墙钟、此刻的刻速、身体在哪。刻数与墙钟对不上
     * (刻速调高了、服务器跟不上)一眼看得出。
     */
    private void log(String event, String extra) {
        EditLedger ledger = driver.ledger();
        int dug = 0;
        int placed = 0;
        int toggled = 0;
        for (EditLedger.Entry e : ledger.entries()) {
            switch (e) {
                case EditLedger.Dug d -> dug++;
                case EditLedger.Placed p -> placed++;
                case EditLedger.Toggled t -> toggled++;
            }
        }
        PathLog.info("{} {} {} 走了 {} 步 挖 {} 放 {} 开关门 {} 身体动作 {} 用了 {} 刻 墙钟 {} 刻速 {}{} {}", who, event,
                status.running() ? driver : describe(status.outcome()), ledger.steps(), dug, placed, toggled,
                driver.actions().size(), entity.level().getGameTime() - startTick,
                PathLog.seconds(System.nanoTime() - startNanos), PathLog.num(entity.level().tickRateManager().tickrate()),
                extra, PathLog.body(entity));
    }

    /** 日志里的结局:几种带格子与方块的写齐坐标与方块 id,其余照它自己的样子。 */
    static String describe(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Blocked b -> "Blocked " + PathLog.blockage(b.blockage());
            case Outcome.Denied d -> "Denied " + PathLog.pos(d.cell()) + " " + d.reason();
            case Outcome.Stranded s -> "Stranded " + PathLog.pos(s.cell()) + " " + PathLog.block(s.block());
            case Outcome.NoLineOfSight n -> "NoLineOfSight " + PathLog.pos(n.target());
            default -> outcome.toString();
        };
    }

    public NavStatus status() {
        return status;
    }

    /** 叫停:在飞的搜索作废,松开所有键(包括潜行),交出实际账。 */
    public Report stop() {
        if (diagnosis != null) {
            diagnosis.cancel();
            diagnosis = null;
        }
        if (status.running()) {
            log("被叫停", "");
            status = NavStatus.STOPPED;
        }
        driver.stop();
        return report();
    }

    /** 到此刻为止的实际账。 */
    public Report report() {
        return new Report(driver.ledger(), driver.actions());
    }

    /** 在推进:宿主的脱困反射读它。 */
    public boolean progressing() {
        return driver.progressing();
    }

    /**
     * 身体此刻站着等搜索的结论(没有路可走而搜索还没回来,或在诊断为什么没路),不在走。等的是搜索线程的真实时间,
     * 宿主据此不把这几刻算成干活。
     */
    public boolean waiting() {
        return status.running() && (diagnosis != null || driver.waiting());
    }

    /** 还没走完的那几步,按先后;排障画路线用。 */
    public java.util.List<com.dwinovo.numen.pathing.search.Route.Leg> remaining() {
        return driver.remaining();
    }

    /** 身体此刻是计划内的坠落:宿主的摔落反射只接管计划外的。 */
    public boolean plannedFall() {
        return driver.plannedFall();
    }

    /** 排障用:段状态机此刻的样子;走过的经过见日志。 */
    @Override
    public String toString() {
        return "Navigation[" + status + " " + driver + "]";
    }

    /**
     * 换目标:跟着的东西挪了,宿主把新目标交进来。在走的路终点在新目标里还算数就照走,否则从身体脚下按新目标重搜。
     * 挪多远才值得换由宿主定(跟随的迟滞在宿主那边)。只对还在走的导航起作用;到了或收场之后要去新的地方,另开一次导航。
     */
    public void retarget(Goal goal) {
        if (status.running() && diagnosis == null) {
            driver.retarget(goal);
        }
    }

    /** 暂停:松开所有键,手上正在挖的放下,路线留着。 */
    public void pause() {
        driver.pause();
    }

    /** 接着走:照留着的路线走下去,不重新搜。 */
    public void resume() {
        driver.resume();
    }
}
