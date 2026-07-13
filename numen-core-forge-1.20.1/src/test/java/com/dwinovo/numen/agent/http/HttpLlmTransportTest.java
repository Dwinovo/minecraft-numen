package com.dwinovo.numen.agent.http;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpLlmTransportTest {
    @Test void neverAutomaticallyRedirectsAuthenticatedRequests() throws Exception {
        HttpLlmTransport transport = new HttpLlmTransport("", Map.of());
        Field clientField = HttpLlmTransport.class.getDeclaredField("client");
        clientField.setAccessible(true);
        HttpClient client = (HttpClient) clientField.get(transport);
        assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
    }
}
