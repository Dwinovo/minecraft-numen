package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 主人给一只同伴定的定时:一条 = 一句话 + 间隔(游戏刻)。到点等于主人说了这句话,送话的是 {@link CompanionLoop}(和主人的话
 * 走同一个入口进她的收件箱),这里只管账:有哪几条、哪条到期了、到期之后下一次是什么时候。
 *
 * <h2>时间</h2>
 * 记的是主世界游戏刻的绝对"下次到期刻"(与 {@code TimerRegistry} 同一口径):世界不跑表就不走,重启不重新计时。
 *
 * <h2>到期只触发一次</h2>
 * {@link #due} 先把这条的下次到期刻改成 <b>此刻 + 间隔</b> 并落盘,再把它交给调用方去送话:落盘的写在她家目录的车道上,
 * 排在随后送进收件箱的那一条前面,所以重载存档不会重发;错过的不补(下一次从此刻起算,不管错过了几个间隔)。
 * 主人不在时调用方不问 {@link #due},到期刻留在过去,他回来后的第一次问到就送一次——不管错过几个间隔,合并成一次。
 *
 * <h2>上限与下限</h2>
 * 每次触发就是一次模型调用,所以每只同伴最多 {@link #MAX} 条,间隔不短于 {@link #MIN_EVERY_TICKS}。
 * 取值的理由见 control 0018 的实现说明。
 *
 * <p>线程:服务端主线程。落盘经她家目录的写车道({@link JsonDoc})。
 */
public final class OwnerSchedules {

    /** 每只同伴最多几条;与她自己的表({@code TimerRegistry.MAX_PER_COMPANION})同一量级。 */
    public static final int MAX = 8;
    /** 最短间隔:一个游戏小时(1000 刻,现实 50 秒)。 */
    public static final long MIN_EVERY_TICKS = 1000L;
    /** 一个游戏日(24000 刻,现实 20 分钟)。 */
    public static final long DAY_TICKS = 24000L;
    /** 最长间隔:一百个游戏日,够"每天一次""每周一次",再长没有意义,也挡住乘法溢出。 */
    public static final long MAX_EVERY_TICKS = 100 * DAY_TICKS;
    /** 一句话最长多少字。 */
    public static final int MAX_TEXT = 500;

    /**
     * 一条定时。
     *
     * @param id       这条的号(随存档,界面按它改、删)
     * @param text     到点对她说的话
     * @param every    间隔,游戏刻
     * @param nextDue  下次到期的主世界游戏刻(绝对值)
     */
    public record Schedule(String id, String text, long every, long nextDue) {}

    private final JsonDoc doc;
    private final List<Schedule> items = new ArrayList<>();

    /** @param saved 落盘的内容;没有是 {@code null} */
    OwnerSchedules(JsonDoc doc, JsonObject saved) {
        this.doc = doc;
        if (saved != null && saved.has("schedules")) {
            for (var e : saved.getAsJsonArray("schedules")) {
                JsonObject o = e.getAsJsonObject();
                items.add(new Schedule(o.get("id").getAsString(), o.get("text").getAsString(),
                        o.get("every").getAsLong(), o.get("next").getAsLong()));
            }
        }
    }

    /** 现有的几条,按加入的先后。 */
    public List<Schedule> list() {
        return List.copyOf(items);
    }

    /**
     * 新增({@code id} 为 {@code null})或修改一条。改过话或间隔就从 {@code now} 起重新计时。
     *
     * @throws IllegalArgumentException 话是空的或太长、间隔越界、条数满了、要改的号不存在
     */
    public Schedule put(String id, String text, long every, long now) {
        String words = text == null ? "" : text.strip();
        if (words.isEmpty()) {
            throw new IllegalArgumentException("a schedule needs words to say");
        }
        if (words.length() > MAX_TEXT) {
            throw new IllegalArgumentException("a schedule's words are at most " + MAX_TEXT + " characters");
        }
        if (every < MIN_EVERY_TICKS || every > MAX_EVERY_TICKS) {
            throw new IllegalArgumentException("a schedule repeats every " + MIN_EVERY_TICKS + " to " + MAX_EVERY_TICKS
                    + " ticks");
        }
        Schedule made = new Schedule(id == null ? UUID.randomUUID().toString().substring(0, 8) : id, words, every,
                now + every);
        if (id == null) {
            if (items.size() >= MAX) {
                throw new IllegalArgumentException("at most " + MAX + " schedules per companion");
            }
            items.add(made);
        } else {
            int at = indexOf(id);
            if (at < 0) {
                throw new IllegalArgumentException("no such schedule");
            }
            items.set(at, made);
        }
        save();
        return made;
    }

    /** 删一条;没有这一条返回 {@code false}。 */
    public boolean delete(String id) {
        int at = indexOf(id);
        if (at < 0) {
            return false;
        }
        items.remove(at);
        save();
        return true;
    }

    /**
     * 此刻到期的各条的话,按加入的先后。每条的下次到期刻已经改成 {@code now + 间隔} 并落盘,所以同一条在这个间隔里不会再被交出来。
     */
    public List<String> due(long now) {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Schedule s = items.get(i);
            if (s.nextDue() <= now) {
                words.add(s.text());
                items.set(i, new Schedule(s.id(), s.text(), s.every(), now + s.every()));
            }
        }
        if (!words.isEmpty()) {
            save();
        }
        return words;
    }

    private int indexOf(String id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void save() {
        if (items.isEmpty()) {
            doc.delete();
            return;
        }
        JsonArray array = new JsonArray();
        for (Schedule s : items) {
            JsonObject o = new JsonObject();
            o.addProperty("id", s.id());
            o.addProperty("text", s.text());
            o.addProperty("every", s.every());
            o.addProperty("next", s.nextDue());
            array.add(o);
        }
        JsonObject out = new JsonObject();
        out.add("schedules", array);
        doc.write(out);
    }
}
