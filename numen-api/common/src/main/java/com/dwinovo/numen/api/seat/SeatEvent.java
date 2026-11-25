package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.loop.Program;
import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * 座位上发出的一件事:世界上发生的、别人对她说的,或一段在主人断线时被切断的程序留下的结局。每条一个递增的序号,留到客户端确认收到
 * ({@code events/ack});带着发生那一刻的代数,与她此刻的样子和游戏刻。
 *
 * @param companion  哪只同伴的座位
 * @param seq        序号,同一只同伴的座位上从 1 起递增;还没进发件箱的是 0
 * @param generation 事件发生时她的身体的代数
 * @param type       事件种类,{@code EventTypes} 里登记过的
 * @param text       事件正文,{@code <event>} 的原样
 * @param ts         真实时间,毫秒
 * @param result     一件身体活收尾时它的结果;别的事件是 null
 * @param outcome    被切断的程序的结局;别的事件是 null
 * @param state      发生那一刻她的样子({@code <runtime_state>}),没有可说的是空串
 * @param gameTime   发生那一刻主世界的游戏刻
 */
public record SeatEvent(UUID companion, long seq, long generation, String type, String text, long ts, boolean urgent,
                        JsonObject result, Program.Outcome outcome, String state, long gameTime) {

    /** 一条服务端发出的事件条目,在这一刻、这个代数下的样子;序号待发件箱盖上。 */
    static SeatEvent of(UUID companion, long generation, EventQueue.Entry entry, String state, long gameTime) {
        return new SeatEvent(companion, 0, generation, entry.type(), entry.text(), entry.ts(), entry.urgent(),
                entry.result(), null, state, gameTime);
    }

    /** 同上,事件是一段程序的结局:正文是回执里的文字。 */
    static SeatEvent ofOutcome(UUID companion, long generation, EventQueue.Entry entry, Program.Outcome outcome,
                               String state, long gameTime) {
        return new SeatEvent(companion, 0, generation, entry.type(), entry.text(), entry.ts(), entry.urgent(), null,
                outcome, state, gameTime);
    }

    /** 盖上序号。 */
    SeatEvent withSeq(long seq) {
        return new SeatEvent(companion, seq, generation, type, text, ts, urgent, result, outcome, state, gameTime);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("companion", companion.toString());
        o.addProperty("seq", seq);
        o.addProperty("generation", generation);
        o.addProperty("type", type);
        o.addProperty("text", text);
        o.addProperty("ts", ts);
        o.addProperty("urgent", urgent);
        if (result != null) {
            o.add("result", result);
        }
        if (outcome != null) {
            o.add("outcome", ProgramResult.outcomeJson(outcome));
        }
        o.addProperty("state", state);
        o.addProperty("game_time", gameTime);
        return o;
    }

    public static SeatEvent fromJson(JsonObject o) {
        return new SeatEvent(UUID.fromString(o.get("companion").getAsString()), o.get("seq").getAsLong(),
                o.get("generation").getAsLong(), o.get("type").getAsString(), o.get("text").getAsString(),
                o.get("ts").getAsLong(), o.get("urgent").getAsBoolean(),
                o.has("result") ? o.getAsJsonObject("result") : null,
                o.has("outcome") ? ProgramResult.outcomeOf(o.getAsJsonObject("outcome")) : null,
                o.get("state").getAsString(), o.get("game_time").getAsLong());
    }
}
