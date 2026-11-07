package com.dwinovo.numen.pathing.api;

/**
 * 一次导航此刻的状态:还在走,到了,收场了(连同结局),或被叫停了。
 *
 * @param outcome 结局;还在走、被叫停为 null
 */
public record NavStatus(State state, Outcome outcome) {

    public enum State {
        RUNNING, ARRIVED, FAILED, STOPPED
    }

    static final NavStatus RUNNING = new NavStatus(State.RUNNING, null);
    static final NavStatus ARRIVED = new NavStatus(State.ARRIVED, Outcome.ARRIVED);

    static final NavStatus STOPPED = new NavStatus(State.STOPPED, null);

    static NavStatus failed(Outcome outcome) {
        return new NavStatus(State.FAILED, outcome);
    }

    public boolean running() {
        return state == State.RUNNING;
    }
}
