# Fake Connection Address Compatibility Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Prevent EasyBot from crashing owner login when a Numen fake player exposes Netty's non-IP embedded remote address.

**Architecture:** Inject at the return of Minecraft's `Connection#getRemoteAddress` and normalize only Numen `FakeConnection` values that are not already `InetSocketAddress` instances. Keep the normalization policy as a private static Mixin helper, invoke it reflectively from policy tests, and assert its visibility so Mixin 0.8.7 can apply the class at runtime.

**Tech Stack:** Java 17, Sponge Mixin 0.8.5 compile API / 0.8.7 runtime, Minecraft 1.20.4 official mappings, JUnit 5, Gradle multi-loader build.

---

### Task 1: Add the failing compatibility tests

**Files:**
- Create: `common/src/test/java/com/dwinovo/numen/core/mixin/ConnectionRemoteAddressMixinTest.java`

**Step 1: Write the failing test**

Create tests that instantiate the published `FakeConnection`, pass its current embedded address to
`ConnectionRemoteAddressMixin.numen$compatibleRemoteAddress`, and require a resolved loopback
`InetSocketAddress` on port `0`. Add preservation tests for a normal `Connection` and an
already-compatible fake address.

**Step 2: Run the focused test to verify it fails**

Run: `.\\gradlew.bat :common:test --tests com.dwinovo.numen.core.mixin.ConnectionRemoteAddressMixinTest --offline`

Expected: FAIL during test compilation because `ConnectionRemoteAddressMixin` does not exist yet.

### Task 2: Implement the minimal Mixin

**Files:**
- Create: `common/src/main/java/com/dwinovo/numen/core/mixin/ConnectionRemoteAddressMixin.java`
- Modify: `common/src/main/resources/numen.mixins.json`

**Step 1: Add the compatibility policy**

Implement a static resolved loopback `InetSocketAddress` and a package-visible method that returns it
only when the connection is `FakeConnection` and the original address is not already an
`InetSocketAddress`.

**Step 2: Add the runtime hook**

Inject at `Connection#getRemoteAddress` return with a cancellable
`CallbackInfoReturnable<SocketAddress>`. Replace the return value only when the compatibility method
returns a different object.

**Step 3: Register the Mixin**

Add `ConnectionRemoteAddressMixin` to `numen.mixins.json`.

**Step 4: Run the focused test to verify it passes**

Run: `.\\gradlew.bat :common:test --tests com.dwinovo.numen.core.mixin.ConnectionRemoteAddressMixinTest --offline`

Expected: PASS.

### Task 3: Verify the repository

**Files:**
- Verify: all changed files

**Step 1: Run all unit tests**

Run: `.\\gradlew.bat test --offline`

Expected: all tests pass.

**Step 2: Build all loader artifacts**

Run: `.\\gradlew.bat build --offline`

Expected: BUILD SUCCESSFUL, including Fabric remapping and Forge assembly.

**Step 3: Check the patch**

Run: `git diff --check`

Expected: no whitespace errors.

**Step 4: Review changed files**

Run: `git diff -- common/src/main/java/com/dwinovo/numen/core/mixin/ConnectionRemoteAddressMixin.java common/src/main/resources/numen.mixins.json common/src/test/java/com/dwinovo/numen/core/mixin/ConnectionRemoteAddressMixinTest.java`

Expected: only the scoped compatibility hook, registration, and regression tests.

### Task 4: Enforce Mixin Runtime Visibility

**Files:**
- Modify: `common/build.gradle`
- Modify: `common/src/test/java/com/dwinovo/numen/core/mixin/ConnectionRemoteAddressMixinTest.java`
- Modify: `common/src/main/java/com/dwinovo/numen/core/mixin/ConnectionRemoteAddressMixin.java`

**Step 1: Add Mixin to the test runtime**

Add `testRuntimeOnly group: 'org.spongepowered', name: 'mixin', version: '0.8.5'`. Reflection resolves
the injection handler's `CallbackInfoReturnable` type, while the production artifacts continue to use
the loader-provided runtime.

**Step 2: Make existing policy tests compatible with a private helper**

Add a test-only reflective invocation helper using `getDeclaredMethod`, `setAccessible(true)`, and
`Method#invoke`. Route the three existing policy tests through it. Production code remains unchanged.

**Step 3: Write the failing visibility regression test**

Resolve `numen$compatibleRemoteAddress(Connection, SocketAddress)` and assert that
`Modifier.isPrivate(method.getModifiers())` is true.

**Step 4: Run the focused test to verify it fails for the reported reason**

Run: `.\\gradlew.bat :common:test --tests com.dwinovo.numen.core.mixin.ConnectionRemoteAddressMixinTest`

Expected: FAIL only in the visibility regression test because the helper is package-visible.

**Step 5: Implement the minimal production fix**

Change the helper declaration from `static SocketAddress` to `private static SocketAddress`. Do not
alter address selection or injection behavior.

**Step 6: Run the focused test to verify it passes**

Run: `.\\gradlew.bat :common:test --tests com.dwinovo.numen.core.mixin.ConnectionRemoteAddressMixinTest`

Expected: all four tests PASS.

**Step 7: Verify runtime packaging and the full repository**

Run the Fabric server far enough to apply `numen.mixins.json`, then run `.\\gradlew.bat build`, inspect
the remapped Fabric JAR with `javap -p`, and run `git diff --check`.

Expected: no `InvalidMixinException`, a private compiled helper, a successful multi-loader build, and
no whitespace errors.
