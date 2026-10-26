package com.dwinovo.numen.api.plugin;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.Delivery;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.gear.GearSource;
import com.dwinovo.numen.api.library.LibraryKind;
import com.dwinovo.numen.api.script.Modules;
import com.dwinovo.numen.api.sdk.Codec;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 装一个插件时交给它的那扇门:只记账——它登记了几组 API、几个技能、几条指令……——再原样转给真正的门。登记本身(检查、
 * 报错、顺序)一点不变,所以"插件加了什么"不靠插件作者多填一份清单,也不会和真正登记下来的对不上。登记抛了就不记。
 */
final class CountingApi implements NumenApi {

    private final NumenApi door;
    private final BiConsumer<PluginRoster.Kind, Integer> ledger;

    /** @param ledger 记账:这一种加了几件 */
    CountingApi(NumenApi door, BiConsumer<PluginRoster.Kind, Integer> ledger) {
        this.door = door;
        this.ledger = ledger;
    }

    private void one(PluginRoster.Kind kind) {
        ledger.accept(kind, 1);
    }

    @Override
    public <T> void on(CompanionEvent<T> event, Consumer<T> handler) {
        door.on(event, handler);
        one(PluginRoster.Kind.EVENT_LISTENER);
    }

    @Override
    public void api(String group, String summary, Class<?> functions) {
        door.api(group, summary, functions);
        one(PluginRoster.Kind.API_GROUP);
    }

    @Override
    public <T> void codec(Class<T> type, Codec<T> codec) {
        door.codec(type, codec);
    }

    @Override
    public void bundleContent(Path root) {
        door.bundleContent(root);
        if (root != null) {
            ledger.accept(PluginRoster.Kind.SKILL, entries(root, SkillRegistry.KIND));
            ledger.accept(PluginRoster.Kind.MODULE, entries(root, Modules.KIND));
        }
    }

    @Override
    public void command(LiteralArgumentBuilder<CommandSourceStack> verb) {
        door.command(verb);
        if (verb != null) one(PluginRoster.Kind.COMMAND);
    }

    @Override
    public void onClient(Runnable clientOnly) {
        door.onClient(clientOnly);
    }

    @Override
    public Path configDir() {
        return door.configDir();
    }

    @Override
    public void contributeBodyState(Function<NumenPlayer, String> fragment) {
        door.contributeBodyState(fragment);
        if (fragment != null) one(PluginRoster.Kind.BODY_STATE);
    }

    @Override
    public void registerGear(GearSource source) {
        door.registerGear(source);
        if (source != null) one(PluginRoster.Kind.GEAR_SOURCE);
    }

    @Override
    public void registerEventType(String type, boolean alwaysUrgent) {
        door.registerEventType(type, alwaysUrgent);
        one(PluginRoster.Kind.EVENT_TYPE);
    }

    @Override
    public void emit(NumenPlayer companion, String type, Map<String, String> attrs, String text, boolean urgent) {
        door.emit(companion, type, attrs, text, urgent);
    }

    @Override
    public Delivery emit(UUID companion, String type, String text) {
        return door.emit(companion, type, text);
    }

    /** 一个 {@code numen/} 目录里这种库有几条:{@code <库>/<命名空间>/<名字>} 的名字。 */
    private static int entries(Path root, LibraryKind kind) {
        Path dir = root.resolve(kind.dir());
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int n = 0;
        try (Stream<Path> spaces = Files.list(dir)) {
            for (Path space : spaces.filter(Files::isDirectory).toList()) {
                try (Stream<Path> items = Files.list(space)) {
                    n += (int) items.filter(item -> kind.isDirectory()
                            ? Files.isRegularFile(item.resolve(kind.marker()))
                            : item.getFileName().toString().endsWith(kind.suffix())).count();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return n;
    }
}
