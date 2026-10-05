package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ScriptType;

import java.util.List;

/**
 * 一种 Java 值与脚本里的值怎么互转,连同它在签名里写成什么类型。读与写与类型说明在同一个对象上,所以帮助里的类型不会和真正收的、
 * 交回的数据走样。
 *
 * <p>脚本的值:{@code null}(nil)、{@link Boolean}、{@link Long}(整数)、{@link Double}、{@link String}、{@link java.util.List}(列表)、
 * {@link java.util.Map}(名字到值的表)。空表 {@code {}} 读进来是空列表。
 *
 * <p>插件给自己的类型登记一个:{@code numen.codec(MyThing.class, codec)}。record、枚举、列表、名字到值的表、{@code Optional} 组件
 * 不用登记,{@link LuaCodecs} 按类型自己造。
 *
 * @param <T> Java 这一侧的类型
 */
public interface Codec<T> {

    /** 签名里写成什么({@code Pos}、{@code integer}、{@code tlm.Maid[]})。 */
    ScriptType type();

    /**
     * 脚本的一个值读成 {@code T}。
     *
     * @throws BadValue 读不成:说要什么样子、给了什么;看得出想写什么时带上照这一种写法该写成的值
     */
    T decode(Object value);

    /** {@code T} 写成脚本的一个值;{@link #decode} 读回来是同一个。 */
    Object encode(T value);

    /** 它的类型按名字引用、由它声明的类(一个 record 的类,连同它字段里的):帮助列出它们。没有是空表。 */
    default List<ScriptType.Class> classes() {
        return List.of();
    }
}
