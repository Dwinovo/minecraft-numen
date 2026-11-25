package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.agent.skill.SkillInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code catalog/get} 的答复在客户端这一头的样子:她能做什么,服务端的注册表拥有的那些。大脑拿它拼系统提示;目录的内容由服务端定,
 * 这里只读。
 *
 * @param version 内容的指纹;变了就是插件或 {@code /reload} 改了目录,要再取一次
 * @param api     API 登记表({@code ApiDocs#catalog})
 * @param skills  随模组与插件发布的技能:名字与说明
 * @param instincts 本能名册里每一条的自述
 */
public record Catalog(String version, JsonObject api, List<SkillInfo> skills, List<String> instincts) {

    /** 一份都没有的目录:座位还没问过服务端。 */
    public static final Catalog EMPTY = new Catalog("", emptyApi(), List.of(), List.of());

    public static Catalog fromJson(JsonObject o) {
        List<SkillInfo> skills = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("skills")) {
            JsonObject s = e.getAsJsonObject();
            skills.add(new SkillInfo(s.get("name").getAsString(), s.get("description").getAsString(), null, null));
        }
        List<String> instincts = new ArrayList<>();
        o.getAsJsonArray("instincts").forEach(i -> instincts.add(i.getAsString()));
        return new Catalog(o.get("version").getAsString(), o.getAsJsonObject("api"), List.copyOf(skills),
                List.copyOf(instincts));
    }

    /** 内置模块的名字。 */
    public Set<String> builtinModules() {
        Set<String> names = new TreeSet<>();
        api.getAsJsonArray("modules").forEach(m -> names.add(m.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    private static JsonObject emptyApi() {
        JsonObject api = new JsonObject();
        for (String key : List.of("groups", "functions", "shared", "modules")) {
            api.add(key, new JsonArray());
        }
        api.add("classes", new JsonObject());
        return api;
    }
}
