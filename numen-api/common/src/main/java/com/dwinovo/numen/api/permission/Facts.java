package com.dwinovo.numen.api.permission;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;

/**
 * 回答者读世界的口。
 *
 * @param view   方块视图:主线程是活世界(只读已加载),搜索线程是冻结快照
 * @param live   只在主线程非空——方块实体内容这类活读只从它读;搜索线程拿到 null,回答按保守值
 * @param actor  要动手的同伴是谁、她的名字、她的主人与主人名下别的同伴;测试可传 {@link Actor#NOBODY}
 * @param owners 她身边一片的方块归属,取这份裁决快照时问回答者们抄下的({@link Owners#around},任何线程可读)
 */
public record Facts(BlockGetter view, ServerLevel live, Actor actor, Owners.Blocks owners) {

    /** 同一份事实,但读不到活世界:搜索线程按保守值回答的那一份。 */
    public Facts withoutLive() {
        return new Facts(view, null, actor, owners);
    }
}
