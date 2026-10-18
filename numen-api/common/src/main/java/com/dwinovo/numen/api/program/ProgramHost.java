package com.dwinovo.numen.api.program;

import com.dwinovo.numen.api.agent.loop.Program;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 工具口({@code CompanionToolPort})把她的 Lua 程序交给谁去跑:一整段程序送去,结局({@link Program.Outcome})恰好交回一次;
 * 跑着的程序可以被叫停在调用之间、当场切断;身体上的活可以叫停。服务端大脑直接交给本进程里的 {@link ServerPrograms}
 * ({@link LocalPrograms})。
 */
public interface ProgramHost {

    /**
     * 跑一段程序。答复经 {@code done} 恰好交回一次。
     *
     * @param programId 这段程序的编号,调用方保证不重
     */
    void run(UUID companion, String programId, String code, Consumer<Program.Outcome> done);

    /**
     * 这段程序所在的那一批作废了(切断),但程序照常停下、交出回执:晚到的结果交给 {@code late},而不是原来等的那一方。
     * 已经回来或根本没送出的,不做什么。
     */
    void afterwards(String programId, Consumer<Program.Outcome> late);

    /** 不再等这段程序的答复:晚到的答复不理。 */
    void forget(String programId);

    /** 叫这段程序停在调用之间:{@code why} 是给模型的原因(主人开口、来了急件)。 */
    void interrupt(UUID companion, String programId, String why);

    /**
     * 这一轮被切断,叫这段程序当场停下。
     *
     * @param stopBody 身体活是不是一起叫停(叫停本身另走 {@link #stopBody}),回执照实说
     */
    void cutOff(UUID companion, String programId, boolean stopBody);

    /** 主人按停止:叫停她身体上在跑的活(程序另用 {@link #cutOff})。 */
    void stopBody(UUID companion);
}
