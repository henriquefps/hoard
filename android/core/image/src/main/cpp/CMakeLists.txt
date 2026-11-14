# Native imaging for dev.hfps.hoard.image: LibRaw (vendored, third_party/LibRaw) + a small JNI layer.
cmake_minimum_required(VERSION 3.22.1)
project(hoardimage CXX)

set(LIBRAW_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/LibRaw)

# Same object list as LibRaw's Makefile.dist (LIB_OBJECTS).
set(LIBRAW_SOURCES
  src/libraw_datastream.cpp src/libraw_c_api.cpp
  src/tables/cameralist.cpp src/decoders/fuji_compressed.cpp src/decoders/crx.cpp
  src/decoders/pana8.cpp src/decoders/fp_dng.cpp src/decoders/decoders_libraw.cpp
  src/decoders/olympus14.cpp src/decoders/sonycc.cpp src/decompressors/losslessjpeg.cpp
  src/decoders/unpack.cpp src/decoders/unpack_thumb.cpp
  src/integration/rawspeed_glue.cpp src/integration/dngsdk_glue.cpp
  src/tables/colorconst.cpp src/utils/utils_libraw.cpp src/utils/init_close_utils.cpp
  src/utils/decoder_info.cpp src/utils/open.cpp src/utils/phaseone_processing.cpp
  src/utils/thumb_utils.cpp src/write/tiff_writer.cpp src/preprocessing/subtract_black.cpp
  src/postprocessing/postprocessing_utils.cpp src/postprocessing/dcraw_process.cpp
  src/preprocessing/raw2image.cpp src/postprocessing/mem_image.cpp
  src/x3f/x3f_utils_patched.cpp src/x3f/x3f_parse_process.cpp
  src/utils/read_utils.cpp src/utils/curves.cpp src/utils/utils_dcraw.cpp
  src/tables/colordata.cpp src/decoders/canon_600.cpp src/decoders/decoders_dcraw.cpp
  src/decoders/decoders_libraw_dcrdefs.cpp src/decoders/generic.cpp
  src/decoders/kodak_decoders.cpp src/decoders/dng.cpp src/decoders/smal.cpp
  src/decoders/load_mfbacks.cpp
  src/metadata/sony.cpp src/metadata/nikon.cpp src/metadata/samsung.cpp
  src/metadata/cr3_parser.cpp src/metadata/canon.cpp src/metadata/epson.cpp
  src/metadata/olympus.cpp src/metadata/leica.cpp src/metadata/fuji.cpp
  src/metadata/adobepano.cpp src/metadata/pentax.cpp src/metadata/p1.cpp
  src/metadata/makernotes.cpp src/metadata/exif_gps.cpp src/metadata/kodak.cpp
  src/metadata/tiff.cpp src/metadata/ciff.cpp src/metadata/mediumformat.cpp
  src/metadata/minolta.cpp src/metadata/identify_tools.cpp
  src/metadata/hasselblad_model.cpp src/metadata/normalize_model.cpp
  src/metadata/identify.cpp src/metadata/misc_parsers.cpp src/tables/wblists.cpp
  src/postprocessing/postprocessing_aux.cpp src/postprocessing/postprocessing_utils_dcrdefs.cpp
  src/postprocessing/aspect_ratio.cpp
  src/demosaic/misc_demosaic.cpp src/demosaic/xtrans_demosaic.cpp src/demosaic/ahd_demosaic.cpp
  src/demosaic/dht_demosaic.cpp src/demosaic/aahd_demosaic.cpp src/demosaic/dcb_demosaic.cpp
  src/write/file_write.cpp src/preprocessing/ext_preprocess.cpp src/write/apply_profile.cpp
)
list(TRANSFORM LIBRAW_SOURCES PREPEND ${LIBRAW_DIR}/)

add_library(raw STATIC ${LIBRAW_SOURCES})
target_include_directories(raw PUBLIC ${LIBRAW_DIR})
target_compile_definitions(raw PUBLIC LIBRAW_NODLL)
target_compile_options(raw PRIVATE -O3 -fexceptions -w -ffunction-sections -fdata-sections)

add_library(hoardimage SHARED hoard_image_jni.cpp)
target_compile_options(hoardimage PRIVATE -O2 -fexceptions -Wall -ffunction-sections -fdata-sections)
target_link_libraries(hoardimage raw jnigraphics log dl)
# Drop unused LibRaw code and every symbol except JNI entry points.
target_link_options(hoardimage PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL
  "-Wl,-z,max-page-size=16384")
