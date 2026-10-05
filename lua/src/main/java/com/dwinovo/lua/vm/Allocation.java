package com.dwinovo.lua.vm;

/**
 * Numen:字符串分配记账。上游 LuaJ 里字符串操作(拼接、{@code string.rep}、格式化)是 Java 代码在干,不算虚拟机指令,指令预算管
 * 不住它们:{@code s = s .. s} 翻倍四十次就要上千 GB。所以每次为字符串开新的字节数组之前,先向当前线程上的计量器报账,超了由
 * 计量器抛出,数组根本不分配。
 *
 * <p>计量器按线程挂:每段脚本跑在它自己的线程上({@code com.dwinovo.lua.LuaSandbox}),所以记到的就是这段脚本的账;没有挂
 * 计量器的线程(编译、宿主自己造字符串)不记。
 */
public final class Allocation {

    /** 收账的一方。超了预算就抛出({@link Error} 的子类,脚本的 pcall 接不住)。 */
    public interface Meter {
        void charge(long bytes);
    }

    private static final ThreadLocal<Meter> CURRENT = new ThreadLocal<>();

    private Allocation() {}

    /** 当前线程从此记到 {@code meter} 的账上。 */
    public static void bind(Meter meter) {
        CURRENT.set(meter);
    }

    /** 当前线程不再记账。 */
    public static void unbind() {
        CURRENT.remove();
    }

    /** 要为字符串开 {@code bytes} 个字节了。 */
    public static void charge(long bytes) {
        Meter meter = CURRENT.get();
        if (meter != null) {
            meter.charge(bytes);
        }
    }
}
