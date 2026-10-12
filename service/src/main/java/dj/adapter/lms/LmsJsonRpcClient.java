package dj.adapter.lms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** Minimal client for LMS's JSON-RPC endpoint ({@code /jsonrpc.js}, method {@code slim.request}). */
public class LmsJsonRpcClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI endpoint;
    private final Duration timeout;

    public LmsJsonRpcClient(URI lmsUrl, Duration timeout) {
        this(HttpClient.newBuilder().connectTimeout(timeout).build(), lmsUrl, timeout);
    }

    LmsJsonRpcClient(HttpClient http, URI lmsUrl, Duration timeout) {
        this.http = http;
        this.endpoint = lmsUrl.resolve("/jsonrpc.js");
        this.timeout = timeout;
    }

    /**
     * Runs an LMS command and returns its {@code result} object.
     *
     * @param playerId the player to run against, or {@code ""} for server-level queries
     * @param command  the command and its arguments, e.g. {@code ["titles", 0, 100, "tags:alu"]}
     */
    public JsonNode request(String playerId, List<?> command) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("id", 1);
        body.put("method", "slim.request");
        body.putArray("params").add(playerId).addPOJO(command);

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("LMS returned HTTP " + response.statusCode() + " for " + command);
        }

        JsonNode result = JSON.readTree(response.body()).path("result");
        if (result.isMissingNode() || result.isNull()) {
            throw new IOException("LMS returned no result for " + command);
        }
        return result;
    }
}
