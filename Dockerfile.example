ARG API_IMAGE=ghcr.io/foederierter-datenpool/directory-api:main
FROM ${API_IMAGE}
ARG SNAPSHOT_URL
ADD ${SNAPSHOT_URL} /tmp/snapshot.tar.gz
USER root
RUN mkdir -p /app/snapshot \
    && tar -xzf /tmp/snapshot.tar.gz --strip-components=1 -C /app/snapshot \
    && rm /tmp/snapshot.tar.gz \
    && test -s /app/snapshot/data/directory.ttl \
    && test -s /app/snapshot/config/federation.ttl
USER app
