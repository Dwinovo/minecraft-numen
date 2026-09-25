package com.dwinovo.numen.cli;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.ArgumentType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Numen 自己的参数类型在 MC 指令参数类型注册表里的那一条(见 {@link NumenCli#registerArgumentTypes})。
 *
 * <p>这些类型只长在她的节点上,玩家收到的指令树按 {@code requires} 滤过,里面没有它们,所以它们从不真的发到哪个
 * 客户端:服务器给她造的那个包交给她的假连接就丢了。于是写出的那一侧什么都不用写,读回来的那一侧不该发生——
 * 真发生了就是有人把她的节点发给了玩家,当场说出来。
 */
final class HerArgumentInfo<A extends ArgumentType<?>> implements ArgumentTypeInfo<A, HerArgumentInfo<A>.Template> {

    @Override
    public void serializeToNetwork(Template template, FriendlyByteBuf buffer) {}

    @Override
    public Template deserializeFromNetwork(FriendlyByteBuf buffer) {
        throw new IllegalStateException("a Numen argument type reached a client: only her command nodes carry it, "
                + "and players never receive those");
    }

    @Override
    public void serializeToJson(Template template, JsonObject json) {}

    @Override
    public Template unpack(A argument) {
        return new Template(argument);
    }

    /** 就是那一个参数类型本身:它不经过网络,不需要拆成数据再装回来。 */
    final class Template implements ArgumentTypeInfo.Template<A> {

        private final A argument;

        Template(A argument) {
            this.argument = argument;
        }

        @Override
        public A instantiate(CommandBuildContext context) {
            return argument;
        }

        @Override
        public ArgumentTypeInfo<A, ?> type() {
            return HerArgumentInfo.this;
        }
    }
}
