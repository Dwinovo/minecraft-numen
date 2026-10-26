package com.dwinovo.numen.api.plugin;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.api.NumenPlugins;
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
 * 插件的花名册:这台服务器上有哪些插件、各自此刻的状态、加了什么,以及服主下次启动要关哪些。{@link NumenPlugins} 是它的
 * 唯一出入口——第三方直接登记的、Numen 内嵌的联动(经 Numen 的闸门)都在这一处留下一行,界面读的也是这一份。
 *
 * <h2>两个时间</h2>
 * 名单({@link PluginSwitches})在第一次用到时读一次,那一份是<b>这次启动的设定</b>:在它里面的插件不装,状态是
 * {@link Status#DISABLED}。服主之后改名单({@link #setDisabled})写进的是<b>下次启动的设定</b>,这一次的运行不变——插件接口只有
 * setup、没有卸下,有的插件还带 Mixin,运行中卸不掉。两者不同的,{@link Plugin#pending} 为真。
 *
 * <p>花名册里只有插件。引擎(Numen API)是宿主,它自己的登记走 {@link NumenPlugins#registerHost},不在这里、名单也管不着;
 * Numen 的内容(工具、任务、技能……)是插件之一,和别的插件一视同仁。
 *
 * <p>线程:任何线程;状态读写都在这个对象的锁里,插件的 setup 本身不在锁里跑。
 */
public final class PluginRoster {

    /** 插件随谁来的。 */
    public enum Source {
        /** 随 Numen 的 jar 内嵌的(Numen 自己和它内嵌的联动)。 */
        BUNDLED,
        /** 单独的 jar。 */
        JAR
    }

    /** 插件此刻的状态。 */
    public enum Status {
        LOADED,
        /** 在这次启动的停用名单里,没有装它。 */
        DISABLED,
        /** setup 抛了;原因在 {@link Plugin#reason}。 */
        FAILED
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
     * @param name       显示名:单独的 jar 取它自己的模组名,内嵌的联动取目标模组的名字;都没有就是 id
     * @param version    版本;内嵌的随 Numen
     * @param authors    作者;内嵌的随 Numen
     * @param target     联动的目标模组 id;不是联动型的是空串
     * @param reason     {@link Status#FAILED} 时的原因,其余是空串
     * @param pending    下次启动的设定和这一次不一样(界面标"重启后生效")
     * @param disabledNext 下次启动时它是关着的
     */
    public record Plugin(String id, String name, String version, List<String> authors, String target, Source source,
                         Status status, String reason, boolean disabledNext, boolean pending,
                         Map<Kind, Integer> adds) {}

    private static final class Entry {
        Source source;
        String target = "";
        Status status = Status.LOADED;
        String reason = "";
        final Map<Kind, Integer> adds = new EnumMap<>(Kind.class);

        Entry(Source source) {
            this.source = source;
        }
    }

    private final Supplier<PluginSwitches> switches;
    private final Function<String, Meta> metaOf;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /** 这次启动的设定:第一次用到时读;之后不变。 */
    private Set<String> atStart;
    /** 下次启动的设定:名单此刻的样子。 */
    private Set<String> next;

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
     * 在这次启动的停用名单里(状态 {@link Status#DISABLED})就不装。
     */
    public synchronized boolean admit(Source source, String id, String target) {
        Entry entry = entry(id, source);
        entry.source = source;
        entry.target = target;
        return begin(id);
    }

    /**
     * 装一个插件:不在这次启动的停用名单里才把它的 setup 跑在{@code door}上,登记下它加了什么;抛了就记下原因再原样抛出。
     */
    public void run(String id, NumenPlugin plugin, NumenApi door) {
        if (!begin(id)) {
            return;
        }
        try {
            plugin.setup(new CountingApi(door, (kind, n) -> count(id, kind, n)));
        } catch (Throwable t) {
            failed(id, t);
            throw t;
        }
    }

    /** 装的过程中(setup 之外,比如装之前的准备)出了事:状态记成失败,原因照实。 */
    public synchronized void failed(String id, Throwable cause) {
        Entry entry = entry(id, null);
        entry.status = Status.FAILED;
        entry.reason = cause.toString();
    }

    private synchronized boolean begin(String id) {
        Entry entry = entry(id, null);
        if (disabledAtStart(id)) {
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

    // ---- 停用名单 ----

    private void load() {
        if (atStart == null) {
            atStart = new LinkedHashSet<>(switches.get().disabled());
            next = new LinkedHashSet<>(atStart);
        }
    }

    private boolean disabledAtStart(String id) {
        load();
        return atStart.contains(id);
    }

    /**
     * 服主改下次启动的设定并落盘;这一次的运行不变。
     *
     * @return 办不成的原因;办成是 {@code null}
     */
    public synchronized String setDisabled(String id, boolean disabled) {
        load();
        if (!entries.containsKey(id)) {
            return "no such plugin";
        }
        if (disabled ? next.add(id) : next.remove(id)) {
            switches.get().write(next);
        }
        return null;
    }

    // ---- 看 ----

    /** 登记过的插件,按登记顺序。 */
    public synchronized List<Plugin> plugins() {
        load();
        List<Plugin> out = new ArrayList<>();
        entries.forEach((id, entry) -> out.add(plugin(id, entry)));
        return out;
    }

    private Plugin plugin(String id, Entry e) {
        Meta carrier = metaOf.apply(e.source == Source.BUNDLED ? NumenPlugins.NUMEN : id);
        Meta named = e.source == Source.BUNDLED && !e.target.isEmpty() ? metaOf.apply(e.target) : carrier;
        boolean off = next.contains(id);
        return new Plugin(id, named.name().isEmpty() ? id : named.name(), carrier.version(), carrier.authors(),
                e.target, e.source, e.status, e.reason, off, off != disabledAtStart(id),
                Collections.unmodifiableMap(new EnumMap<>(e.adds)));
    }
}
