package com.dwinovo.numen.gametest;

import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.network.chat.Component;

/**
 * 用例里抛出的失败:只带一句话,不带"在第几刻"。{@code helper.assertTrue} 在用例里按当前刻报告;这里用在没有
 * {@code helper} 的地方(步骤、轮询的辅助类),给不出那个刻,就不给一个假的。
 */
public final class TestFailure extends GameTestAssertException {

    public TestFailure(String message) {
        super(Component.literal(message), 0);
    }

    @Override
    public Component getDescription() {
        return this.message;
    }
}
