package com.dwinovo.numen.plugins.kubejs;

import com.dwinovo.numen.api.combat.Aim;
import com.dwinovo.numen.api.combat.Flight;
import com.dwinovo.numen.api.combat.Handling;
import com.dwinovo.numen.api.combat.RangedWeapon;
import com.dwinovo.numen.api.entity.NumenPlayer;
import dev.latvian.mods.rhino.util.HideFromJS;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * 脚本里声明的一种远程武器(枪、魔杖……):这件物品认不认得、此刻能不能射、怎么准备(换弹)、怎么射、弹道什么样。
 * 它是 {@link RangedWeapon} 的一个提供者,攻击任务用它和用弓一样:选目标、走位、瞄准、起一发都还是任务的事,
 * <b>该不该打由权限层裁决</b>——脚本只说怎么射。每个方法返回自己。
 *
 * <pre>{@code
 * plugin.rangedWeapon('mygun', weapon => weapon
 *     .handles(stack => stack.id == 'mymod:blaster')
 *     .readiness((body, stack) => 'ready')           // 'ready' | 'loadable' | 'empty'
 *     .hitscan(48)
 *     .fire((body, stack, target, facing) => { … return true }))
 * }</pre>
 */
public final class ScriptWeapon {

    /** 认不认得这件物品。 */
    @FunctionalInterface
    public interface Handles {
        boolean test(ItemStack stack);
    }

    /** 此刻能不能射:{@code ready}、{@code loadable}(要先 {@code prepare})、{@code empty}。 */
    @FunctionalInterface
    public interface Condition {
        String of(NumenPlayer body, ItemStack stack);
    }

    /** 准备一刻(换弹、拉栓):每刻调一次,直到 {@code readiness} 说 {@code ready}。 */
    @FunctionalInterface
    public interface Prepare {
        void run(NumenPlayer body, ItemStack stack);
    }

    /**
     * 射一发:身体已经对准了目标。返回 {@code true} 是射出去了;返回 {@code 'wait'} 是这一刻还射不了(冷却、还没切好枪),下一刻再来;
     * 别的(false、什么都不返回)是没射出去,算这一发哑火。
     */
    @FunctionalInterface
    public interface Fire {
        Object run(NumenPlayer body, ItemStack stack, Entity target, Facing facing);
    }

    /**
     * 射出去的方向(俯仰与偏航,度),也是身体此刻的朝向。模组的射击接口常要两个 {@code Supplier<Float>}:脚本里的箭头函数交回的是 JavaScript 的数,
     * 到了 Java 一侧不是 {@code Float};所以这里直接给现成的 Java 供应器——{@code operator.shoot(facing.pitch(), facing.yaw())}。
     */
    public record Facing(float pitchDegrees, float yawDegrees) {

        public java.util.function.Supplier<Float> pitch() {
            return () -> pitchDegrees;
        }

        public java.util.function.Supplier<Float> yaw() {
            return () -> yawDegrees;
        }
    }

    private final String name;
    private Handles handles;
    private Condition readiness;
    private Prepare prepare = (body, stack) -> { };
    private Fire fire;
    private Flight flight;
    private double range;
    private Double innerRing;
    private Double outerRing;

    ScriptWeapon(String name) {
        this.name = name;
    }

    public ScriptWeapon handles(Handles test) {
        this.handles = test;
        return this;
    }

    public ScriptWeapon readiness(Condition of) {
        this.readiness = of;
        return this;
    }

    /** 命中扫描:一条线打过去,射程 {@code range} 格。 */
    public ScriptWeapon hitscan(double range) {
        this.flight = new Flight.Hitscan();
        this.range = range;
        return this;
    }

    /** 抛物线的弹射物:初速(格/刻)、重力、阻力(0 到 1,原版的箭是 0.05 与 0.99),射程 {@code range} 格。 */
    public ScriptWeapon arc(double velocity, double gravity, double drag, double range) {
        this.flight = new Flight.Arc(velocity, gravity, drag, 0.5, false);
        this.range = range;
        return this;
    }

    /** 站位的环:她站在离目标 {@code inner} 到 {@code outer} 格之间。不写,内沿 3 格,外沿是射程的四分之三。 */
    public ScriptWeapon standoff(double inner, double outer) {
        this.innerRing = inner;
        this.outerRing = outer;
        return this;
    }

    public ScriptWeapon prepare(Prepare run) {
        this.prepare = run;
        return this;
    }

    public ScriptWeapon fire(Fire run) {
        this.fire = run;
        return this;
    }

