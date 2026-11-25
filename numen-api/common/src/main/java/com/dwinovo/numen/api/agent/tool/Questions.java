package com.dwinovo.numen.api.agent.tool;

import com.dwinovo.numen.api.agent.llm.ToolOutcome;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link AskUserQuestionTool} 问的是什么、主人怎么回、模型读到什么:问题的数据与校验、回复的四种形状、每种回复变成结果的文字。
 * 参数的读法与校验、结果的措辞都只在这里;面板({@link Asking})只管把主人的选择收成一份 {@link Reply}。
 */
public final class Questions {

    private Questions() {}

    /** 一个问题最多几个字符的标签。 */
    public static final int MAX_HEADER = 12;
    /** 一次最多问几个问题。 */
    public static final int MAX_QUESTIONS = 4;
    /** 一个问题最少、最多几个选项。 */
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 4;

    /** 一个选项:名字与选了会怎样。 */
    public record Option(String label, String description) {}

    /** 一个问题:完整问句、标签、选项、能不能多选。"其他"不在选项里,面板自动加。 */
    public record Question(String question, String header, List<Option> options, boolean multiSelect) {

        public Question {
            options = List.copyOf(options);
        }
    }

    /** 主人对一个问题的答案;{@code notes} 是他附的话,没附是 null。多选的答案是各项用逗号连起来。 */
    public record Answer(String question, String answer, String notes) {}

    /** 主人怎么回:答了、拒绝(Esc)、想先聊聊。 */
    public sealed interface Reply {

        /** 每个问题都答了。 */
        record Answered(List<Answer> answers) implements Reply {

            public Answered {
                answers = List.copyOf(answers);
            }
        }

        /** 现在不想答。 */
        record Declined() implements Reply {}

        /** 想先聊聊这些问题;{@code answers} 是到此为止已答的。 */
        record Clarify(List<Answer> answers) implements Reply {

            public Clarify {
                answers = List.copyOf(answers);
            }
        }
    }

    private static final String QUESTIONS = "questions";

    // ---- 参数 ----

    /**
     * 读参数里的问题并校验:1–4 个问题,问句彼此不同;每题 2–4 个选项、名字彼此不同,标签不超过 {@link #MAX_HEADER} 个字符。
     *
     * @throws IllegalArgumentException 参数不合规,消息是给模型看的原因
     */
    public static List<Question> parse(JsonObject args) {
        for (String key : args.keySet()) {
            if (!key.equals(QUESTIONS)) {
                throw new IllegalArgumentException("unknown argument '" + key + "'; this takes: " + QUESTIONS);
            }
        }
        if (!(args.get(QUESTIONS) instanceof JsonArray array) || array.isEmpty() || array.size() > MAX_QUESTIONS) {
            throw new IllegalArgumentException("argument '" + QUESTIONS + "' must be a list of 1-" + MAX_QUESTIONS
                    + " questions");
        }
        List<Question> questions = new ArrayList<>();
        Set<String> texts = new HashSet<>();
        for (JsonElement element : array) {
            Question question = question(element);
            if (!texts.add(question.question())) {
                throw new IllegalArgumentException("Question texts must be unique, option labels must be unique "
                        + "within each question");
            }
            questions.add(question);
        }
        return questions;
    }

