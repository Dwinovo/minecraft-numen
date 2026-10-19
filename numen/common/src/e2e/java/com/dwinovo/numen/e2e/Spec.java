package com.dwinovo.numen.e2e;

/**
 * 场景对服务端的全部认识:一个地址、一个 RCON 口、一个本地假模型端点要听的端口,以及种子里那份档案叫什么。
 * 值出自 e2e/build.gradle 的规格(Gradle 把它们交成系统属性),起服的一层也读同一份,所以这里没有任何一个字面量端口。
 */
record Spec(String host, int port, int rconPort, String rconPassword, int modelPort, String modelKey,
            String modelProfile) {

    static Spec fromProperties() {
        return new Spec(property("e2e.host"), Integer.parseInt(property("e2e.port")),
                Integer.parseInt(property("e2e.rcon.port")), property("e2e.rcon.password"),
                Integer.parseInt(property("e2e.model.port")), property("e2e.model.key"),
                property("e2e.model.profile"));
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        if (value == null) {
            throw new IllegalStateException("缺系统属性 " + name + ":端到端测试要经 :e2e:e2eTest 跑,由它交规格");
        }
        return value;
    }
}
