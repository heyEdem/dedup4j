package com.edem.dedup4j.dashboard.polling;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads a registered instance's management API, with a three-second timeout per request. */
public class ManagementClient {
    private final HttpClient client = HttpClient.newBuilder().build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** {@code body} is the parsed JSON of a 2xx answer, null for any other status. */
    public record Response(int status, JsonNode body) {}

    /** Answers with any status; throws when the instance cannot be reached or a 2xx body is not JSON. */
    public Response fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        return new Response(status, status >= 200 && status < 300 ? mapper.readTree(response.body()) : null);
    }

    /** The body of a 2xx answer; any other status is an error. */
    public JsonNode get(String url) throws IOException, InterruptedException {
        Response response = fetch(url);
        if (response.body() == null) throw new IllegalStateException("HTTP " + response.status());
        return response.body();
    }
}
