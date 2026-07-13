package com.dwinovo.numen.agent.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConvoLogRecoveryTest {
    @TempDir Path directory;

    @Test void readsLegacyLinesAndWritesIntegrityEnvelopeForNewMessages() throws Exception {
        ConvoLog log = log();
        Files.createDirectories(log.file().getParent());
        Files.writeString(log.file(), "{\"role\":\"user\",\"content\":\"legacy\"}\n", StandardCharsets.UTF_8);

        log.append(new ConvoState.Msg.User("new"));

        assertEquals(List.of(new ConvoState.Msg.User("legacy"), new ConvoState.Msg.User("new")), log.load(20));
        String[] lines = Files.readString(log.file()).strip().split("\\R");
        JsonObject newLine = JsonParser.parseString(lines[1]).getAsJsonObject();
        assertEquals(0L, newLine.get("seq").getAsLong());
        assertTrue(newLine.has("checksum"));
    }

    @Test void repairsTornTailAndPreservesItForDiagnostics() throws Exception {
        ConvoLog log = log();
        log.append(new ConvoState.Msg.User("safe"));
        Files.writeString(log.file(), "{\"role\":\"assistant\"", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertEquals(List.of(new ConvoState.Msg.User("safe")), log.load(20));
        assertTrue(Files.readString(log.file()).endsWith("\n"));
        Path diagnostics = log.file().getParent().resolve("diagnostics");
        assertTrue(Files.list(diagnostics).anyMatch(path -> path.getFileName().toString().contains("torn-tail")));
    }

    @Test void middleDamageRestoresPreCompactionBackup() throws Exception {
        ConvoLog log = log();
        log.append(new ConvoState.Msg.User("before"));
        log.appendCompactSummary("summary", List.of(), new JsonObject());
        List<String> lines = Files.readAllLines(log.file());
        JsonObject first = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        first.addProperty("content", "tampered");
        lines.set(0, first.toString());
        Files.write(log.file(), lines, StandardCharsets.UTF_8);

        assertEquals(List.of(new ConvoState.Msg.User("before")), log.load(20));
        assertTrue(Files.isDirectory(log.file().getParent().resolve("diagnostics")));
    }

    @Test void middleDamageWithoutBackupRestoresPeriodicSnapshot() throws Exception {
        ConvoLog log = log();
        for (int i = 0; i < ConversationJournal.SNAPSHOT_INTERVAL; i++) {
            log.append(new ConvoState.Msg.User("m" + i));
        }
        List<String> lines = Files.readAllLines(log.file());
        JsonObject damaged = JsonParser.parseString(lines.get(5)).getAsJsonObject();
        damaged.addProperty("content", "changed without checksum");
        lines.set(5, damaged.toString());
        Files.write(log.file(), lines, StandardCharsets.UTF_8);

        List<ConvoState.Msg> restored = log.load(100);

        assertEquals(ConversationJournal.SNAPSHOT_INTERVAL, restored.size());
        assertEquals(new ConvoState.Msg.User("m0"), restored.get(0));
        assertEquals(new ConvoState.Msg.User("m63"), restored.get(63));
    }

    @Test void unknownRoleInMiddleIsQuarantinedInsteadOfSilentlySkipped() throws Exception {
        ConvoLog log = log();
        log.append(new ConvoState.Msg.User("first"));
        Files.writeString(log.file(), "{\"role\":\"future-role\",\"content\":\"x\"}\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        log.append(new ConvoState.Msg.User("after"));

        assertEquals(List.of(), log.load(20));
        assertTrue(Files.isDirectory(log.file().getParent().resolve("diagnostics")));
    }

    @Test void missingPrimaryRestoresPreCompactionBackup() throws Exception {
        ConvoLog log = log();
        log.append(new ConvoState.Msg.User("before"));
        log.appendCompactSummary("summary", List.of(), new JsonObject());
        Files.delete(log.file());

        assertEquals(List.of(new ConvoState.Msg.User("before")), log.load(20));
        assertTrue(Files.isRegularFile(log.file()));
    }

    @Test void snapshotWithUnknownRoleIsRejectedSemantically() throws Exception {
        ConvoLog log = log();
        Files.createDirectories(log.file().getParent());
        Path snapshot = log.file().resolveSibling(log.file().getFileName() + ".snapshot.json");
        Files.writeString(snapshot,
                "{\"schema\":1,\"entries\":[{\"role\":\"future-role\",\"content\":\"x\"}]}",
                StandardCharsets.UTF_8);

        assertEquals(List.of(), log.load(20));
        assertFalse(Files.exists(snapshot));
        assertTrue(Files.isDirectory(log.file().getParent().resolve("diagnostics")));
    }

    @Test void completeInvalidFinalRecordIsQuarantinedRatherThanTreatedAsTorn() throws Exception {
        ConvoLog log = log();
        log.append(new ConvoState.Msg.User("safe"));
        Files.writeString(log.file(), "{\"role\":\"future-role\",\"content\":\"complete\"}",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertEquals(List.of(), log.load(20));
        Path diagnostics = log.file().getParent().resolve("diagnostics");
        assertTrue(Files.list(diagnostics).anyMatch(path -> path.getFileName().toString().contains("middle-damage")));
        assertFalse(Files.list(diagnostics).anyMatch(path -> path.getFileName().toString().contains("torn-tail")));
    }

    @Test void legacyRecordAfterIntegrityEnvelopeIsRejected() throws Exception {
        ConvoLog log = log();
        log.append(new ConvoState.Msg.User("protected"));
        Files.writeString(log.file(), "{\"role\":\"user\",\"content\":\"injected legacy\"}\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertEquals(List.of(), log.load(20));
        assertTrue(Files.isDirectory(log.file().getParent().resolve("diagnostics")));
    }

    @Test void periodicSnapshotKeepsOnlyBoundedRecentTail() throws Exception {
        ConvoLog log = log();
        for (int i = 0; i < 1_024; i++) log.append(new ConvoState.Msg.User("m" + i));

        Path snapshot = log.file().resolveSibling(log.file().getFileName() + ".snapshot.json");
        JsonObject root = JsonParser.parseString(Files.readString(snapshot)).getAsJsonObject();
        JsonArray entries = root.getAsJsonArray("entries");

        assertTrue(root.get("truncated").getAsBoolean());
        assertEquals(1_000, entries.size());
        assertEquals("m24", entries.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("m1023", entries.get(999).getAsJsonObject().get("content").getAsString());
    }

    @Test void boundedSnapshotTailSurvivesJournalReopen() throws Exception {
        UUID id = UUID.randomUUID();
        Path conversations = directory.resolve("conversations");
        ConvoLog first = ConvoLog.forEntity(conversations, id);
        for (int i = 0; i < 1_024; i++) first.append(new ConvoState.Msg.User("m" + i));

        ConvoLog reopened = ConvoLog.forEntity(conversations, id);
        for (int i = 1_024; i < 1_088; i++) reopened.append(new ConvoState.Msg.User("m" + i));

        Path snapshot = reopened.file().resolveSibling(reopened.file().getFileName() + ".snapshot.json");
        JsonObject root = JsonParser.parseString(Files.readString(snapshot)).getAsJsonObject();
        JsonArray entries = root.getAsJsonArray("entries");
        assertTrue(root.get("truncated").getAsBoolean());
        assertEquals(1_000, entries.size());
        assertEquals("m88", entries.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("m1087", entries.get(999).getAsJsonObject().get("content").getAsString());
    }

    private ConvoLog log() { return ConvoLog.forEntity(directory.resolve("conversations"), UUID.randomUUID()); }
}
