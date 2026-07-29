package com.dwinovo.numen.core.follow;

import com.dwinovo.numen.core.pathing.bridge.ContextFactory;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.moves.NavigationCapabilities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class FollowContextProviderTest {

    @Test
    void providerImplementsPlayerNavContractWithSafeCapabilities() {
        PlayerNav.ContextProvider provider = FollowContextProvider.INSTANCE;

        assertNotNull(provider);
        assertSame(NavigationCapabilities.SAFE_FOLLOW,
                FollowContextProvider.capabilities());
    }

    @Test
    void searchAndExecutionShareOneImmutableContextBuilder() {
        ContextFactory.ContextBuilder first = FollowContextProvider.contextBuilder();
        ContextFactory.ContextBuilder second = FollowContextProvider.contextBuilder();

        assertNotNull(first);
        assertSame(first, second);
    }
}
