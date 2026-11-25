package com.dwinovo.numen.api.owner;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.permission.PermissionLibrary;
import com.dwinovo.numen.api.permission.PermissionStore;
import com.dwinovo.numen.api.plugin.PluginRoster;
import com.dwinovo.numen.api.skin.SkinError;
import com.dwinovo.numen.api.skin.SkinFolder;
import com.dwinovo.numen.api.skin.SkinSigning;
import com.dwinovo.numen.api.skin.Wardrobe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家的面板向服务端要的、服务端管的几样东西的读写:皮肤(排队签名的行、服务器皮肤文件夹里的图,给同伴换皮肤)、权限(他名下每只同伴的八个档位与
 * "不用问"名单)、插件花名册。包是 {@code LibraryRequestPayload} 与 {@code LibraryPayload},一问一答,答复是这位玩家此刻看得到的整表。
 * 她的大脑的配置(模型档案、key、人设、声线、技能、札记、定时)是主人客户端上的,不经这里。
 *
 * <p>谁能做什么在这里判:权限只摸得到自己那一份;皮肤只动他名下的同伴;插件的开关只有管理员与房主能改。
 *
 * <p>线程:服务端主线程。
 */
public final class Libraries {

    /** 皮肤:他在签名队里的行(排队的、刚失败的),与给同伴换皮肤、脱掉、撤掉排着的。 */
    public static final String SKIN = "skin";
    /** 服务器皮肤文件夹里的图:只读,客户端要看时才问(带着每张图,不随排队的进度一起推)。 */
    public static final String SKIN_FILES = "skin-files";
    /** 权限:他名下每只同伴的八个档位与每种动作的"不用问"名单,只有他读写他那一份(管理员也一样)。 */
    public static final String PERMISSION = "permission";
    /** 插件花名册:人人读;开关(记在服务器的停用名单里,下次启动生效)只有管理员与房主能改,用 {@link #ENABLE}/{@link #DISABLE}。 */
    public static final String PLUGIN = "plugin";
    public static final String LIST = "list";
    public static final String PUT = "put";
    public static final String DELETE = "delete";
    public static final String ENABLE = "enable";
    public static final String DISABLE = "disable";
    /** 权限专有:设一只同伴的一个档位;套一个预设。从名单里删一项用 {@link #DELETE}。 */
    public static final String TIER = PermissionLibrary.TIER;
    public static final String PRESET = PermissionLibrary.PRESET;
    /** 皮肤专有:撤掉一张排着的签名(或不再显示一条失败)。 */
    public static final String CANCEL = "cancel";

    /** 这台服务器上各样东西的出处;测试给假的。 */
    public interface Sources {

        /** 这位主人用得了的同伴(他创建的、分享给他的)。 */
        List<UUID> companions(UUID owner);

        /** 这位主人的权限:他的规则存档、服主的开关、他名下的同伴。 */
        PermissionLibrary.Ground permission(UUID owner);

        /** 这位玩家是不是房主:他的"我的"皮肤文件夹就是服务器那一个。 */
        boolean host(UUID owner);

        /** 这台服务器上的皮肤签名队(排队的状态、撤掉)。 */
        SkinSigning signer();

        /** 给同伴换皮肤的流程,连同服务器上的皮肤文件夹。 */
        Wardrobe wardrobe();

        /** 这台服务器上的插件花名册。 */
        PluginRoster plugins();
    }

    /**
     * 一次读写的结果。
     *
     * @param entries 这位玩家此刻看得到的整表(JSON 数组)
     * @param problem 办不成的原因(给界面和日志);办成是 {@code null}
     * @param shared  这次改动改了所有人共用的东西(插件的开关):在线的其他玩家各收到他们看得到的新表
     */
    public record Result(String entries, String problem, boolean shared) {}

    /** 这台服务器上的那一份。 */
    public static Libraries of(net.minecraft.server.MinecraftServer server) {
        return new Libraries(new Sources() {
            @Override
            public List<UUID> companions(UUID owner) {
                return CompanionRegistry.get(server).usableBy(owner).stream().map(Map.Entry::getKey).toList();
            }

            @Override
            public PermissionLibrary.Ground permission(UUID owner) {
                return new PermissionLibrary.Ground() {
                    @Override
                    public PermissionStore store() {
                        return PermissionStore.of(server, owner);
                    }

                    @Override
                    public Map<UUID, String> companions() {
                        Map<UUID, String> out = new LinkedHashMap<>();
                        CompanionRegistry.get(server).ownedBy(owner).forEach(e -> out.put(e.getKey(), e.getValue().name()));
                        return out;
                    }
                };
            }

            @Override
            public boolean host(UUID owner) {
                return Homes.of(server).isHost(owner);
            }

            @Override
            public SkinSigning signer() {
                return ServerSkins.of(server).signer();
            }

            @Override
            public Wardrobe wardrobe() {
                return ServerSkins.of(server).wardrobe();
            }

            @Override
            public PluginRoster plugins() {
                return NumenPlugins.roster();
            }
        });
    }

