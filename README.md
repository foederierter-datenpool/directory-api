# Directory API

A generic Spring Boot service. Currently serves `/hello` and health endpoints;
RDF loading, data routes and Fuseki come later.

Copy [compose.example.yml](compose.example.yml) to `compose.yml` in your instance
repository and set `SPRING_APPLICATION_NAME`.

## Local development

Requires JDK 25:

```sh
./gradlew build
java -jar build/libs/directory-api.jar
```

Check `http://localhost:8080/hello` and `/actuator/health/readiness`.
For Docker, including local builds on a Mac:

```sh
docker build -t directory-api:local .
docker run --rm -p 127.0.0.1:18080:8080 --memory=512m directory-api:local
```

## Image publication

Pushes to `main` build, test and publish the amd64 image to
`ghcr.io/foederierter-datenpool/directory-api`, tagged `main` and
`sha-<full-commit-sha>`. Pull requests test without publishing. The workflow uses
GitHub's automatic token. After the first publication, make the container package
**Public** in GitHub's package settings to allow pulls without credentials.

Instance Compose files select the image and configuration. `:main` follows new
builds on redeployment; pin a digest for repeatable deployments or rollback.
Publishing an image does not deploy it automatically. The 512 MiB example budget
is for hello-world, not future RDF workloads.
