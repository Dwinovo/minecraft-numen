package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ToolBook;
import com.dwinovo.numen.api.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.mcp.client.RemoteMcpTool;

import java.util.List;
import java.util.UUID;

/**
 * 一位主人的同伴看得到的工具:全局的加上他自己接进来的 MCP 工具({@link ToolRegistry}),再按他的"接进来的 MCP 工具"那个库
 * 筛一遍——内置那几台服务器的工具,他关掉了、或他在"我的"里放了同名的盖过了,他的同伴就看不到。
 */
final class OwnerTools implements ToolBook {

    private final UUID owner;
    private final Library mcp;

    OwnerTools(UUID owner, Library mcp) {
        this.owner = owner;
        this.mcp = mcp;
    }

    @Override
    public List<NumenTool> all() {
        return ToolRegistry.all(owner).stream().filter(this::visible).toList();
    }

    @Override
    public NumenTool resolve(String name) {
        NumenTool tool = ToolRegistry.resolve(owner, name);
        return tool != null && visible(tool) ? tool : null;
    }

    private boolean visible(NumenTool tool) {
        if (!(tool instanceof RemoteMcpTool remote)) {
            return true;
        }
        Library.Entry entry = mcp.find(EntryId.parse(remote.entryId()));
        return entry != null && entry.group() == remote.group();
    }
}
