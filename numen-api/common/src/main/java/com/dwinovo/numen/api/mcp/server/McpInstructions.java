package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.agent.prompt.NumenPrompts;
import com.dwinovo.numen.api.agent.request.SystemPromptComposer;
import com.dwinovo.numen.api.agent.tool.MemoryTool;
import com.dwinovo.numen.api.agent.tool.SkillTool;
import com.dwinovo.numen.api.agent.tool.TodoTool;
import com.dwinovo.numen.api.script.Modules;
import com.dwinovo.numen.script.ScriptEngine;

/**
 * 外接大脑连上时读到的说明(两代线路的 {@code instructions} 字段):前半是外接这一侧特有的——怎么经这个端点驾驶、事件怎么来、
 * 话怎么说;后半是内置大脑系统提示里同一份操作核心与规矩({@link SystemPromptComposer#core}):身体怎么干活、API 索引、本能、
 * 札记、场面与说话的规则,一个字不另写。只有人设、技能表、札记索引这些因同伴而异的上下文不在其中(说明在连上时一次给出,
 * 那时还没有点名哪只同伴):技能与札记有对应的工具,人设见下面的说明。
 */
final class McpInstructions {

    private McpInstructions() {}

    static String build() {
        String lua = ScriptEngine.IN_USE.toolName();
        return """
                Numen companions are AI-controlled, player-like characters inside a live Minecraft game. Through this \
                server you are the brain of one (or several) of them: perceive, move, mine, build, craft, fight. While you \
                are connected the companion's built-in AI stands aside, and even the owner's in-game chat reaches you \
                (via get_events) instead of it. Below the line is the guide its built-in brain reads, word for word; the \
                "you" in it is the companion, and so is it you.

                How this server carries that guide:
                - Tools: list_companions, create_companion (by name) and delete_companion (for good) say who you can \
                drive. Every other tool takes a 'companion' argument (name or id), so each call targets one body; there is \
                no take-control step, and you can drive several companions in parallel.
                - The one tool for acting is %s: a program that calls the companion's API. Its receipt is what the guide \
                calls the program's receipt. The %s tool loads one of the companion's skills (a name it does not know \
                answers with the list of the skills it can use), the %s tool writes down your plan (the whole plan each \
                call; the owner sees it as a checklist), and the %s tool keeps the companion's own notes. Their index is not \
                sent to you here the way it is to the built-in brain: ask for a note by name with recall.
                - What the built-in brain gets at the end of every request you get at the end of every program receipt and \
                every get_events result: the companion's <runtime_state> (what she is doing, what she carries, her effects, \
                what is around her), as it is at the moment the result is made.
                - Events and the owner's words: call get_events(companion); it returns as soon as there is news and \
                otherwise waits up to %d seconds, so call it again to keep waiting. While you run programs, events that \
                arrive come back with the program's receipt, after the receipt text. Both are the very text the built-in \
                brain would be handed. The owner speaking to her arrives as a <query>.
                - Where the guide has her say something out loud or reply, you call say(companion, text): the words \
                appear in-game as her chat line, speech bubble and voice. Plain text you return to your own client is not \
                heard in the game.
                - Who she is: her bound persona is not sent to you. If the owner has not told you who to be, this is the \
                default one:
                %s
                - Keep your own conversation history; the game stores none for you, and events queue up until you take them.
                - Your programs go through the same permission layer as the companion's own: when it needs the owner's \
                approval, the owner is asked (not you), and the answer arrives as the program's outcome.

                ----

                """.formatted(lua, SkillTool.NAME, TodoTool.NAME, MemoryTool.NAME,
                ExternalBrain.MAX_WAIT_SECONDS, NumenPrompts.DEFAULT_PERSONA)
                + SystemPromptComposer.core(Modules.factory(), "");
    }
}
