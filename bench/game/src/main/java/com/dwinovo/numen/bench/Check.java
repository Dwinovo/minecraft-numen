package com.dwinovo.numen.bench;

import java.util.function.Consumer;

/**
 * 一条断言,在一次运行收场时对终态判一次。写法同 GameTest:不成立就经 {@link Scene#assertTrue} 抛出,消息说看到了什么。
 *
 * @param name 进记录的名字
 */
public record Check(String name, Kind kind, Consumer<Scene> assertion) {

    public enum Kind {
        /** 成功断言:全过才算成功。 */
        SUCCESS("success"),
        /** 负面断言:没碰不该碰的、没越权;有一条没过就不算成功。 */
        GUARD("guard"),
        /** 子目标:不决定成败,报告里算达成的比例。 */
        SUBGOAL("subgoal");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        /** 写进记录的名字。 */
        public String id() {
            return id;
        }
    }

    public static Check success(String name, Consumer<Scene> assertion) {
        return new Check(name, Kind.SUCCESS, assertion);
    }

    public static Check guard(String name, Consumer<Scene> assertion) {
        return new Check(name, Kind.GUARD, assertion);
    }

    public static Check subgoal(String name, Consumer<Scene> assertion) {
        return new Check(name, Kind.SUBGOAL, assertion);
    }
}
