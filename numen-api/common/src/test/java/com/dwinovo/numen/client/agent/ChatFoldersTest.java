package com.dwinovo.numen.client.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话分组:谁归哪个分组、自建分组怎么改、落盘能不能原样读回来。私聊/群聊的判据由调用方从 soloOf
 * 传进来,所以这里钉的是"哪个分组收哪些会话"。
 */
class ChatFoldersTest {

    @Test
    void builtInsSortBySoloness() {
        ChatFolders f = new ChatFolders();
        assertTrue(f.includes(ChatFolders.ALL, "a", true));
        assertTrue(f.includes(ChatFolders.ALL, "g", false));
        assertTrue(f.includes(ChatFolders.SOLO, "a", true));
        assertFalse(f.includes(ChatFolders.SOLO, "g", false));
        assertTrue(f.includes(ChatFolders.GROUP, "g", false));
        assertFalse(f.includes(ChatFolders.GROUP, "a", true));
    }

    @Test
    void customFolderHoldsExactlyWhatWasPicked() {
        ChatFolders f = new ChatFolders();
        ChatFolders.Folder work = f.create("  挖矿  ", List.of("a", "g", "a"));
        assertEquals("挖矿", work.name(), "名字去掉两头空白");
        assertEquals(List.of("a", "g"), work.chats(), "同一个会话只收一次");
        assertTrue(f.includes(work.id(), "a", true));
        assertTrue(f.includes(work.id(), "g", false));
        assertFalse(f.includes(work.id(), "b", true), "私聊不因为是私聊就进自建分组");
        assertFalse(f.includes("nope", "a", true));
    }

    @Test
    void customFoldersFollowTheBuiltInsInCreationOrder() {
        ChatFolders f = new ChatFolders();
        ChatFolders.Folder one = f.create("一", List.of("a"));
        ChatFolders.Folder two = f.create("二", List.of("a"));
        assertEquals(List.of(ChatFolders.ALL, ChatFolders.SOLO, ChatFolders.GROUP, one.id(), two.id()), f.ids());
    }

    @Test
    void editKeepsPositionAndReplacesContent() {
        ChatFolders f = new ChatFolders();
        ChatFolders.Folder one = f.create("一", List.of("a"));
        ChatFolders.Folder two = f.create("二", List.of("a"));
        assertTrue(f.edit(one.id(), "壹", List.of("b")));
        assertEquals("壹", f.get(one.id()).name());
        assertEquals(List.of("b"), f.get(one.id()).chats());
        assertEquals(one.id(), f.ids().get(3), "改了不挪位置");
        assertEquals(two.id(), f.ids().get(4));
        assertFalse(f.edit("nope", "x", List.of("a")));
    }

    @Test
    void toggleAddsThenRemoves() {
        ChatFolders f = new ChatFolders();
        ChatFolders.Folder one = f.create("一", List.of("a"));
        assertTrue(f.toggle(one.id(), "b"));
        assertEquals(List.of("a", "b"), f.get(one.id()).chats());
        assertTrue(f.toggle(one.id(), "a"));
        assertEquals(List.of("b"), f.get(one.id()).chats());
        assertFalse(f.toggle(ChatFolders.SOLO, "a"), "内置分组按会话本身归,放不进");
    }

    @Test
    void deletingTheActiveFolderFallsBackToAll() {
        ChatFolders f = new ChatFolders();
        ChatFolders.Folder one = f.create("一", List.of("a"));
        assertTrue(f.select(one.id()));
        assertTrue(f.delete(one.id()));
        assertEquals(ChatFolders.ALL, f.active());
        assertNull(f.get(one.id()));
        assertFalse(f.delete(one.id()));
        assertFalse(f.delete(ChatFolders.GROUP), "内置分组删不掉");
    }

    @Test
    void deletingAnotherFolderKeepsTheSelection() {
        ChatFolders f = new ChatFolders();
        ChatFolders.Folder one = f.create("一", List.of("a"));
        f.select(ChatFolders.GROUP);
        f.delete(one.id());
        assertEquals(ChatFolders.GROUP, f.active());
    }

    @Test
    void forgetDropsAConversationFromEveryFolder() {
        ChatFolders f = new ChatFolders();
        ChatFolders.Folder one = f.create("一", List.of("g", "a"));
        ChatFolders.Folder two = f.create("二", List.of("g"));
        assertTrue(f.forget("g"));
        assertEquals(List.of("a"), f.get(one.id()).chats());
        assertEquals(List.of(), f.get(two.id()).chats());
        assertFalse(f.forget("g"), "没东西可拿就不该触发落盘");
    }

    @Test
    void startsOnAllAndAllComesFirst() {
        ChatFolders f = new ChatFolders();
        assertEquals(ChatFolders.ALL, f.active());
        assertEquals(ChatFolders.ALL, f.ids().get(0), "全部永远在最前");
    }

    @Test
    void selectReportsChangeOnlyWhenItChanges() {
        ChatFolders f = new ChatFolders();
        assertTrue(f.select(ChatFolders.GROUP));
        assertFalse(f.select(ChatFolders.GROUP), "没变就不该触发落盘");
        assertFalse(f.select("nope"), "不认识的分组不接");
        assertEquals(ChatFolders.GROUP, f.active());
    }

    @Test
    void foldersAndSelectionRoundTrip() {
        ChatFolders f = new ChatFolders();
        ChatFolders.Folder one = f.create("一", List.of("a", "g"));
        f.select(one.id());
        JsonObject root = new JsonObject();
        f.write(root);

        ChatFolders back = new ChatFolders();
        back.read(JsonParser.parseString(root.toString()).getAsJsonObject());
        assertEquals(f.ids(), back.ids());
        assertEquals(one, back.get(one.id()));
        assertEquals(one.id(), back.active(), "选中的是自建分组也得读回来");
    }

    @Test
    void nothingToSaveWritesNothing() {
        JsonObject root = new JsonObject();
        new ChatFolders().write(root);
        assertFalse(root.has("folder"), "选的是全部就没东西要存");
        assertFalse(root.has("folders"));
    }

    @Test
    void unreadableFoldersAreSkipped() {
        ChatFolders f = new ChatFolders();
        f.read(JsonParser.parseString("""
                {"folders": [
                  {"id": "f1", "name": "好的", "chats": ["a", 3, {"x": 1}]},
                  {"id": "f2", "name": "   "},
                  {"name": "没有 id"},
                  "垃圾"
                ], "folder": "f2"}
                """).getAsJsonObject());
        assertEquals(List.of(ChatFolders.ALL, ChatFolders.SOLO, ChatFolders.GROUP, "f1"), f.ids());
        assertEquals(List.of("a", "3"), f.get("f1").chats());
        assertEquals(ChatFolders.ALL, f.active(), "选中的分组没读进来,就停在全部");
    }

    @Test
    void readingAnotherFileReplacesEverything() {
        ChatFolders f = new ChatFolders();
        f.select(f.create("一", List.of("a")).id());
        f.read(new JsonObject());
        assertEquals(ChatFolders.ALL, f.active(), "读的是另一份文件,上一份的选择不能留下来");
        assertEquals(3, f.ids().size(), "上一份的自建分组也不能留下来");
    }
}
