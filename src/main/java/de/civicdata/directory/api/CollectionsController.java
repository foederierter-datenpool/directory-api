package de.civicdata.directory.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ApiResponse(responseCode = "200", description = "Successful response", useReturnTypeSchema = true)
@ApiResponse(responseCode = "503", description = "SPARQL service unavailable",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class CollectionsController {
    private final DirectoryCollections directory;

    CollectionsController(DirectoryCollections directory) {
        this.directory = directory;
    }

    // curl http://localhost:8080/collections
    @Operation(summary = "Discover the configured target-schema collections")
    @GetMapping(value = "/collections", produces = MediaType.APPLICATION_JSON_VALUE)
    public CollectionList collections() {
        return new CollectionList(directory.collections());
    }

    @Operation(summary = "Describe a collection's fields and relationships")
    @ApiResponse(responseCode = "404", description = "Collection not found", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/collections/{collection}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CollectionInfo collection(@Parameter(description = "Collection ID returned by /collections", example = "einrichtungSchema")
                                     @PathVariable String collection) {
        return directory.collection(collection);
    }

    // curl 'http://localhost:8080/collections/einrichtungSchema/items?limit=10'
    @Operation(summary = "List entities in stable IRI order",
            description = "Returns JSON entities with arrays of RDF values. Follow next for the next page; links and IDs apply to this deployment's snapshot.")
    @ApiResponse(responseCode = "400", description = "Invalid pagination", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Collection not found", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/collections/{collection}/items", produces = MediaType.APPLICATION_JSON_VALUE)
    public ItemPage items(@Parameter(example = "einrichtungSchema") @PathVariable String collection,
                          @Parameter(description = "Entities per page, from 1 to 100", example = "10") @RequestParam(defaultValue = "50") int limit,
                          @Parameter(description = "Number of entities to skip", example = "0") @RequestParam(defaultValue = "0") int offset) {
        return directory.items(collection, limit, offset);
    }

    @Operation(summary = "Retrieve one entity",
            description = "Follow an item's href or a relationship link. IDs are URL-safe Base64 encodings of full RDF IRIs; they are not source identifiers.")
    @ApiResponse(responseCode = "400", description = "Invalid item ID", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Collection or item not found", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/collections/{collection}/items/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Item item(@Parameter(example = "einrichtungSchema") @PathVariable String collection,
                     @Parameter(description = "Opaque ID from an item response") @PathVariable String id) {
        return directory.item(collection, id);
    }

    public record CollectionList(List<CollectionInfo> collections) {}
    public record CollectionInfo(String id, String iri, String type, List<RdfValue> labels,
                                 List<Field> fields, String href, String items) {}
    public record Field(String name, String predicate, List<String> collections) {}
    public record Item(String id, String iri, String collection, String href,
                       Map<String, List<RdfValue>> properties) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemPage(List<Item> items, int limit, int offset, String next) {}
    public record Link(String collection, String href) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "A literal has value and optional datatype/language; an IRI has iri and optional links. Blank nodes have a snapshot-local blankNode identifier.")
    public record RdfValue(String value, String datatype, String language, String iri, String blankNode, List<Link> links) {}
}
