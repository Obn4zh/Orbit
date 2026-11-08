package app.orbit.tv.core

import android.content.res.AssetManager
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.streamProvider
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Orbit protocol v1 (see docs/PROTOCOL.md), identical to the Windows receiver. */
class ReceiverServer(
    private val manager: TransferManager,
    private val settings: Settings,
    private val assets: AssetManager,
    /** False while the TV is on an open (password-less) Wi-Fi: uploads are refused there. */
    private val isTrustedNetwork: () -> Boolean = { true },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private val authFailures = ConcurrentHashMap<String, Pair<Int, Long>>()
    private var engine: ApplicationEngine? = null

    @Volatile var port: Int = 0
        private set
    val isRunning get() = engine != null

    @Synchronized
    fun start(preferred: Int = Protocol.DEFAULT_PORT) {
        if (engine != null) return
        val chosen = (preferred until preferred + 10).firstOrNull { isFree(it) }
            ?: ServerSocket(0).use { it.localPort }
        engine = embeddedServer(CIO, port = chosen, host = "0.0.0.0") { module() }.start(wait = false)
        port = chosen
    }

    @Synchronized
    fun stop() {
        engine?.stop(500, 1500)
        engine = null
    }

    private fun isFree(p: Int) = try { ServerSocket(p).use { true } } catch (e: Exception) { false }

    // ------------------------------------------------------------------ routes

    private fun io.ktor.server.application.Application.module() {
        intercept(ApplicationCallPipeline.Plugins) {
            val remote = call.request.local.remoteAddress
            if (!NetUtil.isLocal(remote)) {
                call.error(403, ErrorCodes.NOT_LOCAL, "Only devices on the local network may connect."); finish(); return@intercept
            }
            call.response.header("X-Content-Type-Options", "nosniff")
            // The QR URL itself ("/?k=…") also accepts uploads, so an iOS Shortcut can POST to whatever it scanned.
            val rootUpload = call.request.path() == "/" && call.request.local.method != HttpMethod.Get && call.request.local.method != HttpMethod.Head
            if (call.request.path().startsWith("/api") || rootUpload) {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                if (remote != "127.0.0.1" && !isTrustedNetwork()) {
                    call.error(403, ErrorCodes.UNTRUSTED_NETWORK, "The receiver is on an open Wi-Fi network, receiving is paused."); finish(); return@intercept
                }
                if (isRateLimited(remote)) { call.error(429, ErrorCodes.RATE_LIMITED, "Too many attempts."); finish(); return@intercept }
                if (!isAuthorized(call)) {
                    registerFailure(remote)
                    call.error(401, ErrorCodes.UNAUTHORIZED, "Invalid pairing code."); finish(); return@intercept
                }
            }
        }

        routing {
            get("/") { call.asset("web/index.html", ContentType.Text.Html.withParameter("charset", "utf-8")) }
            post("/") { call.guard { upload(call) } }
            put("/") { call.guard { upload(call) } }
            get("/icon.svg") { call.asset("web/icon.svg", ContentType.Image.SVG) }
            get("/icon.png") { call.asset("web/icon.png", ContentType.Image.PNG) }

            route("/api/v1") {
                get("/info") {
                    val s = settings.current
                    call.ok(buildJsonObject {
                        put("name", s.deviceName); put("deviceId", s.deviceId); put("platform", "android-tv")
                        put("version", Protocol.VERSION); put("receiving", s.receivingEnabled); put("requiresApproval", s.requireApproval)
                    })
                }

                post("/sessions") {
                    call.guard {
                        val req = try { json.decodeFromString<CreateSessionRequest>(call.receiveText()) }
                        catch (e: Exception) { throw TransferError(400, ErrorCodes.INVALID, "Malformed JSON.") }
                        val s = manager.createSession(req.sender ?: "", call.request.local.remoteAddress,
                            req.files.map { Triple(it.name ?: "", it.size, it.type) })
                        call.ok(dto(s))
                    }
                }

                get("/sessions/{id}") {
                    val s = manager.find(call.parameters["id"]!!) ?: return@get call.notFound()
                    call.ok(dto(s))
                }

                delete("/sessions/{id}") {
                    val id = call.parameters["id"]!!
                    val s = manager.find(id) ?: return@delete call.notFound()
                    manager.cancel(id)
                    call.ok(dto(s))
                }

                get("/sessions/{id}/files/{index}") {
                    val f = manager.find(call.parameters["id"]!!)?.file(call.parameters["index"]?.toIntOrNull() ?: -1)
                        ?: return@get call.notFound()
                    call.ok(fileDto(f))
                }

                put("/sessions/{id}/files/{index}") {
                    call.guard {
                        val s = manager.find(call.parameters["id"]!!) ?: throw TransferError(404, ErrorCodes.NOT_FOUND, "Unknown session.")
                        val remote = call.request.local.remoteAddress
                        if (s.remote != remote && remote != "127.0.0.1") throw TransferError(403, ErrorCodes.UNAUTHORIZED, "Session belongs to another device.")
                        val f = s.file(call.parameters["index"]?.toIntOrNull() ?: -1) ?: throw TransferError(404, ErrorCodes.NOT_FOUND, "Unknown file.")
                        val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                        val result = manager.receive(s, f, call.receiveChannel(), offset)
                        if (result == TransferManager.Result.INCOMPLETE)
                            call.respondJson(400, buildJsonObject { put("error", "incomplete"); put("message", "Body ended early."); put("received", f.received.get()) })
                        else call.ok(fileDto(f))
                    }
                }

                // iOS Shortcuts "Get Contents of URL": multipart form or raw body, one request per item.
                route("/upload") {
                    handle {
                        if (call.request.local.method != HttpMethod.Post && call.request.local.method != HttpMethod.Put) return@handle call.notFound()
                        call.guard { upload(call) }
                    }
                }
            }
        }
    }

    private suspend fun upload(call: ApplicationCall) {
        val remote = call.request.local.remoteAddress
        val sender = call.request.queryParameters["from"] ?: guessSender(call.request.header(HttpHeaders.UserAgent) ?: "")
        val session = manager.getOrCreateAdHoc(sender, remote)
        if (!manager.awaitDecision(session))
            throw TransferError(403, if (session.status.value == SessionStatus.EXPIRED) ErrorCodes.EXPIRED else ErrorCodes.DECLINED, "The transfer was not accepted.")

        val saved = mutableListOf<String>()
        if (call.request.contentType().match(ContentType.MultiPart.Any)) {
            val multipart = call.receiveMultipart()
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    if (part is PartData.FileItem) {
                        val name = withExtension(part.originalFileName?.takeIf { it.isNotBlank() } ?: part.name ?: "file", part.contentType?.toString())
                        val f = manager.addAdHocFile(session, name, -1, part.contentType?.withoutParameters()?.toString())
                        part.streamProvider().use { manager.receive(session, f, it, 0) }
                        saved += f.savedName ?: f.safeName
                    }
                } finally { part.dispose() }
            }
        } else {
            val name = withExtension(call.request.queryParameters["name"] ?: call.request.header("X-File-Name") ?: "file", call.request.header(HttpHeaders.ContentType))
            val size = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: -1
            val f = manager.addAdHocFile(session, name, size, call.request.contentType().withoutParameters().toString())
            if (manager.receive(session, f, call.receiveChannel(), 0) == TransferManager.Result.INCOMPLETE)
                throw TransferError(400, "incomplete", "Upload ended early.")
            saved += f.savedName ?: f.safeName
        }
        if (saved.isEmpty()) throw TransferError(400, ErrorCodes.INVALID, "No file in request.")
        call.ok(buildJsonObject {
            put("sessionId", session.id)
            put("saved", kotlinx.serialization.json.JsonArray(saved.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        })
    }

    // ------------------------------------------------------------------ helpers

    private fun isAuthorized(call: ApplicationCall): Boolean {
        val provided = (call.request.header(Protocol.KEY_HEADER) ?: call.request.queryParameters["k"] ?: "").trim().uppercase()
        return MessageDigest.isEqual(provided.toByteArray(), settings.current.pairingKey.toByteArray())
    }

    private fun isRateLimited(remote: String): Boolean {
        val (count, since) = authFailures[remote] ?: return false
        return count >= 20 && System.currentTimeMillis() - since < 60_000
    }

    private fun registerFailure(remote: String) {
        val now = System.currentTimeMillis()
        authFailures.compute(remote) { _, e -> if (e == null || now - e.second > 60_000) 1 to now else (e.first + 1) to e.second }
    }

    private suspend fun ApplicationCall.guard(block: suspend () -> Unit) {
        try { block() }
        catch (e: TransferError) { runCatching { error(e.status, e.code, e.message ?: "") } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { runCatching { error(400, ErrorCodes.INVALID, "Upload interrupted.") } }
    }

    private suspend fun ApplicationCall.asset(path: String, type: ContentType) {
        val bytes = runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() ?: return notFound()
        response.header(HttpHeaders.CacheControl, "no-cache")
        respondBytes(bytes, type)
    }

    private suspend fun ApplicationCall.respondJson(status: Int, body: JsonElement) =
        respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(status))

    private suspend fun ApplicationCall.ok(body: JsonElement) = respondJson(200, body)
    private suspend fun ApplicationCall.error(status: Int, code: String, message: String) =
        respondJson(status, buildJsonObject { put("error", code); put("message", message) })
    private suspend fun ApplicationCall.notFound() = error(404, ErrorCodes.NOT_FOUND, "Not found.")

    private fun dto(s: TransferSession) = buildJsonObject {
        put("sessionId", s.id)
        put("status", s.status.value.name.lowercase())
        put("paused", s.paused.value)
        put("files", kotlinx.serialization.json.JsonArray(s.files.map { fileDto(it) }))
    }

    private fun fileDto(f: TransferFile) = buildJsonObject {
        put("index", f.index); put("name", f.safeName); put("size", f.size); put("received", f.received.get())
        put("status", f.status.name.lowercase()); put("savedAs", if (f.status == FileStatus.DONE) f.savedName else null)
    }

    private fun guessSender(ua: String) = when {
        "iPhone" in ua -> "iPhone"; "iPad" in ua -> "iPad"; "Mac" in ua -> "Mac"; "Android" in ua -> "Android"; else -> "Shortcut"
    }

    private fun withExtension(name: String, contentType: String?): String {
        if (name.substringAfterLast('/').contains('.') || contentType == null) return name
        val ext = when (contentType.substringBefore(';').trim().lowercase()) {
            "image/jpeg" -> ".jpg"; "image/heic" -> ".heic"; "image/heif" -> ".heif"; "image/png" -> ".png"; "image/gif" -> ".gif"
            "image/webp" -> ".webp"; "video/quicktime" -> ".mov"; "video/mp4" -> ".mp4"; "application/pdf" -> ".pdf"
            "text/plain" -> ".txt"; "application/zip" -> ".zip"; "audio/mpeg" -> ".mp3"; "audio/x-m4a" -> ".m4a"
            else -> ""
        }
        return name + ext
    }

    @Serializable private data class FileEntry(val name: String? = null, val size: Long = 0, val type: String? = null)
    @Serializable private data class CreateSessionRequest(val sender: String? = null, val files: List<FileEntry> = emptyList())
}
