package com.dwinovo.numen.api;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.persona.PersonaExtension;
import com.dwinovo.numen.entity.CompanionEvents;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 插件的登记处:{@code NumenPlugins.register(numen -> …)}。
 *
 * <h2>时机不必你操心</h2>
 * 插件在自己模组的构造期登记就行。引擎内部该就绪的东西各有各的时机(工具注册表在
 * 类加载时就有,技能与头像要等客户端起来),这里替你等——先登记的先跑,能跑的立刻跑,
 * 跑不了的等它就绪。
 *
 * <h2>专用服务器上会安静地少几样</h2>
 * 技能和头像只活在玩家的客户端上。它们由客户端启动时{@linkplain #bindClient 接上来};
 * 专用服务器上没人接,于是 {@code bundleSkills} / {@code registerPortrait} 就是空操作。
 * <b>"有没有接上"本身就是判据</b>,不必再去问一遍"我现在是不是客户端"——那种问法
 * 每个加载器一个写法,写在插件里就是每个插件一个写法。
 */
public final class NumenPlugins {

    /** 客户端接上来的那几样;专用服务器上一直是 null,于是相关调用自然成空操作。 */
    private static volatile Consumer<Path> skills;
    private static volatile BiFunction<UUID, String, Delivery> enqueue;
    /** 客户端接上来了没有。它同时就是"我现在是不是客户端"的答案。 */
    private static volatile boolean clientReady;

    private static final NumenApi API = new Impl();
    private static final List<PersonaExtension> PERSONA_EXTENSIONS = new CopyOnWriteArrayList<>();

    private NumenPlugins() {}

    /** 登记一个插件。可在任何时候调用,通常在你模组的构造期。 */
    public static void register(NumenPlugin plugin) {
        if (plugin == null) return;
        try {
            plugin.setup(API);
        } catch (RuntimeException e) {
            Constants.LOG.error("[numen] 插件登记失败,它挂的东西可能只生效了一半", e);
        }
    }

    /**
     * 客户端起来时把只在客户端存在的能力接上来。<b>引擎内部调用</b>,插件不该碰。
     */
    public static void bindClient(Consumer<Path> skillSink,
                                  BiFunction<UUID, String, Delivery> enqueueFn) {
        skills = skillSink;
        enqueue = enqueueFn;
        clientReady = true;
        for (Runnable r : PENDING) runClientBlock(r);
        for (Path root : PENDING_SKILLS) skillSink.accept(root);
        PENDING.clear();
        PENDING_SKILLS.clear();
    }

    /**
     * 客户端还没接上时先攒着,接上再跑。
     *
     * <p>插件在自己的 {@code @Mod} 构造器里登记,而引擎的客户端入口也是一个
     * {@code @Mod} 构造器——谁先谁后由加载器的模组排序决定。不攒的话,插件生不生效
     * 就成了排序的函数:同一份代码换个加载器、加个别的模组就可能整块静默失效,
     * 而且没有任何报错。专用服务器上没人来接,这两个表原样留着不跑,正是要的行为。
     */
    /**
     * 插件挂在 {@code <runtime_state>} 上的现算片段。见 {@link NumenApi#contributeState}。
     * 用 CopyOnWriteArrayList:登记发生在加载期,读发生在每次请求,读远多于写。
     */
    private static final List<Function<UUID, String>> STATE = new CopyOnWriteArrayList<>();

    /**
     * 汇总所有插件对这只同伴的现算片段。<b>引擎内部调用</b>。
     *
     * <p>某个插件算炸了不能连累整条请求——它自己那段丢掉,别人的照常挂上。
     */
    public static String stateFragments(UUID companion) {
        if (STATE.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Function<UUID, String> f : STATE) {
            try {
                String x = f.apply(companion);
                if (x != null && !x.isBlank()) sb.append(x);
            } catch (RuntimeException e) {
                Constants.LOG.error("[numen] 插件的运行期状态算不出来,这一段跳过", e);
            }
        }
        return sb.toString();
    }

    /** Client-side persona parsing: extensions consume their own opaque blocks in registration order. */
    public static ParsedPersona parsePersona(String markdown) {
        String prompt = markdown == null ? "" : markdown;
        Map<String, String> data = new LinkedHashMap<>();
        for (PersonaExtension extension : PERSONA_EXTENSIONS) {
            PersonaExtension.Parsed parsed = extension.parse(prompt);
            if (parsed == null) throw new IllegalArgumentException(
                    "Persona extension returned no parse result: " + extension.id());
            prompt = parsed.promptText();
            data.put(extension.id(), parsed.data());
        }
        return new ParsedPersona(prompt, data);
    }

    public static List<PersonaExtension> personaExtensions() {
        return List.copyOf(PERSONA_EXTENSIONS);
    }

    /** Client-side persona serialization: each extension adds its opaque data to Markdown. */
    public static String composePersona(String prompt, Map<String, String> data) {
        String result = prompt == null ? "" : prompt.strip();
        for (PersonaExtension extension : PERSONA_EXTENSIONS) {
            String value = data == null ? null : data.get(extension.id());
            if (value != null && !value.isBlank()) result = extension.compose(result, value);
        }
        return result;
    }

    /** Called by the summon packet after Numen has created or woken the companion. */
    public static void applyPersonaData(ServerPlayer owner, NumenPlayer companion,
                                        Map<String, String> data) {
        if (data == null || data.isEmpty()) return;
        for (PersonaExtension extension : PERSONA_EXTENSIONS) {
            String value = data.get(extension.id());
            if (value == null || value.isBlank()) continue;
            try {
                extension.onSummon(owner, companion, value);
            } catch (RuntimeException e) {
                Constants.LOG.error("[numen] persona extension {} failed during summon", extension.id(), e);
            }
        }
    }

    public record ParsedPersona(String promptText, Map<String, String> extensionData) {
        public ParsedPersona {
            extensionData = Map.copyOf(extensionData);
        }
    }

    private static final List<Runnable> PENDING = new ArrayList<>();
    private static final List<Path> PENDING_SKILLS = new ArrayList<>();

    private static void runClientBlock(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            Constants.LOG.error("[numen] 插件的客户端初始化出错", e);
        }
    }

    private static final class Impl implements NumenApi {

        @Override
        public <T> void on(CompanionEvent<T> event, Consumer<T> handler) {
            CompanionEvents.subscribe(event, handler);
        }

        @Override
        public void registerTool(NumenTool tool) {
            ToolRegistry.register(tool);
        }

        @Override
        public void registerPersonaExtension(PersonaExtension extension) {
            if (extension == null) return;
            String id = extension.id();
            if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("Persona extension id must be namespaced: " + id);
            }
            synchronized (PERSONA_EXTENSIONS) {
                if (PERSONA_EXTENSIONS.stream().anyMatch(existing -> existing.id().equals(id))) {
                    throw new IllegalStateException("Duplicate persona extension: " + id);
                }
                PERSONA_EXTENSIONS.add(extension);
            }
        }

        @Override
        public <T extends CustomPacketPayload> void registerClientToServer(
                CustomPacketPayload.Type<T> type,
                StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                BiConsumer<T, ServerPlayer> handler) {
            com.dwinovo.numen.platform.Services.NETWORK.registerClientToServer(type, codec, handler);
        }

        @Override
        public <T extends CustomPacketPayload> void registerServerToClient(
                CustomPacketPayload.Type<T> type,
                StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                Consumer<T> handler) {
            com.dwinovo.numen.platform.Services.NETWORK.registerServerToClient(type, codec, handler);
        }

        @Override
        public void sendToServer(CustomPacketPayload payload) {
            com.dwinovo.numen.platform.Services.NETWORK.sendToServer(payload);
        }

        @Override
        public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
            com.dwinovo.numen.platform.Services.NETWORK.sendToPlayer(player, payload);
        }

        @Override
        public void bundleSkills(Path skillsRoot) {
            if (skillsRoot == null) return;
            Consumer<Path> sink = skills;
            if (sink != null) sink.accept(skillsRoot); else PENDING_SKILLS.add(skillsRoot);
        }

        @Override
        public void onClient(Runnable clientOnly) {
            if (clientOnly == null) return;
            if (clientReady) runClientBlock(clientOnly); else PENDING.add(clientOnly);
        }

        @Override
        public void contributeState(Function<UUID, String> fragment) {
            if (fragment != null) STATE.add(fragment);
        }

        @Override
        public Path configDir() {
            return com.dwinovo.numen.NumenPaths.config();
        }

        @Override
        public Delivery enqueue(UUID companion, String message) {
            BiFunction<UUID, String, Delivery> fn = enqueue;
            return fn == null ? Delivery.REJECTED : fn.apply(companion, message);
        }
    }
}
