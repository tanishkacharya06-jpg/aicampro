package com.example.aicamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class AuthExpired : Exception("Session expired - please log in again")

/** Talks to the laptop server. Finds it through the pairing code (or a typed URL). */
object Api {
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).writeTimeout(180, TimeUnit.SECONDS).build()
    lateinit var prefs: SharedPreferences
    private fun rd(k: String): String = prefs.getString(k, "") ?: ""
    private fun wr(k: String, v: String) { prefs.edit().putString(k, v).apply() }
    var link: String
        get() = rd("link")
        set(v) = wr("link", v)
    var base: String
        get() = rd("base")
        set(v) = wr("base", v)
    var sid: String
        get() = rd("sid")
        set(v) = wr("sid", v)
    var user: String
        get() = rd("user")
        set(v) = wr("user", v)
    var pid: Int
        get() = prefs.getInt("pid", 0)
        set(v) { prefs.edit().putInt("pid", v).apply() }

    private fun rq(path: String) = Request.Builder().url(base + path).header("Authorization", "Bearer $sid")
    private fun detail(r: Response): String =
        try { JSONObject(r.body!!.string()).optString("detail", "Error ${r.code}") } catch (e: Exception) { "Error ${r.code}" }
    private fun check(r: Response) {
        if (r.code == 401) throw AuthExpired()
        if (!r.isSuccessful) error(detail(r))
    }

    /** Pairing code -> latest public URL (published by the PC); a typed http(s) URL is used as is. */
    suspend fun resolve(): String = withContext(Dispatchers.IO) {
        val l = link.trim()
        if (l.startsWith("http")) {
            base = l.trimEnd('/')
        } else if (l.isNotEmpty()) {
            try {
                val text = http.newCall(Request.Builder().url("https://ntfy.sh/$l/json?poll=1&since=all").build())
                    .execute().use { it.body!!.string() }
                var latest = ""
                for (line in text.lines()) {
                    if (line.isBlank()) continue
                    val o = JSONObject(line)
                    if (o.optString("event") == "message" && o.optString("message").startsWith("http")) latest = o.getString("message")
                }
                if (latest.isNotEmpty()) base = latest.trim().trimEnd('/')
            } catch (e: Exception) { /* keep the last known address */ }
        }
        if (base.isEmpty()) error("Can't find your PC. Is the server running, and is the pairing code right?")
        base
    }

    suspend fun auth(mode: String, name: String, pass: String): String = withContext(Dispatchers.IO) {
        resolve()
        val body = JSONObject().put("name", name).put("password", pass).toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("$base/api/$mode").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) error(detail(r))
            val c = r.headers("Set-Cookie").firstOrNull { it.startsWith("sid=") } ?: error("Login failed (no session)")
            sid = c.substringAfter("sid=").substringBefore(";")
            user = JSONObject(r.body!!.string()).getString("name")
            user
        }
    }

    suspend fun prompts(): List<Pair<Int, String>> = withContext(Dispatchers.IO) {
        val call = { http.newCall(rq("/api/prompts").build()).execute() }
        val r = try { call() } catch (e: IOException) { resolve(); call() }
        r.use {
            check(it)
            val a = JSONArray(it.body!!.string())
            (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> o.getInt("id") to o.getString("name") } }
        }
    }

    private fun send(body: MultipartBody): String =
        http.newCall(rq("/enhance").post(body).build()).execute().use { r ->
            check(r); JSONObject(r.body!!.string()).getString("job_id")
        }

    /** Upload photo -> wait for the PC/ChatGPT -> download result file(s). */
    suspend fun process(src: File, promptId: Int, outDir: File, stage: (String) -> Unit): List<File> = withContext(Dispatchers.IO) {
        val mime = if (src.extension == "png") "image/png" else "image/jpeg"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("prompt_id", promptId.toString())
            .addFormDataPart("photo", src.name, src.asRequestBody(mime.toMediaType())).build()
        stage("Uploading…")
        val id = try { send(body) } catch (e: IOException) { resolve(); send(body) }
        val t0 = System.currentTimeMillis()
        var count = 1
        while (true) {
            delay(1500)
            val j = http.newCall(rq("/status/$id").build()).execute().use { r -> check(r); JSONObject(r.body!!.string()) }
            val st = j.optString("stage")
            stage((if (st.isEmpty() || st == "null") "Processing" else st) + " · " + (System.currentTimeMillis() - t0) / 1000 + "s")
            val status = j.getString("status")
            if (status == "done") { count = j.optInt("count", 1); break }
            if (status == "error") error(j.optString("error"))
        }
        stage("Downloading…")
        val outs = (1..count).map { n ->
            val f = File(outDir, "res_${System.currentTimeMillis()}_$n.png")
            http.newCall(rq("/result/$id/$n").build()).execute().use { r ->
                check(r); f.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
            }
            f
        }
        try { http.newCall(rq("/job/$id").delete().build()).execute().close() } catch (e: Exception) { }
        outs
    }
}

