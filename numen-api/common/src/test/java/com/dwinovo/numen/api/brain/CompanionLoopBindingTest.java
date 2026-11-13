package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.library.EntryId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 她的每项绑定记着是谁绑的({@link Bound}),这一个值从绑定文件一路到模型口、人设解析和推给客户端的状态;
 * 库条目改名只动改名的那个人绑的。{@link BrainFixture} 是无头的宿主。
 */
class CompanionLoopBindingTest {

    private static final UUID FRIEND = UUID.randomUUID();
    private static final EntryId PROFILE = EntryId.mine("档案");
    private static final EntryId VOICE = EntryId.mine("派蒙");

    private static JsonObject bindingFile(BrainFixture her) throws IOException {
        return JsonParser.parseString(Files.readString(her.home.resolve(Bindings.FILE))).getAsJsonObject();
    }

    /** 记下"谁绑的"之前写的绑定文件只有 id:读进来算创建者绑的,下一次落盘就带着他。 */
    @Test
    void aBindingFileWithOnlyIdsIsReadAsHerCreatorsAndWrittenBackWithHim() throws IOException {
        BrainFixture her = new BrainFixture();
        Files.writeString(her.home.resolve(Bindings.FILE), "{\"provider\":\"my:档案\",\"voice\":\"my:派蒙\",\"skin\":\"abc-slim\"}");

        BrainFixture again = her.reloaded();   // 重载时夹具再绑一次人设,这就是"下一次落盘"

        assertEquals(new Bound(her.creator, PROFILE), again.loop.bound(Slot.PROVIDER));
        assertEquals(new Bound(her.creator, VOICE), again.loop.bound(Slot.VOICE));
        assertEquals("abc-slim", again.loop.state().skinId());
        JsonObject file = bindingFile(again);
        assertEquals(her.creator.toString(), file.getAsJsonObject("provider").get("by").getAsString());
        assertEquals("my:派蒙", file.getAsJsonObject("voice").get("id").getAsString());
        assertEquals("abc-slim", file.get("skin").getAsString());
    }

    /** 档案是谁绑的,模型口收到的就是谁:解析、钥匙、记账都凭这一个值。 */
    @Test
    void theModelIsAskedWithWhoBoundTheProfile() throws IOException {
        BrainFixture her = new BrainFixture();
        her.mind.say("好");
        Bound byFriend = new Bound(FRIEND, PROFILE);
        her.loop.bind(Slot.PROVIDER, byFriend);

        her.loop.submitPrompt("在吗");
        her.tick(2);

        assertEquals(List.of(byFriend), her.mind.askedWith);
    }

    /** 人设正文在绑它的那个人的人设库里取:两个人各有一条同名的,她用的是绑的那位的。 */
    @Test
    void thePersonaTextComesFromTheLibraryOfWhoBoundIt() throws IOException {
        BrainFixture her = new BrainFixture();
        EntryId same = EntryId.mine("同名");
        her.personas.put(new Bound(her.creator, same), "创建者写的人设");
        her.personas.put(new Bound(FRIEND, same), "朋友写的人设");

        her.loop.bind(Slot.PERSONA, new Bound(FRIEND, same));

        assertTrue(her.loop.systemPrompt().contains("朋友写的人设"), her.loop.systemPrompt());
        assertFalse(her.loop.systemPrompt().contains("创建者写的人设"));
    }

    /** 推给客户端的状态里,每项绑定带着绑的人和他的名字;没绑的不在其中。 */
    @Test
    void herStateSaysWhoBoundEachItemAndHisName() throws IOException {
        BrainFixture her = new BrainFixture();
        her.names.put(FRIEND, "小柚");
        her.loop.bind(Slot.PROVIDER, new Bound(FRIEND, PROFILE));

        CompanionState state = her.loop.state();

        assertEquals(new CompanionState.Binding(new Bound(FRIEND, PROFILE), "小柚"), state.binding(Slot.PROVIDER));
        assertEquals(her.creator, state.binding(Slot.PERSONA).bound().by());
        assertEquals("", state.binding(Slot.PERSONA).byName(), "不知道名字的是空串");
        assertNull(state.binding(Slot.VOICE));
        assertEquals(state, CompanionState.fromJson(JsonParser.parseString(state.toJson().toString()).getAsJsonObject()));
    }

    /** 库条目改名只动改名的那个人绑的:别人绑的同名条目是别人库里的另一条。 */
    @Test
    void aRenamedEntryRepointsOnlyWhatItsOwnerBound() throws IOException {
        BrainFixture her = new BrainFixture();
        EntryId renamed = EntryId.mine("新名字");
        her.loop.bind(Slot.VOICE, new Bound(FRIEND, VOICE));

        assertFalse(her.loop.repoint(Slot.VOICE, new Bound(her.creator, VOICE), renamed), "创建者改了他自己的同名条目");
        assertEquals(new Bound(FRIEND, VOICE), her.loop.bound(Slot.VOICE));

        assertTrue(her.loop.repoint(Slot.VOICE, new Bound(FRIEND, VOICE), renamed), "朋友改了他绑的这一条");
        assertEquals(new Bound(FRIEND, renamed), her.loop.bound(Slot.VOICE), "绑在别人创建的同伴身上也跟着改指");
        assertEquals("my:新名字", bindingFile(her).getAsJsonObject("voice").get("id").getAsString());
    }
}
