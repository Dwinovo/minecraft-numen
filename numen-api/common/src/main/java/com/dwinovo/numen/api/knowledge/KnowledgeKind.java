package com.dwinovo.numen.api.knowledge;

/**
 * 物品与配方的知识有三个动词,每个动词一个扩展点:来源按它登记({@link com.dwinovo.numen.api.NumenApi#knowledgeSource}),动词把各来源
 * 的结果合在一起交给模型。来源的函数收一个物品,交回 {@link #returns} 那种页。
 */
public enum KnowledgeKind {

    /** 怎么做:产出这样东西的每一条配方({@code numen.inv.recipes})。来源交回 {@link RecipePage}。 */
    RECIPES("recipes"),
    /** 能拿来做什么:用这样东西当输入的每一条配方({@code numen.inv.uses})。来源交回 {@link RecipePage}。 */
    USES("uses"),
    /** 物品说明:原版的事实、整合包与模组写的说明({@code numen.inv.info})。来源交回 {@link InfoPage}。 */
    INFO("info");

    private final String verb;

    KnowledgeKind(String verb) {
        this.verb = verb;
    }

    /** 动词的名字,即脚本里 {@code numen.inv.<名字>}。 */
    public String verb() {
        return verb;
    }

    /** 来源的函数在名字空间里占的那一组(隐藏的,不进索引):{@code recipes_source}。 */
    public String group() {
        return verb + "_source";
    }

    /** 来源函数交回的页的类型。 */
    public Class<?> returns() {
        return this == INFO ? InfoPage.class : RecipePage.class;
    }
}
