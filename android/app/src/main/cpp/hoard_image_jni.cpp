// JNI layer for dev.hfps.hoard.image.raw.LibRawDecoder: decode a camera RAW file with LibRaw into an
// Android Bitmap (half size, for the screen) or straight into a JPEG file (full size, for 100% zoom,
// without ever holding a full-size Java Bitmap).
//
// Memory: LibRaw holds the raw mosaic (2 bytes/px) and, after dcraw_process, a 4 x uint16 image
// (8 bytes/px at the output size). The 8-bit RGBA result is written IN PLACE over that image buffer
// (4 bytes/px never overtake 8 bytes/px), so the conversion needs no extra buffer. Full size on a
// 24 MP sensor peaks around raw (48 MB) + image (195 MB).

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdio>
#include <cstring>
#include <cmath>

#include "libraw/libraw.h"

#define LOG_TAG "HoardImage"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int ERR_COMPRESS_UNAVAILABLE = -1000;
constexpr int ERR_BITMAP = -1001;
constexpr int ERR_IO = -1002;

// LibRaw subclass: gives access to the histogram and gamma_curve (protected) for the same white
// point and sRGB curve that dcraw_make_mem_image would use.
class HoardRaw : public LibRaw {
public:
    // Builds imgdata.color.curve exactly like LibRaw::copy_mem_image does.
    void buildOutputCurve() {
        if (!libraw_internal_data.output_data.histogram) return;
        int perc, val, total, t_white = 0x2000, c;
        perc = int(imgdata.sizes.width * imgdata.sizes.height * imgdata.params.auto_bright_thr);
        if (libraw_internal_data.internal_output_params.fuji_width) perc /= 2;
        if (!((imgdata.params.highlight & ~2) || imgdata.params.no_auto_bright))
            for (t_white = c = 0; c < imgdata.idata.colors; c++) {
                for (val = 0x2000, total = 0; --val > 32;)
                    if ((total += libraw_internal_data.output_data.histogram[c][val]) > perc) break;
                if (t_white < val) t_white = val;
            }
        gamma_curve(imgdata.params.gamm[0], imgdata.params.gamm[1], 2,
                    int((t_white << 3) / imgdata.params.bright));
    }

    // Converts imgdata.image (uint16 x4 per pixel, width x height, no flip) to RGBA_8888 in place.
    // Returns the start of the RGBA buffer (== imgdata.image).
    uint8_t *convertInPlace(int width, int height) {
        auto *src = imgdata.image;
        auto *dst = reinterpret_cast<uint8_t *>(imgdata.image);
        const ushort *curve = imgdata.color.curve;
        const size_t count = size_t(width) * size_t(height);
        const bool mono = imgdata.idata.colors == 1;
        for (size_t i = 0; i < count; i++) {
            // Read the whole pixel before writing: dst[4i..4i+3] overlaps src[i] only when i == 0.
            const ushort r = src[i][0];
            const ushort g = mono ? r : src[i][1];
            const ushort b = mono ? r : src[i][2];
            uint8_t *out = dst + i * 4;
            out[0] = uint8_t(curve[r] >> 8);
            out[1] = uint8_t(curve[g] >> 8);
            out[2] = uint8_t(curve[b] >> 8);
            out[3] = 0xFF;
        }
        return dst;
    }
};

struct Callback {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
};

int progressCallback(void *data, enum LibRaw_progress stage, int iteration, int expected) {
    auto *cb = static_cast<Callback *>(data);
    if (!cb || !cb->listener) return 0;
    jboolean keepGoing = cb->env->CallBooleanMethod(cb->listener, cb->method, (jint)stage,
                                                    (jint)iteration, (jint)expected);
    if (cb->env->ExceptionCheck()) return 1;
    return keepGoing ? 0 : 1;  // non-zero cancels
}

void throwLibRaw(JNIEnv *env, int code, const char *what) {
    if (env->ExceptionCheck()) return;  // keep the listener's own exception
    jclass cls = env->FindClass("dev/hfps/hoard/image/raw/LibRawException");
    if (!cls) return;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
    char message[256];
    snprintf(message, sizeof(message), "%s: %s (%d)", what,
             code <= ERR_COMPRESS_UNAVAILABLE ? "hoard" : libraw_strerror(code), code);
    jstring jmessage = env->NewStringUTF(message);
    auto ex = static_cast<jthrowable>(env->NewObject(cls, ctor, (jint)code, jmessage));
    env->Throw(ex);
}

