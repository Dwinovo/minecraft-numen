package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.agent.tool.MemoryTool;
import com.dwinovo.numen.api.agent.tool.SkillTool;
import com.dwinovo.numen.api.agent.tool.TodoTool;
import com.dwinovo.numen.script.ScriptEngine;

/**
 * 外接大脑连上时读到的说明(MCP 的 {@code instructions}):只放外接这一侧特有的——怎么经这个端点驾驶、事件与状态怎么来、话用
 * {@code say} 说——并让它开始前先取一次 {@code guide}。操作核心、API 索引、人设、技能表与各种规矩不在这里:它们在内置大脑的
 * 系统提示里({@code SystemPromptComposer},每只同伴不同),{@code guide} 工具把同一份原样交出来。
 *
 * <p>长度压在 {@value #MAX_LENGTH} 字符以内:Claude Code 等客户端把服务器说明截在 4096 字符,超出的会被丢掉。
 */
final class McpInstructions {

    /** 客户端截说明的长度;单测守着不超。 */
    static final int MAX_LENGTH = 4096;

    private McpInstructions() {}

    static String build() {
        return """
                Numen companions are AI-controlled, player-like characters inside a live Minecraft game. Through this \
                server you are the brain of one (or several) of them: while you are connected its built-in AI stands \
                aside, and even the owner's in-game chat reaches you (via get_events) instead of it.

                Start here: call guide(companion) once and keep what it returns. It is the very text the companion's own \
                brain starts every request with: its persona, how its body gets things done, the API index, its skills, \
                its instincts and the rules for notes and for speaking. Where it says "you", it means the companion, and \
                so it means you.

                How this server carries that guide:
                - Tools: list_companions says who you can drive (create_companion summons one by name, delete_companion \
                dismisses one for good). Every other tool takes a 'companion' argument (name or id), so each call targets \
                one body; you can drive several in parallel, and there is no take-control step.
                - Acting: the one tool for acting is %s, a program that calls the companion's API; its receipt is what the \
                guide calls the program's receipt. The %s tool loads one of the companion's skills, the %s tool writes \
                down your plan, and the %s tool keeps the companion's own notes (recall with no name lists them).
                - What the built-in brain gets at the end of every request you get at the end of every program receipt \
                and every get_events result: the companion's <runtime_state> (what it is doing, what it carries, its \
                effects, what is around it), as it is at that moment.
                - Events and the owner's words: call get_events(companion); it returns as soon as there is news and \
                otherwise waits up to %d seconds, so call it again to keep waiting. Events that arrive while you run \
                programs come back with the program's receipt, after the receipt text. The owner speaking to the \
                companion arrives as a <query>.
                - Speaking: where the guide has the companion say or reply, call say(companion, text); the words appear \
                in-game as its chat line, speech bubble and voice. Plain text you return to your own client is not heard \
                in the game.
                - Permission: your programs go through the same permission layer as the companion's own. When it needs \
                the owner's approval the owner is asked (not you), and the answer arrives as the program's outcome.
                - Keep your own conversation history; the game stores none for you, and events queue up until you take \
                them.""".formatted(ScriptEngine.IN_USE.toolName(), SkillTool.NAME, TodoTool.NAME, MemoryTool.NAME,
                ExternalBrain.MAX_WAIT_SECONDS);
    }
}
