package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.prompt.NumenPrompts;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.platform.Services;

/**
 * 系统提示:只放会话内稳定的那几层——人设、操作核心、技能表、命令索引、本能名册、札记的规矩、场面的规矩、说话规则——
 * 好让它成为字节级稳定的缓存前缀。会变的东西不在这里:背包、效果、当前任务挂在每一轮的
 * {@link RuntimeState} 里,她一写就变的札记索引随注入的 user 消息进历史。
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
        // 会变化的札记索引随注入的 user 消息进历史(见 EntityAgentLoop 的 injectionPreamble),
        // 让这里成为字节级稳定的缓存前缀。
        StringBuilder sb = new StringBuilder();
        // Persona = the mutable "who you are" layer, wrapped so it's clearly delimited from the
        // immutable operating core (NumenPrompts.ENTITY_PROMPT) that follows.
        sb.append("<persona>\n").append(base.strip()).append("\n</persona>");
        sb.append(NumenPrompts.ENTITY_PROMPT);
        if (!skillsXml.isEmpty()) {
            sb.append("\n\n").append(skillsXml);
        }
        // 命令索引:装了哪些命令组,一组一句,她不必先 numen help 就知道去哪找。只随组的增减变、按名字排好,
        // 和技能表一样是稳定前缀的一部分;各组的动作与语法只在帮助里。
        String commands = com.dwinovo.numen.cli.NumenCli.index();
        if (!commands.isEmpty()) {
            sb.append("\n\n").append(commands);
        }
        // 本能名册:她得知道身体会自己做哪些事,不然既可能重复去做,也可能对"我怎么突然挪了二十格"
        // 毫无头绪。名册是纯注册表内容、两端都注册,所以这里本地就算得出来,不需要任何网络。
        // 模型只从这里读到它——工具描述里不再另带一份。
        String reflexes = com.dwinovo.numen.task.reflex.ReflexRegistry.overview();
        if (!reflexes.isEmpty()) {
            sb.append("\n\n<instincts>\n").append(reflexes).append("\n</instincts>");
        }
        // 札记的规矩:她有记忆这件事、什么值得记。规矩不变所以在前缀里,内容会变所以在注入块里。
        sb.append(NumenPrompts.MEMORY);
        // 场面的规矩:<audience> 是什么、旁听到的话是什么、她的话叫不醒别人。谁在场随每句话注入。
        sb.append(NumenPrompts.CONVERSATION);
        // 怎么说话压在最末尾:长度与语气离生成位置越近,越不容易在长对话里被冲淡(见 NumenPrompts)
        sb.append(NumenPrompts.SPEAKING);
        return sb.toString();
    }
}
