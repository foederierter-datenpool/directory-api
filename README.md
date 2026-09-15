# Directory API

A Spring Boot API and a separate Apache Jena Fuseki process, packaged in one image.

- `/directory.ttl` streams the deployment's local Turtle snapshot.
- `/collections` discovers target schemas; `/collections/{collection}` describes their fields and relationships.
- `/collections/{collection}/items?limit=50&offset=0` lists entities; follow each item's `href` to retrieve it.
- `/swagger-ui.html` opens Swagger UI, including **Try it out**.
- `/v3/api-docs` serves the OpenAPI description (also `/v3/api-docs.yaml`).
- Fuseki serves `/directory/sparql` on port 3030: SELECT/ASK results and CONSTRUCT/DESCRIBE RDF.

## Configure an instance

Copy [compose.example.yml](compose.example.yml) and [Dockerfile.example](Dockerfile.example)
into your instance as `compose.yml` and `Dockerfile`. Set the `SNAPSHOT_URL` build
argument, image name and `SPRING_APPLICATION_NAME` in Compose. The URL points to a
GitHub publication branch's tar.gz archive (or an immutable commit's archive).

The instance build unpacks that archive into `/app/snapshot`. API and Fuseki use
those same files. Set `DIRECTORY_API_FILE=/app/snapshot/data/directory.ttl` for the
API download. Rebuild to refresh the data; restarting reuses the image's snapshot.

Both Compose and local execution run the same Java launcher, `directory-fuseki.jar`.
At startup it loads Turtle files
from `config/`, `data/`, `webapp/content/` and `webapp/exporters/` in place. It excludes
raw, lifted, extracted and preparation artifacts, hidden files and symlinks. Missing
optional directories are fine; spaces in graph names are URL-encoded.

Only `data/directory.ttl` populates the default graph. Other included files become
named graphs with uniform names: `data/pipeline/merged.ttl` becomes
`urn:directory:data/pipeline/merged.ttl`. Graph names stay stable across restarts.
List populated graphs with `SELECT DISTINCT ?graph WHERE { GRAPH ?graph { ?s ?p ?o } }`.

Fuseki exposes read-only queries with a 30-second timeout; outbound SERVICE calls
and updates are disabled. Invalid Turtle prevents startup. The REST API queries it
at `http://fuseki:3030/directory/sparql` in Compose.

## Collection responses

The API reads the federation's `hasTargetSchema`, `targetClass`, `hasTargetField`
and mapping relationships from `federation.ttl`, loaded in the named graph
`urn:directory:config/federation.ttl`. The default graph remains the directory data. Collection IDs
are the schema IRI's final segment (which must be unique); `hiddenByDefault` does not
hide a collection. No instance-specific Java classes or second schema definition are needed.

Entities contain `id`, the full `iri`, `collection`, `href`, and `properties`.
Property names use predicate final segments; colliding names use their full IRIs.
Collection metadata maps every property name back to its predicate. Only declared
target fields are returned; absent fields are omitted and all present fields are arrays.
Literals preserve their lexical `value`, `datatype` and `language`; resources have
an `iri` and links to matching collections. Blank nodes have snapshot-local IDs and
are not expanded. For example, a name and an address reference look like:

```json
{
  "name": [{"value": "Beratung", "language": "de"}],
  "address": [{"iri": "https://example.org/address/1", "links": [
    {"collection": "addresses", "href": "/collections/addresses/items/aHR0cHM6Ly9leGFtcGxlLm9yZy9hZGRyZXNzLzE"}
  ]}]
}
```

Only entities with IRIs are addressable. Follow returned item/relationship links: IDs encode full IRIs as URL-safe Base64,
so equal local names in different namespaces remain distinct. Pages use stable IRI
order, a `limit` of 1–100, a non-negative `offset`, and an optional `next` link.
Pagination is stable within a snapshot; rebuilding may change its contents.
Invalid parameters return 400, missing collections/items 404 and unavailable Fuseki
503, as Problem Details JSON. Domain-specific filters and additional REST formats
are not implemented yet; Swagger documents the available calls.

## Local development

Requires JDK 25 and local pipeline output. From this repository:

```sh
./gradlew build
java -jar build/fuseki/directory-fuseki.jar ../sosuse-directory-builder
```

The endpoint is `http://localhost:3030/directory/sparql`. Ctrl+C stops Fuseki.
Data stays in the instance directory; restart after a pipeline run to load its new
output. Append `3031` to use another port.

To start the REST API in another terminal:

```sh
java -jar build/libs/directory-api.jar --directory.api.file=../sosuse-directory-builder/data/directory.ttl
```

Open `http://localhost:8080/swagger-ui.html`. The API defaults to the local Fuseki
endpoint. Its download defaults to `./directory.ttl` unless overridden as above.

```sh
curl --get --data-urlencode 'query=SELECT (COUNT(*) AS ?triples) WHERE { ?s ?p ?o }' \
  -H 'Accept: application/sparql-results+json' http://localhost:3030/directory/sparql
```

## Image publication

Pushes to `main` build, test and publish the amd64 image to
`ghcr.io/foederierter-datenpool/directory-api`, tagged `main` and
`sha-<full-commit-sha>`. Pull requests test without publishing. The workflow uses
GitHub's automatic token. Make the container package **Public** after its first publication.

This generic image contains no instance data. Rebuild your instance after publishing
API changes or new pipeline output. To fix the API version, set the `API_IMAGE`
build argument to an image digest. The example allows 512 MiB per container; larger
datasets need measured resource budgets.
