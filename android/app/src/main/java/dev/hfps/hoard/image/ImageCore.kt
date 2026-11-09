package dev.hfps.hoard.image

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dev.hfps.hoard.image.cache.CacheKeys
import dev.hfps.hoard.image.cache.DiskCache
import dev.hfps.hoard.image.cache.PriorityScheduler
import dev.hfps.hoard.image.cache.SizedLruCache
import dev.hfps.hoard.image.cache.Variant
import dev.hfps.hoard.image.decode.BitmapDecoding
import dev.hfps.hoard.image.decode.DecodedImage
import dev.hfps.hoard.image.decode.IntRect
import dev.hfps.hoard.image.decode.ZoomImage
import dev.hfps.hoard.image.format.EmbeddedImage
import dev.hfps.hoard.image.format.ImageInspector
import dev.hfps.hoard.image.format.Inspection
import dev.hfps.hoard.image.raw.Demosaic
import dev.hfps.hoard.image.raw.LibRawDecoder
import dev.hfps.hoard.image.sharpness.Luma
import dev.hfps.hoard.image.sharpness.Sharpness
import dev.hfps.hoard.image.sharpness.SharpnessScore
import dev.hfps.hoard.image.source.BlockCachedSource
import dev.hfps.hoard.image.source.CancelToken
import dev.hfps.hoard.image.source.copyToFile
import dev.hfps.hoard.image.source.readFully
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/** Tunables; the defaults suit a phone (S24 Ultra) and stay safe on a 3 GB emulator. */
data class ImageCoreConfig(
    val root: File,
    /** Decoded screen bitmaps kept in memory (native heap). */
    val memoryCacheBytes: Long,
    /** Local copies of originals (camera JPEGs for zoom, RAWs for LibRaw). */
    val originalsBytes: Long = 1024L * 1024 * 1024,
    /** Extracted RAW previews. */
    val previewsBytes: Long = 128L * 1024 * 1024,
    /** LibRaw renderings (full ~8-12 MB each, half ~2-3 MB). */
    val rendersBytes: Long = 512L * 1024 * 1024,
    /** Screen-sized JPEGs and grid thumbnails for the culling view (~0.6-1.5 MB per screen image). */
    val screensBytes: Long = 384L * 1024 * 1024,
    /** 100% zoom tiles (~60-200 KB each). */
    val tilesBytes: Long = 128L * 1024 * 1024,
    /** Region decoders kept open (current shot, the compared one, the next). */
    val openZooms: Int = 4,
    /** JPEG quality of screen files and tiles (what the WebView shows). */
    val screenQuality: Int = 90,
    /** Decode threads (LibRaw runs one at a time regardless). */
    val threads: Int = 2,
    /** Extra threads reserved for the image on screen (priority 0): prefetch never blocks it. */
    val urgentThreads: Int = 1,
    /** Demosaic for full-size LibRaw renders (AHD: sharp, ~2x slower than PPG). */
    val fullDemosaic: Demosaic = Demosaic.AHD,
    val renderQuality: Int = 92,
    /** Screen bitmaps as HARDWARE (GPU only; no sharpness scoring on them). */
    val hardwareBitmaps: Boolean = false,
) {
    companion object {
        fun default(context: Context): ImageCoreConfig {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // A quarter of the large-heap class (S24 Ultra: 512 MB -> 128 MB ≈ 5 screen images at 3000x2000).
            val memory = (am.largeMemoryClass.toLong() * 1024 * 1024 / 4).coerceIn(48L shl 20, 256L shl 20)
            return ImageCoreConfig(root = File(context.cacheDir, "image"), memoryCacheBytes = memory)
        }
    }
}

/** An upright JPEG on disk and its pixel size. */
data class ImageFileResult(val file: File, val width: Int, val height: Int)

/** A screen image file: what it was made from and whether it is the plan's final quality. */
data class ScreenFile(val file: File, val width: Int, val height: Int, val source: ScreenSource, val final: Boolean)

