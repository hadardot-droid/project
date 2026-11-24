package com.memoryreel.app

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Float) : ExportState
    data class Done(val uri: Uri) : ExportState
    data class Failed(val message: String) : ExportState
}

class ReelViewModel(app: Application) : AndroidViewModel(app) {

    val store = PhotoStore(app, maxSide = 1920)
    private val dates = HashMap<Uri, Long>()

    val photos = mutableStateListOf<Uri>()
    var thumbsVersion by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set

    var style by mutableStateOf(ReelStyle.CALM)
        private set
    var format by mutableStateOf(ReelFormat.STORY)
    var secondsPerPhoto by mutableStateOf(ReelStyle.CALM.defaultSeconds)
    var title by mutableStateOf("")
    var subtitle by mutableStateOf("")
    var highQuality by mutableStateOf(true)
    var depth3d by mutableStateOf(true)

    /** Photos whose depth map is ready, and whether the model could load on this phone. */
    var depthReady by mutableIntStateOf(0)
        private set
    var depthAvailable by mutableStateOf(true)
        private set
    private val estimatorLazy = lazy { DepthEstimator(app) }
    private val estimator by estimatorLazy
    private var depthJob: Job? = null
    var musicUri by mutableStateOf<Uri?>(null)
        private set
    var musicName by mutableStateOf("")
        private set

    var exportState by mutableStateOf<ExportState>(ExportState.Idle)
        private set
    private var exportJob: Job? = null

    fun config() = ReelConfig(photos.toList(), style, format, secondsPerPhoto, title, subtitle, depth3d && depthAvailable)

    fun selectStyle(s: ReelStyle) {
        style = s
        secondsPerPhoto = s.defaultSeconds
    }

    fun surpriseMe() {
        val others = ReelStyle.entries.filter { it != style }
        selectStyle(others.random())
    }

    /** Adds photos, sorts everything by the date it was taken and suggests a title. */
    fun addPhotos(uris: List<Uri>) {
        val fresh = uris.filter { it !in photos }.take(MAX_PHOTOS - photos.size)
        if (fresh.isEmpty()) return
        val wasEmpty = photos.isEmpty()
        loading = true
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                fresh.filter { uri ->
                    readDate(uri)?.let { dates[uri] = it }
                    store.loadThumb(uri)
                }
            }
            val all = (photos + ok).sortedWith(compareBy { dates[it] ?: Long.MAX_VALUE })
            photos.clear()
            photos.addAll(all)
            if (wasEmpty || title.isBlank()) suggestTitle()
            thumbsVersion++
            loading = false
            computeDepths()
        }
    }

    /** Runs the depth model on every photo that does not have a depth map yet, one by one. */
    private fun computeDepths() {
        val previous = depthJob
        depthJob = viewModelScope.launch(Dispatchers.Default) {
            previous?.join()
            if (!estimator.available) {
                depthAvailable = false
                return@launch
            }
            for (uri in photos.toList()) {
                if (!store.hasDepth(uri)) store.computeDepth(uri, estimator)
                depthReady = photos.count { store.hasDepth(it) }
            }
        }
    }

    fun remove(uri: Uri) {
        photos.remove(uri)
    }

    fun move(uri: Uri, delta: Int) {
        val i = photos.indexOf(uri)
        val j = i + delta
        if (i < 0 || j !in photos.indices) return
        photos.removeAt(i)
        photos.add(j, uri)
    }

    fun clearAll() {
        photos.clear()
        title = ""
        subtitle = ""
        musicUri = null
        musicName = ""
        exportState = ExportState.Idle
    }

    fun setMusic(uri: Uri?) {
        musicUri = uri
        musicName = uri?.let { displayName(it) } ?: ""
    }

    fun startExport() {
        if (photos.isEmpty() || exportState is ExportState.Running) return
        val app = getApplication<Application>()
        val cfg = config()
        exportState = ExportState.Running(0f)
        exportJob = viewModelScope.launch {
            try {
                if (cfg.depth3d) depthJob?.join()
                val tmp = File(app.cacheDir, "memory_export.mp4")
                withContext(Dispatchers.Default) {
                    VideoExporter(app).export(
                        store = store,
                        config = cfg,
                        scale = if (highQuality) 1f else 2f / 3f,
                        musicUri = musicUri,
                        outFile = tmp,
                    ) { p -> exportState = ExportState.Running(p) }
                }
                val uri = withContext(Dispatchers.IO) { saveToGallery(tmp) }
                exportState = ExportState.Done(uri)
            } catch (e: CancellationException) {
                exportState = ExportState.Idle
                throw e
            } catch (e: VideoExporter.ExportException) {
                exportState = ExportState.Failed(e.message ?: "שגיאה")
            } catch (e: Throwable) {
                exportState = ExportState.Failed("משהו השתבש ביצירת הסרטון (${e.javaClass.simpleName}).")
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportState = ExportState.Idle
    }

    fun dismissExport() {
        exportState = ExportState.Idle
    }

    private fun saveToGallery(file: File): Uri {
        val resolver = getApplication<Application>().contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Memory_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Memories")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw VideoExporter.ExportException("לא ניתן לשמור בגלריה.")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw VideoExporter.ExportException("לא ניתן לשמור בגלריה.")
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        file.delete()
        return uri
    }

    private fun readDate(uri: Uri): Long? = try {
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            raw?.let { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time }
        }
    } catch (e: Exception) {
        null
    }

    private fun suggestTitle() {
        val known = photos.mapNotNull { dates[it] }
        if (known.isEmpty()) {
            if (title.isBlank()) title = "הרגעים שלנו"
            return
        }
        val he = Locale.forLanguageTag("he")
        val month = SimpleDateFormat("LLLL", he)
        val monthYear = SimpleDateFormat("LLLL yyyy", he)
        val first = Calendar.getInstance().apply { timeInMillis = known.min() }
        val last = Calendar.getInstance().apply { timeInMillis = known.max() }
        val sameYear = first.get(Calendar.YEAR) == last.get(Calendar.YEAR)
        val sameMonth = sameYear && first.get(Calendar.MONTH) == last.get(Calendar.MONTH)
        subtitle = when {
            sameMonth -> monthYear.format(first.time)
            sameYear -> "${month.format(first.time)} – ${monthYear.format(last.time)}"
            else -> "${first.get(Calendar.YEAR)} – ${last.get(Calendar.YEAR)}"
        }
        if (title.isBlank()) title = "הרגעים שלנו"
    }

    private fun displayName(uri: Uri): String = try {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: "שיר נבחר"
    } catch (e: Exception) {
        "שיר נבחר"
    }

    override fun onCleared() {
        store.release()
        depthJob?.cancel()
        if (estimatorLazy.isInitialized()) runCatching { estimator.close() }
    }

    companion object {
        const val MAX_PHOTOS = 40
    }
}
