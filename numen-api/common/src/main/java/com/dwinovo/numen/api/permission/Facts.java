package com.dwinovo.numen.api.permission;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;

/**
 * 对象组与属性读世界的口。
 *
 * @param view      方块视图:主线程是活世界(只读已加载),搜索线程是冻结快照
 * @param placed    这一维度的玩家放置记录(任何线程可读)
 * @param live      只在主线程非空——方块实体内容这类活读只从它读;搜索线程拿到 null,
 *                  对象组与属性按"不知道"的保守值回答(见各自的说明)
 * @param actor     要动手的同伴是谁、她的名字与主人(放置记号与驯服记录认人只看这个);测试可传 {@link Actor#NOBODY}
 * @param structures 世界生成时记下的结构件范围(任何线程可读)
 * @param unknownAsOther 说不清是谁的方块按别人的算(专用服务器默认如此,见 {@link PermissionConfig})
 */
public record Facts(BlockGetter view, PlacedBlocks placed, ServerLevel live, Actor actor, Structures structures,
                    boolean unknownAsOther) {

    /** 同一份事实,但读不到活世界:搜索线程按保守值回答的那一份。 */
    public Facts withoutLive() {
        return new Facts(view, placed, null, actor, structures, unknownAsOther);
    }
}