/** The 100% image: upright size and where its pixels come from. */
data class ZoomInfo(val width: Int, val height: Int, val source: ZoomSource)

/**
 * The imaging engine behind the culling view. Thread-safe; all heavy work runs on its own
 * [PriorityScheduler]; every public call that decodes returns a [PriorityScheduler.Job] whose
 * `future` the caller waits on (or cancels).
 *
 * Typical use from the culling UI:
 * 1. `ShotPairing.pair(listing)` -> shots; for each visible shot build a [ShotRequest].
 * 2. On showing shot i: `screen(shot, screenLongSide, step = plan.first())` for an instant image,
 *    then `screen(shot, screenLongSide)` for the final one; `prefetch(shots, i, screenLongSide)`.
 * 3. On zoom: `zoom(shot)` -> [ZoomImage]; decode upright tiles with `ZoomImage.decode(rect, sample)`.
 * 4. `inspect(file)` for the overlay (EXIF) and sorting/grouping by capture time.
 */
class ImageCore(val config: ImageCoreConfig) : ComponentCallbacks2 {

    constructor(context: Context) : this(ImageCoreConfig.default(context))

    private val originals = DiskCache(File(config.root, "originals"), config.originalsBytes)
    private val previews = DiskCache(File(config.root, "previews"), config.previewsBytes)
    private val renders = DiskCache(File(config.root, "renders"), config.rendersBytes)
    private val screens = DiskCache(File(config.root, "screens"), config.screensBytes)
    private val tiles = DiskCache(File(config.root, "tiles"), config.tilesBytes)
    /** Upright pixel size of files written to [screens] and [tiles] (name -> width, height). */
    private val fileSizes = SizedLruCache<String, IntArray>(20_000, { 1 })
    private val zooms = object : LinkedHashMap<String, ZoomImage>(8, 0.75f, true) {}
    @Volatile private var prefetchedFiles: Set<String> = emptySet()
    private val memory = SizedLruCache<String, DecodedImage>(config.memoryCacheBytes, { it.byteCount })
    private val inspections = SizedLruCache<String, Inspection>(4000, { 1 })
    private val scheduler = PriorityScheduler(config.threads, urgentThreads = config.urgentThreads)
    private val fileLocks = ConcurrentHashMap<String, Any>()
    private val direct = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var prefetched: Set<String> = emptySet()
    private val openSequence = java.util.concurrent.atomic.AtomicLong()

    val libRawAvailable: Boolean get() = LibRawDecoder.isAvailable

    // --- Metadata ----------------------------------------------------------------------------

    /** Type, EXIF and embedded previews from the file's header ranges (memoized). Blocking: I/O. */
    fun inspect(file: ImageFile): Inspection {
        val key = CacheKeys.key(file.identity, Variant.ORIGINAL, "inspect")
        inspections[key]?.let { return it }
        val result = file.open().use { ImageInspector.inspect(BlockCachedSource(it), file.name) }
        inspections.put(key, result)
        return result
    }

    // --- Screen ------------------------------------------------------------------------------

    /** Blocking (inspects the RAW). */
    fun screenPlan(shot: ShotRequest, targetLongSide: Int): List<ScreenSource> {
        val preview = shot.raw?.let { inspect(it).largestPreview }
        return ImagePlans.screen(shot.rendered != null, preview?.let { max(it.width, it.height) }, targetLongSide, libRawAvailable)
    }

    /** Memory hit for [step] (null = final quality), without I/O or scheduling (safe on the UI thread). */
    fun cachedScreen(shot: ShotRequest, targetLongSide: Int, step: ScreenSource? = null): DecodedImage? =
        memory[screenKey(shot, targetLongSide, step)]

