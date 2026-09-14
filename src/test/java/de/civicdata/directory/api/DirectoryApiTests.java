package de.civicdata.directory.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DirectoryApiTests {
    @LocalServerPort
    private int port;

    @Test
    void servesHelloAndHealthOverHttp() throws Exception {
        var hello = get("/hello");
        assertThat(hello.statusCode()).isEqualTo(200);
        assertThat(hello.headers().firstValue("content-type").orElse("")).startsWith("application/json");
        assertThat(hello.body()).isEqualTo("{\"message\":\"Hello world!\"}");
        var health = get("/actuator/health/readiness");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
