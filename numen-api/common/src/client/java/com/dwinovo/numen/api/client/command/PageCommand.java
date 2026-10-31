package com.dwinovo.numen.api.client.command;

import net.minecraft.client.resources.language.I18n;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.client.agent.RemoteCompanion;

/**
 * 把主人带到她资料页某一节的命令({@code /memory})。和 {@link PopupCommand} 一样是多实现一个接口:命令自己只说"去哪一节",
 * 怎么翻页、怎么滚过去是面板的事;没有资料页的宿主(快捷对话)给不了,走 {@link #run} 说清楚得在聊天面板里用。
 */
public interface PageCommand extends ChatCommand {

    /** 资料页上能被命令带去的节。 */
    enum Page { MEMORY }

    /** 要打开的那一节。 */
    Page page();

    @Override
    default String run(RemoteCompanion loop, String args) {
        return I18n.get(Keys.CMD_POPUP_ONLY, ChatCommands.PREFIX + name());
    }
}
