# Debian-based (not Alpine/musl): libpcsclite's Debian package has
# /run/pcscd/pcscd.comm compiled in as its default IPC socket path, matching
# the host's own pcscd build — that's what makes the bind-mount in
# docker-compose.yml work with zero configuration on either side.
FROM golang:1.25-bookworm AS builder
RUN apt-get update && apt-get install -y --no-install-recommends libpcsclite-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=1 GOOS=linux go build -o /out/reader-agent .

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends libpcsclite1 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -u 10001 -m appuser
COPY --from=builder /out/reader-agent /usr/local/bin/reader-agent
USER appuser
EXPOSE 17420
ENTRYPOINT ["/usr/local/bin/reader-agent"]
