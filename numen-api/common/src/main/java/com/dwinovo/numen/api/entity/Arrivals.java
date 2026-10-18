package com.dwinovo.numen.api.entity;

import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 一具身体进世界的编排:平台接纳(可能是异步的)→ 回到主线程 → 核对还要不要她 → 加入。只管这几步的次序与"在路上"的登记,
 * 不认识 Minecraft 的玩家——身体是什么、怎么造、怎么加入、平台怎么答,都从外面传进来,所以能用一个可控的假接纳单测
 * (见 {@code ArrivalsTest}):当场接、当场拒、挂起后再接/再拒。
 *
 * <p>"在路上"的登记在<b>受理的那一刻</b>就占住(同一具身体的 UUID 与名字):第二个来的——同一具身体得到"在路上"、
 * 别的身体想用同一个名字由调用方据 {@link #arrivingNamed} 当场拒绝——绝不另起一趟,免得进来两具同 UUID 或同名的。
 *
 * <p>线程:服务端主线程;平台的答复也必须回到主线程再调。
 *
 * @param <B> 身体
 */
public final class Arrivals<B> {

    /** 结果的种类。 */
    public enum Kind {
        /** 进来了。 */
        ARRIVED,
        /** 平台不接纳:登录插件、封禁、白名单明确说不,{@code refusal} 是它给的原因。 */
        REFUSED,
        /** 这具身体已经在路上:不另起一趟。 */
        UNDER_WAY,
        /** 平台接纳了,但回到主线程时登记里已经不要她了(期间被遣散、主人改了主意):没有加入。 */
        WITHDRAWN
    }

    /** 一趟的结果。 */
    public record Arrival<B>(B body, Component refusal, Kind kind) {

        static <B> Arrival<B> arrived(B body) {
            return new Arrival<>(body, null, Kind.ARRIVED);
        }

        static <B> Arrival<B> refused(Component reason) {
            return new Arrival<>(null, reason, Kind.REFUSED);
        }

        static <B> Arrival<B> underWay() {
            return new Arrival<>(null, null, Kind.UNDER_WAY);
        }

        static <B> Arrival<B> withdrawn() {
            return new Arrival<>(null, null, Kind.WITHDRAWN);
        }
    }

    /** 平台那一头:要不要接纳,答复经 {@code verdict}(主线程)交回,{@code null} 是接纳,否则是拒绝的原因。 */
    @FunctionalInterface
    public interface Admission {
        void admit(Consumer<Component> verdict);
    }

    /** 在路上的:UUID → 名字(小写,名字不分大小写,和玩家重名的判法一致)。 */
    private final Map<UUID, String> underWay = new HashMap<>();

    /** 这具身体正在路上。 */
    public boolean isArriving(UUID id) {
        return underWay.containsKey(id);
    }

    /** 哪具在路上的身体占着这个名字;没有是空。 */
    public Optional<UUID> arrivingNamed(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return underWay.entrySet().stream().filter(e -> e.getValue().equals(key)).map(Map.Entry::getKey).findFirst();
    }

    /** 世界作废(关服):在路上的都不算数了。 */
    public void clear() {
        underWay.clear();
    }

    /**
     * 受理一具身体进世界:占住登记,问平台,答复回来以后核对 {@code stillWanted},还要才 {@code join}。
     * 每种结局 {@code then} 恰好跑一次,登记在 {@code then} 之前放掉。
     *
     * @param stillWanted 平台答复回来时,名册里是不是仍然要她
     * @param join        加入世界,交回身体
     */
    public void arrive(UUID id, String name, Admission admission, BooleanSupplier stillWanted, Supplier<B> join,
                       Consumer<Arrival<B>> then) {
        if (underWay.containsKey(id)) {
            then.accept(Arrival.underWay());
            return;
        }
        underWay.put(id, name.toLowerCase(Locale.ROOT));
        try {
            admission.admit(refusal -> {
                underWay.remove(id);
                if (refusal != null) {
                    then.accept(Arrival.refused(refusal));
                } else if (!stillWanted.getAsBoolean()) {
                    then.accept(Arrival.withdrawn());
                } else {
                    then.accept(Arrival.arrived(join.get()));
                }
            });
        } catch (RuntimeException e) {
            underWay.remove(id);
            throw e;
        }
    }
}
