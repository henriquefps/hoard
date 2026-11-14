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

# ffprobe for video metadata (ADR 0014): a small static build with only what phone and camera videos need (the
# demuxers, parsers and decoders below; no network, no filters), so the runtime image stays distroless and small.
# Built for the target platform. The tarball is pinned by SHA-256 (its signature was checked against the FFmpeg release
# key FCF9 86EA 15E6 E293 A564 4F10 B432 2F04 D676 58D8 when pinned); bump both on purpose.
FROM alpine:3.22 AS ffprobe
ARG FFMPEG_VERSION=9.0.2
ARG FFMPEG_SHA256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
RUN apk add --no-cache build-base curl xz
WORKDIR /src
RUN curl -fsSL -o ffmpeg.tar.xz "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" \
 && echo "${FFMPEG_SHA256}  ffmpeg.tar.xz" | sha256sum -c - \
 && tar xJf ffmpeg.tar.xz --strip-components=1 && rm ffmpeg.tar.xz
RUN ./configure --disable-everything --disable-autodetect --disable-network --disable-doc --disable-debug \
      --disable-ffmpeg --disable-ffplay --enable-ffprobe --disable-avdevice --disable-avfilter --disable-swscale \
      --disable-swresample --disable-asm --enable-static --disable-shared --extra-ldflags=-static \
      --enable-protocol=file,subfile,pipe \
      --enable-demuxer=mov,matroska,mpegts,avi \
      --enable-parser=h264,hevc,av1,vp8,vp9,mpeg4video,mpegvideo,mjpeg,aac,ac3,mpegaudio,opus \
      --enable-decoder=h264,hevc,av1,vp8,vp9,prores,mpeg4,mpeg2video,mjpeg,aac,mp3,ac3,eac3,opus,alac,pcm_s16le,pcm_s16be,pcm_s24le \
 && make -j"$(nproc)" ffprobe && strip ffprobe && ./ffprobe -version | head -1

FROM gcr.io/distroless/static-debian13:nonroot
ARG VERSION=dev
LABEL org.opencontainers.image.title="hoard-server" \
      org.opencontainers.image.description="Self-hosted photo server for Hoard and Reveal (formerly reveal-sync)" \
      org.opencontainers.image.source="https://github.com/henriquefps/hoard" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.version="${VERSION}"
COPY --from=build /out/hoard-server /usr/local/bin/hoard-server
COPY --from=ffprobe /src/ffprobe /usr/local/bin/ffprobe
# Owned by the image's user (65532), so fresh named volumes are writable. Bind mounts keep their host
# ownership: chown them to 65532 (README, "Docker").
COPY --from=build --chown=65532:65532 /out/data /data
COPY --from=build --chown=65532:65532 /out/mnt /mnt
ENV PORT=3071 \
    DATA_DIR=/data \
    MOUNTS=/mnt \
    FFPROBE=/usr/local/bin/ffprobe
EXPOSE 3071
VOLUME /data
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/hoard-server"]
CMD ["serve"]
