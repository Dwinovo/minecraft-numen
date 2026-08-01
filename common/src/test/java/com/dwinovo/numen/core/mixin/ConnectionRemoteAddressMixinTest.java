package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.entity.FakeConnection;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionRemoteAddressMixinTest {

    @Test
    void convertsEmbeddedFakeConnectionAddressToResolvedLoopback() {
        FakeConnection connection = new FakeConnection();
        SocketAddress original = connection.getRemoteAddress();
        assertFalse(original instanceof InetSocketAddress);

        SocketAddress compatible = invokeCompatibleRemoteAddress(connection, original);

        InetSocketAddress address = assertInstanceOf(InetSocketAddress.class, compatible);
        assertNotNull(address.getAddress());
        assertTrue(address.getAddress().isLoopbackAddress());
        assertEquals(0, address.getPort());
    }

    @Test
    void preservesRealConnectionAddress() {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        InetSocketAddress original = new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);

        assertSame(original, invokeCompatibleRemoteAddress(connection, original));
    }

    @Test
    void preservesAlreadyCompatibleFakeConnectionAddress() {
        FakeConnection connection = new FakeConnection();
        InetSocketAddress original = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);

        assertSame(original, invokeCompatibleRemoteAddress(connection, original));
    }

    @Test
    void keepsStaticCompatibilityHelperPrivateForMixinRuntime() {
        Method method = compatibleRemoteAddressMethod();

        assertTrue(Modifier.isPrivate(method.getModifiers()),
                "Mixin 0.8.7 rejects non-private static methods during application");
    }

    private static SocketAddress invokeCompatibleRemoteAddress(
            Connection connection, SocketAddress original) {
        try {
            return (SocketAddress) compatibleRemoteAddressMethod().invoke(null, connection, original);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("Could not invoke compatibility helper", e);
        }
    }

    private static Method compatibleRemoteAddressMethod() {
        try {
            Method method = ConnectionRemoteAddressMixin.class.getDeclaredMethod(
                    "numen$compatibleRemoteAddress", Connection.class, SocketAddress.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Compatibility helper is missing", e);
        }
    }
}
