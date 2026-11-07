package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.FakeWorld;
import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.network.payload.LibraryPayload;
import com.dwinovo.numen.api.permission.Groups;
import com.dwinovo.numen.api.permission.Layers;
import com.dwinovo.numen.api.permission.PermissionLibrary;
import com.dwinovo.numen.api.permission.PermissionStore;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Verdict;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 权限页在客户端读到的:服务端编码的整表({@link PermissionLibrary#view})原样读回成界面用的记录,规则写错的答复读出错在哪。 */
@Tag("mc")
class ServerPermissionTest {

    @Test
    void theTableTheServerEncodesIsReadBackAsTheScreenUsesIt() {
        assumeTrue(FakeWorld.boot(), "Minecraft 引导不可用");
        PermissionStore store = new PermissionStore();
        UUID blue = UUID.randomUUID();
        store.setMode(blue, Mode.BYPASS);
        store.add(Verdict.Kind.ASK, new PermissionStore.Row(Groups.parse("break(owner)"),
                PermissionStore.Source.HER, "别动箱子"));
        store.hit(Layers.OWNER, Verdict.Kind.ASK, Groups.parse("break(owner)"));

        RemoteCompanions.library().onPayload(new LibraryPayload(Libraries.PERMISSION,
                PermissionLibrary.view(ground(store, blue)).toString(), ""));
        ServerLibraries.Permission read = ServerLibraries.permission();

        assertTrue(read.hardRules());
        assertEquals(1, read.modes().size());
        assertEquals("bypass", read.modes().get(0).mode());
        assertEquals(blue, read.modes().get(0).companion());
        assertEquals("小蓝", read.modes().get(0).name());
        var hers = read.rules().stream().filter(r -> r.editable() && r.rule().equals("break(owner)")).findFirst().orElseThrow();
        assertEquals("ask", hers.table());
        assertTrue(hers.byHer());
        assertEquals("别动箱子", hers.note());
        assertEquals(1, hers.count());
        assertTrue(read.rules().stream().anyMatch(r -> r.layer().equals("hard") && r.rule().equals("break(other)")));
        assertFalse(read.rules().stream().filter(r -> r.layer().equals("hard")).anyMatch(ServerLibraries.PermissionRule::editable));
        assertNull(read.mistake());
    }

    @Test
    void aRefusedRuleComesWithWhereItWentWrong() {
        JsonArray rows = new JsonArray();
        JsonObject mistake = new JsonObject();
        mistake.addProperty("code", "verb");
        JsonArray args = new JsonArray();
        args.add("dig");
        mistake.add("args", args);
        JsonObject extra = new JsonObject();
        extra.add("mistake", mistake);
        rows.add(extra);

        RemoteCompanions.library().onPayload(new LibraryPayload(Libraries.PERMISSION, rows.toString(), "unknown verb"));

        assertEquals("verb", ServerLibraries.permission().mistake().code());
        assertEquals("dig", ServerLibraries.permission().mistake().args().get(0));
        assertEquals("unknown verb", RemoteCompanions.library().problem(Libraries.PERMISSION));
    }

    private static PermissionLibrary.Ground ground(PermissionStore store, UUID companion) {
        return new PermissionLibrary.Ground() {
            @Override
            public PermissionStore store() {
                return store;
            }

            @Override
            public boolean hardRules() {
                return true;
            }

            @Override
            public Map<UUID, String> companions() {
                return Map.of(companion, "小蓝");
            }
        };
    }
}
