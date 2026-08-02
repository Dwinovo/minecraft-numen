/**
 * ②短动作车道:占身体、最坏情况几秒内<b>保证</b>干完的有界动作(装备/丢弃/
 * 进食/交互/合成/容器)。走 TaskDispatch.enqueue 进队,回合挂起等结果——
 * 短到值得等。自测判据:能写出一个不冤枉它的固定 deadline。
 *
 * <p>机制例外:LocateBiome/LocateStructure 语义上是查询,但查群系/结构必须
 * 在服务端线程摸世界数据,机制上只能走本车道。这类"语义 query、机制 act"
 * 的例外要在类注释里写明理由。四连问见 {@link com.dwinovo.numen.core.tools}。
 */
package com.dwinovo.numen.core.tools.act;
