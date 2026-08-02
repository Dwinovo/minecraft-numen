/**
 * ①感官车道:不占身体、现场完成的只读工具——向世界提问(扫描/巡视/查状态/
 * 查配方/读蓝图)。结果在 invoke 现场 complete,串行派发器立即推进下一个调用。
 *
 * <p>红线:本包不得出现 TaskDispatch.enqueue / dispatchAsync——需要进任务
 * 系统的工具不属于这里;管大脑自己的工具归 meta。四连问见
 * {@link com.dwinovo.numen.core.tools} 包说明。
 */
package com.dwinovo.numen.core.tools.sense;
