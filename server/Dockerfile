# hoard-server image: a static binary on distroless, non-root. Build from server/:
#   docker build -t hoard-server .
#   docker build --build-arg VERSION=0.4.0 -t hoard-server .

# BuildKit sets BUILDPLATFORM/TARGETOS/TARGETARCH; the legacy builder needs them as --build-arg (README, "Docker").
ARG BUILDPLATFORM
FROM --platform=$BUILDPLATFORM golang:1.27-alpine AS build
ARG TARGETOS
ARG TARGETARCH
ARG VERSION=dev
ARG COMMIT=none
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH \
    go build -trimpath -ldflags "-s -w -X main.version=${VERSION} -X main.commit=${COMMIT}" -o /out/hoard-server . \
 && mkdir -p /out/data /out/mnt

FROM gcr.io/distroless/static-debian13:nonroot
ARG VERSION=dev
LABEL org.opencontainers.image.title="hoard-server" \
      org.opencontainers.image.description="Self-hosted photo server for Hoard and Reveal (formerly reveal-sync)" \
      org.opencontainers.image.source="https://github.com/henriquefps/hoard" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.version="${VERSION}"
COPY --from=build /out/hoard-server /usr/local/bin/hoard-server
# Owned by the image's user (65532), so fresh named volumes are writable. Bind mounts keep their host
# ownership: chown them to 65532 (README, "Docker").
COPY --from=build --chown=65532:65532 /out/data /data
COPY --from=build --chown=65532:65532 /out/mnt /mnt
ENV PORT=3071 \
    DATA_DIR=/data \
    MOUNTS=/mnt
EXPOSE 3071
VOLUME /data
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/hoard-server"]
CMD ["serve"]
