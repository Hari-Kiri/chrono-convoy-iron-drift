package com.chronoconvoy.client;

/**
 * Top-level package alias for {@link com.chronoconvoy.client.net.BackendApiClient}.
 */
public class BackendApiClient extends com.chronoconvoy.client.net.BackendApiClient {

    public BackendApiClient() {
        super();
    }

    public BackendApiClient(String baseUrl) {
        super(baseUrl);
    }

    public BackendApiClient(String baseUrl, String playerId, String convoyId) {
        super(baseUrl, playerId, convoyId);
    }
}