    /**
     * Upright image for the screen, at least [targetLongSide] on its longest side when the source
     * allows. [step] = null means the plan's final quality (see [ImagePlans.screen]). Never does I/O
     * on the calling thread.
     */
    fun screen(shot: ShotRequest, targetLongSide: Int, priority: Int = 0, step: ScreenSource? = null): PriorityScheduler.Job<DecodedImage> {
        val key = screenKey(shot, targetLongSide, step)
        memory[key]?.let { return scheduler.done(key, it) }
        return track(scheduler.submit(key, priority) { cancel ->
            memory[key] ?: run {
                val source = step ?: screenPlan(shot, targetLongSide).lastOrNull() ?: error("Nothing displayable in $shot")
                decodeScreen(shot, targetLongSide, source, cancel)
            }.also { memory.put(key, it) }
        }, direct = priority == 0)
    }

    private fun screenKey(shot: ShotRequest, target: Int, step: ScreenSource?): String =
        "screen:" + CacheKeys.key(shot.primary.identity, Variant.ORIGINAL, "screen:${step?.name ?: "final"}:$target:${shot.raw?.identity}")

    private fun decodeScreen(shot: ShotRequest, target: Int, step: ScreenSource, cancel: CancelToken): DecodedImage {
        val started = System.nanoTime()
        val result = when (step) {
            ScreenSource.RAW_PREVIEW -> {
                val raw = shot.raw!!
                val inspection = inspect(raw)
                val preview = inspection.previewFor(target) ?: error("No embedded preview in ${raw.name}")
                BitmapDecoding.decode(previewBytes(raw, preview), target, inspection.metadata.orientation, config.hardwareBitmaps)
            }
            ScreenSource.RENDERED -> {
                val file = shot.rendered!!
                val local = stageOriginal(file, cancel)
                BitmapDecoding.decode(local, target, inspect(file).metadata.orientation, config.hardwareBitmaps)
            }
            ScreenSource.RAW_HALF -> {
                val raw = shot.raw!!
                val render = render(raw, half = true, cancel)
                BitmapDecoding.decode(render, target, inspect(raw).metadata.orientation, config.hardwareBitmaps)
            }
        }
        val name = if (step == ScreenSource.RENDERED) shot.rendered?.name else shot.raw?.name
        Log.d(TAG, "screen $step $name -> ${result.bitmap.width}x${result.bitmap.height} in ${(System.nanoTime() - started) / 1_000_000} ms")
        return result
    }

    // --- Zoom --------------------------------------------------------------------------------

    /** Blocking (inspects the RAW). */
    fun zoomPlan(shot: ShotRequest): ZoomSource? {
        val inspection = shot.raw?.let { inspect(it) }
        val preview = inspection?.largestPreview
        val sensor = inspection?.metadata?.let { m -> listOfNotNull(m.width, m.height).maxOrNull() }
        return ImagePlans.zoom(shot.rendered != null, preview?.let { max(it.width, it.height) }, sensor, libRawAvailable)
    }

    /**
     * A region decoder over the full-resolution pixels: the camera JPEG, a full-size embedded JPEG,
     * or a LibRaw rendering (first time: seconds; then cached on disk). Each call returns its own
     * [ZoomImage]; the caller closes it. The expensive part ([prepareZoom]) is shared and cached.
     */
    fun zoom(shot: ShotRequest, priority: Int = 0): PriorityScheduler.Job<ZoomImage> {
        val key = zoomKey(shot) + ":open:" + openSequence.incrementAndGet()
        return track(scheduler.submit(key, priority) { cancel ->
            val (file, orientation) = prepareZoomNow(shot, cancel)
            ZoomImage(file, orientation)
        }, direct = priority == 0)
    }

    /** Makes the full-resolution file local (stage, extract or render) without opening a decoder. */
    fun prepareZoom(shot: ShotRequest, priority: Int = 0): PriorityScheduler.Job<Pair<File, Int>> =
        track(scheduler.submit(zoomKey(shot), priority) { cancel -> prepareZoomNow(shot, cancel) }, direct = priority == 0)

    private fun zoomKey(shot: ShotRequest) = "zoom:" + CacheKeys.key(shot.primary.identity, Variant.ORIGINAL, "zoom:${shot.raw?.identity}")

