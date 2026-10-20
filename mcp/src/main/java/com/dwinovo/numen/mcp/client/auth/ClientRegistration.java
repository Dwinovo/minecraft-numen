package com.dwinovo.numen.mcp.client.auth;

import com.dwinovo.numen.mcp.client.McpServerSpec;
import com.nimbusds.oauth2.sdk.GrantType;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.as.AuthorizationServerMetadata;
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod;
import com.nimbusds.oauth2.sdk.client.ClientInformation;
import com.nimbusds.oauth2.sdk.client.ClientMetadata;
import com.nimbusds.oauth2.sdk.client.ClientRegistrationErrorResponse;
import com.nimbusds.oauth2.sdk.client.ClientRegistrationRequest;
import com.nimbusds.oauth2.sdk.client.ClientRegistrationResponse;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;

import java.net.URI;
import java.util.Set;

/**
 * 拿哪个客户端 ID 去登录,照 MCP 授权规范的优先顺序:
 * <ol>
 *   <li>服务器配置里填了 {@code client_id} 就用它;</li>
 *   <li>授权服务器元数据里有 {@code client_id_metadata_document_supported: true},就用客户端 ID 元数据文档:{@link #CLIENT_METADATA_URL}
 *       这个固定的 HTTPS 地址,授权服务器自己去取那份文件(里面的 {@code redirect_uris} 是回环地址,端口按 RFC 8252 可变,
 *       我们照旧用临时端口);</li>
 *   <li>否则授权服务器有 {@code registration_endpoint} 就动态注册(RFC 7591);</li>
 *   <li>都不行就明确告诉主人去配置客户端 ID。</li>
 * </ol>
 */
final class ClientRegistration {

    /** Numen 的客户端 ID 元数据文档:网站上托管的一份固定文件,地址同时就是客户端 ID。 */
    static final String CLIENT_METADATA_URL = "https://numen.dwinovo.cn/oauth/client-metadata.json";

    private ClientRegistration() {}

    static ClientID choose(McpServerSpec spec, AuthorizationServerMetadata as, URI redirectUri) throws Exception {
        if (!spec.clientId().isBlank()) {
            return new ClientID(spec.clientId());
        }
        if (Boolean.TRUE.equals(as.getCustomParameter("client_id_metadata_document_supported"))) {
            return new ClientID(CLIENT_METADATA_URL);
        }
        if (as.getRegistrationEndpointURI() != null) {
            return register(as.getRegistrationEndpointURI(), redirectUri);
        }
        throw new IllegalStateException("the authorization server (" + as.getIssuer() + ") accepts neither a client id "
                + "metadata document nor dynamic registration: set \"client_id\" in this server's config to a client "
                + "you registered with it yourself");
    }

    private static ClientID register(URI endpoint, URI redirectUri) throws Exception {
        ClientMetadata metadata = new ClientMetadata();
        metadata.setName("Numen");
        metadata.setRedirectionURI(redirectUri);
        metadata.setGrantTypes(Set.of(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN));
        metadata.setResponseTypes(Set.of(ResponseType.CODE));
        metadata.setTokenEndpointAuthMethod(ClientAuthenticationMethod.NONE);   // 公开客户端,靠 PKCE
        metadata.setCustomField("application_type", "native");
        HTTPResponse response = new ClientRegistrationRequest(endpoint, metadata, null).toHTTPRequest().send();
        ClientRegistrationResponse parsed = ClientRegistrationResponse.parse(response);
        if (!parsed.indicatesSuccess()) {
            ClientRegistrationErrorResponse error = parsed.toErrorResponse();
            throw new IllegalStateException("dynamic client registration was refused: "
                    + (error.getErrorObject() == null ? "HTTP " + response.getStatusCode()
                    : error.getErrorObject().toString()));
        }
        ClientInformation info = parsed.toSuccessResponse().getClientInformation();
        return info.getID();
    }
}
