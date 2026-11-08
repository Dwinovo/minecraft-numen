package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import java.util.function.IntFunction;

import com.dwinovo.numen.api.entity.BodyCompat;
import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.pathing.world.Kinematics;
import com.dwinovo.numen.pathing.world.Kinematics.Keys;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * 空中的身体:键盘补上的那几段(双击跳起飞、飞着升降、落地停飞、空中按跳展开鞘翅)在游戏里能不能用,以及真身体飞、滑翔时
 * 与 {@link Kinematics} 的逐刻步进对不对得上——同一串输入喂给真身体,每刻对照步进器的预测,误差上限 {@link #LIMIT}。
 * 用的是普通假玩家({@link TestBody})与同一副键盘,不过宿主。
 */
public class AirGameTests {

    private static final String BATCH = "pathing_air";
    /** 逐刻对照的误差上限(格,格每刻):同一套公式在同一份 double 上走,差在舍入之内。 */
    private static final double LIMIT = 1.0E-9;

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    // ==================== 键盘 ====================

    /** 许飞的身体站在地上:连按两下跳起飞(同时起跳),按住跳往上升,按住潜行往下降,落了地就停飞。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 300)
    public static void a_double_tap_of_jump_takes_off_and_landing_ends_the_flight(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(5, 1, 5);
        body.setGameMode(GameType.CREATIVE);
        double groundY = body.getY();
        int[] age = {0};
        helper.onEachTick(() -> {
            int i = age[0]++;
            if (i < 5) {
                return;
            }
            int k = i - 5;
            switch (k) {
                case 0 -> body.controls().press(Key.JUMP);
                case 1 -> body.controls().release(Key.JUMP);
                case 2 -> body.controls().press(Key.JUMP);
                case 3 -> {
                    if (!body.getAbilities().flying) {
                        throw new GameTestAssertException("连按两下跳没有起飞");
                    }
                }
                case 12 -> {
                    if (body.getY() < groundY + 2) {
                        throw new GameTestAssertException("飞着按住跳,十来刻该升到两格以上,脚在 " + (body.getY() - groundY));
                    }
                    body.controls().release(Key.JUMP);
                    body.controls().press(Key.SNEAK);
                }
                default -> {
                    if (k > 12 && body.onGround()) {
                        body.controls().releaseAll();
                        if (body.getAbilities().flying) {
                            throw new GameTestAssertException("落了地还在飞");
                        }
                        body.leave();
                        helper.succeed();
                    }
                }
            }
        });
    }

    /** 只按一下跳:是起跳,不是起飞;两下隔得太久(过了 7 刻)也不算双击。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void a_single_jump_or_two_far_apart_do_not_take_off(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(5, 1, 5);
        body.setGameMode(GameType.CREATIVE);
        int[] age = {0};
        helper.onEachTick(() -> {
            int i = age[0]++;
            int k = i - 5;
            if (k == 0 || k == 30) {
                body.controls().press(Key.JUMP);
            } else if (k == 1 || k == 31) {
                body.controls().release(Key.JUMP);
            } else if (k == 32) {
                if (body.getAbilities().flying) {
                    throw new GameTestAssertException("隔了三十刻的两下跳也飞起来了");
                }
            } else if (k == 100) {
                body.leave();
                helper.succeed();
            }
        });
    }

    /** 生存模式连按两下跳不会飞:许不许飞由原版服务端照能力判。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void a_body_not_allowed_to_fly_does_not_when_it_taps_twice(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(5, 1, 5);
        int[] age = {0};
        helper.onEachTick(() -> {
            int k = age[0]++ - 5;
            if (k == 0 || k == 2) {
                body.controls().press(Key.JUMP);
            } else if (k == 1 || k == 3) {
                body.controls().release(Key.JUMP);
            } else if (k == 6) {
                if (body.getAbilities().flying) {
                    throw new GameTestAssertException("生存模式的身体飞起来了");
                }
                body.leave();
                helper.succeed();
            }
        });
    }

    // ==================== 逐刻对照 ====================

    /**
     * 一串输入喂给真身体,每刻与步进器对照。{@code script} 给第 {@code i} 刻按的键(偏航、俯仰也在里面);每刻开头把键按上、身体转到那个朝向,
     * 用身体此刻的状态预测下一刻,身体走完这一刻再对;{@code length} 刻之后收场。
     */
    private static final class Rehearsal {
        private final TestBody body;
        private final IntFunction<Keys> script;
        private final boolean gliding;
        private final int length;
        private final Runnable done;
        private Motion predicted;
        private int tick;
        private double worst;

        Rehearsal(TestBody body, IntFunction<Keys> script, boolean gliding, int length, Runnable done) {
            this.body = body;
            this.script = script;
            this.gliding = gliding;
            this.length = length;
            this.done = done;
        }

        void tick() {
            if (predicted != null) {
                double position = predicted.pos().distanceTo(body.position());
                double velocity = predicted.velocity().distanceTo(body.getDeltaMovement());
                worst = Math.max(worst, Math.max(position, velocity));
                if (position > LIMIT || velocity > LIMIT) {
                    throw new GameTestAssertException("第 " + tick + " 刻与步进器对不上:预测 " + predicted + ",真身体 "
                            + new Motion(body.position(), body.getDeltaMovement()) + ",位置差 " + position + ",速度差 " + velocity);
                }
            }
            if (tick == length) {
                done.run();
                return;
            }
            Keys keys = script.apply(tick++);
            body.setYRot(keys.yaw());
            body.setXRot(keys.pitch());
            body.controls().set(Key.FORWARD, keys.forward() > 0);
            body.controls().set(Key.BACK, keys.forward() < 0);
            body.controls().set(Key.LEFT, keys.left() > 0);
            body.controls().set(Key.RIGHT, keys.left() < 0);
            body.controls().set(Key.JUMP, keys.jump());
            body.controls().set(Key.SNEAK, keys.sneak());
            body.controls().set(Key.SPRINT, keys.sprint());
            Motion now = new Motion(body.position(), body.getDeltaMovement());
            predicted = gliding ? Kinematics.glide(now, keys, BodyCompat.gravity(body))
                    : Kinematics.fly(now, keys, body.getAbilities().getFlyingSpeed());
        }

        double worst() {
            return worst;
        }
    }

    /**
     * 创造飞行逐刻对照:疾飞、边升边飞、边降边横移、后退刹车、松开滑行——每一种键的组合真身体与步进器一刻一刻对得上。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void creative_flight_follows_the_stepper_tick_by_tick(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(3, 12, 20);
        body.setGameMode(GameType.CREATIVE);
        IntFunction<Keys> script = i -> {
            if (i < 10) {
                return new Keys(1, 0, false, false, true, -90, 0, false);
            } else if (i < 15) {
                return new Keys(1, 0, true, false, false, -90, 0, false);
            } else if (i < 20) {
                return new Keys(0, 1, false, true, false, 0, 0, false);
            } else if (i < 25) {
                return new Keys(-1, 0, false, false, false, 0, 0, false);
            }
            return Keys.idle(0, 0);
        };
        int[] age = {0};
        Rehearsal[] rehearsal = {null};
        helper.onEachTick(() -> {
            int i = age[0]++;
            if (i == 0) {
                body.controls().press(Key.JUMP);
            } else if (i == 1) {
                body.controls().release(Key.JUMP);
            } else if (i == 2) {
                body.controls().press(Key.JUMP);
            } else if (i == 3) {
                body.controls().release(Key.JUMP);
            } else if (i == 5) {
                if (!body.getAbilities().flying) {
                    throw new GameTestAssertException("没有飞起来");
                }
                rehearsal[0] = new Rehearsal(body, script, false, 40, () -> {
                    body.leave();
                    helper.succeed();
                });
            }
            if (rehearsal[0] != null) {
                rehearsal[0].tick();
            }
        });
    }

    /** 鞘翅滑翔逐刻对照:空中按一下跳展开,平飞、俯冲、拉起、转向,每一刻真身体与步进器对得上。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void gliding_follows_the_stepper_tick_by_tick(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(3, 22, 20);
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        IntFunction<Keys> script = i -> {
            if (i < 8) {
                return Keys.idle(-90, 5);
            } else if (i < 14) {
                return Keys.idle(-90, 40);
            } else if (i < 20) {
                return Keys.idle(-90, -35);
            }
            return Keys.idle(-45, 0);
        };
        int[] age = {0};
        Rehearsal[] rehearsal = {null};
        helper.onEachTick(() -> {
            int i = age[0]++;
            if (i == 0) {
                body.controls().press(Key.JUMP);
            } else if (i == 1) {
                body.controls().release(Key.JUMP);
            } else if (i == 3) {
                if (!body.isFallFlying()) {
                    throw new GameTestAssertException("空中按跳没有展开鞘翅");
                }
                rehearsal[0] = new Rehearsal(body, script, true, 24, () -> {
                    body.leave();
                    helper.succeed();
                });
            }
            if (rehearsal[0] != null) {
                rehearsal[0].tick();
            }
        });
    }

    /** 滑翔时放烟花:烟花挂在身上的每一刻,真身体与步进器(滑翔之后加推力)对得上;烟花烧完之后回到纯滑翔。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 300)
    public static void a_rocket_boost_follows_the_stepper_tick_by_tick(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(3, 12, 3);
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        FireworkRocketEntity[] rocket = {null};
        IntFunction<Keys> script = i -> new Keys(0, 0, false, false, false, -45, 0, rocket[0] != null && !rocket[0].isRemoved());
        int[] age = {0};
        Rehearsal[] rehearsal = {null};
        helper.onEachTick(() -> {
            int i = age[0]++;
            if (i == 0) {
                body.controls().press(Key.JUMP);
            } else if (i == 1) {
                body.controls().release(Key.JUMP);
            } else if (i == 3) {
                if (!body.isFallFlying()) {
                    throw new GameTestAssertException("空中按跳没有展开鞘翅");
                }
                body.setYRot(-45);
                body.setXRot(0);
                rocket[0] = new FireworkRocketEntity(helper.getLevel(), new ItemStack(Items.FIREWORK_ROCKET), body);
                helper.getLevel().addFreshEntity(rocket[0]);
                rehearsal[0] = new Rehearsal(body, script, true, 20, () -> {
                    body.leave();
                    helper.succeed();
                });
            }
            if (rehearsal[0] != null) {
                rehearsal[0].tick();
            }
        });
    }
}
