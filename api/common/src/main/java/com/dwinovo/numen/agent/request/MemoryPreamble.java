package com.dwinovo.numen.agent.request;

import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.memory.NoteBook;

/**
 * 注入时垫在最前的 {@code <memory>} 索引(内核的 {@code HostPort#injectionPreamble})。
 *
 * <p>索引随注入的 user 消息进历史,不放系统提示:她一 remember 它就变了,放系统提示会打碎请求前缀的 prompt cache。
 * 贴的是<b>全份</b>,但只在"历史里那份没了或者过时了"的时候贴:开一局、压缩/清空之后、她刚写过。没变就不重贴——
 * 历史里已经躺着一份,再贴一份是白花的 token。
 */
public final class MemoryPreamble {

    private final NoteBook notes;
    /** 札记索引上次贴进历史时的版本。 */
    private int revisionInHistory = -1;
    /** 历史里那份还在不在:开一局时不在,压缩/清空把它吃掉之后也不在。 */
    private boolean inHistory;

    public MemoryPreamble(NoteBook notes) {
        this.notes = notes;
    }

    /** 这一次注入要垫的索引;历史里那份还新就是空串。 */
    public String next() {
        int revision = notes.revision();
        if (inHistory && revision == revisionInHistory) {
            return "";
        }
        inHistory = true;
        revisionInHistory = revision;
        return notes.formatXml();
    }

    /**
     * 整理或清空之后,历史里那份札记索引没了(摘要把它嚼掉了),下一次注入得重贴一份完整的。
     *
     * <p>真源在磁盘上,历史里的只是复述——所以复述丢了不要紧,照着真源再念一遍就行。
     */
    public void on(LoopEvent event) {
        if (event instanceof LoopEvent.TranscriptBoundary) {
            inHistory = false;
        }
    }
}
