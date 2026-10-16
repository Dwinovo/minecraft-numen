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
}
