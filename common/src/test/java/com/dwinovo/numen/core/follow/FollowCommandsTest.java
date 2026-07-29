package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

class FollowCommandsTest {

    @Test
    void commandTreeUsesExistingNumenRootAndExactFollowShape() {
        CommandDispatcher<CommandSourceStack> dispatcher =
                new CommandDispatcher<>();
        dispatcher.register(Commands.literal("numen")
                .then(Commands.literal("existing")));

        FollowCommands.register(dispatcher, FollowConfig.defaults());

        var numen = dispatcher.getRoot().getChild("numen");
        assertTrue(numen.getChild("existing") != null);
        var follow = numen.getChild("follow");
        assertTrue(follow != null);
        var name = follow.getChild(FollowCommands.COMPANION_ARGUMENT);
        assertTrue(name != null);
        assertEquals(1, name.getChildren().size());
        assertTrue(name.getChild(FollowCommands.ACTION_ARGUMENT) != null);
    }

    @ParameterizedTest
    @EnumSource(FollowAction.class)
    void commandActionUsesTheSharedCaseInsensitiveParser(FollowAction action) {
        assertEquals(action,
                FollowAction.parse(action.name()).orElseThrow());
        assertEquals(action,
                FollowAction.parse(action.argumentValue()).orElseThrow());
    }

    @Test
    void ownerCanResolveOnlyTheirExactOnlineCompanionName() {
        UUID owner = UUID.randomUUID();
        Candidate mine = new Candidate("Alpha", owner);
        Candidate theirs = new Candidate("Alpha", UUID.randomUUID());

        FollowCommands.Resolution<Candidate> result =
                resolve(List.of(theirs, mine), owner, "Alpha");

        assertEquals(FollowCommands.ResolutionCode.FOUND, result.code());
        assertSame(mine, result.value());
    }

    @Test
    void otherOwnersCompanionIsRejected() {
        UUID owner = UUID.randomUUID();
        Candidate theirs = new Candidate("Alpha", UUID.randomUUID());

        FollowCommands.Resolution<Candidate> result =
                resolve(List.of(theirs), owner, "Alpha");

        assertEquals(FollowCommands.ResolutionCode.NOT_FOUND, result.code());
        assertNull(result.value());
    }

    @Test
    void missingCompanionIsRejectedWithoutChoosingAFirstCompanion() {
        UUID owner = UUID.randomUUID();
        Candidate available = new Candidate("Beta", owner);

        FollowCommands.Resolution<Candidate> result =
                resolve(List.of(available), owner, "Missing");

        assertEquals(FollowCommands.ResolutionCode.NOT_FOUND, result.code());
    }

    @Test
    void multipleOwnedCompanionsAreResolvedByExplicitName() {
        UUID owner = UUID.randomUUID();
        Candidate alpha = new Candidate("Alpha", owner);
        Candidate beta = new Candidate("Beta", owner);

        assertSame(alpha,
                resolve(List.of(alpha, beta), owner, "Alpha").value());
        assertSame(beta,
                resolve(List.of(alpha, beta), owner, "Beta").value());
    }

    @Test
    void duplicateOwnedNamesAreRejectedAsAmbiguous() {
        UUID owner = UUID.randomUUID();

        FollowCommands.Resolution<Candidate> result = resolve(
                List.of(
                        new Candidate("Alpha", owner),
                        new Candidate("Alpha", owner)),
                owner,
                "Alpha");

        assertEquals(FollowCommands.ResolutionCode.AMBIGUOUS, result.code());
        assertNull(result.value());
    }

    @Test
    void allAndUuidTextCannotBypassExplicitHumanName() {
        UUID owner = UUID.randomUUID();
        UUID companionUuid = UUID.randomUUID();
        Candidate candidate = new Candidate(companionUuid.toString(), owner);

        assertEquals(FollowCommands.ResolutionCode.INVALID_NAME,
                resolve(List.of(candidate), owner, "all").code());
        assertEquals(FollowCommands.ResolutionCode.INVALID_NAME,
                resolve(List.of(candidate), owner, companionUuid.toString()).code());
    }

    @Test
    void emptyNameIsRejected() {
        assertEquals(FollowCommands.ResolutionCode.INVALID_NAME,
                resolve(List.of(), UUID.randomUUID(), " ").code());
    }

    @Test
    void nameResolutionNeverFallsBackToRecentOrCaseInsensitivePlayer() {
        UUID owner = UUID.randomUUID();
        Candidate candidate = new Candidate("Alpha", owner);

        assertEquals(FollowCommands.ResolutionCode.NOT_FOUND,
                resolve(List.of(candidate), owner, "alpha").code());
    }

    private static FollowCommands.Resolution<Candidate> resolve(
            List<Candidate> online, UUID owner, String requested) {
        return FollowCommands.resolveOwnedByName(
                online,
                owner,
                requested,
                Candidate::name,
                Candidate::ownerUuid);
    }

    private record Candidate(String name, UUID ownerUuid) {}
}
