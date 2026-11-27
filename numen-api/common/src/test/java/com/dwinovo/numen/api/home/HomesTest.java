package com.dwinovo.numen.api.home;

import com.dwinovo.numen.api.store.Lanes;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomesTest {

    private static final UUID HER = UUID.randomUUID();
    private static final UUID HIM = UUID.randomUUID();

    private final Homes homes = new Homes(Path.of("save", "numen"), Path.of("config", "numen"),
            new Lanes(Runnable::run, e -> { }), HIM);

    @Test
    void herThingsLiveInTheWorldAndTheServersLiveUnderTheConfigRoot() {
        assertEquals(Path.of("save", "numen", "companions", HER.toString()), homes.companion(HER).dir());
        assertEquals(Path.of("save", "numen", "players", HIM.toString()), homes.worldOwner(HIM).dir());
        assertEquals(Path.of("config", "numen", "server"), homes.server().dir());
    }

    @Test
    void theServersKeychainLivesUnderTheServerRoot() {
        assertEquals(Path.of("config", "numen", "server", "keys"), homes.server().file("keys"), "服主的钥匙串在服务器根下");
    }

    @Test
    void onlyTheHostIsTheServerAndADedicatedServerHasNoHost() {
        assertTrue(homes.isHost(HIM));
        assertFalse(homes.isHost(HER));
        assertFalse(new Homes(Path.of("save", "numen"), Path.of("config", "numen"), new Lanes(Runnable::run, e -> { }), null)
                .isHost(HIM), "专用服务器没有房主");
    }

    @Test
    void aFileIsNamedByTheHomeNotSpelledOutByTheUser() {
        assertEquals(Path.of("save", "numen", "companions", HER.toString(), "chat.jsonl"),
                homes.companion(HER).file("chat.jsonl"));
    }

    @Test
    void conversationRecordsLiveInTheWorldUnderOneLaneOfTheirOwn() {
        UUID conversation = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

        assertEquals(Path.of("save", "numen", "conversations", conversation + ".jsonl"),
                homes.conversations().file(conversation + ".jsonl"));
        assertNotSame(homes.companion(HER).io().executor(), homes.conversations().io().executor());
    }

    @Test
    void allOfHerFilesShareHerLaneAndOthersDoNot() {
        Object herLane = homes.companion(HER).io().executor();
        assertSame(herLane, homes.companion(HER).io().executor(), "同一只同伴每次拿到的是同一条车道");
        assertNotSame(herLane, homes.companion(HIM).io().executor());
        assertNotSame(herLane, homes.worldOwner(HIM).io().executor());
    }
}
