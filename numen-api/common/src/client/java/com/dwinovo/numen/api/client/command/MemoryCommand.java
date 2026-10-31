package com.dwinovo.numen.api.client.command;

import net.minecraft.client.resources.language.I18n;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;

/** {@code /memory} —— 打开她资料页的记忆一节:看全部札记,新增、修改、删除。 */
final class MemoryCommand implements PageCommand {

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return I18n.get(Keys.CMD_MEMORY);
    }

    @Override
    public boolean touchesContext() {
        return true;
    }

    @Override
    public Page page() {
        return Page.MEMORY;
    }
}