    private fun prepareZoomNow(shot: ShotRequest, cancel: CancelToken): Pair<File, Int> {
        val source = zoomPlan(shot) ?: error("No full-resolution source for $shot")
        val file = if (source == ZoomSource.RENDERED) shot.rendered!! else shot.raw!!
        val orientation = inspect(file).metadata.orientation
        val local = when (source) {
            ZoomSource.RENDERED -> stageOriginal(file, cancel)
            ZoomSource.RAW_PREVIEW -> previewFile(file, inspect(file).largestPreview ?: error("No preview in ${file.name}"))
            ZoomSource.RAW_FULL -> render(file, half = false, cancel)
        }
        return local to orientation
    }

    // --- Prefetch ----------------------------------------------------------------------------

    /**
     * Keeps screen images of the shots around [current] decoding in the background (ahead first),
     * and cancels prefetches that left the window. [zoomAhead] also prepares 100% sources (stages
     * camera JPEGs, renders RAW-only shots with LibRaw) for the next N shots.
     */
    fun prefetch(shots: List<ShotRequest>, current: Int, targetLongSide: Int, ahead: Int = 3, behind: Int = 1, zoomAhead: Int = 0) {
        val keys = HashSet<String>()
        for ((index, priority) in ImagePlans.prefetchWindow(current, shots.size, ahead, behind)) {
            val shot = shots[index]
            if (memory[screenKey(shot, targetLongSide, null)] == null) keys += screen(shot, targetLongSide, priority).key
            if (index > current && index - current <= zoomAhead) keys += prepareZoom(shot, priority + 10).key
        }
        val stale = prefetched - keys - direct
        prefetched = keys
        stale.forEach(scheduler::cancel)
    }

    fun cancel(job: PriorityScheduler.Job<*>) = scheduler.cancel(job.key)

    private fun <T> track(job: PriorityScheduler.Job<T>, direct: Boolean): PriorityScheduler.Job<T> {
        if (direct) {
            this.direct += job.key
            job.future.whenComplete { _, _ -> this.direct -= job.key }
        }
        return job
    }


    // --- Files for the culling view (WebView <img>, ADR 0008) --------------------------------

    /**
     * An upright JPEG file of [shot] for the screen. [fast] = the plan's first step (embedded preview
     * when there is one), else its final quality; when the plan has one step both are the same file.
     * Never does I/O on the calling thread.
     */
    fun screenFile(shot: ShotRequest, targetLongSide: Int, fast: Boolean, priority: Int = 0): PriorityScheduler.Job<ScreenFile> {
        val key = "screenfile:" + CacheKeys.key(shot.primary.identity, Variant.SCREEN, "${if (fast) "fast" else "final"}:$targetLongSide:${shot.raw?.identity}")
        return track(scheduler.submit(key, priority) { cancel ->
            val plan = screenPlan(shot, targetLongSide)
            val step = (if (fast) plan.firstOrNull() else plan.lastOrNull()) ?: error("Nothing displayable in $shot")
            produceScreenFile(shot, targetLongSide, step, step == plan.last(), cancel)
        }, direct = priority == 0)
    }

    private fun produceScreenFile(shot: ShotRequest, target: Int, step: ScreenSource, final: Boolean, cancel: CancelToken): ScreenFile {
        val started = System.nanoTime()
        if (step == ScreenSource.RAW_PREVIEW) {
            val raw = shot.raw!!
            val inspection = inspect(raw)
            val preview = inspection.previewFor(target) ?: error("No embedded preview in ${raw.name}")
            if (CullPlans.servePreviewAsIs(inspection.metadata.orientation, max(preview.width, preview.height), target)) {
                // Already an upright JPEG of the right size: served as it is, no decode at all.
                return ScreenFile(previewFile(raw, preview), preview.width, preview.height, step, final)
            }
        }
        val name = CacheKeys.fileName(shot.primary.identity, Variant.SCREEN, "${step.name}:$target:${shot.raw?.identity}")
        cachedFile(screens, name)?.let { (file, size) -> return ScreenFile(file, size[0], size[1], step, final) }
        cancel.throwIfCancelled()
        val decoded = decodeScreen(shot, target, step, cancel)
        val file = writeJpeg(screens, name, decoded.bitmap, config.screenQuality)
        val result = ScreenFile(file, decoded.bitmap.width, decoded.bitmap.height, step, final)
        decoded.bitmap.recycle()
        Log.d(TAG, "screen file $step ${shot.primary.name} ${result.width}x${result.height} in ${(System.nanoTime() - started) / 1_000_000} ms")
        return result
    }

