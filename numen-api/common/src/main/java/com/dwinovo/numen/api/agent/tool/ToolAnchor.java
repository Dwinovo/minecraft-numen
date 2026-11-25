package com.dwinovo.numen.api.agent.tool;

import com.dwinovo.numen.api.agent.memory.NoteBook;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;

import java.util.UUID;

/**
 * 一次工具调用锚定的同伴,和她的大脑里工具要碰的几样东西:她的札记({@code memory} 工具)、她的技能({@code skill} 工具)、她向主人提的问题({@code AskUserQuestion} 工具)。这些是她的大脑的,
 * 由持有大脑的一方({@code LoopHost})给,工具不到任何静态的登记处去找。
 */
public interface ToolAnchor {

    /** 同伴的稳定 UUID。 */
    UUID entityUuid();

    /** 她的札记。 */
    NoteBook notes();

    /** 她的技能:自己的加随发布的。 */
    SkillRegistry skills();

    /** 她向主人提的问题挂在这里({@code AskUserQuestion} 工具)。 */
    QuestionDesk questions();

    /** 主人此刻是经聊天渠道在对她说话(不在游戏面板前)。 */
    boolean channelVisit();
}
