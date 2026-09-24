package com.dwinovo.numen.client.agent;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话分组:谁归哪个分组、选中的分组怎么落盘。私聊/群聊的判据由调用方从 soloOf 传进来,
 * 所以这里钉的是"哪个分组收哪一类",以及落盘能原样读回来。
 */
class ChatFoldersTest {

    @Test
    void builtInsSortBySoloness() {
        ChatFolders f = new ChatFolders();
        assertTrue(f.includes(ChatFolders.ALL, true));
        assertTrue(f.includes(ChatFolders.ALL, false));
        assertTrue(f.includes(ChatFolders.SOLO, true));
        assertFalse(f.includes(ChatFolders.SOLO, false));
        assertTrue(f.includes(ChatFolders.GROUP, false));
        assertFalse(f.includes(ChatFolders.GROUP, true));
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
    void activeFolderRoundTrips() {
        ChatFolders f = new ChatFolders();
        f.select(ChatFolders.SOLO);
        JsonObject root = new JsonObject();
        f.write(root);

        ChatFolders back = new ChatFolders();
        back.read(root);
        assertEquals(ChatFolders.SOLO, back.active());
    }

    @Test
    void allIsNotWritten() {
        JsonObject root = new JsonObject();
        new ChatFolders().write(root);
        assertFalse(root.has("folder"), "选的是全部就没东西要存");
    }

    @Test
    void readingAFileWithoutFoldersResetsToAll() {
        ChatFolders f = new ChatFolders();
        f.select(ChatFolders.GROUP);
        f.read(new JsonObject());
        assertEquals(ChatFolders.ALL, f.active(), "读的是另一份文件,上一份的选择不能留下来");
    }
}