    /** Grid thumbnail: the RAW's embedded preview, a local JPEG decoded small, or the JPEG's EXIF thumbnail. */
    fun thumbnailFile(shot: ShotRequest, maxSize: Int, priority: Int = 0): PriorityScheduler.Job<ImageFileResult> {
        val name = CacheKeys.fileName(shot.primary.identity, Variant.THUMB, "$maxSize:${shot.raw?.identity}")
        return scheduler.submit("thumb:$name", priority) { cancel ->
            cachedFile(screens, name)?.let { (file, size) -> return@submit ImageFileResult(file, size[0], size[1]) }
            val decoded = decodeThumbnail(shot, maxSize, cancel)
            val file = writeJpeg(screens, name, decoded.bitmap, 85)
            ImageFileResult(file, decoded.bitmap.width, decoded.bitmap.height).also { decoded.bitmap.recycle() }
        }
    }

    private fun decodeThumbnail(shot: ShotRequest, maxSize: Int, cancel: CancelToken): DecodedImage {
        shot.raw?.let { raw ->
            val inspection = inspect(raw)
            inspection.previewFor(maxSize)?.let { preview ->
                return BitmapDecoding.decode(previewBytes(raw, preview), maxSize, inspection.metadata.orientation)
            }
        }
        val rendered = shot.rendered ?: error("No embedded preview in ${shot.raw?.name}")
        val inspection = inspect(rendered)
        val local = rendered.local?.takeIf { it.isFile } ?: originals.get(CacheKeys.fileName(rendered.identity, Variant.ORIGINAL))
        if (local != null) return BitmapDecoding.decode(local, maxSize, inspection.metadata.orientation)
        // Remote JPEG-only shot: its EXIF thumbnail (160 px on Sony) when there is one, else the whole file.
        inspection.previewFor(maxSize)?.let { thumb ->
            val bytes = ByteArray(thumb.length.toInt())
            rendered.open().use { it.readFully(thumb.offset, bytes) }
            return BitmapDecoding.decode(bytes, maxSize, inspection.metadata.orientation)
        }
        return BitmapDecoding.decode(stageOriginal(rendered, cancel), maxSize, inspection.metadata.orientation)
    }

    /** Size and source of the 100% image (stages, extracts or renders it first: seconds for LibRaw). */
    fun zoomInfo(shot: ShotRequest, priority: Int = 0): PriorityScheduler.Job<ZoomInfo> =
        track(scheduler.submit(zoomKey(shot) + ":info", priority) { cancel ->
            val image = openZoom(shot, cancel)
            ZoomInfo(image.width, image.height, zoomPlan(shot)!!)
        }, direct = priority == 0)

    /**
     * One upright region of the 100% image at [sample] (1 = 100%, 2 = 50%…) as a JPEG file. Tiles
     * are cached on disk, so panning back costs nothing.
     */
    fun tileFile(shot: ShotRequest, x: Int, y: Int, width: Int, height: Int, sample: Int, priority: Int = 0): PriorityScheduler.Job<ImageFileResult> {
        val name = CacheKeys.fileName(shot.primary.identity, Variant.TILE, "$x,$y,$width,$height,$sample:${shot.raw?.identity}")
        return track(scheduler.submit("tile:$name", priority) { cancel ->
            cachedFile(tiles, name)?.let { (file, size) -> return@submit ImageFileResult(file, size[0], size[1]) }
            val image = openZoom(shot, cancel)
            val clip = CullPlans.clipTile(x, y, width, height, sample, image.width, image.height)
                ?: throw IllegalArgumentException("Tile $x,$y ${width}x$height @$sample is outside ${image.width}x${image.height}")
            cancel.throwIfCancelled()
            val bitmap = image.decode(IntRect(clip[0], clip[1], clip[0] + clip[2], clip[1] + clip[3]), sample)
            val file = writeJpeg(tiles, name, bitmap, config.screenQuality)
            ImageFileResult(file, bitmap.width, bitmap.height).also { bitmap.recycle() }
        }, direct = priority == 0)
    }

