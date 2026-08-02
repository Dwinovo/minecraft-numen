/**
 * ①问答车道:不占身体、现场完成的只读/元工具(感知/巡视/扫描/查配方/读蓝图,
 * 外加 TodoWrite/LoadSkill 两个元工具)。结果在 invoke 现场 complete,串行
 * 派发器立即推进下一个调用。
 *
 * <p>红线:本包不得出现 TaskDispatch.enqueue / dispatchAsync——需要进任务
 * 系统的工具不属于这里。四连问见 {@link com.dwinovo.numen.core.tools} 包说明。
 */
package com.dwinovo.numen.core.tools.query;
