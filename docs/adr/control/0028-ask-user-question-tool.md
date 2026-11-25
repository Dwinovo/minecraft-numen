# 0028 她的提问工具 AskUserQuestion:照 Claude Code 的同名工具,参数、名字、说明、结局一字对齐

- 状态:已接受,已落地(面板待真机看)
- 日期:2026-11-26
- 另见:[0027](0027-permission-by-action-answered-by-many.md)(权限审批,另一回事)、[runtime 0008](../runtime/0008-brain-on-owners-client.md)
  (大脑在主人客户端)、[0008](0008-lua-only-tool.md)(她的工具)

## 背景

她拿不准时只能开口问一句、这一轮就结束了,主人的回答进来时她已不记得在等什么。用户:"我想干脆做 HITL 工具了,比如说你的
AskUserQuestion";"最好完全对标一下你的 AskUserQuestion 这个工具的参数和名字,以及工具描述";"权限审批和 HITL 不太一样……
HITL 确实是一个工具"。

对照的是 Claude Code 的 `AskUserQuestion`(源码 `tools/AskUserQuestionTool`、`components/permissions/AskUserQuestionPermissionRequest`)
与 Claude Agent SDK 的 "Handle approvals and user input":提问是一次工具调用,调用挂起等人作答,答案就是这次调用的结果。

## 决定

心智模型:**提问是她的一个工具。她调 `AskUserQuestion`,这次调用挂起;主人在面板上作答,答案原样成为这次调用的结果。**
权限审批是权限层的门(0027),不是她的工具,这里不动。

1. **名字与参数照抄。** 工具名 `AskUserQuestion`。参数:
   - `questions`:1–4 个;问话彼此不同。
   - 每个问题:`question`(完整问句,以问号结尾)、`header`(标签,最多 12 个字符)、`options`(2–4 个,`label` 彼此不同)、
     `multiSelect`(默认 false)。
   - 每个选项:`label`(1–5 个词)、`description`(选了会怎样)。
   - 不做 `preview`。"其他"不在参数里,面板自动加。
   参数说明的英文原样照抄,只把 "the user" 换成 "your owner"。
2. **工具说明照抄**:"Asks the user multiple choice questions to gather information, clarify ambiguity, understand preferences, make
   decisions or offer them choices." 与它的用法说明(偏好、澄清、做选择;"其他"永远在;多选;推荐项放第一个并在名字后加
   "(Recommended)"),同样换成 "your owner";计划模式与预览那两段删掉,她没有这两样。
3. **四种结局,模型看到的话照抄:**
   - 答了:`Your owner has answered your questions: "<问话>"="<答案>", …. You can now continue with your owner's answers in mind.`
     (多选的答案用逗号连起来;"其他"的答案是主人写的原话)
   - 拒绝(Esc):"Your owner doesn't want to answer right now. STOP what you are doing and wait for your owner to tell you how to proceed."
   - 想先聊聊("聊聊这个"):拒绝并附上主人的话与已问的问题,照原文 "The owner wants to clarify these questions…"。
   - 打断(停止键、急事、她离开世界):照内核里别的工具被打断的那一句。
4. **面板。** 挂起时这次调用的面板接管输入框:标签、问句、编号选项(选项名后跟淡色的说明)、"其他回答…"输入行、"聊聊这个"。
   数字键选,回车交"其他";多个问题一次显示一个,上面标 1/3,最后一步"确认提交";只有一个单选问题时选了就交。
   挂着时她的过程那一行写"等待回答"。
5. **谁答、在哪答。** 只问创建者(脑子在他电脑上)。只在游戏里答:G 面板与 Y 快捷对话;没在看那个会话时,照她说话那样弹消息通知、
   聊天栏留一行。QQ 等渠道不收这个问题:主人从渠道来跟她说话时她调用它,当场拒绝并告诉她在回话里问(工具表不随渠道变,免得
   打乱缓存)。外接大脑驾驶时它自己问坐在电脑前的人,工具表里没有这个工具。
6. **不设超时。** 主人下线她本来就登出(body 0008),问题随之打断。

## 后果

- 她可以先问再做,主人的回答直接回到那次调用里。
- 工具、参数、说明、结局与 Claude Code 一致,模型在别处学到的用法在这里同样成立。
- 权限审批仍是对话流里的征询卡与它自己的去处;它和提问长得像,但谁发起、谁能答、答完落到哪都不同,两者不合并。