    /** The open region decoder of [shot] (kept for the next tiles; the least recent one closes). */
    private fun openZoom(shot: ShotRequest, cancel: CancelToken): ZoomImage {
        val key = zoomKey(shot)
        synchronized(zooms) { zooms[key]?.let { return it } }
        val (file, orientation) = prepareZoomNow(shot, cancel)
        val image = ZoomImage(file, orientation)
        val evicted = ArrayList<ZoomImage>()
        synchronized(zooms) {
            zooms[key]?.let { existing -> image.close(); return existing }
            zooms[key] = image
            val it = zooms.entries.iterator()
            while (zooms.size > config.openZooms && it.hasNext()) {
                val e = it.next()
                if (e.key == key) continue
                evicted += e.value
                it.remove()
            }
        }
        evicted.forEach { it.close() }
        return image
    }

    /** Closes every region decoder (the culling view closed). */
    fun closeZooms() {
        val open = synchronized(zooms) { zooms.values.toList().also { zooms.clear() } }
        open.forEach { it.close() }
    }

    /**
     * File prefetch for the culling view: fast then final screen files around [current], then the
     * 100% sources of the next [zoomAhead] shots ([CullPlans.prefetch]); anything that left the
     * window is cancelled.
     */
    fun prefetchFiles(shots: List<ShotRequest>, current: Int, targetLongSide: Int, ahead: Int = 2, behind: Int = 1, zoomAhead: Int = 0, fastAhead: Int = 6) {
        val keys = HashSet<String>()
        for (step in CullPlans.prefetch(current, shots.size, ahead, behind, zoomAhead, fastAhead)) {
            val shot = shots[step.index]
            keys += when (step.step) {
                PrefetchStep.Step.FAST -> screenFile(shot, targetLongSide, fast = true, priority = step.priority).key
                PrefetchStep.Step.FINAL -> screenFile(shot, targetLongSide, fast = false, priority = step.priority).key
                PrefetchStep.Step.ZOOM -> prepareZoom(shot, step.priority + 10).key
            }
        }
        val stale = prefetchedFiles - keys - direct
        prefetchedFiles = keys
        stale.forEach(scheduler::cancel)
    }

    /** Cancels every queued prefetch (the culling view closed). */
    fun cancelPrefetch() {
        val stale = prefetchedFiles - direct
        prefetchedFiles = emptySet()
        stale.forEach(scheduler::cancel)
    }

    private fun cachedFile(cache: DiskCache, name: String): Pair<File, IntArray>? {
        val file = cache.get(name) ?: return null
        val size = fileSizes[name] ?: BitmapDecoding.bounds(file)?.let { intArrayOf(it.first, it.second) }?.also { fileSizes.put(name, it) } ?: return null
        return file to size
    }

