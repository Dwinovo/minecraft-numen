package com.dwinovo.numen.agent.script;

import com.dwinovo.numen.agent.script.lua.LuaEngine;

import java.util.List;
import java.util.function.Consumer;

/**
 * 脚本用哪种语言写、怎么读、怎么跑。派发、等身体收尾、上限、打断、回执、脚本名词都与它无关,只经这里认语言;换语言只换
 * {@link #IN_USE} 这一处与它的实现。
 */
public interface ScriptEngine {

    /** 眼下用的那一种。 */
    ScriptEngine IN_USE = new LuaEngine();

    /** 语言的名字,给模型看:{@code Lua 5.2}。 */
    String language();

    /** 收这种程序的工具叫什么:{@code lua}。 */
    String toolName();

    /** 存成文件时的扩展名:{@code .lua}。 */
    String extension();

    /**
     * 一个 API 函数在这种语言里怎么调,给模型看的几个例子与规则(对象、选项、多词的值、返回值、出错、打印、参数),一段话。
     */
    String howToCall();

    /**
     * 一个名字空间、组名或动作名在这种语言里写成什么:撞上语言自己用掉的名字(关键字、自带的全局)时改写的那一条规则只在这里。
     * 帮助、提示与生成函数都经它,所以写法只有一种。
     */
    String functionName(String name);

    /** 一条点隔开的名字({@code numen.move}、{@code numen.move.until})逐段经 {@link #functionName} 写:{@code numen.move.until_}。 */
    default String pathName(String path) {
        return String.join(".", java.util.Arrays.stream(path.split("\\.", -1)).map(this::functionName).toList());
    }

    /** 一个名字能不能直接写成选项表的键({@code {type = "world"}}):不是语言的关键字就能。 */
    boolean isKey(String name);

    /** 一个动作在这种语言里的函数全名:组的全名加动作名,{@code numen.move.go}。 */
    default String function(String group, String verb) {
        return pathName(group) + "." + functionName(verb);
    }

    /**
     * 一次调用写成这种语言里的样子:{@code numen.work.dig({x = 1, y = 2, z = 3}, {count = 2})}。对象是字符串、数、布尔或它们的列表,选项按名字。
     */
    String call(String function, List<Object> objects, java.util.Map<String, Object> options);

    /** 一张名字到值的表写成这种语言里的样子:{@code {arrive = "dig"}}。 */
    String table(java.util.Map<String, Object> options);

    /**
     * 一个值写成这种语言里的字面量:字符串、数、布尔、列表、名字到值的表(按迭代顺序),{@code null} 是 nil。报错里说"你给了什么"、
     * 提示里写一个位置都经它。
     */
    String value(Object value);

    /**
     * 一个值写成给模型读的样子:同 {@link #value} 的写法,大的缩略(长列表只显示首尾几项并写明总数,深嵌套写成 {@code {...}},长文字截断并写明
     * 总长),缩略处说怎么看更多。回执里的 {@code print}、{@code return} 的值都经它;{@link #value} 是精确的,提示里的"改好的那一行"用它。
     */
    String display(Object value);

    // ---- 签名:帮助与系统提示里的类型注解 ----

    /** 一个类型写成这种语言的类型注解:{@code Pos|string}、{@code {count?: integer}}。 */
    String typeText(ScriptType type);

    /** 一个类的声明:名字、说明、每个字段一行。 */
    default String classText(ScriptType.Class type) {
        return classText(type, List.of());
    }

    /** 一个类的声明,带上它的方法({@link #methods}),每个方法一行签名。 */
    String classText(ScriptType.Class type, List<Defined> methods);

    /** 一个函数的全部说明:签名(参数逐个带说明、返回什么)、选项与结果的字段、例子、注意、相关。 */
    String functionText(FunctionDoc fn);

    /** 一个函数在一组的清单里的一行:名字、参数与返回的类型、一句说明。 */
    String functionLine(FunctionDoc fn);

    /** 一组的清单:{@code group} 是组名,{@code lines} 是每个函数一行({@link #functionLine} 或 {@link #libraryLine})。 */
    String groupText(String group, String summary, List<String> lines);

