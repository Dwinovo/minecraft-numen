package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.NumenPaths;
import com.dwinovo.numen.api.agent.memory.NoteBook;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.api.mcp.server.ServerMcp;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.script.Modules;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
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
        ServerLifecycle.onReloaded(ServerBrains::reload);
    }

    static void start(MinecraftServer server) {
        Homes homes = Homes.of(server);
        CompanionRegistry registry = CompanionRegistry.get(server);
        // 内置要先同步进服务器目录,库才有东西可读
        LibraryHub hub = LibraryHub.start(server);
        migrateHost(server, homes, registry);
        // 她的札记在世界里她的家下;模块与技能是主人那一份库(内置加我的)
        NoteBook.init(uuid -> {
            Home home = homes.companion(uuid);
            return new Home(home.dir().resolve("memory"), home.io());
        }, () -> (int) (server.overworld().getDayTime() / 24000L));
        Modules.init(companion -> hub.library(Modules.KIND, ownerOf(registry, companion)));
        SkillRegistry.init(companion -> hub.library(SkillRegistry.KIND, ownerOf(registry, companion)));
        ModelServices.start(server);
        SkinSignings.start(server);
        McpClients.start(server);
        ServerMcp.start(server);
        Visits.start();
        for (UUID companion : registry.all()) {
            try {
                CompanionBrains.ensure(server, companion);
            } catch (RuntimeException ex) {
                Constants.LOG.error("[numen] 同伴 {} 的大脑没能起来,跳过", companion, ex);
            }
        }
        // 旧版为主人离线攒下的事件:一次性倒进她们的收件箱
        Path outbox = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("numen_event_outbox.dat");
        LegacyMigration.outbox(outbox).forEach((companion, entries) ->
                CompanionLoops.get(companion).ifPresent(loop -> loop.push(entries)));
    }

    /**
     * {@code /reload}:库(内置与每位玩家的)、钥匙串、服务器设置、接进来的 MCP 工具都从磁盘重新读——管理员或玩家拿编辑器改过的
     * 文件在这里读到。jar 里的内置不重新同步(jar 在进程里不会变)。
     */
    static void reload(MinecraftServer server) {
        LibraryHub.of(server).reloadAll();
        ModelServices.of(server).reload();
        McpClients.of(server).reconcileAll();
        Constants.LOG.info("[numen] 库与钥匙串已从磁盘重新读");
    }

    /** 这只同伴的主人;名册上没有她是编程错误。 */
    private static UUID ownerOf(CompanionRegistry registry, UUID companion) {
        CompanionRegistry.Entry entry = registry.find(companion);
        if (entry == null) {
            throw new IllegalStateException("没有这只同伴: " + companion);
        }
        return entry.owner();
    }

    private static void stop() {
        Visits.stop();
        McpMode.instance().shutdown();
    }

    /**
     * 单人游戏的房主在旧版留下的、归他自己的东西,搬进现在的布局:模型档案与 key、人设、Lua 模块、技能、接进来的 MCP 服务器、
     * 会话成员表、主动性档位、每只同伴的家(以及它们绑定里的档案与人设的新名字),和这台电脑上旧的声线库、皮肤库、
     * 每只同伴的声线皮肤选择({@link LegacyLooks})。只做一次,复制不移动
     * ({@link LegacyMigration}、{@link LegacyLibraries});多人服务器上旧客户端的数据没法这样读到,不迁也不上传。
     * 要在库开张、大脑建起来之前做完,它们读的是搬好的。
     */
    private static void migrateHost(MinecraftServer server, Homes homes, CompanionRegistry registry) {
        var profile = server.getSingleplayerProfile();
        if (profile == null) {
            return;
        }
        UUID host = profile.getId();
        Path legacy = NumenPaths.config();
        Path serverDir = homes.server().dir();
        Path hostDir = homes.owner(host).dir();
        LegacyMigration.initiative(legacy, homes.owner(host).file("prefs.json"));
        LegacyMigration.conversations(LegacyMigration.conversationsFile(legacy),
                conv -> conv.members().stream().anyMatch(member -> registry.find(member) != null),
                homes.worldOwner(host).file("conversations.json"));
        LegacyMigration.file(legacy.resolve("mcp_oauth.json"), serverDir.resolve("mcp_oauth.json"));
        LegacyMigration.file(legacy.resolve("mcp_server.json"), serverDir.resolve("mcp_server.json"));

        OwnerPrefs prefs = OwnerPrefs.of(server, host);
        var providerIds = LegacyLibraries.providers(legacy.resolve("providers.json"), serverDir);
        // 人设与模块旧版放在房主的目录里平铺;更早的版本放在根下(模块按账号分)
        Path flatPersona = Files.isDirectory(hostDir.resolve("persona")) ? hostDir.resolve("persona")
                : legacy.resolve("persona");
        var personaIds = LegacyLibraries.personas(flatPersona, hostDir.resolve("persona").resolve(EntryId.MINE), serverDir);
        Path flatModules = Files.isDirectory(hostDir.resolve("lua")) ? hostDir.resolve("lua")
                : legacy.resolve("lua").resolve(host.toString());
        LegacyLibraries.modules(flatModules, hostDir.resolve("modules"), serverDir, prefs);
        LegacyLibraries.skills(legacy.resolve("skills"), legacy.resolve("skills_state.json"),
                hostDir.resolve("skills").resolve(EntryId.MINE), serverDir.resolve("skills"), prefs);
        LegacyLibraries.mcp(legacy.resolve("mcp_clients.json"), serverDir.resolve("mcp"));

        List<Path> bindings = new ArrayList<>();
        Map<UUID, Path> justMoved = new LinkedHashMap<>();
        for (UUID companion : registry.all()) {
            boolean moved = LegacyMigration.companion(legacy.resolve("companions").resolve(companion.toString()),
                    homes.companion(companion).dir());
            bindings.add(homes.companion(companion).file("binding.json"));
            if (moved) {
                justMoved.put(companion, homes.companion(companion).file("binding.json"));
            }
        }
        LegacyLibraries.bindings(bindings, providerIds, personaIds);

        // 声线库、皮肤库和每只同伴的声线皮肤选择在这台电脑的 client/ 里(客户端先把更旧的位置接了过去)
        Path clientDir = legacy.resolve("client");
        var voiceIds = LegacyLooks.voices(clientDir.resolve("voice.json"), hostDir.resolve("voice").resolve(EntryId.MINE),
                clientDir.resolve("keys"));
        var skinIds = LegacyLooks.skins(clientDir.resolve("skin.json"), clientDir.resolve("skins"),
                hostDir.resolve("skin").resolve(EntryId.MINE));
        LegacyLooks.bindings(clientDir.resolve("looks.json"), legacy.resolve("companions"), justMoved, voiceIds, skinIds);
    }
}
