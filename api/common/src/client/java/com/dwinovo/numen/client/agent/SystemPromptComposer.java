package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.prompt.NumenPrompts;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.platform.Services;

/**
 * 系统提示:只放会话内稳定的那几层——人设、操作核心、技能表、本能名册、延迟工具目录、说话规则——
 * 好让它成为字节级稳定的缓存前缀。会变的东西不在这里:背包、效果、当前任务挂在每一轮的
 * {@link RuntimeState} 里,随放置而变的工作站坐标随注入的 user 消息进历史。
 */
final class SystemPromptComposer {

    private SystemPromptComposer() {}

    /**
     * @param personaText 这只同伴绑定的人设正文;没绑或条目没了为 {@code null}(退到全局配置,再退到内置默认人设)
     */
    static String compose(String personaText) {
        // 人设层:同伴绑的人设 → 全局配置的人设 → 内置默认人设。空着的槽会让她退回通用助手的腔调,
        // 所以最后一档是一个具体的性格,不是"自由发挥"。
        String base = (personaText != null && !personaText.isBlank())
                ? personaText : Services.CONFIG.getSystemPrompt();
        if (base == null || base.isBlank()) base = NumenPrompts.DEFAULT_PERSONA;
        String skillsXml = SkillRegistry.instance().formatXml();

        // 系统提示只放会话内稳定的层——人设/操作核心/技能表/情绪词表。
        // 会变化的 <known_blocks> 随注入的 user 消息进历史(见 EntityAgentLoop 的 injectionPreamble),
        // 让这里成为字节级稳定的缓存前缀。
        StringBuilder sb = new StringBuilder();
        // Persona = the mutable "who you are" layer, wrapped so it's clearly delimited from the
        // immutable operating core (NumenPrompts.ENTITY_PROMPT) that follows.
        sb.append("<persona>\n").append(base.strip()).append("\n</persona>");
        sb.append(NumenPrompts.ENTITY_PROMPT);
        if (!skillsXml.isEmpty()) {
            sb.append("\n\n").append(skillsXml);
        }
        // 本能名册:她得知道身体会自己做哪些事,不然既可能重复去做,也可能对"我怎么突然挪了二十格"
        // 毫无头绪。名册是纯注册表内容、两端都注册,所以这里本地就算得出来,不需要任何网络。
        // 模型只从这里读到它——工具描述里不再另带一份。
        String reflexes = com.dwinovo.numen.task.reflex.ReflexRegistry.overview();
        if (!reflexes.isEmpty()) {
            sb.append("\n\n<instincts>\n").append(reflexes).append("\n</instincts>");
        }
        // 延迟工具目录。它随注册表变(接了 MCP server 会多出几行),但不随回合变,
        // 所以仍然待得住这个稳定层——与技能表、本能名册同一档。
        String catalogue = com.dwinovo.numen.agent.tool.ToolDisclosure
                .catalog(ToolRegistry.deferred());
        if (!catalogue.isEmpty()) {
            sb.append("\n\n").append(catalogue);
        }
        // 怎么说话压在最末尾:长度与语气离生成位置越近,越不容易在长对话里被冲淡(见 NumenPrompts)
        sb.append(NumenPrompts.SPEAKING);
        return sb.toString();
    }
}