    /** 库里一个函数的全部说明:它上面那几行注释(带类型注解)原样,接着它的定义行。 */
    String libraryText(Defined fn);

    /** 库里一个函数在一组的清单里的一行:按它的类型注解写,和 {@link #functionLine} 同一种样子。 */
    String libraryLine(Defined fn);

    /** 一行说明在这种语言里写成注释的样子(说明从正文开头那行注释读,见 {@link #summary}):{@code -- Dig out the given blocks.}。 */
    String comment(String text);

    /** 读一段正文,不运行:读不通返回语言自己的报错原话(带行号),读得通是 null。 */
    String check(String name, String code);

    /** 一句话说明:正文开头那行注释,去掉注释号;开头不是注释是 null。 */
    String summary(String code);

    /**
     * 一个模块定义的函数,按出现的顺序:函数名写成用的人写的样子({@code my.lumber.chop}:模块名加它返回的那张表里的名字)、形参、紧挨在
     * 定义上面的那几行注释(原样,带注释号与类型注解)。
     */
    List<Defined> functions(String module, String code);

    /**
     * 一个模块给类 {@code type} 定义的方法,按出现的顺序({@code function M.Pos:offset(dx, dy, dz)}):名字是方法名、形参(不含它自己)、
     * 紧挨在定义上面的那几行注释。类的方法写在哪个模块见 {@link ScriptType.Class#home}。
     */
    List<Defined> methods(String type, String code);

    /**
     * 一个名字在这种语言里能不能当模块名:两段 {@code 名字空间.组}({@code numen.work}、{@code my.lumber}),每段写得出来、不是关键字,
     * 名字空间是一个全局名,还不能撞语言自带的全局与引擎自己的全局({@code raise}、{@code require})。和第 ① 层的组同名可以(那是给这一组
     * 加函数)。能是 null,不能是那句话。模块文件放在哪的规矩在 api 的 {@code Modules}。
     */
    String moduleName(String name);

    /** 模块里定义的一个函数。{@code doc} 是它上面的注释行,原样;没写是空表。 */
    record Defined(String name, List<String> params, List<String> doc) {

        public Defined {
            params = List.copyOf(params);
            doc = List.copyOf(doc);
        }
    }

    /** 模块函数的一句话说明:它上面那几行不是类型注解的注释连起来的第一句(到第一个句号);没写是空串。 */
    String summaryOf(Defined fn);

    /**
     * 只读一段正文调了哪些 API 函数,不执行:每个宿主函数与模块函数都换成只记下调用、返回 nil 的那一种,照常跑这段正文。
     * 给写在文字里的例子用(帮助里的例子、技能与提示里的写法),和真跑同一套从调用到参数的换法。模块函数的调用记成
     * {@link ScriptRun.Call},组与动词是模块名与函数名。
     *
     * @throws IllegalArgumentException 读不通(语法错);消息是语言自己的报错原话
     */
    Reading calls(String name, String code, ScriptCatalog catalog);

    /**
     * 存一份模块之前读一遍,和运行时同一个解释器:读不通,或它给第 ① 层的名字(登记的 API 函数与它们的组)赋值,返回那句话(第一处);
     * 都没有是 null。API 函数都换成只记下调用、返回声明的样子的那一种,照常跑一遍正文——赋值的那一行跑到了才查得出来,运行时同一条
     * 规则照样拦着。
     */
    String checkModule(String name, String code, ScriptCatalog catalog);

    /**
     * 只读不跑的结果。
     *
     * @param calls 调到的函数,按先后
     * @param error 跑的时候出的错(调用都返回 nil,拿返回值往下算的写法会在这里停);跑完是 null
     */
    record Reading(List<ScriptRun.Call> calls, String error) {}

    /**
     * 开一次运行。读不通的正文也开得出来,第一步就是带着报错的 {@link ScriptRun.Done}。
     *
     * @param name    这段程序叫什么,进报错的开头(她当场写的一段是工具名)
     * @param catalog 能调的 API 函数与模块
     * @param printer 打印出的每一行
     */
    ScriptRun start(String name, String code, ScriptCatalog catalog, Consumer<String> printer);
}
