package com.readygym.server.ai;

/** The upstream model provider. An interface so tests can run without network or a real key. */
public interface ModelGateway {
    /** Sends an OpenAI-compatible chat request body and returns the provider's raw JSON response. */
    UpstreamResponse complete(String requestJson);

    record UpstreamResponse(int status, String body) {}
}
