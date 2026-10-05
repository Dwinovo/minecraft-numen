package com.dwinovo.numen.agent.inbox;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投递语义只有一个来源:每一档自己声明 {@link EventTypes.Delivery#wakes} 与 {@link EventTypes.Delivery#joins},
 * 队列与循环只读声明。加一档新投递只改 {@code Delivery} 一处——这里用两样东西钉住:
 * 队列对每一档的行为都能从它的声明推出来;实现代码里没有一处按档名判断。
 */
class DeliveryDeclarationsTest {

    private static final long T0 = 1_000_000L;

    /** 每一档登记一种探针类型,只差投递档。 */
    private static String probe(EventTypes.Delivery d) {
        String id = "delivery_probe_" + d.name().toLowerCase();
        synchronized (DeliveryDeclarationsTest.class) {
            if (!EventTypes.isRegistered(id)) {
                EventTypes.register(new EventTypes.Type(id, s -> s, s -> null, false, false, d, false));
            }
        }
        return id;
    }

    private static EventQueue queueOf(String... types) {
        EventQueue q = new EventQueue(EventQueue.Journal.NONE);
        for (String t : types) {
            q.push(t, "<event kind=\"" + t + "\">x</event>", T0, false);
        }
        return q;
    }

    @Test
    void theQueueTreatsEveryDeliveryExactlyAsItsDeclarationsSay() {
        for (EventTypes.Delivery d : EventTypes.Delivery.values()) {
            String id = probe(d);
            boolean anyCall = d.joins() == EventTypes.Delivery.Joins.ANY_CALL;

            assertEquals(d.wakes(), queueOf(id).ripeness(T0, EventQueue.MIN_LEVEL).ripe(), d + ":闲时算不算叫醒她的理由");
            assertEquals(d.wakes(), queueOf(id).hasWaking(), d + ":开不开得起一次 run");
            assertEquals(d.wakes(), queueOf(id).wantsAnswer(), d + ":本来要停时让不让 run 接着走");
            assertEquals(d.wakes() ? 1 : 0, queueOf(id).takeForCall(false, T0).size(),
                    d + ":本来要停时,它自己引起的那次调用带不带它");
            assertEquals(anyCall ? 1 : 0, queueOf(id).takeForCall(true, T0).size(),
                    d + ":本来就要调模型时带不带它");
            assertEquals(d.control() ? 1 : 0, queueOf(id).nextControls().size(), d + ":是不是循环自己执行的");
            assertEquals(d.control() ? 0 : 1, queueOf(id).takeText(T0).size(), d + ":外接大脑取不取");
            assertEquals(d.control(), queueOf(id, EventTypes.QUERY).takeForCall(true, T0).isEmpty(),
                    d + ":是不是墙——排在它后面的主人的话要不要等它");
        }
    }

    @Test
    void aFollowUpOnlyJoinsTheCallItCausesItself() {
        String own = probe(EventTypes.Delivery.FOLLOW_UP);
        for (EventTypes.Delivery other : EventTypes.Delivery.values()) {
            if (other.control()) {
                continue;
            }
            EventQueue q = queueOf(own, probe(other));
            boolean othersCall = other.wakes() && other.joins() == EventTypes.Delivery.Joins.ANY_CALL;
            List<String> taken = q.takeForCall(false, T0).stream().map(EventQueue.Entry::type).toList();
            assertEquals(!othersCall, taken.contains(own), "旁边是 " + other + " 时接续跟不跟这次调用");
        }
    }

    private static final Pattern NAMES_A_DELIVERY =
            Pattern.compile("Delivery\\s*\\.\\s*(" + String.join("|",
                    Stream.of(EventTypes.Delivery.values()).map(Enum::name).toList()) + ")\\b");

    /** 档名只出现在类型表里(登记内置的那几行)。别处出现一次,就是又开始按档名判断了。 */
    @Test
    void onlyTheTypeTableNamesADelivery() throws IOException {
        Path main = Path.of("src/main/java");
        assertTrue(Files.isDirectory(main), "从 agent 模块目录跑:" + main.toAbsolutePath());
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(main)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("EventTypes.java"))
                    .filter(p -> {
                        try {
                            return NAMES_A_DELIVERY.matcher(Files.readString(p, StandardCharsets.UTF_8)).find();
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .toList();
        }
        assertEquals(List.of(), offenders, "这些文件按档名判断投递方式,该改读声明");
    }
}
