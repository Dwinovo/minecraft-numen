package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.FakeWorld;
import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.network.payload.LibraryPayload;
import com.dwinovo.numen.api.permission.PermissionLibrary;
import com.dwinovo.numen.api.permission.PermissionStore;
import com.dwinovo.numen.permission.Edit;
import com.dwinovo.numen.permission.Tier;
import com.dwinovo.numen.permission.Verb;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 权限页在客户端读到的:服务端编码的整表({@link PermissionLibrary#view})原样读回成界面用的记录。 */
@Tag("mc")
class ServerPermissionTest {

    @Test
    void theTableTheServerEncodesIsReadBackAsTheScreenUsesIt() {
        assumeTrue(FakeWorld.boot(), "Minecraft 引导不可用");
        PermissionStore store = new PermissionStore();
        UUID blue = UUID.randomUUID();
        store.edit(blue, new Edit.SetTier(Verb.USE_ITEM, Tier.DENY));
        store.edit(blue, new Edit.Add(Verb.COMMAND, "msg"));

        ClientCompanions.library().onPayload(new LibraryPayload(Libraries.PERMISSION,
                PermissionLibrary.view(ground(store, blue)).toString(), ""));
        ClientLibraries.Permission read = ClientLibraries.permission();

        assertEquals(java.util.List.of(blue), read.companions());
        assertEquals(Verb.values().length, read.tiers().size());
        var held = read.tiers().stream().filter(t -> t.verb().equals("use_item")).findFirst().orElseThrow();
        assertEquals("deny", held.tier());
        assertEquals("小蓝", held.name());
        assertEquals(1, read.listed().size());
        assertEquals("command", read.listed().get(0).verb());
        assertEquals("msg", read.listed().get(0).entry());
        assertTrue(read.tiers().stream().filter(t -> t.verb().equals("drop")).allMatch(t -> t.tier().equals("ask")));
    }

    private static PermissionLibrary.Ground ground(PermissionStore store, UUID companion) {
        return new PermissionLibrary.Ground() {
            @Override
            public PermissionStore store() {
                return store;
            }

            @Override
            public Map<UUID, String> companions() {
                return Map.of(companion, "小蓝");
            }
        };
    }
}
