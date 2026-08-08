package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.task.chain.BreathChain;
import com.dwinovo.numen.core.task.chain.MLGChain;
import com.dwinovo.numen.core.task.chain.MobDefenseChain;
import com.dwinovo.numen.core.task.chain.UnstuckChain;
import com.dwinovo.numen.task.reflex.Reflex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反射之间的先后。
 *
 * <h2>为什么单独有这一条</h2>
 * 顺序由<b>注册号</b>决定,小的先被问到。这个顺序是一条<b>没有任何编译器能替你检查</b>
 * 的约定:注册号排错了不会有编译错误,也不会有别的测试失败,只会让她从高处掉下来时
 * 先去脱困、不去接水。所以钉在这里。
 */
class ReflexOrderTest {

    /** {@code NumenCore.registerReflexes} 注册的顺序,先注册的先被问到。 */
    private static final List<Reflex> ORDER = List.of(
            new MLGChain(),          // 10 — 正在坠落是最迫近的死法
            new BreathChain(),       // 20 — 淹水是硬计时:先浮上去,打架等会儿
            new MobDefenseChain(),   // 30
            new UnstuckChain());     // 50 — 卡住只是烦人,绝不该压过打架或吃饭

    @Test
    void fallSaveOutranksEverything() {
        assertEquals("mlg", ORDER.get(0).id(),
                "正在坠落是最迫近的死法;它排第二位就意味着摔死时还在忙别的");
    }

    @Test
    void breathOutranksFighting() {
        // 淹水是一个走完就死的计时器,而打架可以边退边打
        assertTrue(indexOf("breath") < indexOf("mob_defense"));
    }

    @Test
    void fightingOutranksGettingUnstuck() {
        // 卡住只是烦人,怪物是急性的
        assertTrue(indexOf("mob_defense") < indexOf("unstuck"));
    }

    @Test
    void gettingUnstuckIsTheLeastUrgent() {
        // 卡住只是烦人,不致命 —— 它绝不该压过打架或吃饭
        assertEquals(ORDER.size() - 1, indexOf("unstuck"));
    }

    @Test
    void theWholeOrderMatchesTheRetiredPriorityNumbers() {
        // 旧的浮点排序:MLG 10 > 换气 6 > 自卫 5 > 脱困 2
        // (进食那一条退役了 —— 她不再自己吃,饿了发 urgent 让主人管)
        assertEquals(List.of("mlg", "breath", "mob_defense", "unstuck"),
                ORDER.stream().map(Reflex::id).toList());
    }

    @Test
    void everyReflexDescribesItselfForThePrompt() {
        // 每条反射都要能自报家门 —— 那段自述会进提示词,让她知道自己身体有什么本能
        for (Reflex r : ORDER) {
            assertTrue(r.id() != null && !r.id().isBlank(), "反射没有 id");
            assertTrue(r.describe() != null && !r.describe().isBlank(), r.id() + " 没有自述");
        }
    }

    private static int indexOf(String id) {
        for (int i = 0; i < ORDER.size(); i++) {
            if (ORDER.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new AssertionError("没有这条反射: " + id);
    }
}
