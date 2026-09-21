package com.dwinovo.numen.agent.group;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 主人在群里说了一句话,谁醒。
 *
 * <h2>{@code @} 是转话头,不是寻址</h2>
 * 点了名就把话头转给她们,转过去就一直是她们,直到下次转。于是 {@code @} 从"每句都要打"
 * 变成"偶尔转向"——快捷对话(Y)那条路才不会废掉一半。
 *
 * <pre>
 * @小柚 去挖铁     → 话头给小柚,小柚醒
 * 多挖点            → 还是小柚醒,不必再打 @
 * @阿岚 你去砍树    → 话头转给阿岚
 * </pre>
 *
 * <p>一句话里没有 {@code @},话头又还空着时<b>全体都醒</b>。这不是兜底:没点名的话通常本来
 * 就是说给全体的("我回来了"、"天黑了"),而派活的时候人自己就会打 {@code @}。
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
     * @param floor 说完之后的话头。点了名就是被点的那些,没点名则原样保留
     */
    public record Routing(List<UUID> awake, List<UUID> floor) {}

    private Mentions() {}

    /**
     * @param text    主人说的那句话原文
     * @param members 群成员
     * @param floor   说这句话之前的话头(空 = 还没落到任何人身上)
     */
    public static Routing route(String text, List<Member> members, List<UUID> floor) {
        List<UUID> mentioned = mentioned(text, members);
        if (!mentioned.isEmpty()) {
            return new Routing(mentioned, mentioned);
        }
        List<UUID> held = new ArrayList<>();
        for (UUID u : floor == null ? List.<UUID>of() : floor) {
            if (contains(members, u)) {
                held.add(u);
            }
        }
        if (!held.isEmpty()) {
            return new Routing(List.copyOf(held), List.copyOf(held));
        }
        // 话头还空着:全体都醒,而且话头仍然空着——下一句没点名还是全体
        List<UUID> all = new ArrayList<>();
        for (Member m : members) {
            all.add(m.uuid());
        }
        return new Routing(List.copyOf(all), List.of());
    }

    /**
     * 话里 {@code @} 到了谁,按出现先后。
     *
     * <p>长名字先试,匹配掉的区间不再参与——{@code @Anna} 因此不会又被 {@code Ann} 认领一次。
     */
    public static List<UUID> mentioned(String text, List<Member> members) {
        if (text == null || text.isEmpty() || members == null || members.isEmpty()) {
            return List.of();
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        boolean[] eaten = new boolean[haystack.length()];
        List<Member> byLength = new ArrayList<>(members);
        byLength.sort(Comparator.comparingInt((Member m) -> m.name() == null ? 0 : m.name().length())
                .reversed());

        // UUID → 第一次出现的位置,用来还原"话里出现的先后"
        List<int[]> hits = new ArrayList<>();
        List<UUID> owners = new ArrayList<>();
        for (Member m : byLength) {
            if (m.name() == null || m.name().isBlank()) {
                continue;
            }
            String needle = "@" + m.name().toLowerCase(Locale.ROOT);
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
                hits.add(new int[]{at});
                owners.add(m.uuid());
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(i -> hits.get(i)[0]));
        LinkedHashSet<UUID> out = new LinkedHashSet<>();
        for (int i : order) {
            out.add(owners.get(i));
        }
        return List.copyOf(out);
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

    private static boolean contains(List<Member> members, UUID uuid) {
        for (Member m : members) {
            if (m.uuid().equals(uuid)) {
                return true;
            }
        }
        return false;
    }
}
