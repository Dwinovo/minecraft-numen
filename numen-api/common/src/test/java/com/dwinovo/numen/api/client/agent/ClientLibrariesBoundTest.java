package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.mind.Bound;
import com.dwinovo.numen.api.entity.Attendance;
import com.dwinovo.numen.api.entity.Keeper;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.mind.LibraryDesk;
import com.dwinovo.numen.api.network.payload.LibraryPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 她绑的条目在这台客户端看不看得到,按"谁绑的 + 条目"认:我绑的是我叠起来之后在用的那一条;她的另一位主人绑的,只有服务器提供的
 * 那一条我也看得到——他"我的"里的我看不到,我"我的"里同名的是另一条。她用得了的技能在创建者的电脑上:分享来的这里看不到。
 */
@Tag("mc")
class ClientLibrariesBoundTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID FRIEND = UUID.randomUUID();

    @AfterEach
    void forget() {
        ClientCompanions.library().clear();
        NumenRoster.instance().clear();
    }

    private static JsonObject row(String id, boolean server, boolean paired, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", EntryId.parse(id).name());
        o.addProperty("group", server ? LibraryDesk.GROUP_SERVER : LibraryDesk.GROUP_MINE);
        o.addProperty("enabled", true);
        o.addProperty("paired", paired);
        o.addProperty("text", text);
        return o;
    }

    private static void personas(JsonObject... rows) {
        JsonArray table = new JsonArray();
        for (JsonObject row : rows) {
            table.add(row);
        }
        ClientCompanions.library().onPayload(new LibraryPayload(LibraryDesk.PERSONA, table.toString(), ""));
    }

    private static Bound bound(UUID by, String id) {
        return new Bound(by, EntryId.parse(id));
    }

    @Test
    void aSharedCompanionsSkillsAreNotMineEither() {
        UUID mine = UUID.randomUUID();
        UUID shared = UUID.randomUUID();
        NumenRoster.instance().replaceAll("w1", List.of(
                new NumenRoster.Entry(mine, "小焰", false, Attendance.Outside.NONE, List.of(new Keeper(ME, "我"))),
                new NumenRoster.Entry(shared, "阿岩", false, Attendance.Outside.NONE,
                        List.of(new Keeper(FRIEND, "小柚"), new Keeper(ME, "我")))));
        JsonArray own = new JsonArray();
        own.add(skill("my:fishing", "fishing", "我的钓鱼", true));
        own.add(skill("my:mining", "mining", "我关掉的", false));
        ClientCompanions.library().onPayload(new LibraryPayload(LibraryDesk.SKILL, own.toString(), ""));
        assertEquals(List.of(new ClientLibraries.HerSkill("fishing", "我的钓鱼")), ClientLibraries.skillsOf(mine, ME),
                "我创建的:我技能库里开着的");
        assertEquals(List.of(), ClientLibraries.skillsOf(shared, ME),
                "分享来的:技能在她创建者的电脑上,这里看不到,我自己的技能库管不着她");
        assertEquals(List.of(new ClientLibraries.HerSkill("fishing", "我的钓鱼")), ClientLibraries.skillsOf(null, ME),
                "没指明哪一只:我自己的");
    }

    private static JsonObject skill(String id, String name, String description, boolean enabled) {
        JsonObject o = row(id, false, false, "");
        o.addProperty("skill", name);
        o.addProperty("description", description);
        o.addProperty("enabled", enabled);
        return o;
    }

    @Test
    void whatIBoundIsTheEntryISee() {
        personas(row("numen:冷面", true, false, "服务器的"), row("my:小焰", false, false, "我的"));

        assertEquals("我的", ClientLibraries.persona(bound(ME, "my:小焰"), ME).text());
        assertEquals("服务器的", ClientLibraries.persona(bound(ME, "numen:冷面"), ME).text());
        assertNull(ClientLibraries.persona(bound(ME, "my:不存在"), ME), "删掉了");
    }

    @Test
    void anotherOwnersOwnEntryIsNotMineOfTheSameName() {
        personas(row("my:小焰", false, false, "我写的小焰"));

        assertNull(ClientLibraries.persona(bound(FRIEND, "my:小焰"), ME),
                "朋友绑的是他库里的那一条:我库里同名的不是它,不能拿来当她的人设");
    }

    @Test
    void anotherOwnersServerEntryIsTheServerOneEvenWhenMineCoversIt() {
        personas(row("numen:冷面", true, true, "服务器的"), row("numen:冷面", false, true, "我改过的"));

        assertEquals("服务器的", ClientLibraries.persona(bound(FRIEND, "numen:冷面"), ME).text(),
                "朋友绑的在他的库里解析;我能看到的只有服务器提供的那一条,我盖在上面的同名的不算");
        assertEquals("我改过的", ClientLibraries.persona(bound(ME, "numen:冷面"), ME).text(),
                "我自己绑的,是我叠起来之后算数的那一条");
    }
}
