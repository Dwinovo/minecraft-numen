package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.brain.Spoken;
import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * 座位上发出的一件事:世界上发生的、别人对她说的,或一段在主人断线时被切断的程序留下的结局。每条一个递增的序号,留到客户端确认收到
 * ({@code events/ack});带着发生那一刻的代数与时间(真实时间在条目里,游戏刻与游戏里的时刻在这里)。事件不带她的样子:样子一轮开头
 * {@code state/get} 取一次,程序结局随带。
 *
 * @param companion  哪只同伴的座位
 * @param seq        序号,同一只同伴的座位上从 1 起递增;还没进发件箱的是 0
 * @param generation 事件发生时她的身体的代数
 * @param entry      事件条目(种类、正文、真实时间、急不急、编号),和收件箱落盘的是同一个编码
 * @param outcome    被切断的程序的结局;别的事件是 null
 * @param spoken     别人对她说的话:是谁、在哪儿说的,她回话与替谁做事都按它;别的事件是 null
 * @param gameTime   发生那一刻主世界的游戏刻
 * @param dayTime    发生那一刻主世界的时刻
 */
public record SeatEvent(UUID companion, long seq, long generation, EventQueue.Entry entry, Program.Outcome outcome,
                        Spoken spoken, long gameTime, long dayTime) {

    /** 一条服务端发出的事件条目;序号待发件箱盖上。 */
    static SeatEvent of(UUID companion, long generation, EventQueue.Entry entry, long gameTime, long dayTime) {
        return new SeatEvent(companion, 0, generation, entry, null, null, gameTime, dayTime);
    }

    /** 同上,事件是别人对她说的话。 */
    static SeatEvent ofSpoken(UUID companion, long generation, EventQueue.Entry entry, Spoken spoken, long gameTime,
                              long dayTime) {
        return new SeatEvent(companion, 0, generation, entry, null, spoken, gameTime, dayTime);
    }

    /** 同上,事件是一段程序的结局:正文是回执里的文字。 */
    static SeatEvent ofOutcome(UUID companion, long generation, EventQueue.Entry entry, Program.Outcome outcome,
                               long gameTime, long dayTime) {
        return new SeatEvent(companion, 0, generation, entry, outcome, null, gameTime, dayTime);
    }

    /** 盖上序号。 */
    SeatEvent withSeq(long seq) {
        return new SeatEvent(companion, seq, generation, entry, outcome, spoken, gameTime, dayTime);
    }

    /** 事件种类。 */
    public String type() {
        return entry.type();
    }

    /** 事件正文。 */
    public String text() {
        return entry.text();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("companion", companion.toString());
        o.addProperty("seq", seq);
        o.addProperty("generation", generation);
        o.add("entry", entry.toJson());
        if (outcome != null) {
            o.add("outcome", ProgramResult.outcomeJson(outcome));
        }
        if (spoken != null) {
            o.add("spoken", spoken.toJson());
        }
        o.addProperty("game_time", gameTime);
        o.addProperty("day_time", dayTime);
        return o;
    }

    public static SeatEvent fromJson(JsonObject o) {
        return new SeatEvent(UUID.fromString(o.get("companion").getAsString()), o.get("seq").getAsLong(),
                o.get("generation").getAsLong(), EventQueue.Entry.fromJson(o.getAsJsonObject("entry")),
                o.has("outcome") ? ProgramResult.outcomeOf(o.getAsJsonObject("outcome")) : null,
                o.has("spoken") ? Spoken.fromJson(o.getAsJsonObject("spoken")) : null,
                o.get("game_time").getAsLong(), o.get("day_time").getAsLong());
    }
}
