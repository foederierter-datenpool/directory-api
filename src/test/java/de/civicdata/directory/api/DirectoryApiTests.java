package de.civicdata.directory.api;

import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DirectoryApiTests {
    private static final String TURTLE = "<https://example.org/service> <https://schema.org/name> \"Beratung für alle\" .\n";
    @TempDir
    static Path data;

    @DynamicPropertySource
    static void directoryFile(DynamicPropertyRegistry properties) throws IOException {
        var file = Files.writeString(data.resolve("directory.ttl"), TURTLE);
        properties.add("directory.api.file", file::toString);
    }

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

    @Test
    void servesTheLocalSnapshotAndReportsMissingFiles() throws Exception {
        var download = get("/directory.ttl");
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("content-type")).contains("text/turtle");
        assertThat(download.headers().firstValue("content-disposition")).contains("attachment; filename=\"directory.ttl\"");
        assertThat(download.body()).isEqualTo(TURTLE);

        Files.delete(data.resolve("directory.ttl"));
        try {
            var failure = get("/directory.ttl");
            assertThat(failure.statusCode()).isEqualTo(503);
            assertThat(failure.headers().firstValue("content-disposition").orElse("")).doesNotContain("attachment");
        } finally {
            Files.writeString(data.resolve("directory.ttl"), TURTLE);
        }
    }

    @Test
    void documentsTheDownloadAndServesSwaggerUi() throws Exception {
        var docs = get("/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("\"/directory.ttl\"", "\"text/turtle\"", "\"binary\"", "\"503\"", "\"url\":\"/\"");
        assertThat(get("/v3/api-docs.yaml").statusCode()).isEqualTo(200);
        var redirect = get("/swagger-ui.html");
        assertThat(redirect.statusCode()).isEqualTo(302);
        var ui = get(redirect.headers().firstValue("location").orElseThrow());
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("swagger-ui-bundle.js");
        assertThat(get("/v3/api-docs/swagger-config").body()).contains("\"url\":\"/v3/api-docs\"");
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .header("Accept", path.equals("/directory.ttl") ? "text/turtle" : "*/*").build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
