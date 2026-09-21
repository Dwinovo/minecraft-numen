package com.dwinovo.numen.agent.conversation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 主人在群里说了一句话,谁醒。
 *
 * <h2>{@code @} 谁谁回,不 {@code @} 全体回</h2>
 * 每句话各算各的,没有粘住的状态:上一句点了小柚,这一句没点名就是说给大家的,小柚也在其中。
 * 这和 {@code @} 在别处的直觉一致——点名只是那一句的事。
 *
 * <pre>
 * @小柚 去挖铁     → 只有小柚醒
 * 多挖点            → 没点名 = 说给大家的,全体醒
 * @阿岚 你去砍树    → 只有阿岚醒
 * </pre>
 *
 * <p>没点名就全体醒不是兜底:没点名的话通常本来就是说给全体的("我回来了"、"天黑了"),
 * 而派活的时候人自己就会打 {@code @}。
 *
 * <h2>为什么不做子串匹配</h2>
 * 名字互为前缀会误判:{@code @Anna} 里含着 {@code Ann}。所以长名字先匹配,吃掉的那一段不再参与,
 * 而且 {@code @} 后面那个名字必须整个对上——后面不能紧跟着字母或数字。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class Mentions {

    /** 一个候选收件人:同伴的 UUID 和她在界面上的名字。 */
    public record Member(UUID uuid, String name) {}

    /**
     * 这一句话的去向。
     *
     * @param awake 要唤醒的那些(按话里出现的先后;没点名时是全体,按成员顺序)
     */
    public record Routing(List<UUID> awake) {}

    private Mentions() {}

    /**
     * @param text    主人说的那句话原文
     * @param members 群成员
     */
    public static Routing route(String text, List<Member> members) {
        List<UUID> mentioned = mentioned(text, members);
        if (!mentioned.isEmpty()) {
            return new Routing(mentioned);
        }
        List<UUID> all = new ArrayList<>();
        for (Member m : members) {
            all.add(m.uuid());
        }
        return new Routing(List.copyOf(all));
    }

    /** 话里被 {@code @} 到的一段:{@code [start, end)} 是原文里的区间,{@code whom} 是这个名字喊到的那些。 */
    public record Span(int start, int end, List<UUID> whom) {}

    /** 话里 {@code @} 到了谁,按出现先后。 */
    public static List<UUID> mentioned(String text, List<Member> members) {
        LinkedHashSet<UUID> out = new LinkedHashSet<>();
        for (Span s : spans(text, members)) {
            out.addAll(s.whom());
        }
        return List.copyOf(out);
    }

    /**
     * 话里所有 {@code @名字} 的区间,按出现先后。路由用它定谁醒,面板用它把名字画亮——同一份匹配,
     * 亮的正好是会醒的。
     *
     * <p>长名字先试,匹配掉的区间不再参与——{@code @Anna} 因此不会又被 {@code Ann} 认领一次。
     */
    public static List<Span> spans(String text, List<Member> members) {
        if (text == null || text.isEmpty() || members == null || members.isEmpty()) {
            return List.of();
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        boolean[] eaten = new boolean[haystack.length()];

        // 先按名字归拢:两只同伴同名时,喊那个名字就是把她们都喊上——总比按成员顺序
        // 任选一只好,那个"第一只"完全是任意的。
        Map<String, List<UUID>> byName = new LinkedHashMap<>();
        for (Member m : members) {
            if (m == null || m.name() == null || m.name().isBlank()) {
                continue;
            }
            byName.computeIfAbsent(m.name().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(m.uuid());
        }
        // 长名字先试,吃掉的那一段不再参与——@Anna 因此不会又被 @Ann 认领一次
        List<String> names = new ArrayList<>(byName.keySet());
        names.sort(Comparator.comparingInt(String::length).reversed());

        // 位置 → 那一段,用来还原"话里出现的先后"
        Map<Integer, Span> hits = new java.util.TreeMap<>();
        for (String name : names) {
            String needle = "@" + name;
            int from = 0;
            while (true) {
                int at = haystack.indexOf(needle, from);
                if (at < 0) {
                    break;
                }
                from = at + 1;
                int end = at + needle.length();
                if (chewed(eaten, at, end) || glued(haystack, end)) {
                    continue;
                }
                for (int i = at; i < end; i++) {
                    eaten[i] = true;
                }
                hits.put(at, new Span(at, end, List.copyOf(byName.get(name))));
            }
        }
        return List.copyOf(hits.values());
    }

    /** 这一段是不是已经被更长的名字吃掉了。 */
    private static boolean chewed(boolean[] eaten, int from, int to) {
        for (int i = from; i < to; i++) {
            if (eaten[i]) {
                return true;
            }
        }
        return false;
    }

    /** 名字后面紧跟着字母或数字 = 没整个对上({@code @Ann} 遇上 {@code @Anna})。 */
    private static boolean glued(String text, int end) {
        return end < text.length() && Character.isLetterOrDigit(text.charAt(end));
    }
}