// Opens, unpacks and processes [path]; on success the processor holds an RGBA image of
// outWidth x outHeight at imgdata.image.
bool process(JNIEnv *env, HoardRaw &raw, jstring path, jboolean halfSize, jint demosaic,
             Callback *cb, int *outWidth, int *outHeight) {
    auto &p = raw.imgdata.params;
    p.use_camera_wb = 1;       // white balance as shot
    p.use_camera_matrix = 1;
    p.output_color = 1;        // sRGB
    p.output_bps = 8;
    p.gamm[0] = 1.0 / 2.4;     // sRGB transfer curve
    p.gamm[1] = 12.92;
    p.half_size = halfSize ? 1 : 0;
    p.user_qual = demosaic;    // 0 linear, 1 VNG, 2 PPG, 3 AHD, 4 DCB, 11 DHT, 12 AAHD
    p.user_flip = 0;           // orientation is applied by the caller (it knows it from EXIF)
    raw.set_progress_handler(cb ? progressCallback : nullptr, cb);

    const char *cpath = env->GetStringUTFChars(path, nullptr);
    int rc = raw.open_file(cpath);
    env->ReleaseStringUTFChars(path, cpath);
    if (rc != LIBRAW_SUCCESS) { throwLibRaw(env, rc, "open_file"); return false; }
    if ((rc = raw.unpack()) != LIBRAW_SUCCESS) { throwLibRaw(env, rc, "unpack"); return false; }
    if ((rc = raw.dcraw_process()) != LIBRAW_SUCCESS) { throwLibRaw(env, rc, "dcraw_process"); return false; }
    if (env->ExceptionCheck()) return false;
    // Tell Kotlin the output step starts (timing); also a last chance to cancel.
    if (cb && progressCallback(cb, LIBRAW_PROGRESS_STAGE27, 0, 1)) {
        throwLibRaw(env, LIBRAW_CANCELLED_BY_CALLBACK, "cancelled");
        return false;
    }

    int width = 0, height = 0, colors = 0, bps = 0;
    raw.get_mem_image_format(&width, &height, &colors, &bps);
    if (width <= 0 || height <= 0 || !raw.imgdata.image) { throwLibRaw(env, LIBRAW_UNSPECIFIED_ERROR, "empty image"); return false; }
    // copy_mem_image's layout with flip == 0: image is width x height, row-major.
    raw.imgdata.sizes.iwidth = width;
    raw.imgdata.sizes.iheight = height;
    raw.buildOutputCurve();
    raw.convertInPlace(width, height);
    *outWidth = width;
    *outHeight = height;
    return true;
}

Callback makeCallback(JNIEnv *env, jobject listener) {
    Callback cb{env, listener, nullptr};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        cb.method = env->GetMethodID(cls, "onProgress", "(III)Z");
        if (!cb.method) cb.listener = nullptr;
    }
    return cb;
}

using CompressFn = int (*)(const AndroidBitmapInfo *, int32_t, const void *, int32_t, int32_t, void *,
                           bool (*)(void *, const void *, size_t));

CompressFn compressFunction() {
    // AndroidBitmap_compress is API 30+; minSdk is 24, so look it up at run time.
    static CompressFn fn = reinterpret_cast<CompressFn>(dlsym(RTLD_DEFAULT, "AndroidBitmap_compress"));
    return fn;
}

