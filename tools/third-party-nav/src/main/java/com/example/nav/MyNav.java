package com.example.nav;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.task.TaskFactory;

import net.neoforged.fml.common.Mod;

/**
 * 第三方的寻路:登记一个函数让模型调用({@code mynav.nav.go}),函数起一件活({@link GoTask}),活每刻按她的键盘、转她的
 * 视角让她走,结局如实交回。只经 Numen API 公开的那扇门。
 */
@Mod(MyNav.ID)
public final class MyNav {

    public static final String ID = "mynav";

    public MyNav() {
        NumenPlugins.register(ID, numen -> {
            numen.api("nav", "A minimal pathfinder: walk straight at a block.", NavApi.class);
            TaskFactory.register(GoRecord.class, (her, record) -> new GoTask(record));
        });
    }
}
