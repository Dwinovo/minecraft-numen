package com.dwinovo.numen.api.agent.llm;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.function.Function;

/**
 * {@link ConvoState} 的账写成对话记录的行({@link Rows}):每条消息、状态、整理边界换成行,按顺序交给 {@link Sink}。
 * 行往哪儿去(座位上的追加、测试里的一张表)是 Sink 的事,这里不认识。
 *
 * <p>线程:同 {@link ConvoState},一个线程。
 */
public final class RowJournal implements ConvoState.Journal {

    /** 写下的行去哪儿。 */
    public interface Sink {

        /** 新写的行,按写的顺序。 */
        void rows(List<JsonObject> rows);

        /** 对话接着在一个新的会话里写:此后的行属于它,旧的一份留在原处。 */
        void newConversation();
    }

    private final Rows.Writer writer;
    private final Function<ConvoState.Msg, Rows.Stamp> stamp;
    private final Sink sink;

    /**
     * @param stamp 一条消息的 {@code conv};状态与边界行盖 {@code stamp.apply(null)}
     */
    public RowJournal(Rows.Writer writer, Function<ConvoState.Msg, Rows.Stamp> stamp, Sink sink) {
        this.writer = writer;
        this.stamp = stamp;
        this.sink = sink;
    }

    @Override
    public void append(ConvoState.Msg msg) {
        sink.rows(writer.write(msg, stamp.apply(msg)));
    }

    @Override
    public void attach(String type, JsonObject fields) {
        sink.rows(List.of(writer.attach(type, fields, stamp.apply(null))));
    }

    @Override
    public void compacted(String summary, int keptTail, JsonObject meta) {
        sink.rows(writer.compact(summary, keptTail, meta, stamp.apply(null)));
    }

    @Override
    public void cleared() {
        writer.restart();
        sink.newConversation();
    }
}
