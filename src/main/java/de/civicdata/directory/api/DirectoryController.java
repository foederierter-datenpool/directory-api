package de.civicdata.directory.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class DirectoryController {
    private final Resource directory;

    public DirectoryController(@Value("${directory.api.file:./directory.ttl}") String file) {
        this.directory = new FileSystemResource(file);
    }

    @Operation(summary = "Download the published directory",
            description = "Downloads the Turtle snapshot included in this deployment.")
    @ApiResponse(responseCode = "200", description = "directory.ttl download",
            content = @Content(mediaType = "text/turtle", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "503", description = "The directory snapshot is missing or unreadable",
            content = @Content(mediaType = "application/json"))
    @GetMapping(value = "/directory.ttl", produces = "text/turtle")
    public ResponseEntity<Resource> download() {
        if (!directory.isReadable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Directory snapshot is unavailable");
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"directory.ttl\"")
                .body(directory);
    }
}
