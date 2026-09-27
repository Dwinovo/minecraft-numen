package com.dwinovo.numen.pathing.api;

import com.dwinovo.numen.pathing.drive.Driver;
import com.dwinovo.numen.pathing.drive.Halt;
import com.dwinovo.numen.pathing.search.Pending;
import com.dwinovo.numen.pathing.search.Searches;

/**
 * 一次在走的导航。宿主每个服务端刻调一次 {@link #tick}(在世界所在的线程上),直到它不再是 {@code RUNNING};
 * 半路不要了就 {@link #stop}。到了、收场、被叫停,都交出实际账({@link Report})。
 *
 * <p>段状态机说"搜索没交出路"时,为什么没路在工作线程上诊断(见 {@link Diagnosis}),这几刻身体站着不动,
 * 状态仍是 {@code RUNNING}。
 */
public final class Navigation {

    private final Driver driver;
    private Pending<Outcome> diagnosis;
    private NavStatus status = NavStatus.RUNNING;

    Navigation(Driver driver) {
        this.driver = driver;
    }

    public NavStatus tick() {
        if (!status.running()) {
            return status;
        }
        if (diagnosis != null) {
            Outcome outcome = diagnosis.poll();
            if (outcome != null) {
                diagnosis = null;
                status = NavStatus.failed(outcome);
            }
            return status;
        }
        switch (driver.tick()) {
            case RUNNING -> {
            }
            case ARRIVED -> status = NavStatus.ARRIVED;
            case HALTED -> conclude(driver.halt());
        }
        return status;
    }

    private void conclude(Halt halt) {
        switch (halt) {
            case Halt.Searched searched -> diagnosis = Searches.submit(
                    cancelled -> Diagnosis.of(searched.stop(), searched.search(), cancelled));
            case Halt.Blocked blocked -> status = NavStatus.failed(new Outcome.Blocked(blocked.blockage()));
            case Halt.Denied denied -> status = NavStatus.failed(new Outcome.Denied(denied.cell(), denied.reason()));
            case Halt.NoSight sight -> status = NavStatus.failed(new Outcome.NoLineOfSight(sight.target()));
            case Halt.Stranded stranded -> status = NavStatus.failed(
                    new Outcome.Stranded(stranded.cell(), stranded.block()));
        }
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
        driver.stop();
        if (status.running()) {
            status = NavStatus.STOPPED;
        }
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

    /** 身体此刻是计划内的坠落:宿主的摔落反射只接管计划外的。 */
    public boolean plannedFall() {
        return driver.plannedFall();
    }

    /** 暂停:松开所有键,路线留着。 */
    public void pause() {
        driver.pause();
    }

    /** 接着走:照留着的路线走下去,不重新搜。 */
    public void resume() {
        driver.resume();
    }
}
