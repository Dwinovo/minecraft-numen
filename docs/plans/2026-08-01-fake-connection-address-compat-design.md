# Fake Connection Address Compatibility Design

## Problem

Numen companions are server-side fake players created through the vanilla player join path. Their
`FakeConnection` owns a Netty `EmbeddedChannel`, whose remote address is an
`EmbeddedSocketAddress`. Fabric therefore publishes a normal `ServerPlayConnectionEvents.JOIN`
event for the companion with a non-IP `SocketAddress`.

EasyBot assumes every joined player has an `InetSocketAddress` and casts without checking. When an
owner logs in and Numen respawns a companion synchronously, EasyBot's exception escapes through the
owner's join call and Minecraft reports `Invalid player data`.

## Constraints

- The current repository owns Numen Core, not the `FakeConnection` implementation in `numen-api`.
- The fix must work with the already published `numen-api` 0.0.7 and 0.0.8 artifacts.
- Real network connections must retain their real remote address.
- A future `numen-api` release that supplies an `InetSocketAddress` must not be overwritten.
- The vanilla player join path and Fabric join events must remain intact.
- Every static method declared by a Mixin class must be private so Mixin 0.8.7 can apply it.

## Design

Add a common Mixin on `net.minecraft.network.Connection#getRemoteAddress`. At method return, inspect
the receiver and the original address:

- For a `com.dwinovo.numen.entity.FakeConnection` whose address is not an
  `InetSocketAddress`, return a resolved loopback address on port `0`.
- For real connections, return the original object unchanged.
- For a future already-compatible fake connection, return its original `InetSocketAddress`
  unchanged.

The compatibility policy lives in a private static method on the Mixin class. This satisfies Mixin's
runtime visibility rules while keeping the hook small. Ordinary JUnit tests invoke that private method
reflectively so the policy remains directly covered without bootstrapping a Minecraft Mixin runtime.
The test runtime includes the same Mixin API already used at compile time because Java reflection must
resolve the injection handler's `CallbackInfoReturnable` parameter while enumerating declared methods;
this dependency remains test-only and is not bundled into either loader artifact.

## Data Flow

1. Numen creates a `FakeConnection` backed by an `EmbeddedChannel`.
2. A mod calls the standard `Connection#getRemoteAddress` API during the fake player's join event.
3. The Mixin receives the original embedded address.
4. The compatibility policy substitutes `127.0.0.1:0` (or the JVM's resolved loopback equivalent).
5. EasyBot receives an `InetSocketAddress`; real player addresses are untouched.

## Error Handling

The hook does not catch third-party exceptions and does not alter event ordering. It only normalizes
the fake connection's address contract. Port `0` communicates that there is no real remote socket,
while a resolved loopback address avoids null-address failures in code that calls
`InetSocketAddress#getAddress`.

## Testing

Unit tests cover three cases:

1. The current `FakeConnection` embedded address becomes a resolved loopback
   `InetSocketAddress` on port `0`.
2. A normal `Connection` keeps the exact original address object.
3. An already-compatible fake connection address is preserved, protecting future API fixes.

A structural regression test also requires the compatibility helper to remain private. This mirrors
Mixin 0.8.7's applicator check and prevents a build-only verification run from producing another JAR
that compiles and remaps successfully but fails when the server transforms `Connection`.

The full Gradle test and build tasks then verify compilation, Mixin remapping, resources, and both
loader artifacts.
