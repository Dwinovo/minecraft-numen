package com.dwinovo.numen.api.home;

import com.dwinovo.numen.api.store.Lanes;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class HomesTest {

    private static final UUID HER = UUID.randomUUID();
    private static final UUID HIM = UUID.randomUUID();

    private final Homes homes = new Homes(Path.of("save", "numen"), Path.of("config", "numen"),
            new Lanes(Runnable::run, e -> { }));

    @Test
    void herThingsLiveInTheWorldAndTheOwnersLibrariesLiveOnTheServer() {
        assertEquals(Path.of("save", "numen", "companions", HER.toString()), homes.companion(HER).dir());
        assertEquals(Path.of("save", "numen", "players", HIM.toString()), homes.worldOwner(HIM).dir());
        assertEquals(Path.of("config", "numen", "players", HIM.toString()), homes.owner(HIM).dir());
        assertEquals(Path.of("config", "numen", "server"), homes.server().dir());
    }

    @Test
    void everyOwnerOfAThingHasItsOwnRootAndNothingLivesLooseUnderTheConfigRoot() {
        assertEquals(Path.of("config", "numen", "server", "keys"), homes.server().file("keys"), "服主的钥匙串在服务器根下");
        assertEquals(Path.of("config", "numen", "players", HIM.toString(), "keys"), homes.owner(HIM).file("keys"),
                "玩家的钥匙串在他自己的根下");
        assertFalse(homes.server().dir().equals(homes.owner(HIM).dir().getParent()), "服务器的根不是玩家们的根");
    }

    @Test
    void aFileIsNamedByTheHomeNotSpelledOutByTheUser() {
        assertEquals(Path.of("save", "numen", "companions", HER.toString(), "chat.jsonl"),
                homes.companion(HER).file("chat.jsonl"));
    }

    @Test
    void allOfHerFilesShareHerLaneAndOthersDoNot() {
        Object herLane = homes.companion(HER).io().executor();
        assertSame(herLane, homes.companion(HER).io().executor(), "同一只同伴每次拿到的是同一条车道");
        assertNotSame(herLane, homes.companion(HIM).io().executor());
        assertNotSame(herLane, homes.worldOwner(HIM).io().executor());
        assertNotSame(herLane, homes.owner(HIM).io().executor());
    }
}
