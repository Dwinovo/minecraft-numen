package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.network.payload.LibraryPayload;
import com.dwinovo.numen.api.network.payload.LibraryRequestPayload;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 库在客户端只是服务端说的那张表:存和删只变成请求,表要等服务端推回来才变。 */
class RemoteLibraryTest {

    private final List<LibraryRequestPayload> sent = new ArrayList<>();
    private final RemoteLibrary library = new RemoteLibrary(sent::add);

    @Test
    void whatTheServerSaysIsTheTableAndTheLastRefusalIsTheProblem() {
        assertEquals(0, library.entries("persona").size(), "没读过是空表");

        library.onPayload(new LibraryPayload("persona", "[{\"id\":\"甲\",\"name\":\"甲\",\"text\":\"一\"}]", ""));
        assertEquals(1, library.entries("persona").size());
        assertNull(library.problem("persona"));

        library.onPayload(new LibraryPayload("persona", "[]", "no such persona"));
        assertEquals("no such persona", library.problem("persona"));
        library.onPayload(new LibraryPayload("persona", "[]", ""));
        assertNull(library.problem("persona"), "办成了就没有问题");
    }

    @Test
    void askingPuttingAndDeletingAreRequestsNotChanges() {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", "乙");

        library.ask("provider");
        library.put("provider", entry);
        library.delete("provider", "p1");

        assertEquals(List.of(new LibraryRequestPayload("provider", "list", "{}"),
                new LibraryRequestPayload("provider", "put", "{\"name\":\"乙\"}"),
                new LibraryRequestPayload("provider", "delete", "{\"id\":\"p1\"}")), sent);
        assertEquals(0, library.entries("provider").size(), "表等服务端推回来才变");
    }

    @Test
    void aDeletedRowGoesAtOnceAndTheServersNextTableTakesOver() {
        library.onPayload(new LibraryPayload("provider",
                "[{\"id\":\"p1\",\"name\":\"甲\"},{\"id\":\"p2\",\"name\":\"乙\"}]", ""));

        library.delete("provider", "p1");

        assertEquals(1, library.entries("provider").size(), "删了的先画成办成,不等一个来回");
        assertEquals("p2", library.entries("provider").get(0).getAsJsonObject().get("id").getAsString());

        library.onPayload(new LibraryPayload("provider", "[{\"id\":\"p1\",\"name\":\"甲\"}]", "no such profile"));
        assertEquals(1, library.entries("provider").size());
        assertEquals("p1", library.entries("provider").get(0).getAsJsonObject().get("id").getAsString(),
                "服务端没办成就推回真表,以真表为准");
    }

    @Test
    void aTablesVersionMovesWhenTheServerSpeaksOrARowIsDrawnAsDeletedAndOnlyThen() {
        long start = library.version("provider");

        library.onPayload(new LibraryPayload("provider", "[{\"id\":\"p1\",\"name\":\"甲\"}]", ""));
        long told = library.version("provider");
        assertEquals(true, told != start, "服务端推来一张表");

        library.entries("provider");
        library.ask("provider");
        assertEquals(told, library.version("provider"), "读与发请求都不动版本");

        library.delete("provider", "p1");
        assertEquals(true, library.version("provider") != told, "删了的行先画掉也算变");
    }
}
