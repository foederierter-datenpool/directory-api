ARG API_IMAGE=ghcr.io/foederierter-datenpool/directory-api:main
FROM ${API_IMAGE}
ARG DIRECTORY_SOURCE_URL
ADD --chmod=644 ${DIRECTORY_SOURCE_URL} /app/directory.ttl
ARG FEDERATION_SOURCE_URL
ADD --chmod=644 ${FEDERATION_SOURCE_URL} /app/federation.ttl
RUN test -s /app/directory.ttl && test -s /app/federation.ttl
