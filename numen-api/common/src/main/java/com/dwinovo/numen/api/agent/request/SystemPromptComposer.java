package com.dwinovo.numen.api.agent.request;

import com.dwinovo.numen.api.agent.prompt.NumenPrompts;
import com.dwinovo.numen.api.seat.Catalog;
import com.dwinovo.numen.api.task.reflex.ReflexRegistry;
import com.dwinovo.numen.script.ScriptEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统提示:只放会话内稳定的那几层,好让它成为字节级稳定的缓存前缀。固定的部分在前(所有同伴一字不差),按情况拼的在后,按这个顺序:
 * 记忆的规矩、场面的规矩、人设(绑了才有)、技能表(有才有)、Lua API 的名字空间一行(有才有)、本能(有才有)。引擎只说通用的事,
 * 不点名任何插件的函数:有哪些插件只列名字,要用时她经 {@code ApiSearch} 读。会变的东西不在这里:背包、效果、当前任务挂在每一轮的
 * {@link RuntimeState} 里,她一写就变的记忆索引随注入的 user 消息进历史。
 *
 * <p>不碰客户端类:主人客户端上的大脑与评测组装的是同一份。
 */
public final class SystemPromptComposer {

    private SystemPromptComposer() {}

    /**
     * @param personaText 这只同伴绑定的人设正文;没绑或条目没了为 {@code null},这一节整段不出现
     * @param catalog     服务端的目录:Lua API 的名字空间、本能名册
     * @param skillList   她能用的技能的表({@code SkillRegistry#formatList});没有是空串
     */
    public static String compose(String personaText, Catalog catalog, String skillList) {
        List<String> sections = new ArrayList<>();
        sections.add(NumenPrompts.FIXED);
        sections.add(NumenPrompts.MEMORY);
        sections.add(NumenPrompts.CONVERSATION);
        if (personaText != null && !personaText.isBlank()) {
            sections.add(NumenPrompts.PERSONA_HEADING + "\n" + personaText.strip());
        }
        if (!skillList.isEmpty()) {
            sections.add(skillList);
        }
        if (!catalog.namespaces().isEmpty()) {
            sections.add("Lua API: " + String.join(", ", catalog.namespaces()) + ". Read one with " + ScriptEngine.DOCS_TOOL
                    + " before you use it.");
        }
        String instincts = ReflexRegistry.overview(catalog.instincts());
        if (!instincts.isEmpty()) {
            sections.add(instincts);
        }
        return String.join("\n\n", sections);
    }
}
