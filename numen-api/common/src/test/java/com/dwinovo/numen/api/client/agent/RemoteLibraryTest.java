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
    void anAnswerGoesToTheAskerByRequestNumberAndAPushSqueezedInBetweenIsNotTheAnswer() {
        List<RemoteLibrary.Reply> replies = new ArrayList<>();
        library.request("permission", "put", new JsonObject(), replies::add);
        long id = sent.get(0).request();
        assertEquals(1, id);

        library.onPayload(new LibraryPayload("permission", "[]", ""));   // 服务端主动推的,不答任何请求
        assertEquals(0, replies.size(), "推送不是答复");

        library.onPayload(new LibraryPayload("permission", "[]", "unknown verb", id));
        assertEquals(1, replies.size());
        assertEquals("unknown verb", replies.get(0).problem());

        library.onPayload(new LibraryPayload("permission", "[]", "", id));
        assertEquals(1, replies.size(), "一个号只答一次");
    }

    @Test
    void aSuccessfulAnswerIsOkAndTwoRequestsAreAnsweredEachByItsOwnNumber() {
        List<String> order = new ArrayList<>();
        library.request("permission", "put", new JsonObject(), r -> order.add("first:" + r.ok()));
        library.request("permission", "put", new JsonObject(), r -> order.add("second:" + r.ok()));

        library.onPayload(new LibraryPayload("permission", "[]", "no", sent.get(1).request()));
        library.onPayload(new LibraryPayload("permission", "[]", "", sent.get(0).request()));

        assertEquals(List.of("second:false", "first:true"), order);
    }

    @Test
    void aFormThatAsksAgainOnlyTakesTheAnswerToItsLatestAskEvenWithAPushInBetween() {
        LatestReply latest = new LatestReply();
        List<String> taken = new ArrayList<>();
        library.request("catalog", "list", new JsonObject(), latest.take(r -> taken.add("first")));
        library.request("catalog", "list", new JsonObject(), latest.take(r -> taken.add("second")));

        library.onPayload(new LibraryPayload("catalog", "[]", ""));   // 别处推来的表,插在两个答复之前
        library.onPayload(new LibraryPayload("catalog", "[]", "", sent.get(0).request()));   // 第一问的答复晚到:不属于现在的草稿
        assertEquals(List.of(), taken, "推送与过时的答复都不算");

        library.onPayload(new LibraryPayload("catalog", "[]", "", sent.get(1).request()));
        assertEquals(List.of("second"), taken);
    }

    @Test
    void anAnswerToARequestFromBeforeTheFormWasReopenedIsNotTaken() {
        LatestReply latest = new LatestReply();
        List<String> taken = new ArrayList<>();
        library.request("provider-check", "run", new JsonObject(), latest.take(r -> taken.add("old")));

        latest.cancel();
        library.onPayload(new LibraryPayload("provider-check", "[]", "", sent.get(0).request()));

        assertEquals(List.of(), taken);
    }

    @Test
    void theCreatedIdRidesTheAnswerToTheAskerAndIsNotARowOfTheTable() {
        List<RemoteLibrary.Reply> replies = new ArrayList<>();
        library.put("voice", new JsonObject(), replies::add);

        library.onPayload(new LibraryPayload("voice", "[{\"id\":\"my:甲\",\"name\":\"甲\"},{\"created\":\"my:甲\"}]", "",
                sent.get(0).request()));

        assertEquals("my:甲", replies.get(0).created());
        assertEquals(1, library.entries("voice").size(), "那一行不进表");
    }

    @Test
    void theRequestNumberSurvivesTheWireBothWays() {
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        LibraryRequestPayload.STREAM_CODEC.encode(buf, new LibraryRequestPayload("permission", "put", "{}", 42));
        assertEquals(42, LibraryRequestPayload.STREAM_CODEC.decode(buf).request());
        LibraryPayload.STREAM_CODEC.encode(buf, new LibraryPayload("permission", "[]", "no", 42));
        assertEquals(42, LibraryPayload.STREAM_CODEC.decode(buf).request());
        LibraryPayload.STREAM_CODEC.encode(buf, new LibraryPayload("permission", "[]", ""));
        assertEquals(0, LibraryPayload.STREAM_CODEC.decode(buf).request(), "推送不带号");
    }

    @Test
    void askingPuttingAndDeletingAreRequestsNotChanges() {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", "乙");

        library.ask("provider");
        library.put("provider", entry);
        library.delete("provider", "p1", false);

        assertEquals(List.of(new LibraryRequestPayload("provider", "list", "{}"),
                new LibraryRequestPayload("provider", "put", "{\"name\":\"乙\"}"),
                new LibraryRequestPayload("provider", "delete", "{\"id\":\"p1\"}")), sent);
        assertEquals(0, library.entries("provider").size(), "表等服务端推回来才变");
    }

    @Test
    void aDeletedRowGoesAtOnceAndTheServersNextTableTakesOver() {
        library.onPayload(new LibraryPayload("provider",
                "[{\"id\":\"p1\",\"name\":\"甲\"},{\"id\":\"p2\",\"name\":\"乙\"}]", ""));

        library.delete("provider", "p1", false);

        assertEquals(1, library.entries("provider").size(), "删了的先画成办成,不等一个来回");
        assertEquals("p2", library.entries("provider").get(0).getAsJsonObject().get("id").getAsString());

        library.onPayload(new LibraryPayload("provider", "[{\"id\":\"p1\",\"name\":\"甲\"}]", "no such profile"));
        assertEquals(1, library.entries("provider").size());
        assertEquals("p1", library.entries("provider").get(0).getAsJsonObject().get("id").getAsString(),
                "服务端没办成就推回真表,以真表为准");
    }

    @Test
    void aBuiltinAndAMineRowOfTheSameNameAreTwoRowsAndDeletingOneKeepsTheOther() {
        library.onPayload(new LibraryPayload("persona",
                "[{\"id\":\"numen:小焰\",\"group\":\"builtin\"},{\"id\":\"numen:小焰\",\"group\":\"mine\"}]", ""));

        library.delete("persona", "numen:小焰", false);

        assertEquals(1, library.entries("persona").size());
        assertEquals("builtin", library.entries("persona").get(0).getAsJsonObject().get("group").getAsString());
        assertEquals(new LibraryRequestPayload("persona", "delete", "{\"id\":\"numen:小焰\"}"), sent.get(0));

        library.delete("persona", "numen:小焰", true);
        assertEquals(0, library.entries("persona").size());
        assertEquals("{\"id\":\"numen:小焰\",\"group\":\"builtin\"}", sent.get(1).json(), "删内置的带上组");
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

        library.delete("provider", "p1", false);
        assertEquals(true, library.version("provider") != told, "删了的行先画掉也算变");
    }
}