    private fun writeJpeg(cache: DiskCache, name: String, bitmap: Bitmap, quality: Int): File {
        val file = cache.put(name) { temp -> temp.outputStream().buffered(1 shl 16).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) } }
        fileSizes.put(name, intArrayOf(bitmap.width, bitmap.height))
        return file
    }

    /** Disk usage of the culling caches (for the ADR's measurements and Settings). */
    fun cacheBytes(): Map<String, Long> = mapOf(
        "originals" to originals.sizeBytes(), "previews" to previews.sizeBytes(), "renders" to renders.sizeBytes(),
        "screens" to screens.sizeBytes(), "tiles" to tiles.sizeBytes(), "memory" to memory.sizeBytes,
    )

    // --- Sharpness ---------------------------------------------------------------------------

    /** Focus score of a decoded (software) bitmap, analysed at [Sharpness.ANALYSIS_LONG_SIDE]. */
    fun sharpness(bitmap: Bitmap, cols: Int = 4, rows: Int = 4): SharpnessScore = Companion.sharpness(bitmap, cols, rows)

    // --- Files -------------------------------------------------------------------------------

    /** A local copy of [file] (whole file), shared by screen decode, zoom and LibRaw. */
    fun stageOriginal(file: ImageFile, cancel: CancelToken = CancelToken.NONE): File {
        file.local?.takeIf { it.isFile }?.let { return it }
        val name = CacheKeys.fileName(file.identity, Variant.ORIGINAL)
        originals.get(name)?.let { return it }
        synchronized(fileLocks.getOrPut(name) { Any() }) {
            originals.get(name)?.let { return it }
            return try {
                originals.put(name) { temp -> file.open().use { it.copyToFile(temp, cancel) } }
            } finally {
                fileLocks.remove(name)
            }
        }
    }

    private fun previewBytes(raw: ImageFile, preview: EmbeddedImage): ByteArray {
        val name = CacheKeys.fileName(raw.identity, Variant.PREVIEW, preview.offset)
        previews.get(name)?.let { return it.readBytes() }
        val bytes = ByteArray(preview.length.toInt())
        raw.open().use { it.readFully(preview.offset, bytes) }
        runCatching { previews.put(name) { it.writeBytes(bytes) } }
        return bytes
    }

    private fun previewFile(raw: ImageFile, preview: EmbeddedImage): File {
        val name = CacheKeys.fileName(raw.identity, Variant.PREVIEW, preview.offset)
        previews.get(name)?.let { return it }
        val bytes = previewBytes(raw, preview)
        return previews.get(name) ?: previews.put(name) { it.writeBytes(bytes) }
    }

    /** LibRaw rendering of [raw] as an unrotated JPEG (disk cached). */
    private fun render(raw: ImageFile, half: Boolean, cancel: CancelToken): File {
        val variant = if (half) Variant.RAW_HALF else Variant.RAW_FULL
        val demosaic = if (half) Demosaic.AHD else config.fullDemosaic
        val name = CacheKeys.fileName(raw.identity, variant, demosaic)
        renders.get(name)?.let { return it }
        synchronized(fileLocks.getOrPut(name) { Any() }) {
            renders.get(name)?.let { return it }
            try {
                val local = stageOriginal(raw, cancel)
                return renders.put(name) { temp ->
                    val result = LibRawDecoder.decodeToJpeg(local, temp, half, demosaic, config.renderQuality, cancel)
                    Log.i(TAG, "LibRaw ${if (half) "half" else "full"} ${raw.name}: ${result.width}x${result.height} ${result.stats}")
                }
            } finally {
                fileLocks.remove(name)
            }
        }
    }

    // --- Lifecycle ---------------------------------------------------------------------------

    override fun onTrimMemory(level: Int) {
        @Suppress("DEPRECATION")
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> memory.clear()
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> memory.trimTo(config.memoryCacheBytes / 4)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> memory.trimTo(config.memoryCacheBytes / 2)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = memory.clear()

    fun close() {
        scheduler.shutdown()
        closeZooms()
        memory.clear()
    }

    companion object {
        private const val TAG = "HoardImage"

        fun sharpness(bitmap: Bitmap, cols: Int = 4, rows: Int = 4): SharpnessScore {
            require(bitmap.config != Bitmap.Config.HARDWARE) { "Sharpness needs a software bitmap" }
            val longest = max(bitmap.width, bitmap.height)
            val scale = minOf(1f, Sharpness.ANALYSIS_LONG_SIDE.toFloat() / longest)
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
            } else bitmap
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            val score = Sharpness.score(Luma.fromArgb(small.width, small.height, pixels), cols, rows)
            if (small !== bitmap) small.recycle()
            return score
        }
    }
}
