package com.dwinovo.numen.build;

import com.dwinovo.numen.ApiLintReport;
import com.dwinovo.numen.NumenApiFixture;
import com.dwinovo.numen.api.sdk.ApiTester;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建筑技能文档里点名的每一个方块都必须真的存在。文档是喂给模型的词汇表:写错一个名字,模型就会照着吐一个无效的 block_id,
 * 而那一格不会报错、只会缺一块。判据是注册表本身,不另立一份清单:反引号里凡是带下划线的小写词,要么在方块/物品注册表里查得到,
 * 要么是风格文件名,两边都不是就是错字。
 */
class SkillDocsTest {

    @BeforeAll
    static void install() {
        NumenApiFixture.install();
    }

    @Test
    void skillDocsNameRealBlocks() throws IOException, URISyntaxException {
        List<ApiTester.Text> docs = ApiLintReport.documents("numen/skills/numen/building_design");
        assertTrue(docs.size() >= 40, "expected the style reference set, found only " + docs.size() + " doc(s)");
        // 风格文件名也在反引号里(正文那份索引),它们是文档名不是方块名。用实际存在的文件当判据,
        // 顺带把索引里指向不存在文件的错字也一起抓了。
        Set<String> docNames = new HashSet<>();
        for (ApiTester.Text doc : docs) {
            String n = Path.of(doc.where()).getFileName().toString();
            docNames.add(n.substring(0, n.length() - 3));
        }
        List<String> bad = new ArrayList<>();
        int checked = 0;
        var token = Pattern.compile("`([^`]+)`");
        var ident = Pattern.compile("[a-z][a-z0-9_]*");
        for (ApiTester.Text doc : docs) {
            List<String> words = new ArrayList<>();
            // 反引号里的(SKILL.md 用这种写法)
            var m = token.matcher(doc.body());
            while (m.find()) {
                String body = m.group(1);
                if (body.indexOf('*') >= 0) {
                    continue;   // 通配写法(stripped_*_log)与调色权重(oak_slab*5)
                }
                // 方块状态跟在方块名后面(`oak_slab[type=double]`):状态键与值是原版的词汇,由原版解析器把关,这里只管方块名对不对
                if (body.indexOf('[') >= 0) {
                    body = body.substring(0, body.indexOf('['));
                }
                var w = ident.matcher(body);
                while (w.find()) {
                    words.add(w.group());
                }
            }
            // 材料槽的裸列(风格文件用这种写法):`- **roof**: a, b, c — 说明`。只收"整块就是一个词"的项,带空格的是散文不是方块名。
            for (String line : doc.body().split("\\R")) {
                if (!line.startsWith("- **") || !line.contains("**:")) {
                    continue;
                }
                String list = line.substring(line.indexOf("**:") + 3);
                for (String sep : new String[]{"—", ";", "("}) {
                    int cut = list.indexOf(sep);
                    if (cut >= 0) {
                        list = list.substring(0, cut);
                    }
                }
                for (String chunk : list.split("[,/]")) {
                    String t = chunk.trim();
                    if (!t.isEmpty() && t.indexOf(' ') < 0) {
                        words.add(t);
                    }
                }
            }
            for (String word : words) {
                if (word.indexOf('_') < 0 || docNames.contains(word)) {
                    continue;
                }
                checked++;
                var id = ResourceLocation.tryParse("minecraft:" + word);
                boolean known = id != null
                        && (BuiltInRegistries.BLOCK.containsKey(id) || BuiltInRegistries.ITEM.containsKey(id));
                if (!known) {
                    bad.add(doc.where() + ": " + word);
                }
            }
        }
        assertTrue(bad.isEmpty(), "skill docs name " + bad.size() + " block(s) that do not exist: " + bad);
        assertTrue(checked > 200, "the lint matched only " + checked + " block names — the extractor is broken");
    }
}
