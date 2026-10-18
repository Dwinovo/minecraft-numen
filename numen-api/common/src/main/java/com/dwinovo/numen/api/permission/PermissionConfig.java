package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.NumenPaths;
import com.dwinovo.numen.api.platform.ServerLifecycle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 服主在服务端配置里能动的两样({@code config/numen/permission.json}),不在世界存档里:
 * <ul>
 *   <li>{@code hard_rules}:硬规则({@link FactoryRules#HARD},别人的东西碰不得)开不开,默认开。单机就是你自己,想关就关;</li>
 *   <li>{@code unknown_as_other}:说不清是谁的方块({@code unknown})按别人的({@code other})算。专用服务器默认是:多人服上说不清的多半是
 *       别人装模组前盖的,让主人答应就等于让想搞破坏的主人一键拆别人的房子;单机与局域网默认否,照旧问主人。</li>
 * </ul>
 * 缺的键取默认;专用服务器上文件不存在时写出一份默认的,服主打开就看得见有什么可改。读不懂的文件记一条错误日志、取默认。
 * 一个世界读一次,关服作废。
 *
 * @param hardRules      硬规则生效
 * @param unknownAsOther 说不清的按别人的算
 */
public record PermissionConfig(boolean hardRules, boolean unknownAsOther) {

    static final String FILE = "permission.json";
    static final String HARD_RULES = "hard_rules";
    static final String UNKNOWN_AS_OTHER = "unknown_as_other";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile PermissionConfig loaded;

    static {
        ServerLifecycle.onStopped(() -> loaded = null);
    }

    /** 这个世界的配置:第一次问时读文件。 */
    public static PermissionConfig of(MinecraftServer server) {
        PermissionConfig config = loaded;
        if (config == null) {
            config = load(NumenPaths.config().resolve(FILE), server.isDedicatedServer());
            loaded = config;
        }
        return config;
    }

    static PermissionConfig load(Path file, boolean dedicated) {
        if (!Files.exists(file)) {
            PermissionConfig defaults = parse(new JsonObject(), dedicated);
            if (dedicated) {
                write(file, defaults);
            }
            return defaults;
        }
        try (Reader in = Files.newBufferedReader(file)) {
            return parse(JsonParser.parseReader(in).getAsJsonObject(), dedicated);
        } catch (IOException | JsonParseException | IllegalStateException e) {
            Constants.LOG.error("[numen-permission] 读不懂 {}:{};用默认值", file, e.toString());
            return parse(new JsonObject(), dedicated);
        }
    }

    /** 文件里的两个键 → 配置;缺的取默认。 */
    static PermissionConfig parse(JsonObject json, boolean dedicated) {
        return new PermissionConfig(
                json.has(HARD_RULES) ? json.get(HARD_RULES).getAsBoolean() : true,
                json.has(UNKNOWN_AS_OTHER) ? json.get(UNKNOWN_AS_OTHER).getAsBoolean() : dedicated);
    }

    private static void write(Path file, PermissionConfig config) {
        JsonObject json = new JsonObject();
        json.addProperty(HARD_RULES, config.hardRules);
        json.addProperty(UNKNOWN_AS_OTHER, config.unknownAsOther);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(json));
        } catch (IOException e) {
            Constants.LOG.error("[numen-permission] 写不出默认的 {}:{}", file, e.toString());
        }
    }
}