    private final Sources sources;

    public Libraries(Sources sources) {
        this.sources = sources;
    }

    /** 一次操作的结局:办不成的原因,是不是改了所有人共用的东西。 */
    private record Done(String problem, boolean shared) {

        static Done ok() {
            return new Done(null, false);
        }

        static Done problem(String problem) {
            return new Done(problem, false);
        }
    }

    /**
     * @param owner 发请求的玩家
     * @param admin 他是不是管理员({@code Authority.isAdmin})
     * @param body  存和删带的 JSON;列不用
     */
    public Result handle(UUID owner, boolean admin, String kind, String op, JsonObject body) {
        Done done = switch (kind) {
            case SKIN -> skin(owner, op, body);
            case PERMISSION -> permission(owner, op, body);
            case PLUGIN -> new Done(plugin(admin || sources.host(owner), op, body), true);
            case SKIN_FILES -> LIST.equals(op) ? Done.ok() : Done.problem("unknown operation");
            default -> Done.problem("unknown library");
        };
        return new Result(view(owner, admin, kind), done.problem(), done.shared() && done.problem() == null);
    }

    /** 这位玩家此刻看得到的整表。看不到的库是空表。 */
    public String view(UUID owner, boolean admin, String kind) {
        JsonArray out = new JsonArray();
        switch (kind) {
            case SKIN -> pendingRows(owner, out);
            case SKIN_FILES -> fileRows(owner, out);
            case PLUGIN -> pluginRows(admin || sources.host(owner), out);
            case PERMISSION -> out.addAll(PermissionLibrary.view(sources.permission(owner)));
            default -> { }
        }
        return out.toString();
    }

    /**
     * 服务器皮肤文件夹里的图({@code row: "file"}:名字、原图 base64,图不能用的带原因码)。房主的"我的"文件夹就是服务器那一个,
     * 客户端自己列,这里不再重复给他一节"服务器提供",是空表。
     */
    private void fileRows(UUID owner, JsonArray out) {
        if (sources.host(owner)) {
            return;
        }
        SkinFolder folder = sources.wardrobe().folder();
        for (SkinFolder.Entry file : folder.list()) {
            JsonObject row = new JsonObject();
            row.addProperty("row", "file");
            row.addProperty("name", file.name());
            try {
                row.addProperty("png", java.util.Base64.getEncoder().encodeToString(folder.read(file.name())));
            } catch (SkinError unusable) {
                row.add("problem", skinProblem(unusable));
            }
            out.add(row);
        }
    }

    /** 这位玩家在签名队里的与刚失败的({@code row: "pending"},带排到第几、在等限流、失败的原因码与原话)。 */
    private void pendingRows(UUID owner, JsonArray out) {
        for (SkinSigning.Pending pending : sources.signer().pendingOf(owner)) {
            JsonObject row = new JsonObject();
            row.addProperty("row", "pending");
            row.addProperty("name", pending.name());
            row.addProperty("job", pending.id());
            row.addProperty("state", pending.failure() != null ? "FAILED" : pending.state().name());
            row.addProperty("position", pending.position());
            if (pending.failure() != null) {
                row.add("problem", skinProblem(pending.failure()));
            }
            out.add(row);
        }
    }

    private static JsonObject skinProblem(SkinError error) {
        JsonObject problem = new JsonObject();
        problem.addProperty("code", error.code());
        JsonArray args = new JsonArray();
        error.args().forEach(args::add);
        problem.add("args", args);
        return problem;
    }

