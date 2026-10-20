package com.dwinovo.numen.api.agent.request;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.permission.Groups;
import com.dwinovo.numen.api.sdk.CompassUtil;
import com.dwinovo.numen.api.sdk.EntityLabel;
import com.dwinovo.numen.api.sdk.NearbyEntities;
import com.dwinovo.numen.api.task.CompanionTickDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 她四周此刻的样子,{@code <runtime_state>} 里的 {@code <nearby>}:半径 {@link NearbyEntities#RADIUS} 格,不看视线。
 * 逐个列出和她有关系的、敌对的、玩家,最多 {@value #MAX_LINES} 条;其余按种类计数。每次请求现取,不进历史——事件说的是过去,
 * 这一栏说的是此刻,用的是同一套身份({@link EntityLabel})。
 *
 * <p>每个请求都按全价计费,所以写得短:一条一行,没有的栏整个不写。
 *
 * @param lines  逐个列出的,有关系的在前、其次敌对、再次玩家,同类由近及远
 * @param counts 其余按种类计数(含超出条数上限的),多的在前
 */
public record Nearby(List<Line> lines, List<Count> counts) {

    /** 逐个列出的至多这么多条。 */
    public static final int MAX_LINES = 12;
    /** 计数的种类至多列这么多,再多的合成一个 {@code other}:一座农场的牲口种类也不该撑大这一栏。 */
    static final int MAX_KINDS = 10;

    public static final Nearby NONE = new Nearby(List.of(), List.of());

    /** 为什么值得逐个列出,先后即列出的先后。 */
    enum Rank { RELATED, HOSTILE, PLAYER, OTHER }

    /** 一个在半径里的实体,算好了再排;{@code health} 是 {@code 20/20},没有血量的东西是空串。 */
    record Candidate(String id, String kind, String health, double distance, String bearing, Rank rank,
                     boolean targetsHer) {}

    /** 列出的一条。 */
    public record Line(String id, String health, long distance, String bearing, boolean targetsHer) {}

    /** 一个种类有几个。 */
    public record Count(String kind, int n) {}

    /** 半径里什么都没有。 */
    public boolean isEmpty() {
        return lines.isEmpty() && counts.isEmpty();
    }

    // ---- 取:从世界里看 ----

    /**
     * 她此刻四周的实体。和她有关系的:盯着她的(怪的攻击目标是她)、她正在打、用、骑着的、她名下的(她驯服的狼、女仆;
     * 判据和 {@code numen.scan.entities} 的 owner 是同一处,{@link Groups#ownerOf})。
     */
    public static Nearby of(NumenPlayer her) {
        Set<UUID> engaged = new HashSet<>();
        CompanionTickDispatcher.engagedBy(her).forEach(e -> engaged.add(e.getUUID()));
        List<Candidate> candidates = new ArrayList<>();
        for (Entity e : NearbyEntities.within(her, NearbyEntities.RADIUS, Entity.class, Entity::isAlive)) {
            boolean targetsHer = e instanceof Mob mob && mob.getTarget() == her;
            boolean related = targetsHer || engaged.contains(e.getUUID()) || her.getUUID().equals(Groups.ownerOf(e));
            Rank rank = related ? Rank.RELATED
                    : e instanceof Enemy ? Rank.HOSTILE
                    : e instanceof Player ? Rank.PLAYER
                    : Rank.OTHER;
            String health = e instanceof LivingEntity living
                    ? Math.round(living.getHealth()) + "/" + Math.round(living.getMaxHealth())
                    : "";
            candidates.add(new Candidate(EntityLabel.of(e), BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(),
                    health, her.distanceTo(e), CompassUtil.compass(her, e), rank, targetsHer));
        }
        return compose(candidates);
    }

    /** 排好、截断、其余计数。 */
    static Nearby compose(List<Candidate> candidates) {
        List<Candidate> ranked = candidates.stream().filter(c -> c.rank() != Rank.OTHER)
                .sorted(Comparator.comparing(Candidate::rank).thenComparingDouble(Candidate::distance)).toList();
        int shown = Math.min(MAX_LINES, ranked.size());
        List<Line> lines = new ArrayList<>(shown);
        for (Candidate c : ranked.subList(0, shown)) {
            lines.add(new Line(c.id(), c.health(), Math.round(c.distance()), c.bearing(), c.targetsHer()));
        }
        Map<String, Integer> tally = new TreeMap<>();
        ranked.subList(shown, ranked.size()).forEach(c -> tally.merge(c.kind(), 1, Integer::sum));
        candidates.stream().filter(c -> c.rank() == Rank.OTHER).forEach(c -> tally.merge(c.kind(), 1, Integer::sum));
        List<Count> counts = new ArrayList<>(tally.entrySet().stream()
                .map(e -> new Count(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(Count::n).reversed().thenComparing(Count::kind)).toList());
        if (counts.size() > MAX_KINDS) {
            int rest = counts.subList(MAX_KINDS, counts.size()).stream().mapToInt(Count::n).sum();
            counts = new ArrayList<>(counts.subList(0, MAX_KINDS));
            counts.add(new Count("other", rest));
        }
        return new Nearby(List.copyOf(lines), List.copyOf(counts));
    }

    // ---- 写:给模型看 ----

    /** {@code <nearby>} 一栏;半径里什么都没有就一个字都不写(和别的栏对"没有"的惯例一样)。 */
    public String xml() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<nearby radius=\"").append((int) NearbyEntities.RADIUS).append("\">");
        for (Line line : lines) {
            out.append('\n').append(line.id());
            if (!line.health().isEmpty()) {
                out.append(' ').append(line.health()).append(" HP");
            }
            out.append(", ").append(line.distance() == 0 && line.bearing().equals("here")
                    ? "on you" : line.distance() + " blocks " + line.bearing());
            if (line.targetsHer()) {
                out.append(", targeting you");
            }
        }
        if (!counts.isEmpty()) {
            out.append('\n');
            for (int i = 0; i < counts.size(); i++) {
                out.append(i == 0 ? "" : ", ").append(counts.get(i).kind()).append('×').append(counts.get(i).n());
            }
        }
        return out.append("\n</nearby>").toString();
    }
}
