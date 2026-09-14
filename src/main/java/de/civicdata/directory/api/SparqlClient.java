package de.civicdata.directory.api;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class SparqlClient {
    private final RestClient client;
    private final String endpoint;
    private final JsonMapper json = JsonMapper.builder().build();

    SparqlClient(@Value("${directory.api.sparql-url:http://localhost:3030/directory/sparql}") String endpoint) {
        this.endpoint = endpoint;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(35));
        client = RestClient.builder().requestFactory(factory).build();
    }

    JsonNode select(String query) {
        try {
            var result = client.post().uri(endpoint)
                    .contentType(MediaType.parseMediaType("application/sparql-query"))
                    .accept(MediaType.parseMediaType("application/sparql-results+json"))
                    .body(query).retrieve().body(JsonNode.class);
            if (result == null || !result.path("results").path("bindings").isArray()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid response from the SPARQL service");
            }
            return result.path("results").path("bindings");
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The SPARQL service is unavailable");
        }
    }

    // JSON string escaping also produces a valid SPARQL string literal.
    String string(String value) {
        return json.writeValueAsString(value);
    }
}
