package com.dwinovo.numen.event;

import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;

import java.util.Map;

/**
 * 事件登记处:游戏世界通知 agent 的所有事件类型在此登记,每种事件声明自己的
 * 默认档位——{@code WAKE}(立刻唤醒空闲的 brain 开一轮)或 {@code RIDER}
 * (进缓冲,搭下一个协议边界的车:回合进行中贴在工具批结算后,空闲时等下一次
 * 主人回合)。发射统一走 {@link #emit},XML 组装与转义收口在这里,散装手搓
 * {@code <event>} 字符串的写法到此为止。
 *
 * <p>两条纪律:
 * <ul>
 *   <li>核心域身体叙事(本能/被打断的故事)永远经 BodyLog 双轨,它的出口档位
 *       恒为 RIDER——身体日记只通知、从不唤醒(心智模型宪法 §4);</li>
 *   <li>WAKE 是要花一次 LLM 请求的,新事件默认先登 RIDER,确有"必须现在反应"
 *       的理由才升 WAKE。</li>
 * </ul>
 *
 * <p>客户端内部注入的事件(死亡叙事、人设切换)不经服务端信道,不在此登记,
 * 但同属 {@code <event>} 词汇表:death / persona-change。
 */
public final class GameEvents {

    /** 登记表:kind 字符串 + 默认档位。 */
    public enum Kind {
        /** 异步任务收尾(done/failed/timeout 唤醒;stopped 是主动叫停的回执,搭车即可)。 */
        TASK_FINISHED("task_finished", true),
        /** 身体自理日记(BodyLog 空闲轨出口)。永不唤醒,宪法 §4。 */
        BODY_LOG("body_log", false),
        /** 同伴自己跨了维度。 */
        DIMENSION_CHANGE("dimension_change", false);

        private final String kind;
        private final boolean wake;

        Kind(String kind, boolean wake) {
            this.kind = kind;
            this.wake = wake;
        }

        public String kindName() { return kind; }
        public boolean wakesByDefault() { return wake; }
    }

    private GameEvents() {}

    /** 按登记的默认档位发射。 */
    public static void emit(NumenPlayer body, Kind kind, Map<String, String> attrs, String text) {
        emit(body, kind, attrs, text, kind.wake);
    }

    /** 按次覆盖档位发射(如 task_finished 的 stopped 状态降为搭车)。 */
    public static void emit(NumenPlayer body, Kind kind, Map<String, String> attrs, String text, boolean wake) {
        StringBuilder sb = new StringBuilder("<event kind=\"").append(kind.kind).append('"');
        if (attrs != null) {
            for (Map.Entry<String, String> e : attrs.entrySet()) {
                sb.append(' ').append(e.getKey()).append("=\"").append(escape(e.getValue())).append('"');
            }
        }
        sb.append('>').append(escape(text)).append("</event>");
        Companions.emitEvent(body, sb.toString(), wake);
    }

    /**
     * 异步任务收尾事件。{@code status} ∈ done / failed / timeout / stopped;
     * stopped 是 LLM 或主人主动叫停的回执,降为搭车,其余三种唤醒(它们顶替的
     * 正是旧同步模式里最后那次工具结果往返,不是新增开销)。
     */
    public static void taskFinished(NumenPlayer body, String taskId, String tool,
                                    String status, String message) {
        java.util.LinkedHashMap<String, String> attrs = new java.util.LinkedHashMap<>();
        attrs.put("id", taskId);
        attrs.put("task", tool);
        attrs.put("status", status);
        emit(body, Kind.TASK_FINISHED, attrs, message, !"stopped".equals(status));
    }

    /** XML 词汇表用尖括号,正文里的尖括号一律圆括号化,防注入也防解析歧义。 */
    public static String escape(String s) {
        return s == null ? "" : s.replace('<', '(').replace('>', ')');
    }
}
