# hoard-server image: a static Go binary plus the preview tools (libvips, ffmpeg) on Alpine, non-root. Build from
# server/:
#   docker build -t hoard-server .
#   docker build --build-arg VERSION=0.6.0 -t hoard-server .

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

# Preview tools (ADR 0015), built for the target platform into /opt/hoard. Every tarball is pinned by SHA-256 (the
# FFmpeg one's signature was checked against the FFmpeg release key FCF9 86EA 15E6 E293 A564 4F10 B432 2F04 D676 58D8
# when pinned); bump both on purpose. Only decoders: no encoder of libheif, no loader libvips does not need.
FROM alpine:3.22 AS tools
RUN apk add --no-cache build-base cmake meson ninja pkgconf curl xz nasm \
      glib-dev expat-dev libjpeg-turbo-dev lcms2-dev libexif-dev libwebp-dev libspng-dev libraw-dev highway-dev \
      libde265-dev dav1d-dev x264-dev zimg-dev zlib-dev
ENV LD_LIBRARY_PATH=/opt/hoard/lib
WORKDIR /src

# libheif: HEIC (libde265) and AVIF (dav1d) decoding only.
ARG LIBHEIF_VERSION=1.23.7
ARG LIBHEIF_SHA256=f7483e818503ea6224c8863e4a56ac02c7089021a98472c811c12aa19faf2475
RUN curl -fsSL -o heif.tar.gz "https://github.com/strukturag/libheif/releases/download/v${LIBHEIF_VERSION}/libheif-${LIBHEIF_VERSION}.tar.gz" \
 && echo "${LIBHEIF_SHA256}  heif.tar.gz" | sha256sum -c - \
 && mkdir heif && tar xzf heif.tar.gz -C heif --strip-components=1 \
 && cmake -S heif -B heif/build -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/opt/hoard \
      -DCMAKE_INSTALL_LIBDIR=lib -DENABLE_PLUGIN_LOADING=OFF -DWITH_EXAMPLES=OFF -DWITH_GDK_PIXBUF=OFF \
      -DBUILD_TESTING=OFF -DBUILD_DOCUMENTATION=OFF -DWITH_LIBSHARPYUV=OFF \
      -DWITH_LIBDE265=ON -DWITH_DAV1D=ON -DWITH_X265=OFF -DWITH_X264=OFF -DWITH_AOM_DECODER=OFF -DWITH_AOM_ENCODER=OFF \
      -DWITH_OpenH264_DECODER=OFF -DWITH_OpenJPEG_DECODER=OFF -DWITH_OpenJPEG_ENCODER=OFF -DWITH_SvtEnc=OFF \
      -DWITH_RAV1E=OFF -DWITH_FFMPEG_DECODER=OFF -DWITH_JPEG_DECODER=OFF -DWITH_JPEG_ENCODER=OFF \
      -DWITH_OPENJPH_ENCODER=OFF -DWITH_UNCOMPRESSED_CODEC=OFF -DWITH_KVAZAAR=OFF -DWITH_VVDEC=OFF -DWITH_VVENC=OFF \
      -DWITH_UVG266=OFF \
 && ninja -C heif/build -j"$(nproc)" install && rm -rf heif heif.tar.gz

# libvips: JPEG, PNG, WebP, HEIF/AVIF, RAW (LibRaw), PPM; lcms2 for ICC profiles, libexif for orientation.
ARG VIPS_VERSION=8.18.7
ARG VIPS_SHA256=5baaead3b0bb20ffdb9e9ff09aa9fda08620923df77b63b436654cb5e0b3bf94
RUN curl -fsSL -o vips.tar.xz "https://github.com/libvips/libvips/releases/download/v${VIPS_VERSION}/vips-${VIPS_VERSION}.tar.xz" \
 && echo "${VIPS_SHA256}  vips.tar.xz" | sha256sum -c - \
 && mkdir vips && tar xJf vips.tar.xz -C vips --strip-components=1 \
 && PKG_CONFIG_PATH=/opt/hoard/lib/pkgconfig meson setup vips/build vips --prefix=/opt/hoard --libdir=lib \
      --buildtype=release -Dauto_features=disabled -Dmodules=disabled -Ddeprecated=false -Dexamples=false \
      -Dcplusplus=false -Dintrospection=disabled -Dvapi=false -Ddocs=false -Dcpp-docs=false \
      -Djpeg=enabled -Dspng=enabled -Dwebp=enabled -Dheif=enabled -Draw=enabled -Dlcms=enabled -Dexif=enabled \
      -Dhighway=enabled -Dzlib=enabled -Dppm=true -Dnsgif=false -Danalyze=false -Dradiance=false \
 && ninja -C vips/build -j"$(nproc)" install && rm -rf vips vips.tar.xz \
 && /opt/hoard/bin/vips --version

