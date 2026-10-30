package com.dwinovo.numen.api.plugin;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.agent.scope.Scope;
import com.dwinovo.numen.api.platform.Services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 插件的花名册:这台服务器上有哪些插件、各自此刻的状态、加了什么,以及服主关了哪些。{@link NumenPlugins} 是它的
 * 唯一出入口——第三方直接登记的、Numen 内嵌的联动(经 Numen 的闸门)、脚本里写的插件都在这一处留下一行,界面读的也是这一份。
 *
 * <h2>登记归在插件名下</h2>
 * 一个插件的门把它登记的每一条都收进一个{@link Scope 作用域}。<b>撤回 = 关掉作用域,重登 = 撤回后再把它的 setup 跑一遍</b>
 * ({@link #reload}),服主的开关、脚本重载、单测走的是这同一对操作,没有第二条路。同一个 id 登记几次是一个插件:setup 按登记顺序
 * 都留着,作用域是同一个,重登时按序全部再跑。撤回之后,它原先那扇门再登记任何东西都会抛(见 {@link NumenPlugins}),
 * 不会留下半死的登记。
 *
 * <h2>开关当场生效,除非插件声明了卸下要重启</h2>
 * 名单({@link PluginSwitches})是现行的设定:开关一翻就落盘,并当场装上或撤回该插件的登记。只经门登记的插件({@link Unloading#LIVE})
 * 都能这样;在门外动了东西的(Mixin、挂在加载器上的监听、改不回的客户端块)登记时声明{@link Unloading#NEEDS_RESTART},
 * 它们装着时翻到关,状态仍是{@link Status#LOADED}、{@link Plugin#pending}为真(界面标"重启后生效")。启动时就在名单上、
 * 登记方没把 setup 交来的插件,翻到开同样要重启。不靠猜:要不要重启只看登记时的声明。
 *
 * <p>花名册里只有插件。引擎(Numen API)是宿主,它自己的登记走 {@link NumenPlugins#registerHost},不在这里、名单也管不着;
 * Numen 的内容(工具、任务、技能……)是插件之一,和别的插件一视同仁。
 *
 * <p>线程:任何线程。状态读写在这个对象的锁里;装上与撤回另有一把生命周期锁串起来,插件的 setup 在生命周期锁里跑、不占状态锁
 * (界面读花名册不用等它)。
 */
public final class PluginRoster {

    /** 插件随谁来的。 */
    public enum Source {
        /** 随 Numen 的 jar 内嵌的(Numen 自己和它内嵌的联动)。 */
        BUNDLED,
        /** 单独的 jar。 */
        JAR,
        /** 服务端脚本里写的(KubeJS 联动),脚本重载时整批换掉。 */
        SCRIPT
    }

    /** 插件此刻的状态。 */
    public enum Status {
        LOADED,
        /** 在停用名单里:登记没装(或已撤回)。 */
        DISABLED,
        /** setup 抛了;它那一半登记已撤回,原因在 {@link Plugin#reason}。 */
        FAILED
    }

    /** 插件在登记时对"卸下"的声明。 */
    public enum Unloading {
        /** 只经门登记:撤回作用域就卸干净了。 */
        LIVE,
        /** 在门外动了东西(Mixin、加载器上的监听、改不回的客户端块):卸下要重启。 */
        NEEDS_RESTART
    }

    /** 插件往 Numen 里加的东西,按种类记数。 */
    public enum Kind {
        API_GROUP, MODULE, SKILL, COMMAND, EVENT_LISTENER, EVENT_TYPE, GEAR_SOURCE, BODY_STATE
    }

    /** 加载器对一个模组的说法;没有的项是空串/空表。 */
    public record Meta(String name, String version, List<String> authors) {

        public static final Meta NONE = new Meta("", "", List.of());
    }

    /**
     * 花名册里的一行。
     *
     * @param name     显示名:单独的 jar 取它自己的模组名,内嵌的联动取目标模组的名字;都没有就是 id
     * @param version  版本;内嵌的随 Numen
     * @param authors  作者;内嵌的随 Numen
     * @param target   联动的目标模组 id;不是联动型的是空串
     * @param reason   {@link Status#FAILED} 时的原因,其余是空串
     * @param disabled 服主把它关了(名单里有它)
     * @param pending  开关和此刻的状态不一致、要重启才对得上(界面标"重启后生效")
     */
    public record Plugin(String id, String name, String version, List<String> authors, String target, Source source,
                         Status status, String reason, boolean disabled, boolean pending,
                         Map<Kind, Integer> adds) {}

    private static final class Entry {
        Source source;
        String target = "";
        Status status = Status.LOADED;
        String reason = "";
        final Map<Kind, Integer> adds = new EnumMap<>(Kind.class);
        /** 登记时交来的 setup,按登记顺序;重登时按序再跑。 */
        final List<NumenPlugin> setups = new ArrayList<>();
        Unloading unloading = Unloading.LIVE;
        /** 门的造法:登记时 {@link NumenPlugins} 给的,一个作用域造一扇门。 */
        Function<Scope, NumenApi> doors;
        /** 装着的登记的作用域;没装着是 null。 */
        Scope scope;

        Entry(Source source) {
            this.source = source;
        }
    }

    private final Supplier<PluginSwitches> switches;
    private final Function<String, Meta> metaOf;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /** 停用名单此刻的样子:第一次用到时读,之后随开关一起改、一起落盘。 */
    private Set<String> disabledIds;
    /** 装上与撤回串行。 */
    private final Object lifecycle = new Object();

    /**
     * @param switches 停用名单;第一次用到才取
     * @param metaOf   模组 id → 加载器对它的说法;出界面时才问
     */
    public PluginRoster(Supplier<PluginSwitches> switches, Function<String, Meta> metaOf) {
        this.switches = switches;
        this.metaOf = metaOf;
    }

    /** 加载器对模组 {@code modId} 的说法;这台服务器上没有这个模组就是 {@link Meta#NONE}。 */
    public static Meta platformMeta(String modId) {
        var platform = Services.platform();
        String name = platform.modName(modId);
        return name == null ? Meta.NONE : new Meta(name, platform.modVersion(modId), platform.modAuthors(modId));
    }

    // ---- 装载 ----

    /**
     * 联动型插件在装之前报到(目标模组在场才报到,不在场的联动不算这台服务器上的插件):它联动哪个模组。返回能不能接着装——
     * 在停用名单里(状态 {@link Status#DISABLED})就不装。
     *
     * @throws IllegalArgumentException 这个 id 已经被另一种来源的插件登记了 setup
     */
    public synchronized boolean admit(Source source, String id, String target) {
        Entry entry = entry(id, source);
        if (!entry.setups.isEmpty() && entry.source != source) {
            throw new IllegalArgumentException("the plugin id '" + id + "' is already used by a " + entry.source
                    + " plugin; a " + source + " plugin cannot add to it");
        }
        entry.source = source;
        entry.target = target;
        return begin(id, entry);
    }

    /**
     * 登记一个插件(或同一个 id 再加一段 setup):setup 留着(重登要再跑);不在停用名单里就把它跑在这个插件的作用域上,
     * 登记下它加了什么;抛了就把这个插件已登记的整个撤回、这一段不留、记下原因再原样抛出(登记不全的插件比没有更糟)。
     *
     * @param doors 造门:一个作用域造一扇往它里面登记的门
     */
    public void run(String id, NumenPlugin plugin, Unloading unloading, Function<Scope, NumenApi> doors) {
        synchronized (lifecycle) {
            boolean enabled;
            Scope active;
            synchronized (this) {
                Entry entry = entry(id, null);
                entry.setups.add(plugin);
                entry.doors = doors;
                if (unloading == Unloading.NEEDS_RESTART) {
                    entry.unloading = Unloading.NEEDS_RESTART;
                }
                enabled = begin(id, entry);
                active = entry.scope;
            }
            if (!enabled) {
                return;
            }
            try {
                if (active != null) {
                    runSetups(id, active, List.of(plugin));
                } else {
                    activate(id);
                }
            } catch (Throwable t) {
                // 这一段 setup 登记不上:整个插件已撤回,它不留着,别的段下次装上时照常
                synchronized (this) {
                    entry(id, null).setups.remove(plugin);
                }
                Registrations.changed();
                throw t;
            }
        }
        Registrations.changed();
    }

    /** 装的过程中(setup 之外,比如装之前的准备)出了事:状态记成失败,原因照实。 */
    public synchronized void failed(String id, Throwable cause) {
        Entry entry = entry(id, null);
        entry.status = Status.FAILED;
        entry.reason = cause.toString();
    }

    private boolean begin(String id, Entry entry) {
        if (disabledIds().contains(id)) {
            entry.status = Status.DISABLED;
            return false;
        }
        return true;
    }

    private synchronized void count(String id, Kind kind, int n) {
        if (n > 0) {
            entry(id, null).adds.merge(kind, n, Integer::sum);
        }
    }

    private Entry entry(String id, Source source) {
        return entries.computeIfAbsent(id, key -> new Entry(source != null ? source : Source.JAR));
    }

    /** 把一个插件的全部 setup 装到一个新的作用域上(生命周期锁里)。抛了就撤回、记失败、原样抛出。 */
    private void activate(String id) {
        Scope scope = new Scope();
        List<NumenPlugin> setups;
        synchronized (this) {
            Entry entry = entry(id, null);
            setups = List.copyOf(entry.setups);
            entry.scope = scope;
            entry.adds.clear();
            entry.status = Status.LOADED;
            entry.reason = "";
        }
        runSetups(id, scope, setups);
    }

    private void runSetups(String id, Scope scope, List<NumenPlugin> setups) {
        Function<Scope, NumenApi> doors;
        synchronized (this) {
            doors = entry(id, null).doors;
        }
        NumenApi door = new CountingApi(doors.apply(scope), (kind, n) -> count(id, kind, n));
        try {
            for (NumenPlugin setup : setups) {
                setup.setup(door);
            }
        } catch (Throwable t) {
            deactivate(id);
            failed(id, t);
            throw t;
        }
    }

    /** 撤回一个插件装着的登记:关掉作用域,记数清零,状态是停用(生命周期锁里)。 */
    private void deactivate(String id) {
        Scope scope;
        synchronized (this) {
            Entry entry = entry(id, null);
            scope = entry.scope;
            entry.scope = null;
            entry.adds.clear();
            entry.status = Status.DISABLED;
        }
        if (scope != null) {
            scope.close();
        }
    }

    // ---- 撤回与重登 ----

    /**
     * 撤回一个插件:它登记的一切从各张表里撤掉,它的 setup 也不再留着,花名册里没有它的一行。脚本重载时桥对上一次登记的脚本插件
     * 逐个这样撤,再按新脚本重登。
     *
     * @throws IllegalArgumentException 没有这个插件
     * @throws IllegalStateException    它声明了卸下要重启
     */
    public void withdraw(String id) {
        synchronized (lifecycle) {
            requireUnloadable(id);
            deactivate(id);
            synchronized (this) {
                entries.remove(id);
            }
        }
        Registrations.changed();
    }

    /**
     * 重登:撤回它登记的一切,再把它的 setup 按序跑一遍。在停用名单里的什么也不做。
     *
     * @throws IllegalArgumentException 没有这个插件
     * @throws IllegalStateException    它声明了卸下要重启
     */
    public void reload(String id) {
        synchronized (lifecycle) {
            requireUnloadable(id);
            deactivate(id);
            if (!disabled(id)) {
                activate(id);
            }
        }
        Registrations.changed();
    }

    private synchronized void requireUnloadable(String id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("no such plugin: " + id);
        }
        if (entry.unloading == Unloading.NEEDS_RESTART && entry.scope != null) {
            throw new IllegalStateException("plugin '" + id + "' changed things outside the door (a Mixin, a loader "
                    + "listener, a client block); unloading it needs a restart");
        }
    }

    // ---- 停用名单 ----

    private synchronized Set<String> disabledIds() {
        if (disabledIds == null) {
            disabledIds = new LinkedHashSet<>(switches.get().disabled());
        }
        return disabledIds;
    }

    private synchronized boolean disabled(String id) {
        return disabledIds().contains(id);
    }

    /**
     * 服主翻一个插件的开关:落盘,并当场装上或撤回它的登记(要重启的除外,见类说明)。
     *
     * @return 办不成的原因;办成是 {@code null}。装上时 setup 抛了,开关已翻、状态是 FAILED,原因在这里和 {@link Plugin#reason}
     */
    public String setDisabled(String id, boolean disabled) {
        String problem = null;
        synchronized (lifecycle) {
            synchronized (this) {
                if (!entries.containsKey(id)) {
                    return "no such plugin";
                }
                if (disabled ? disabledIds().add(id) : disabledIds().remove(id)) {
                    switches.get().write(disabledIds);
                }
            }
            if (disabled) {
                if (isActive(id) && !needsRestart(id)) {
                    deactivate(id);
                }
            } else if (canActivate(id)) {
                try {
                    activate(id);
                } catch (Throwable t) {
                    problem = t.toString();
                }
            }
        }
        Registrations.changed();
        return problem;
    }

    private synchronized boolean isActive(String id) {
        return entries.get(id).scope != null;
    }

    private synchronized boolean needsRestart(String id) {
        return entries.get(id).unloading == Unloading.NEEDS_RESTART;
    }

    /** 能当场装上:没装着、登记方交来过 setup(没装上过就没有)、没有失败过。 */
    private synchronized boolean canActivate(String id) {
        Entry entry = entries.get(id);
        return entry.scope == null && entry.status == Status.DISABLED && !entry.setups.isEmpty();
    }

    // ---- 看 ----

    /** 登记过的插件,按登记顺序。 */
    public synchronized List<Plugin> plugins() {
        List<Plugin> out = new ArrayList<>();
        entries.forEach((id, entry) -> out.add(plugin(id, entry)));
        return out;
    }

    private Plugin plugin(String id, Entry e) {
        Meta carrier = metaOf.apply(e.source == Source.BUNDLED ? NumenPlugins.NUMEN : id);
        Meta named = e.source == Source.BUNDLED && !e.target.isEmpty() ? metaOf.apply(e.target) : carrier;
        boolean off = disabled(id);
        boolean mismatch = e.status != Status.FAILED && off == (e.status == Status.LOADED);
        return new Plugin(id, named.name().isEmpty() ? id : named.name(), carrier.version(), carrier.authors(),
                e.target, e.source, e.status, e.reason, off, mismatch,
                Collections.unmodifiableMap(new EnumMap<>(e.adds)));
    }
}
