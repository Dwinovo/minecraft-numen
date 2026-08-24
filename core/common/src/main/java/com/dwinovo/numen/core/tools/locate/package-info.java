/**
 * 找地方:群系/结构定位。语义是查询,但必须进服务端线程摸世界数据,机制上
 * 走 enqueue 短任务——本包整体是四连问第 2 问的"机制例外"。四连问见
 * {@link com.dwinovo.numen.core.tools} 包说明。
 */
package com.dwinovo.numen.core.tools.locate;