    /**
     * 皮肤的操作,都对他名下的一只同伴({@code companion})。存({@link #PUT})是给她换上这张皮肤,图是 {@code png}(base64,他本机文件夹里的)、
     * {@code file}(服务器文件夹里的文件名)、{@code url} 或 {@code player} 之一,{@code variant} 是手臂模型(不写是看图判断):签过的当场穿上,
     * 没签过的排上队,排到第几、被限流在等、失败的原因都在表里({@link #pendingRows})。删({@link #DELETE})是脱掉,回到默认皮肤。
     * 撤({@link #CANCEL})撤掉自己排着的或不再显示失败的。皮肤不是库里的条目,没有"复制到我的"这类操作。
     */
    private Done skin(UUID owner, String op, JsonObject body) {
        switch (op) {
            case LIST:
                return Done.ok();
            case CANCEL:
                JsonElement job = body.get("job");
                return job != null && job.isJsonPrimitive() && sources.signer().cancel(owner, job.getAsLong())
                        ? Done.ok() : Done.problem("no such pending skin");
            case PUT:
            case DELETE:
                break;
            default:
                return Done.problem("unknown operation");
        }
        UUID companion = ownedCompanion(owner, body);
        if (companion == null) {
            return Done.problem("not your companion");
        }
        if (DELETE.equals(op)) {
            sources.wardrobe().takeOff(companion);
            return Done.ok();
        }
        String variant = string(body, "variant");
        String name = string(body, "name") != null ? string(body, "name") : "skin";
        try {
            long ticket;
            if (string(body, "png") != null) {
                byte[] png;
                try {
                    png = java.util.Base64.getDecoder().decode(string(body, "png"));
                } catch (IllegalArgumentException notBase64) {
                    return Done.problem("unreadable image");
                }
                ticket = sources.wardrobe().wear(owner, companion, name, new SkinSigning.Source.Image(png), variant,
                        outcome -> { });
            } else if (string(body, "file") != null) {
                ticket = sources.wardrobe().wearFile(owner, companion, string(body, "file"), variant, outcome -> { });
            } else if (string(body, "url") != null) {
                ticket = sources.wardrobe().wear(owner, companion, name, new SkinSigning.Source.Url(string(body, "url")),
                        variant, outcome -> { });
            } else if (string(body, "player") != null) {
                ticket = sources.wardrobe().wear(owner, companion, string(body, "player"),
                        new SkinSigning.Source.Player(string(body, "player")), variant, outcome -> { });
            } else {
                return Done.problem("a skin needs an image, a file, a url or a player name");
            }
            return ticket < 0 ? Done.problem("too many skins are waiting to be signed") : Done.ok();
        } catch (SkinError refused) {
            return Done.problem(refused.getMessage());
        }
    }

    /** 权限:每位玩家只摸得到自己那一份,不看是不是管理员。办不成的原因(英文原话)在 {@code problem} 里。 */
    private Done permission(UUID owner, String op, JsonObject body) {
        if (LIST.equals(op)) {
            return Done.ok();
        }
        String problem = PermissionLibrary.write(sources.permission(owner), op, body).problem();
        return problem == null ? Done.ok() : Done.problem(problem);
    }

    // ---- 插件 ----

    /**
     * 插件花名册的行,人人看得到同样的内容:谁、哪个版本、联动谁、此刻的状态、加了什么,以及服主关了没有({@code disabled})、
     * 开关和此刻的状态是不是要重启才对得上({@code pending})。{@code toggle} 是这位玩家能不能改它的开关。
     */
    private void pluginRows(boolean mayToggle, JsonArray out) {
        for (PluginRoster.Plugin plugin : sources.plugins().plugins()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", plugin.id());
            row.addProperty("name", plugin.name());
            row.addProperty("version", plugin.version());
            JsonArray authors = new JsonArray();
            plugin.authors().forEach(authors::add);
            row.add("authors", authors);
            row.addProperty("target", plugin.target());
            // 目标模组只在客户端的联动:这一行装没装,看看的人自己的客户端(见 ClientMods)
            row.addProperty("clientMod", com.dwinovo.numen.api.plugin.ClientMods.isClientMod(plugin.id()));
            row.addProperty("source", plugin.source().name().toLowerCase(java.util.Locale.ROOT));
            row.addProperty("status", plugin.status().name().toLowerCase(java.util.Locale.ROOT));
            row.addProperty("reason", plugin.reason());
            row.addProperty("disabled", plugin.disabled());
            row.addProperty("pending", plugin.pending());
            row.addProperty("toggle", mayToggle);
            JsonObject adds = new JsonObject();
            plugin.adds().forEach((kind, n) -> adds.addProperty(kind.name().toLowerCase(java.util.Locale.ROOT), n));
            row.add("adds", adds);
            out.add(row);
        }
    }

    /** 插件的开关:当场装上或撤回它的登记(声明了卸下要重启的除外),管理员与房主才能改,别人只读。 */
    private String plugin(boolean mayToggle, String op, JsonObject body) {
        if (LIST.equals(op)) {
            return null;
        }
        if (!ENABLE.equals(op) && !DISABLE.equals(op)) {
            return "unknown operation";
        }
        if (!mayToggle) {
            return "only a server admin or the host may switch plugins";
        }
        return sources.plugins().setDisabled(string(body, "id"), DISABLE.equals(op));
    }

    private UUID ownedCompanion(UUID owner, JsonObject body) {
        String text = string(body, "companion");
        UUID companion;
        try {
            companion = text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
        return companion != null && sources.companions(owner).contains(companion) ? companion : null;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
