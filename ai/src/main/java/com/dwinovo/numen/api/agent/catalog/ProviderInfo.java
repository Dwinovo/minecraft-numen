package com.dwinovo.numen.api.agent.catalog;

import java.util.List;
import java.util.Map;

/**
 * 目录里的一个厂家。
 *
 * @param baseUrl 缺省接口地址,可能为空(云厂商按租户拼地址的那种)
 * @param api     厂家缺省的协议(型号可以各自覆盖,见 {@link ModelInfo#api})
 * @param docUrl  文档链接
 * @param headers 请求要带的额外报头
 * @param models  型号,保持来源里的顺序
 */
public record ProviderInfo(String id, String name, String baseUrl, Api api, String docUrl,
                           Map<String, String> headers, List<ModelInfo> models) {

    public ProviderInfo {
        headers = Map.copyOf(headers);
        models = List.copyOf(models);
    }

    /** 接口地址里的主机名(界面上用来认厂家);地址为空是空串。不走 URI 解析:目录里有带占位符的地址。 */
    public String host() {
        int scheme = baseUrl.indexOf("://");
        String rest = scheme >= 0 ? baseUrl.substring(scheme + 3) : baseUrl;
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(0, slash) : rest;
    }

    /** 厂家下的某个型号;没有给 {@code null}。 */
    public ModelInfo model(String modelId) {
        if (modelId != null) {
            for (ModelInfo m : models) {
                if (m.id().equals(modelId)) return m;
            }
        }
        return null;
    }
}