    private static Question question(JsonElement element) {
        if (!(element instanceof JsonObject o)) {
            throw new IllegalArgumentException("each question is an object with question, header, options, multiSelect");
        }
        String text = text(o, "question");
        String header = text(o, "header");
        if (header.codePointCount(0, header.length()) > MAX_HEADER) {
            throw new IllegalArgumentException("header \"" + header + "\" is longer than " + MAX_HEADER + " characters");
        }
        if (!(o.get("options") instanceof JsonArray options) || options.size() < MIN_OPTIONS
                || options.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("question \"" + text + "\" must have " + MIN_OPTIONS + "-" + MAX_OPTIONS
                    + " options");
        }
        boolean multi = false;
        JsonElement flag = o.get("multiSelect");
        if (flag != null && !flag.isJsonNull()) {
            if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("multiSelect of question \"" + text + "\" must be true or false");
            }
            multi = flag.getAsBoolean();
        }
        List<Option> list = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (JsonElement e : options) {
            if (!(e instanceof JsonObject option)) {
                throw new IllegalArgumentException("each option is an object with label and description");
            }
            String label = text(option, "label");
            if (!labels.add(label)) {
                throw new IllegalArgumentException("Question texts must be unique, option labels must be unique "
                        + "within each question");
            }
            list.add(new Option(label, description(option)));
        }
        return new Question(text, header, list, multi);
    }

    private static String text(JsonObject o, String key) {
        JsonElement value = o.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("'" + key + "' is missing: a non-empty string");
        }
        return value.getAsString();
    }

    private static String description(JsonObject o) {
        JsonElement value = o.get("description");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("'description' is missing: what choosing this option means");
        }
        return value.getAsString();
    }

    // ---- 结果 ----

    /** 主人拒绝(Esc)时模型读到的话。 */
    static final String DECLINED = "Your owner doesn't want to answer right now. STOP what you are doing and wait "
            + "for your owner to tell you how to proceed.";

    private static final String DECLINED_WITH_REASON = "Your owner doesn't want to answer right now. To tell you how "
            + "to proceed, your owner said:\n";

    /** 一次调用的结果:回复照它的形状变成信封;答了的结果还带着结构化的答案,界面据它在对话流里画一行。 */
    public static String result(List<Question> asked, Reply reply) {
        return switch (reply) {
            case Reply.Answered answered -> answered(answered.answers());
            case Reply.Declined declined -> ToolOutcome.failure(DECLINED);
            case Reply.Clarify clarify -> ToolOutcome.failure(DECLINED_WITH_REASON + clarifyFeedback(asked, clarify.answers()));
        };
    }

    private static String answered(List<Answer> answers) {
        String parts = answers.stream().map(a -> "\"" + a.question() + "\"=\"" + a.answer() + "\""
                + (a.notes() == null || a.notes().isBlank() ? "" : " user notes: " + a.notes()))
                .collect(Collectors.joining(", "));
        JsonObject envelope = JsonParser.parseString(ToolOutcome.success("Your owner has answered your questions: "
                + parts + ". You can now continue with your owner's answers in mind.")).getAsJsonObject();
        JsonArray list = new JsonArray();
        for (Answer a : answers) {
            JsonObject o = new JsonObject();
            o.addProperty("question", a.question());
            o.addProperty("answer", a.answer());
            if (a.notes() != null && !a.notes().isBlank()) {
                o.addProperty("notes", a.notes());
            }
            list.add(o);
        }
        envelope.add("answers", list);
        return envelope.toString();
    }

    private static String clarifyFeedback(List<Question> asked, List<Answer> answers) {
        StringBuilder out = new StringBuilder("The owner wants to clarify these questions.\n"
                + "This means they may have additional information, context or questions for you.\n"
                + "Take their response into account and then reformulate the questions if appropriate.\n"
                + "Start by asking them what they would like to clarify.\n\n"
                + "Questions asked:");
        for (Question q : asked) {
            Answer a = answers.stream().filter(x -> x.question().equals(q.question())).findFirst().orElse(null);
            out.append("\n- \"").append(q.question()).append("\"\n  ")
                    .append(a == null ? "(No answer provided)" : "Answer: " + a.answer());
        }
        return out.toString();
    }

    /**
     * 一条工具结果里主人的答案(界面在对话流里画"你答「…」"用);不是答了的结果(失败、拒绝、别的工具的结果)是空表。
     */
    public static List<Answer> answersIn(String result) {
        if (result == null || ToolOutcome.failed(result) || !result.stripLeading().startsWith("{")) {
            return List.of();
        }
        try {
            JsonElement list = JsonParser.parseString(result).getAsJsonObject().get("answers");
            if (!(list instanceof JsonArray array)) {
                return List.of();
            }
            List<Answer> out = new ArrayList<>();
            for (JsonElement e : array) {
                JsonObject o = e.getAsJsonObject();
                out.add(new Answer(o.get("question").getAsString(), o.get("answer").getAsString(),
                        o.has("notes") ? o.get("notes").getAsString() : null));
            }
            return out;
        } catch (RuntimeException notAnswers) {
            return List.of();   // 别的工具的结果:没有答案可读
        }
    }
}
