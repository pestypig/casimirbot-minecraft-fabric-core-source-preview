package com.casimirbot.helixsensor.probe;

import com.casimirbot.helixsensor.HelixHttpClient;
import com.casimirbot.helixsensor.HelixJson;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ProbeResultPublisher {
    private final HelixHttpClient httpClient;

    public ProbeResultPublisher(HelixHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public CompletableFuture<HelixHttpClient.IngressResponse> publishAsync(
        Map<String, Object> result
    ) {
        return httpClient.postProbeResultAsync(HelixJson.stringify(result));
    }
}
