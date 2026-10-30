package com.dwinovo.numen.api.combat;

/**
 * 用这件武器打的站位:攻击任务让她站在离目标 {@code innerRing} 到 {@code outerRing} 格之间(走位的环),目标超过 {@code maxRange} 格就不算
 * 瞄得到。弓是 8 到 12 格;枪可以站得更远。
 */
public record Handling(double innerRing, double outerRing, double maxRange) {

    public Handling {
        if (innerRing < 0 || outerRing < innerRing || maxRange < outerRing) {
            throw new IllegalArgumentException("a ranged weapon's handling needs 0 <= innerRing <= outerRing <= maxRange, got "
                    + innerRing + ", " + outerRing + ", " + maxRange);
        }
    }
}