fun loadBmp(f: File): Bitmap {
    val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })
    val deg = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
        6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
    }
    return if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
}

fun saveToGallery(c: Context, f: File) {
    val v = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "AI_${System.currentTimeMillis()}.${f.extension}")
        put(MediaStore.Images.Media.MIME_TYPE, if (f.extension == "png") "image/png" else "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AICamera")
    }
    val u = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
    c.contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.prefs = getSharedPreferences("cfg", 0)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF8FB1), secondary = Color(0xFFA98BFF))) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF120B1F)) { Root() }
            }
        }
    }
}

@Composable
fun Root() {
    var user by remember { mutableStateOf(if (Api.sid.isNotEmpty()) Api.user else "") }
    if (user.isEmpty()) LoginScreen { user = it }
    else MainScreen { Api.sid = ""; Api.user = ""; user = "" }
}

@Composable
fun LoginScreen(onDone: (String) -> Unit) {
    var link by remember { mutableStateOf(Api.link) }
    var name by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun go(mode: String) {
        busy = true; msg = ""; Api.link = link.trim()
        scope.launch {
            try { onDone(Api.auth(mode, name.trim(), pass)) } catch (e: Exception) { msg = e.message ?: "Failed" }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("AI Camera", style = MaterialTheme.typography.headlineLarge)
        Text("Sign in to continue", color = Color.Gray)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(link, { link = it }, label = { Text("Pairing code (from the website › Phone app)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg, color = Color(0xFFFF6B81), modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = !busy, onClick = { go("login") }) { Text(if (busy) "Connecting…" else "Log in") }
            OutlinedButton(enabled = !busy, onClick = { go("signup") }) { Text("Create account") }
        }
    }
}

@Composable
fun Chips(items: List<Pair<Int, String>>, sel: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (id, name) ->
            Button(onClick = { onPick(id) },
                colors = ButtonDefaults.buttonColors(containerColor = if (id == sel) Color(0xFFFF8FB1) else Color(0x66FFFFFF))) { Text(name) }
        }
    }
}

/** Before/after slider. New results "unwrap": sweep from original to enhanced, then settle in the middle. */
@Composable
fun Compare(before: Bitmap, after: Bitmap, animate: Boolean) {
    val frac = remember(after) { Animatable(if (animate) 1f else 0.5f) }
    var stopped by remember(after) { mutableStateOf(false) }
    var w by remember { mutableStateOf(1) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(after) {
        if (animate) {
            delay(300)
            if (!stopped) frac.animateTo(0f, tween(1600, easing = FastOutSlowInEasing))
            if (!stopped) frac.animateTo(0.5f, tween(700))
        }
    }
    Box(Modifier.fillMaxSize().padding(top = 24.dp, bottom = 110.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(after.width.toFloat() / after.height).onSizeChanged { w = it.width }
            .pointerInput(after) {
                detectHorizontalDragGestures(onDragStart = { stopped = true }) { change, _ ->
                    change.consume()
                    scope.launch { frac.snapTo((change.position.x / size.width).coerceIn(0f, 1f)) }
                }
            }) {
            Image(after.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Image(before.asImageBitmap(), null,
                Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * frac.value) { this@drawWithContent.drawContent() } },
                contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxHeight().width(3.dp).offset { IntOffset((w * frac.value).roundToInt() - 4, 0) }.background(Color.White))
            Text("BEFORE", Modifier.align(Alignment.TopStart).padding(10.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text("AFTER", Modifier.align(Alignment.TopEnd).padding(10.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun MainScreen(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) perm.launch(Manifest.permission.CAMERA) }

    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    var prompts by remember { mutableStateOf(listOf<Pair<Int, String>>()) }
    var pid by remember { mutableStateOf(Api.pid) }
    var original by remember { mutableStateOf<File?>(null) }
    var results by remember { mutableStateOf(listOf<File>()) }
    var idx by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf(false) }

    fun fail(e: Exception) { if (e is AuthExpired) onLogout() else msg = e.message ?: "Failed" }

    LaunchedEffect(Unit) {
        try {
            prompts = Api.prompts()
            if (prompts.isNotEmpty() && prompts.none { it.first == pid }) pid = prompts[0].first
        } catch (e: Exception) { fail(e) }
    }

    fun enhance(src: File) {
        busy = true; msg = ""; stage = "Starting…"
        scope.launch {
            try { results = Api.process(src, pid, ctx.cacheDir) { stage = it }; idx = 0; fresh = true }
            catch (e: Exception) { fail(e) }
            busy = false
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val f = File(ctx.cacheDir, "pick_${System.currentTimeMillis()}.jpg")
            ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            original = f; results = listOf(); enhance(f)
        }
    }

    fun shoot() {
        val f = File(ctx.cacheDir, "img_${System.currentTimeMillis()}.jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(o: ImageCapture.OutputFileResults) { original = f; results = listOf(); enhance(f) }
                override fun onError(e: ImageCaptureException) { msg = e.message ?: "Capture error" }
            })
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val orig = original
        if (orig == null) {
            if (granted) AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                PreviewView(c).also { pv ->
                    val fut = ProcessCameraProvider.getInstance(c)
                    fut.addListener({
                        val p = fut.get()
                        val prev = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                        p.unbindAll()
                        p.bindToLifecycle(c as LifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, prev, capture)
                    }, ContextCompat.getMainExecutor(c))
                }
            })
            TextButton(onClick = onLogout, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) { Text("Log out") }
            if (msg.isNotEmpty()) Text(msg, color = Color(0xFFFF6B81), modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 16.dp, end = 16.dp))
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Chips(prompts, pid) { pid = it; Api.pid = it }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    OutlinedButton(onClick = { pick.launch("image/*") }) { Text("Gallery") }
                    Box(Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(7.dp).background(Color.White, CircleShape).clickable { shoot() })
                    Spacer(Modifier.width(88.dp))
                }
            }
        } else {
            val before = remember(orig) { loadBmp(orig) }
            if (results.isEmpty()) {
                Image(before.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                val after = remember(results[idx]) { loadBmp(results[idx]) }
                Compare(before, after, fresh)
            }
            if (busy) Column(Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(18.dp)).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(10.dp))
                Text(stage)
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (msg.isNotEmpty()) Text(msg, color = Color(0xFFFF6B81), modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { original = null; results = listOf(); msg = ""; fresh = false }) { Text("Back") }
                    if (results.isEmpty() && msg.isNotEmpty()) Button(enabled = !busy, onClick = { enhance(orig) }) { Text("Retry") }
                    if (results.isNotEmpty()) {
                        Button(enabled = !busy, onClick = {
                            saveToGallery(ctx, results[idx]); Toast.makeText(ctx, "Saved to gallery", Toast.LENGTH_SHORT).show()
                        }) { Text("Save") }
                        Button(enabled = !busy, onClick = { fresh = true; enhance(results[idx]) }) { Text("Re-send") }
                        if (results.size > 1) Button(onClick = { idx = (idx + 1) % results.size }) { Text("Next ${idx + 1}/${results.size}") }
                    }
                }
            }
        }
    }
}
