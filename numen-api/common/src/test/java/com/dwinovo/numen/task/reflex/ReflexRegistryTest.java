package com.dwinovo.numen.task.reflex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本能名册(宪法 §6):每条自主机制在 init 报到一次,换来一件事——<b>自述进提示词</b>。
 *
 * <p><b>名册即全部</b>:登记了就是开着的,没有"每条本能的开关"。要做开关,先做主人能按
 * 的面板入口,再让名册长出开关——只有开关没有入口的话,每次启动读一个文件、写一个文件,
 * 里面的值永远全是 true。看着有、实际没有,比明说没做更难查。
 */
class ReflexRegistryTest {

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        ReflexRegistry.resetForTest();   // 静态名册:别把注册泄漏进别的用例
    }

    @Test
    void registrationIsIdempotentById() {
        ReflexRegistry.register(new PolicyReflex("food", "饿了会自己吃东西"));
        ReflexRegistry.register(new PolicyReflex("food", "第二次注册的自述(应被忽略)"));
        assertTrue(ReflexRegistry.overview().contains("饿了会自己吃东西"));
        assertFalse(ReflexRegistry.overview().contains("应被忽略"));
    }

    @Test
    void overviewJoinsDescriptionsInRegistrationOrder() {
        ReflexRegistry.register(new PolicyReflex("mlg", "会用水桶自救高坠"));
        ReflexRegistry.register(new PolicyReflex("armor", "会自动穿上更好的盔甲"));

        String overview = ReflexRegistry.overview();

        assertTrue(overview.startsWith("你的身体有这些本能"));
        int mlg = overview.indexOf("会用水桶自救高坠");
        int armor = overview.indexOf("会自动穿上更好的盔甲");
        assertTrue(mlg >= 0 && armor > mlg, "顺序该是报到顺序:" + overview);
        assertTrue(overview.contains("你的显式动作永远优先"));
    }

    @Test
    void everyRegisteredReflexShowsUp() {
        // 名册即全部——没有"开着的才算"这回事了
        ReflexRegistry.register(new PolicyReflex("mlg", "会用水桶自救高坠"));
        ReflexRegistry.register(new PolicyReflex("armor", "会自动穿上更好的盔甲"));

        String overview = ReflexRegistry.overview();

        assertTrue(overview.contains("会用水桶自救高坠"));
        assertTrue(overview.contains("会自动穿上更好的盔甲"));
    }

    @Test
    void emptyRosterHasEmptyOverview() {
        assertEquals("", ReflexRegistry.overview());
    }
}