bool writeChunk(void *userContext, const void *data, size_t size) {
    return fwrite(data, 1, size, static_cast<FILE *>(userContext)) == size;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_dev_hfps_hoard_image_raw_LibRawNative_nativeVersion(JNIEnv *env, jclass) {
    return env->NewStringUTF(LibRaw::version());
}

JNIEXPORT jboolean JNICALL
Java_dev_hfps_hoard_image_raw_LibRawNative_nativeCanCompress(JNIEnv *, jclass) {
    return compressFunction() != nullptr;
}

// Decodes to a new ARGB_8888 Bitmap (unrotated).
JNIEXPORT jobject JNICALL
Java_dev_hfps_hoard_image_raw_LibRawNative_nativeDecodeBitmap(JNIEnv *env, jclass, jstring path,
                                                              jboolean halfSize, jint demosaic,
                                                              jobject listener) {
    auto *raw = new HoardRaw();
    Callback cb = makeCallback(env, listener);
    int width = 0, height = 0;
    jobject bitmap = nullptr;
    if (process(env, *raw, path, halfSize, demosaic, listener ? &cb : nullptr, &width, &height)) {
        jclass configCls = env->FindClass("android/graphics/Bitmap$Config");
        jfieldID argb = env->GetStaticFieldID(configCls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
        jobject config = env->GetStaticObjectField(configCls, argb);
        jclass bitmapCls = env->FindClass("android/graphics/Bitmap");
        jmethodID create = env->GetStaticMethodID(bitmapCls, "createBitmap",
                                                  "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        bitmap = env->CallStaticObjectMethod(bitmapCls, create, width, height, config);
        if (env->ExceptionCheck() || !bitmap) {
            bitmap = nullptr;  // OutOfMemoryError stays pending for the caller
        } else {
            void *pixels = nullptr;
            AndroidBitmapInfo info;
            if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
                AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
                throwLibRaw(env, ERR_BITMAP, "lockPixels");
                bitmap = nullptr;
            } else {
                auto *src = reinterpret_cast<const uint8_t *>(raw->imgdata.image);
                for (int row = 0; row < height; row++) {
                    memcpy(static_cast<uint8_t *>(pixels) + size_t(row) * info.stride,
                           src + size_t(row) * width * 4, size_t(width) * 4);
                }
                AndroidBitmap_unlockPixels(env, bitmap);
            }
        }
    }
    raw->recycle();
    delete raw;
    return bitmap;
}

// Decodes and compresses straight to a JPEG file (unrotated). Returns {width, height}.
// Throws LibRawException(ERR_COMPRESS_UNAVAILABLE) below Android 11.
JNIEXPORT jintArray JNICALL
Java_dev_hfps_hoard_image_raw_LibRawNative_nativeDecodeJpeg(JNIEnv *env, jclass, jstring path,
                                                            jboolean halfSize, jint demosaic,
                                                            jstring outPath, jint quality,
                                                            jobject listener) {
    CompressFn compress = compressFunction();
    if (!compress) { throwLibRaw(env, ERR_COMPRESS_UNAVAILABLE, "AndroidBitmap_compress"); return nullptr; }
    auto *raw = new HoardRaw();
    Callback cb = makeCallback(env, listener);
    int width = 0, height = 0;
    jintArray result = nullptr;
    if (process(env, *raw, path, halfSize, demosaic, listener ? &cb : nullptr, &width, &height)) {
        const char *cout = env->GetStringUTFChars(outPath, nullptr);
        FILE *file = fopen(cout, "wb");
        env->ReleaseStringUTFChars(outPath, cout);
        if (!file) {
            throwLibRaw(env, ERR_IO, "fopen");
        } else {
            AndroidBitmapInfo info{};
            info.width = uint32_t(width);
            info.height = uint32_t(height);
            info.stride = uint32_t(width) * 4;
            info.format = ANDROID_BITMAP_FORMAT_RGBA_8888;
            info.flags = ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE;
            // ADataSpace SRGB = 142671872; JPEG = ANDROID_BITMAP_COMPRESS_FORMAT_JPEG (0).
            int rc = compress(&info, 142671872, raw->imgdata.image, 0, quality, file, writeChunk);
            bool ok = fclose(file) == 0 && rc == ANDROID_BITMAP_RESULT_SUCCESS;
            if (!ok) {
                throwLibRaw(env, ERR_IO, "AndroidBitmap_compress");
            } else {
                result = env->NewIntArray(2);
                jint dims[2] = {width, height};
                env->SetIntArrayRegion(result, 0, 2, dims);
            }
        }
    }
    raw->recycle();
    delete raw;
    return result;
}

}  // extern "C"
