package com.dwinovo.numen.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeConnectionTest {
    @Test void constructorAttachesAnActiveEmbeddedChannel() {
        FakeConnection connection = new FakeConnection();
        assertNotNull(connection.channel());
        assertTrue(connection.channel().isActive());
    }
}
