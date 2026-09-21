package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.group.CompanionGroup;
import com.dwinovo.numen.client.data.JsonLibrary;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 这台机器上的群名册,落在 {@code companions/groups.json}(见 {@link CompanionHome#groups})。
 *
 * <h2>成员不做清理,读的时候按名册过滤</h2>
 * 同伴被遣散后,她的 UUID 还留在群里。不去追着删是有意的:遣散只有"删一个目录"这一个动作,
 * 一旦要求它顺手改群,就又多了一处"忘了改就留脏数据"的地方。所以判据放在用的时候——
 * {@link #membersAlive} 拿当前名册过滤,遣散的那只自然不再出现。
 *
 * <p>客户端主线程专用,与其余几个库同一制式。
 */
public final class CompanionGroups extends JsonLibrary<CompanionGroup> {

    private static CompanionGroups instance;

    private CompanionGroups(java.nio.file.Path file) {
        super(file);
    }

    public static CompanionGroups instance() {
        if (instance == null) {
            instance = new CompanionGroups(CompanionHome.groups());
            instance.load();
        }
        return instance;
    }

    @Override
    protected String logTag() {
        return "numen-group";
    }

    @Override
    protected String idOf(CompanionGroup entry) {
        return entry.id();
    }

    @Override
    protected CompanionGroup readEntry(JsonObject o) {
        return CompanionGroup.fromJson(o);
    }

    @Override
    protected JsonObject writeEntry(CompanionGroup entry) {
        return entry.toJson();
    }

    // ---- 用 ----

    /** 拉一个群出来:名字先空着(显示时拼成员名),话头空着 = 全体。 */
    public CompanionGroup create(List<UUID> members) {
        CompanionGroup g = CompanionGroup.of(members);
        putAndSave(g);
        return g;
    }

    /** 改名、换成员、转话头都走这里——群这个对象是不可变的,改就是换一份存回去。 */
    public void put(CompanionGroup group) {
        if (group != null) {
            putAndSave(group);
        }
    }

    /** 这只同伴在哪些群里(按名册过滤之后仍然有她的那些)。 */
    public List<CompanionGroup> containing(UUID companion) {
        List<CompanionGroup> out = new ArrayList<>();
        for (CompanionGroup g : list()) {
            if (g.has(companion)) {
                out.add(g);
            }
        }
        return out;
    }

    /**
     * 群里还活着的成员:拿当前名册过滤。遣散掉的那只不再出现,也不必回头改这份文件。
     *
     * @param onRoster 名册上还在的同伴
     */
    public static List<UUID> membersAlive(CompanionGroup group, java.util.Set<UUID> onRoster) {
        List<UUID> out = new ArrayList<>();
        for (UUID m : group.members()) {
            if (onRoster.contains(m)) {
                out.add(m);
            }
        }
        return out;
    }
}
