# Directory API

A generic Spring Boot service with a Turtle download and interactive API documentation.

- `/directory.ttl` streams the deployment's local Turtle snapshot.
- `/swagger-ui.html` opens Swagger UI, including **Try it out**.
- `/v3/api-docs` serves the OpenAPI description (also `/v3/api-docs.yaml`).

## Configure an instance

Copy [compose.example.yml](compose.example.yml) and [Dockerfile.example](Dockerfile.example)
into your instance repository as `compose.yml` and `Dockerfile`. Set
`DIRECTORY_SOURCE_URL` under build arguments and `SPRING_APPLICATION_NAME` under environment.

The instance build takes the published API image and downloads the Turtle file
into `/app/directory.ttl`. Failed or empty downloads fail the build. Each build
fetches the source again; restarting a container keeps its existing snapshot.
No Java compilation happens in the instance build.

The API only reads the local file, streaming it without loading it all into memory.
A missing or unreadable snapshot returns 503. RDF parsing and Fuseki come later.

## Local development

Requires JDK 25 and a pipeline output file:

```sh
./gradlew build
java -jar build/libs/directory-api.jar --directory.api.file=/absolute/path/to/directory.ttl
```

Open `http://localhost:8080/swagger-ui.html`. The default file is `./directory.ttl`;
`DIRECTORY_API_FILE` can override it in a container.

## Image publication

Pushes to `main` build, test and publish the amd64 image to
`ghcr.io/foederierter-datenpool/directory-api`, tagged `main` and
`sha-<full-commit-sha>`. Pull requests test without publishing. The workflow uses
GitHub's automatic token. Make the container package **Public** after its first publication.

This generic image contains no instance data. Rebuild your instance after publishing
API changes or new pipeline output. To fix the API version, set the `API_IMAGE`
build argument to an image digest. The 512 MiB example limit does not account for
future RDF query workloads.
