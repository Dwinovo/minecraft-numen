package com.dwinovo.numen.api.program;

import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.script.Modules;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 座位({@code Seat})把主人客户端的大脑送来的程序交给谁去跑:身体所在的服务端。一整段程序送去,结局({@link Program.Outcome})恰好
 * 交回一次;跑着的程序可以被叫停在调用之间、当场切断;身体上的活可以叫停。大脑那一头看到的是 {@link ProgramHost}(经座位说这几句话),
 * 这里是座位另一头的执行者。
 *
 * <p>线程:都在服务端主线程上。
 */
public interface ProgramRunner {

    /**
     * 跑一段程序。结局经 {@code done} 恰好交回一次。
     *
     * @param modules 这段程序能用的模块:内置的加她自己的(客户端报来的)
     */
    void run(UUID companion, String programId, String code, Modules modules, Consumer<Program.Outcome> done);

    /** 叫这段程序停在调用之间:{@code why} 是给模型的原因(主人开口、来了急件)。 */
    void interrupt(UUID companion, String programId, String why);

    /**
     * 当场停下这段程序。
     *
     * @param stopBody 身体活是不是一起叫停(叫停本身另走 {@link #stopBody}),回执照实说
     */
    void cutOff(UUID companion, String programId, boolean stopBody);

    /** 主人按停止:叫停她身体上在跑的活(程序另用 {@link #cutOff})。 */
    void stopBody(UUID companion);
}
