package com.dwinovo.numen.api.agent.tool;

import com.dwinovo.numen.api.agent.llm.ToolOutcome;
import com.dwinovo.numen.api.agent.tool.Questions.Question;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 她向主人提选择题的工具,照 Claude Code 的 AskUserQuestion:一次调用问 1–4 个问题,调用挂起,主人在面板上作答,答案就是这次调用的结果
 * ({@link Questions#result})。主人的面板接管输入框({@link QuestionDesk} 与 {@link Asking} 是面板读的状态);等待没有时限,
 * 停止键、急件、她离开世界把调用放弃或打断,和别的工具被打断走同一条路({@link ToolCall#onAbandon})。大脑在主人电脑上,
 * 所以只问创建者;外接大脑与渠道来访没有这块面板,不提供这个工具({@link #waitsForOwner})。
 */
public final class AskUserQuestionTool implements NumenTool {

    /** 工具名。 */
    public static final String NAME = "AskUserQuestion";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Asks your owner multiple choice questions to gather information, clarify ambiguity, understand "
                + "preferences, make decisions or offer them choices.\n\n"
                + "Use this tool when you need to ask your owner questions during execution. This allows you to:\n"
                + "1. Gather your owner's preferences or requirements\n"
                + "2. Clarify ambiguous instructions\n"
                + "3. Get decisions on implementation choices as you work\n"
                + "4. Offer choices to your owner about what direction to take.\n\n"
                + "Usage notes:\n"
                + "- Your owner will always be able to select \"Other\" to provide custom text input\n"
                + "- Use multiSelect: true to allow multiple answers to be selected for a question\n"
                + "- If you recommend a specific option, make that the first option in the list and add "
                + "\"(Recommended)\" at the end of the label";
    }

    /** 提问不改世界、不改她的东西;同样的问题再问一次只是再问一次。 */
    @Override
    public ToolHints hints() {
        return new ToolHints("Ask your owner", true, false, true, false);
    }

    @Override
    public boolean waitsForOwner() {
        return true;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> label = string("The display text for this option that your owner will see and select. "
                + "Should be concise (1-5 words) and clearly describe the choice.");
        Map<String, Object> description = string("Explanation of what this option means or what will happen if "
                + "chosen. Useful for providing context about trade-offs or implications.");
        Map<String, Object> optionProperties = new LinkedHashMap<>();
        optionProperties.put("label", label);
        optionProperties.put("description", description);
        Map<String, Object> option = object(optionProperties, List.of("label", "description"));

        Map<String, Object> options = array("The available choices for this question. Must have 2-4 options. Each "
                + "option should be a distinct, mutually exclusive choice (unless multiSelect is enabled). There "
                + "should be no 'Other' option, that will be provided automatically.", option);
        options.put("minItems", Questions.MIN_OPTIONS);
        options.put("maxItems", Questions.MAX_OPTIONS);

        Map<String, Object> multi = new LinkedHashMap<>();
        multi.put("type", "boolean");
        multi.put("description", "Set to true to allow your owner to select multiple options instead of just one. "
                + "Use when choices are not mutually exclusive.");
        multi.put("default", false);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("question", string("The complete question to ask your owner. Should be clear, specific, and "
                + "end with a question mark. Example: \"Which library should we use for date formatting?\" If "
                + "multiSelect is true, phrase it accordingly, e.g. \"Which features do you want to enable?\""));
        properties.put("header", string("Very short label displayed as a chip/tag (max " + Questions.MAX_HEADER
                + " chars). Examples: \"Auth method\", \"Library\", \"Approach\"."));
        properties.put("options", options);
        properties.put("multiSelect", multi);
        Map<String, Object> question = object(properties, List.of("question", "header", "options"));

        Map<String, Object> questions = array("Questions to ask your owner (1-" + Questions.MAX_QUESTIONS
                + " questions)", question);
        questions.put("minItems", 1);
        questions.put("maxItems", Questions.MAX_QUESTIONS);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("questions", questions);
        return object(root, List.of("questions"));
    }

    /** 第一个问题的标签:面板上这一行读作"提问  炉子"。 */
    @Override
    public String summary(JsonObject args) {
        if (args.get("questions") instanceof JsonArray list && !list.isEmpty()
                && list.get(0) instanceof JsonObject first && first.get("header") instanceof JsonElement header
                && header.isJsonPrimitive()) {
            return header.getAsString();
        }
        return "";
    }

    /** 参数合规就挂上提问,答复到了的那一刻才交结果;不合规当场失败。 */
    @Override
    public void invoke(ToolCall call) {
        List<Question> questions;
        try {
            questions = Questions.parse(call.args());
        } catch (IllegalArgumentException bad) {
            call.complete(ToolOutcome.failure(bad.getMessage()));
            return;
        }
        QuestionDesk desk = call.ctx().questions();
        Asking asking = desk.ask(questions, reply -> call.complete(Questions.result(questions, reply)));
        call.onAbandon(() -> desk.withdraw(asking));
    }

    private static Map<String, Object> string(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> array(String description, Map<String, Object> items) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "array");
        p.put("description", description);
        p.put("items", items);
        return p;
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "object");
        p.put("properties", new LinkedHashMap<>(properties));
        p.put("required", required);
        p.put("additionalProperties", false);
        return p;
    }
}
