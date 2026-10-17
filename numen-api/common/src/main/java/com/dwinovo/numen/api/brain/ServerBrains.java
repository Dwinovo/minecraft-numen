package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.NumenPaths;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.memory.NoteBook;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.mcp.client.McpClientManager;
import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.api.mcp.server.ServerMcp;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.script.Modules;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 服务器起来时同伴的大脑在服务端开工要备好的东西:札记与 Lua 模块的落点、技能、型号目录、接进来的 MCP 工具、
 * 外接大脑宿主,然后名册上每只同伴(主人在不在线都一样)一个大脑。服务器停时这些放手。
 * 谁在这里登记,谁就不必在两个加载器里各写一遍。
 */
public final class ServerBrains {

    private ServerBrains() {}

    /** 由 {@code CommonClass.init} 调一次。 */
    public static void register() {
        ServerLifecycle.onStarted(ServerBrains::start);
        ServerLifecycle.onStopped(ServerBrains::stop);
    }

    static void start(MinecraftServer server) {
        Homes homes = Homes.of(server);
        CompanionRegistry registry = CompanionRegistry.get(server);
        // 她的札记在世界里她的家下,模块按主人放在服务器上他的目录下
        NoteBook.init(uuid -> {
            Home home = homes.companion(uuid);
            return new Home(home.dir().resolve("memory"), home.io());
        }, () -> (int) (server.overworld().getDayTime() / 24000L));
        Modules.init(companion -> {
            CompanionRegistry.Entry entry = registry.find(companion);
            if (entry == null) {
                throw new IllegalStateException("没有这只同伴: " + companion);
            }
            return homes.owner(entry.owner()).dir().resolve("lua");
        });
        // 技能是服务器全局的:插件随包的加上 config/numen/skills 下的
        NumenPlugins.bindSkills(root -> SkillRegistry.instance().declareBundled(root));
        SkillRegistry.instance().scan(NumenPaths.config().resolve("skills"));
        ProviderLibrary.instance().refreshCatalog();
        migrateHost(server, homes, registry);
        McpClientManager.init(NumenPaths.config(), server, () -> Replies.share(server, null, Libraries.MCP_CLIENT));
        ServerMcp.start(server);
        for (UUID companion : registry.all()) {
            try {
                CompanionBrains.ensure(server, companion);
            } catch (RuntimeException ex) {
                Constants.LOG.error("[numen] 同伴 {} 的大脑没能起来,跳过", companion, ex);
            }
        }
    }

    private static void stop() {
        McpClientManager.shutdown();
        McpMode.instance().shutdown();
    }

    /**
     * 单人游戏的房主在旧版客户端留下的、归他自己的东西:人设库、Lua 模块、会话成员表。只做一次,复制不移动
     * ({@link LegacyMigration})。同伴各自的家在 {@link CompanionBrains#ensure} 里搬。
     */
    private static void migrateHost(MinecraftServer server, Homes homes, CompanionRegistry registry) {
        var profile = server.getSingleplayerProfile();
        if (profile == null) {
            return;
        }
        UUID host = profile.getId();
        Path legacy = NumenPaths.config();
        LegacyMigration.ownerLibraries(legacy, host, homes.owner(host).dir());
        LegacyMigration.initiative(legacy, homes.owner(host).file("prefs.json"));
        LegacyMigration.conversations(LegacyMigration.conversationsFile(legacy),
                conv -> conv.members().stream().anyMatch(member -> registry.find(member) != null),
                homes.worldOwner(host).file("conversations.json"));
    }
}
