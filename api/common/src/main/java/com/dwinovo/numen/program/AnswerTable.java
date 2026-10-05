package com.dwinovo.numen.program;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** 发出去的反向请求等答复的地方:按调用编号记着谁在等、等谁的答复;只有被问的那位主人答得了。两种传输共用。 */
final class AnswerTable {

    private record Waiting(UUID owner, java.util.function.Consumer<ClientTransport.Answer> done) {}

    private final Map<String, Waiting> waiting = new ConcurrentHashMap<>();

    /** 记下 {@code callId} 在等 {@code owner} 的答复。 */
    void expect(String callId, UUID owner, java.util.function.Consumer<ClientTransport.Answer> done) {
        waiting.put(callId, new Waiting(owner, done));
    }

    /** {@code from} 答了 {@code callId}:是被问的那一位,就交给等的一方。不是在等的、或不是被问的,不理。 */
    void complete(String callId, UUID from, ClientTransport.Answer answer) {
        Waiting w = waiting.get(callId);
        if (w == null || !w.owner().equals(from) || !waiting.remove(callId, w)) {
            return;
        }
        w.done().accept(answer);
    }

    /** 不再等 {@code callId} 的答复(等的一方超时了、程序结束了):之后才到的答复不理。没在等的不理。 */
    void cancel(String callId) {
        waiting.remove(callId);
    }

    /** 这位主人再也答不了了(断线):等着他的每一个都以 {@code failure} 给的失败结束。 */
    void failOwner(UUID owner, Function<String, ClientTransport.Answer> failure) {
        List<String> ids = new ArrayList<>();
        waiting.forEach((id, w) -> {
            if (w.owner().equals(owner)) {
                ids.add(id);
            }
        });
        for (String id : ids) {
            Waiting w = waiting.remove(id);
            if (w != null) {
                w.done().accept(failure.apply(id));
            }
        }
    }

    /** 关服。 */
    void clear() {
        waiting.clear();
    }
}
