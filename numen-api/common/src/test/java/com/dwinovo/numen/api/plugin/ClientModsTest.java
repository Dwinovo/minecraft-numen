package com.dwinovo.numen.api.plugin;

import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.brain.Presence;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.script.Modules;
import com.dwinovo.numen.api.sdk.ApiDocs;
import com.dwinovo.numen.api.sdk.ApiRegistry;
import com.dwinovo.numen.api.sdk.ClientCall;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.SdkFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目标模组只在客户端的联动:模型的 API 清单、帮助、技能表里有没有它,只看主人的客户端报到时报了什么({@code Presence}),
 * 一位主人报到齐之前、没报、下线之后都没有;别的名字空间不受影响。
 */
class ClientModsTest {

    private static final UUID HER = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final String NAMESPACE = "gtcm";

    /** 一个只在客户端的联动的一组函数。 */
    public static final class Pad {

        private Pad() {}

        @Fn("Read the pad.")
        @Example("gtcm.pad.read()")
        public static String read(ClientCall call) {
            return "";
        }
    }

    @TempDir
    static Path home;

    private static SkillRegistry skills;

    @BeforeAll
    static void install() throws IOException, ClassNotFoundException {
        Class.forName(SdkFixture.class.getName());   // 夹具的初始化:引擎自己的几组登记好、模块库接好
        ApiRegistry.register(NAMESPACE, "pad", "The pad of a client-only mod.", Pad.class);
        ClientMods.declare(NAMESPACE, "gt_pad_mod");
        ClientMods.bindOwners(companion -> OWNER);
        Path skill = Files.createDirectories(home.resolve("server").resolve("skills").resolve(NAMESPACE).resolve("padwork"));
        Files.writeString(skill.resolve(SkillRegistry.SKILL_FILENAME), """
                ---
                name: padwork
                description: What the pad of the client-only mod reads.
                ---

                # Pad
                """);
        var library = LibraryHub.headless(home).library(SkillRegistry.KIND, UUID.randomUUID());
        SkillRegistry.init(companion -> library);
        skills = SkillRegistry.of(HER);
    }

    @AfterEach
    void ownerLeaves() {
        Presence.left(OWNER);
    }

    private static boolean listed() {
        return ApiDocs.index(Modules.of(HER)).contains("gtcm.pad");
    }

    @Test
    void anOwnerWhoHasNotReportedSeesNeitherTheGroupNorItsHelpNorItsSkill() {
        assertFalse(listed());
        assertTrue(ApiDocs.help("gtcm.pad", Modules.of(HER)) == null && ApiDocs.help("gtcm.pad.read", Modules.of(HER)) == null
                && ApiDocs.help(NAMESPACE, Modules.of(HER)) == null);
        assertFalse(skills.formatXml().contains("padwork"), skills.formatXml());
        assertTrue(skills.get("padwork").isEmpty());
    }

    @Test
    void aVersionWithoutTheCapabilitiesIsNotYetAClientAndNothingIsListed() {
        assertTrue(Presence.reported(OWNER, Presence.PROTOCOL));
        assertFalse(Presence.hasClient(OWNER));
        assertFalse(listed());
    }

    @Test
    void anOwnerWhoseClientProvidesItSeesTheGroupItsHelpAndItsSkill() {
        Presence.reported(OWNER, Presence.PROTOCOL);
        assertTrue(Presence.ready(OWNER, Set.of(NAMESPACE)));
        assertTrue(Presence.hasClient(OWNER) && listed());
        assertTrue(ApiDocs.help("gtcm.pad.read", Modules.of(HER)) != null);
        assertTrue(skills.formatXml().contains("<name>padwork</name>"), skills.formatXml());
        assertTrue(skills.get("padwork").isPresent());
    }

    @Test
    void aClientThatDoesNotProvideItIsStillAClientButTheGroupStaysOut() {
        Presence.reported(OWNER, Presence.PROTOCOL);
        assertTrue(Presence.ready(OWNER, Set.of()));
        assertTrue(Presence.hasClient(OWNER));
        assertFalse(listed());
        assertTrue(ClientMods.offered(HER, "numen"), "a namespace that is not client-only is always offered");
    }

    @Test
    void theCapabilitiesAloneOrFromAnotherVersionDoNotMakeAClient() {
        assertFalse(Presence.ready(OWNER, Set.of(NAMESPACE)), "capabilities without a reported version");
        assertFalse(Presence.reported(OWNER, Presence.PROTOCOL + 1));
        assertFalse(Presence.ready(OWNER, Set.of(NAMESPACE)));
        assertFalse(Presence.hasClient(OWNER) || listed());
    }

    @Test
    void whenTheOwnerLeavesTheyAreGoneAgain() {
        Presence.reported(OWNER, Presence.PROTOCOL);
        Presence.ready(OWNER, Set.of(NAMESPACE));
        Presence.left(OWNER);
        assertFalse(Presence.hasClient(OWNER) || listed());
    }
}
