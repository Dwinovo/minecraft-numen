package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.LibraryKind;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 房主个人目录里的库条目与钥匙并进服务器目录:可重来,同名按"我的优先"改名,不丢东西。 */
class HostMergeTest {

    private static final LibraryKind PERSONA = LibraryKind.files("persona", ".md");
    private static final LibraryKind SKILLS = LibraryKind.directories("skills", "SKILL.md");
    private static final List<LibraryKind> KINDS = List.of(PERSONA, SKILLS);

    @TempDir
    Path dir;

    private Path host() {
        return dir.resolve("players").resolve("host");
    }

    private Path server() {
        return dir.resolve("server");
    }

    private void put(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void entriesMoveOverWhetherTheyAreFilesOrDirectoriesAndTheEmptyShelvesGoAway() throws IOException {
        put(host().resolve("persona/my/甲.md"), "甲");
        put(host().resolve("skills/my/lumber/SKILL.md"), "技能");
        put(host().resolve("skills/my/lumber/references/trees.md"), "树");
        put(server().resolve("persona/numen/小焰.md"), "自带");

        List<HostMerge.Renamed> renamed = HostMerge.libraries(host(), server(), KINDS);

        assertTrue(renamed.isEmpty());
        assertEquals("甲", Files.readString(server().resolve("persona/my/甲.md")));
        assertEquals("树", Files.readString(server().resolve("skills/my/lumber/references/trees.md")), "目录整个搬");
        assertEquals("自带", Files.readString(server().resolve("persona/numen/小焰.md")), "服务器原有的不动");
        assertFalse(Files.exists(host().resolve("persona")) || Files.exists(host().resolve("skills")), "个人目录里搬空的库不留");
    }

    @Test
    void aSameNameWithOtherContentKeepsTheServersAndMovesTheHostsUnderANewName() throws IOException {
        put(server().resolve("persona/numen/小焰.md"), "服务器的");
        put(host().resolve("persona/numen/小焰.md"), "他改的");
        put(host().resolve("skills/numen/mining/SKILL.md"), "他的技能");
        put(server().resolve("skills/numen/mining/SKILL.md"), "原来的技能");

        List<HostMerge.Renamed> renamed = HostMerge.libraries(host(), server(), KINDS);

        assertEquals(List.of(
                new HostMerge.Renamed(PERSONA, new EntryId("numen", "小焰"), new EntryId("numen", "小焰_2")),
                new HostMerge.Renamed(SKILLS, new EntryId("numen", "mining"), new EntryId("numen", "mining_2"))), renamed);
        assertEquals("服务器的", Files.readString(server().resolve("persona/numen/小焰.md")));
        assertEquals("他改的", Files.readString(server().resolve("persona/numen/小焰_2.md")), "他原来读到的内容原样在新名字下");
        assertEquals("他的技能", Files.readString(server().resolve("skills/numen/mining_2/SKILL.md")));
        assertEquals("原来的技能", Files.readString(server().resolve("skills/numen/mining/SKILL.md")));
    }

    @Test
    void aSameNameWithTheSameContentJustDropsTheHostsCopy() throws IOException {
        put(server().resolve("persona/numen/小焰.md"), "一样");
        put(host().resolve("persona/numen/小焰.md"), "一样");

        assertTrue(HostMerge.libraries(host(), server(), KINDS).isEmpty());

        assertFalse(Files.exists(host().resolve("persona")));
        assertFalse(Files.exists(server().resolve("persona/numen/小焰_2.md")));
    }

    @Test
    void mergingAgainFindsNothingToMoveAndLeavesWhatIsNotAnEntryAlone() throws IOException {
        put(host().resolve("persona/my/甲.md"), "甲");
        put(host().resolve("persona/my/甲.md.new"), "不是条目");
        put(host().resolve("persona/loose.md"), "不在命名空间里");
        HostMerge.libraries(host(), server(), KINDS);

        assertTrue(HostMerge.libraries(host(), server(), KINDS).isEmpty());

        assertEquals("甲", Files.readString(server().resolve("persona/my/甲.md")));
        assertEquals("不是条目", Files.readString(host().resolve("persona/my/甲.md.new")));
        assertEquals("不在命名空间里", Files.readString(host().resolve("persona/loose.md")));
    }

    @Test
    void theHostsKeyWinsOverTheServersAndTheReplacedServiceIsReported() throws IOException {
        put(host().resolve("keys/openai.key"), "sk-host");
        put(host().resolve("keys/deepseek.key"), "sk-same");
        put(host().resolve("keys/moonshotai.key"), "sk-new");
        put(server().resolve("keys/openai.key"), "sk-server");
        put(server().resolve("keys/deepseek.key"), "sk-same");

        List<String> replaced = HostMerge.keys(host().resolve("keys"), server().resolve("keys"));

        assertEquals(List.of("openai"), replaced);
        assertEquals("sk-host", Files.readString(server().resolve("keys/openai.key")));
        assertEquals("sk-same", Files.readString(server().resolve("keys/deepseek.key")));
        assertEquals("sk-new", Files.readString(server().resolve("keys/moonshotai.key")));
        assertFalse(Files.exists(host().resolve("keys")), "搬空了不留");
        assertTrue(HostMerge.keys(host().resolve("keys"), server().resolve("keys")).isEmpty(), "再来一次没有什么可搬的");
    }

    /**
     * 改了名的是房主"我的"里的条目,所以改指的是<b>房主绑的</b>那些绑定:他自己的同伴(只有 id 的旧值算创建者绑的)和别人创建、
     * 他绑了这一条的同伴;别人绑的同名条目指的是服务器目录里原有的那一条,不动。
     */
    @Test
    void whatTheHostBoundFollowsTheRenamedEntryWhoeverCreatedHer() throws IOException {
        UUID host = UUID.randomUUID();
        UUID guest = UUID.randomUUID();
        EntryId old = new EntryId("numen", "小焰");
        Path hers = dir.resolve("hers").resolve("binding.json");
        put(hers, "{\"persona\":\"numen:小焰\",\"voice\":\"my:派蒙\"}");
        Path shared = dir.resolve("shared").resolve("binding.json");
        put(shared, "{\"persona\":" + new Bound(host, old).toJson() + "}");
        Path guests = dir.resolve("guests").resolve("binding.json");
        put(guests, "{\"persona\":\"numen:小焰\"}");
        Map<Path, UUID> creators = new LinkedHashMap<>();
        creators.put(hers, host);
        creators.put(shared, guest);
        creators.put(guests, guest);
        creators.put(dir.resolve("none.json"), host);

        int changed = HostMerge.rebind(creators, host, List.of(
                new HostMerge.Renamed(PERSONA, old, new EntryId("numen", "小焰_2"))));

        assertEquals(2, changed);
        Bindings now = Bindings.read(JsonParser.parseString(Files.readString(hers)).getAsJsonObject(), guest);
        assertEquals(new Bound(host, new EntryId("numen", "小焰_2")), now.bound(Slot.PERSONA));
        assertEquals(new Bound(host, EntryId.mine("派蒙")), now.bound(Slot.VOICE), "别的项不动");
        assertEquals(new Bound(host, new EntryId("numen", "小焰_2")),
                Bindings.read(JsonParser.parseString(Files.readString(shared)).getAsJsonObject(), guest).bound(Slot.PERSONA),
                "房主绑在别人创建的同伴身上的也跟着改指");
        assertEquals("{\"persona\":\"numen:小焰\"}", Files.readString(guests), "别人绑的同名条目不动");
    }
}
