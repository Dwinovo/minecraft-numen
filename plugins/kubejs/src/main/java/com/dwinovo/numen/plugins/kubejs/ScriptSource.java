package com.dwinovo.numen.plugins.kubejs;

import com.dwinovo.numen.api.knowledge.InfoPage;
import com.dwinovo.numen.api.knowledge.Recipe;
import com.dwinovo.numen.api.knowledge.RecipePage;
import com.dwinovo.numen.api.sdk.ApiFunction;
import com.dwinovo.numen.api.sdk.Codec;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import dev.latvian.mods.rhino.util.HideFromJS;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 脚本里声明的一个物品查询:整合包作者用它登记<b>物品说明</b>({@code <名字空间>.lookup.info})或<b>配方</b>
 * ({@code <名字空间>.lookup.recipes}、{@code <名字空间>.lookup.uses})——是这个插件自己的函数,不并进别的插件的答复。
 * 写法同函数({@link ScriptFn}):说明必填、方法连着写,最后 {@code run}。
 *
 * <p>{@code run(item => …)} 收物品 id({@code minecraft:iron_ingot}),返回:
 * <ul>
 *   <li>{@code info}:一串说明文字 {@code ['…', '…']};</li>
 *   <li>{@code recipes}、{@code uses}:一串配方 {@code {id, category, item, makes, ingredients, catalysts, categoryName, alsoMakes, ticks}},
 *       必填的是 {@code category}(配方类型或机器的 id)与 {@code item}(产出),其余不写就是没有。</li>
 * </ul>
 * 读不到的字段({@code notProvided})如实声明,写进函数的说明里,不写不猜。
 */
public final class ScriptSource {

    private final String namespace;
    /** 查询的种类:recipes、uses、info。 */
    static final java.util.Set<String> KINDS = java.util.Set.of("recipes", "uses", "info");

    private final String kind;
    private String provides = "";
    private List<String> notProvided = List.of();
    private ScriptLookup lookup;

    ScriptSource(String namespace, String kind) {
        this.namespace = namespace;
        this.kind = kind;
    }

    /** 这个查询多给的是哪一类信息(一句话):写进函数的说明,模型据此知道它答什么。 */
    public ScriptSource provides(String text) {
        this.provides = text == null ? "" : text.strip();
        return this;
    }

    /** 它读不到的字段(概率、耗时、热量、能量……),结果里不会有它们的值。 */
    public ScriptSource notProvided(String... fields) {
        this.notProvided = List.of(fields);
        return this;
    }

    /** 查一样物品:收物品 id,返回见类说明。 */
    public ScriptSource run(ScriptLookup run) {
        this.lookup = run;
        return this;
    }

    /** 查一样物品的执行体。 */
    @FunctionalInterface
    public interface ScriptLookup {
        Object find(String item);
    }

    /**
     * @throws IllegalArgumentException 没写说明或执行体
     */
    @HideFromJS
    @SuppressWarnings("unchecked")
    ApiFunction build() {
        String where = namespace + " " + kind + " lookup";
        if (provides.isBlank()) {
            throw new IllegalArgumentException(where + " needs source.provides(\"…\"): the model reads it to know what "
                    + "this lookup answers");
        }
        if (lookup == null) {
            throw new IllegalArgumentException(where + " needs a body (source.run(item => …))");
        }
        Codec<Object> item = (Codec<Object>) LuaCodecs.of(Item.class, namespace);
        Codec<Object> page = (Codec<Object>) LuaCodecs.of(kind.equals("info") ? InfoPage.class : RecipePage.class, namespace);
        return ApiFunction.builder(kind)
                .summary(provides + (notProvided.isEmpty() ? "" : " It cannot say: " + String.join(", ", notProvided) + "."))
                .required("item", item, "The item.")
                .returns(page)
                .server((call, args) -> {
                    String id = BuiltInRegistries.ITEM.getKey((Item) args.get("item")).toString();
                    try {
                        Object found = lookup.find(id);
                        return kind.equals("info") ? info(found) : recipes(found);
                    } catch (RuntimeException error) {
                        throw ScriptErrors.asFailure(where, error);
                    }
                });
    }

    private static InfoPage info(Object found) {
        List<String> notes = new ArrayList<>();
        for (Object note : list(found)) {
            notes.add(String.valueOf(note));
        }
        return new InfoPage(Optional.empty(), notes);
    }

    private static RecipePage recipes(Object found) {
        List<Recipe> out = new ArrayList<>();
        for (Object one : list(found)) {
            if (!(one instanceof Map<?, ?> fields)) {
                throw new IllegalArgumentException("a recipe is an object like {category: 'mypack:press', item: "
                        + "'minecraft:stick', ingredients: ['oak_log']}, not " + one);
            }
            out.add(new Recipe(text(fields, "id"), required(fields, "category"), text(fields, "categoryName"),
                    required(fields, "item"), number(fields, "makes").orElse(1), strings(fields, "alsoMakes"),
                    strings(fields, "ingredients"), strings(fields, "catalysts"), Optional.empty(), Optional.empty(),
                    number(fields, "ticks")));
        }
        return RecipePage.of(out);
    }

    private static List<?> list(Object found) {
        return found == null ? List.of() : (List<?>) found;
    }

    private static Optional<String> text(Map<?, ?> fields, String key) {
        Object value = fields.get(key);
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    private static String required(Map<?, ?> fields, String key) {
        return text(fields, key).orElseThrow(() -> new IllegalArgumentException("a recipe needs '" + key + "'"));
    }

    private static Optional<Integer> number(Map<?, ?> fields, String key) {
        return fields.get(key) instanceof Number n ? Optional.of(n.intValue()) : Optional.empty();
    }

    private static List<String> strings(Map<?, ?> fields, String key) {
        return list(fields.get(key)).stream().map(String::valueOf).toList();
    }
}
