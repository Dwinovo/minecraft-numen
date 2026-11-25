package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.agent.skill.SkillInfo;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.library.Fingerprint;
import com.dwinovo.numen.api.sdk.ApiDocs;
import com.dwinovo.numen.api.task.reflex.ReflexRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * {@link SeatMethods#CATALOG_GET} 的答复:她能做什么,只含服务端的注册表拥有的东西——大脑拿它拼系统提示,别的(人设、说话的规矩、
 * 操作核心的文字、她自己的技能与模块)是持有大脑的一方自己的。
 *
 * <ul>
 *   <li>{@code api}:API 登记表({@link ApiDocs#catalog}):组、函数的元数据、声明了的类、内置模块;</li>
 *   <li>{@code skills}:随模组与插件发布的技能的名字与说明({@link SkillRegistry#bundled});正文要用时再取;</li>
 *   <li>{@code instincts}:本能名册里每一条的自述({@link ReflexRegistry#descriptions});</li>
 *   <li>{@code version}:以上内容的指纹。插件装上或撤回、{@code /reload} 之后它变,座位通知客户端再取。</li>
 * </ul>
 */
final class SeatCatalog {

    private SeatCatalog() {}

    static JsonObject of(UUID companion) {
        JsonObject body = new JsonObject();
        body.add("api", ApiDocs.catalog(companion));
        JsonArray skills = new JsonArray();
        for (SkillInfo skill : SkillRegistry.of(companion).bundled()) {
            JsonObject s = new JsonObject();
            s.addProperty("name", skill.name());
            s.addProperty("description", skill.description());
            skills.add(s);
        }
        body.add("skills", skills);
        JsonArray instincts = new JsonArray();
        ReflexRegistry.descriptions().forEach(instincts::add);
        body.add("instincts", instincts);
        JsonObject catalog = new JsonObject();
        catalog.addProperty("version", Fingerprint.of(body.toString()));
        body.asMap().forEach(catalog::add);
        return catalog;
    }
}