    /**
     * @throws IllegalArgumentException 缺了必须写的:认物品、能不能射、弹道、怎么射;站位不成立
     */
    @HideFromJS
    RangedWeapon build() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (handles == null) {
            missing.add("handles(stack => …)");
        }
        if (readiness == null) {
            missing.add("readiness((body, stack) => 'ready' | 'loadable' | 'empty')");
        }
        if (flight == null) {
            missing.add("hitscan(range) or arc(velocity, gravity, drag, range)");
        }
        if (fire == null) {
            missing.add("fire((body, stack, target, facing) => …)");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("the ranged weapon '" + name + "' needs " + String.join(", ", missing));
        }
        double inner = innerRing == null ? 3.0 : innerRing;
        double outer = outerRing == null ? Math.max(range * 0.75, inner) : outerRing;
        return new Provider(name, handles, readiness, prepare, fire, flight, new Handling(inner, outer, Math.max(range, outer)));
    }

    /** 脚本写的提供者:把脚本的几个回调接成 {@link RangedWeapon}。 */
    private record Provider(String name, Handles handles, Condition readiness, Prepare prepare, Fire fire,
                            Flight flight, Handling handling) implements RangedWeapon {

        @Override
        public boolean handles(ItemStack stack) {
            return handles.test(stack);
        }

        @Override
        public RangedWeapon.Readiness readiness(NumenPlayer body, ItemStack stack) {
            String said = readiness.of(body, stack);
            return switch (said == null ? "" : said) {
                case "ready" -> RangedWeapon.Readiness.READY;
                case "loadable" -> RangedWeapon.Readiness.LOADABLE;
                case "empty" -> RangedWeapon.Readiness.EMPTY;
                default -> throw new IllegalStateException("the ranged weapon '" + name + "' said its readiness is '" + said
                        + "'; it is 'ready', 'loadable' or 'empty'");
            };
        }

        @Override
        public Flight flight(NumenPlayer body, ItemStack stack) {
            return flight;
        }

        @Override
        public Handling handling(NumenPlayer body, ItemStack stack) {
            return handling;
        }

        @Override
        public Shot begin(NumenPlayer body, ItemStack stack) {
            return new ScriptShot(this, body);
        }

        @Override
        public String misfireText() {
            return "the " + name + " did not fire";
        }
    }

    /** 一发:准备到能射、对准、射(冷却时等着),脚本的回调抛了或射不出去按哑火收场。 */
    private static final class ScriptShot implements RangedWeapon.Shot {

        /** 准备了这么多刻还是没法射,算这一发哑火。 */
        private static final int PREPARE_TIMEOUT_TICKS = 200;
        /** 空了(没弹药)这么多刻没有起色,算这一发哑火。 */
        private static final int EMPTY_TICKS = 4;

        private final Provider weapon;
        private final NumenPlayer body;
        private int waited;
        private boolean fired;

        ScriptShot(Provider weapon, NumenPlayer body) {
            this.weapon = weapon;
            this.body = body;
        }

        @Override
        public Flight flight() {
            return weapon.flight;
        }

        @Override
        public boolean aboutToRelease() {
            return ready(body.getMainHandItem()) == RangedWeapon.Readiness.READY;
        }

        private RangedWeapon.Readiness ready(ItemStack stack) {
            return weapon.readiness(body, stack);
        }

        @Override
        public boolean tick(Aim aim, Entity target) {
            ItemStack stack = body.getMainHandItem();
            try {
                switch (ready(stack)) {
                    case EMPTY -> {
                        return ++waited >= EMPTY_TICKS;
                    }
                    case LOADABLE -> {
                        weapon.prepare.run(body, stack);
                        return ++waited >= PREPARE_TIMEOUT_TICKS;
                    }
                    case READY -> {
                        if (!aim.aligned(body.getViewVector(1.0f))) {
                            return ++waited >= PREPARE_TIMEOUT_TICKS;
                        }
                        Object result = weapon.fire.run(body, stack, target, new Facing(body.getXRot(), body.getYRot()));
                        if ("wait".equals(result)) {
                            return ++waited >= PREPARE_TIMEOUT_TICKS;
                        }
                        fired = Boolean.TRUE.equals(result);
                        return true;
                    }
                }
            } catch (RuntimeException error) {
                // 脚本写错了:记进 KubeJS 控制台,这一发按哑火收场,连着几发都不成攻击任务自会说这把武器不顶用
                ScriptErrors.log("the ranged weapon '" + weapon.name + "'", error);
                return true;
            }
            return false;
        }

        @Override
        public boolean fired() {
            return fired;
        }

        @Override
        public void abort() {
        }
    }
}