# ffmpeg and ffprobe: the containers, parsers and decoders of phone and camera videos (ffprobe for metadata, ADR
# 0014), and for previews (ADR 0015) H.264 (x264) and AAC encoding, PPM frames, zscale + tonemap for HDR. No network.
# GPL configuration because of x264; run as separate programs.
ARG FFMPEG_VERSION=9.0.2
ARG FFMPEG_SHA256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
RUN curl -fsSL -o ffmpeg.tar.xz "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" \
 && echo "${FFMPEG_SHA256}  ffmpeg.tar.xz" | sha256sum -c - \
 && mkdir ffmpeg && tar xJf ffmpeg.tar.xz -C ffmpeg --strip-components=1 \
 && cd ffmpeg && ./configure --prefix=/opt/hoard --disable-everything --disable-autodetect --disable-network \
      --disable-doc --disable-debug --disable-ffplay --disable-avdevice --enable-gpl \
      --enable-libx264 --enable-libzimg --enable-libdav1d --enable-zlib \
      --enable-protocol=file,subfile,pipe \
      --enable-demuxer=mov,matroska,mpegts,avi \
      --enable-muxer=mp4,image2pipe \
      --enable-parser=h264,hevc,av1,vp8,vp9,mpeg4video,mpegvideo,mjpeg,aac,ac3,mpegaudio,opus,amr \
      --enable-decoder=h264,hevc,libdav1d,vp8,vp9,prores,mpeg4,mpeg2video,mjpeg,aac,mp3,mp2,ac3,eac3,opus,alac,amrnb,amrwb,pcm_s16le,pcm_s16be,pcm_s24le,pcm_f32le \
      --enable-encoder=libx264,aac,ppm \
      --enable-filter=scale,zscale,tonemap,format,fps,transpose,hflip,vflip,rotate,setsar,null,anull,aresample,aformat \
      --enable-swscale --enable-swresample --enable-avfilter --enable-shared --disable-static \
 && make -j"$(nproc)" && make install && cd .. && rm -rf ffmpeg ffmpeg.tar.xz \
 && /opt/hoard/bin/ffmpeg -hide_banner -version | head -1 \
 && rm -rf /opt/hoard/include /opt/hoard/share /opt/hoard/lib/pkgconfig /opt/hoard/lib/*.a /opt/hoard/lib/cmake \
 && find /opt/hoard/bin -type f ! -name vips ! -name ffmpeg ! -name ffprobe -delete \
 && strip /opt/hoard/bin/* /opt/hoard/lib/*.so.* 2>/dev/null; true

FROM alpine:3.22
ARG VERSION=dev
LABEL org.opencontainers.image.title="hoard-server" \
      org.opencontainers.image.description="Self-hosted photo server for Hoard and Reveal (formerly reveal-sync)" \
      org.opencontainers.image.source="https://github.com/henriquefps/hoard" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.version="${VERSION}"
# Runtime libraries of the tools above (Alpine packages), no shell tools added.
RUN apk add --no-cache glib expat libjpeg-turbo lcms2 libexif libwebp libwebpmux libwebpdemux libsharpyuv libspng \
      libraw libhwy libde265 dav1d x264-libs zimg zlib libstdc++ libgomp
COPY --from=tools /opt/hoard /opt/hoard
# Fail the build if a library is missing: every program starts and has its loaders and encoders.
RUN export LD_LIBRARY_PATH=/opt/hoard/lib \
 && /opt/hoard/bin/vips --version \
 && for l in jpegload heifload dcrawload ppmload; do /opt/hoard/bin/vips -l foreign | grep -q "($l)"; done \
 && /opt/hoard/bin/ffmpeg -hide_banner -encoders | grep -q libx264 \
 && /opt/hoard/bin/ffmpeg -hide_banner -filters | grep -q tonemap \
 && /opt/hoard/bin/ffprobe -version | head -1
COPY --from=build /out/hoard-server /usr/local/bin/hoard-server
# Owned by the image's user (65532), so fresh named volumes are writable. Bind mounts keep their host
# ownership: chown them to 65532 (README, "Docker").
COPY --from=build --chown=65532:65532 /out/data /data
COPY --from=build --chown=65532:65532 /out/mnt /mnt
ENV PORT=3071 \
    DATA_DIR=/data \
    MOUNTS=/mnt \
    LD_LIBRARY_PATH=/opt/hoard/lib \
    FFPROBE=/opt/hoard/bin/ffprobe \
    FFMPEG=/opt/hoard/bin/ffmpeg \
    VIPS=/opt/hoard/bin/vips
EXPOSE 3071
VOLUME /data
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/hoard-server"]
CMD ["serve"]
