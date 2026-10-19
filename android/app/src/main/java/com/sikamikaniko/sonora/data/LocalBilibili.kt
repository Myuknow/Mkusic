package com.sikamikaniko.sonora.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface BiliInput {
    data class Video(val bvid: String? = null, val aid: String? = null, val page: Int = 1) : BiliInput
    data class Audio(val sid: String) : BiliInput
    data class Favorite(val fid: String) : BiliInput
}

object BiliInputs {
    fun parse(text: String): BiliInput {
        val link = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).find(text)?.value
            ?.trimEnd(')', '）', ']', '】', ',', '，', '。')?.toHttpUrlOrNull()
        if (link != null) require(link.host == "bilibili.com" || link.host.endsWith(".bilibili.com")) { "请输入 B站视频、音频或公开收藏夹链接" }
        val source = link?.toString() ?: text.trim()
        val fid = link?.queryParameter("fid") ?: link?.queryParameter("media_id")
            ?: Regex("(?:^|/)ml(\\d+)(?:[/?#]|$)").find(source)?.groupValues?.get(1)
        if (fid?.matches(Regex("[0-9]+")) == true) return BiliInput.Favorite(fid)
        val page = link?.queryParameter("p")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        Regex("BV[0-9a-zA-Z]{10}", RegexOption.IGNORE_CASE).find(source)?.value?.let {
            return BiliInput.Video(bvid = "BV" + it.substring(2), page = page)
        }
        Regex("(?:^|[/\\s])av(\\d+)", RegexOption.IGNORE_CASE).find(source)?.groupValues?.get(1)?.let {
            return BiliInput.Video(aid = it, page = page)
        }
        Regex("(?:^|[/\\s])au(\\d+)", RegexOption.IGNORE_CASE).find(source)?.groupValues?.get(1)?.let { return BiliInput.Audio(it) }
        throw IllegalArgumentException("请输入 BV 号、av/au 号、B站视频链接或公开收藏夹链接")
    }
}

/** Stable logical sources; expiring CDN URLs are resolved only when a loader opens them. */
object LocalMusicSources {
    const val SCHEME = "mkusic"
    fun playable(track: WorkstationTrack): Boolean = when (track.kind) {
        "bilibili" -> !track.bvid.isNullOrBlank() || !track.aid.isNullOrBlank()
        "biliaudio" -> !track.sid.isNullOrBlank()
        else -> false
    }
    fun key(track: WorkstationTrack) = "mkusic-local:${track.id}"
    fun uri(track: WorkstationTrack): String {
        require(playable(track)) { "这首歌曲不是可解析的 B站音源" }
        val builder = "https://audio/track.m4a".toHttpUrl().newBuilder()
        listOf("bvid" to track.bvid, "aid" to track.aid, "cid" to track.cid, "sid" to track.sid).forEach { (name, value) ->
            value?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter(name, it) }
        }
        return builder.build().toString().replaceFirst("https:", "$SCHEME:")
    }
    fun fromUri(value: String): WorkstationTrack {
        require(value.startsWith("$SCHEME://audio/")) { "无效的本机音源" }
        val url = value.replaceFirst("$SCHEME:", "https:").toHttpUrl()
        return WorkstationTrack(kind = if (url.queryParameter("sid") != null) "biliaudio" else "bilibili",
            bvid = url.queryParameter("bvid"), aid = url.queryParameter("aid"), cid = url.queryParameter("cid"), sid = url.queryParameter("sid"))
            .also { require(playable(it)) { "本机音源缺少 B站编号" } }
    }
    fun artwork(track: WorkstationTrack): String? = track.cover?.let { raw ->
        (if (raw.startsWith("//")) "https:$raw" else raw).toHttpUrlOrNull()?.takeIf {
            it.username.isEmpty() && it.password.isEmpty() &&
                (it.host == "hdslb.com" || it.host.endsWith(".hdslb.com") || it.host == "bilibili.com" || it.host.endsWith(".bilibili.com"))
        }?.let { if (it.isHttps) it else it.newBuilder().scheme("https").port(443).build() }?.toString()
    }
}

fun WorkstationTrack.playableIn(server: String): Boolean = if (server.isBlank()) LocalMusicSources.playable(this)
    else server.toHttpUrlOrNull()?.let { streamUrl(it) != null } == true
fun WorkstationTrack.artworkIn(server: String): String? = if (server.isBlank()) LocalMusicSources.artwork(this)
    else server.toHttpUrlOrNull()?.let(::coverUrl)

fun pendingLocalDownloads(tracks: List<WorkstationTrack>, statuses: Map<String, String>): List<WorkstationTrack> =
    tracks.filter { track ->
        val status = statuses[track.id]
        track.id.isNotBlank() && LocalMusicSources.playable(track) && status != "已完整缓存" &&
            status != "等待缓存" && status != "清理缓存中" && status?.startsWith("缓存中 ") != true
    }.distinctBy { it.id }

class BilibiliException(message: String) : IOException(message)

class LocalBilibiliClient(
    private val client: OkHttpClient = httpClient(),
    private val apiRoot: HttpUrl = "https://api.bilibili.com/".toHttpUrl(),
    private val audioRoot: HttpUrl = "https://www.bilibili.com/".toHttpUrl()
) {
    companion object {
        // Some Bilibili CDNs reject the mobile/custom UA with 403 even for a fresh URL.
        // Keep API resolution and media/download requests on the same browser headers.
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36",
            "Referer" to "https://www.bilibili.com/")
        fun httpClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).followSslRedirects(false).build()
        fun pickAudio(data: JsonObject): String {
            val preview = data.get("is_preview")?.takeIf { it.isJsonPrimitive }?.asString
            if (preview == "1" || preview == "true") throw BilibiliException("该内容只提供试听音频，独立模式暂不支持")
            val audio = data.getAsJsonObject("dash")?.getAsJsonArray("audio")?.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }.orEmpty()
            val compatible = audio.filter { it.string("mimeType", "mime_type").contains("mp4") || it.string("codecs").startsWith("mp4a") }
            val selected = (compatible.ifEmpty { audio }).sortedByDescending { it.get("bandwidth")?.asLong ?: 0 }
            val urls = selected.flatMap { item -> listOf(item.string("baseUrl", "base_url")) +
                (item.getAsJsonArray("backupUrl") ?: item.getAsJsonArray("backup_url"))?.mapNotNull { it.takeIf { element -> element.isJsonPrimitive }?.asString }.orEmpty() }
            return urls.mapNotNull { it.toHttpUrlOrNull() }.firstOrNull { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
                ?: throw BilibiliException("没有可播放的独立音频；该内容可能不可用或需要登录")
        }
    }
    private fun json(root: HttpUrl, path: String, query: Map<String, String>): JsonObject {
        val url = root.resolve(path)!!.newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw BilibiliException("B站请求失败（HTTP ${response.code}），请稍后重试")
            val body = try { JsonParser.parseString(response.body?.string()).asJsonObject } catch (_: Exception) {
                throw BilibiliException("B站返回了无法识别的数据，请稍后重试")
            }
            val code = body.get("code")?.asInt ?: -1
            if (code != 0) throw BilibiliException(when (code) {
                -412, -352 -> "B站暂时限制了请求，请稍后再试"
                -404 -> "该内容不存在或已失效"
                -101 -> "该内容需要登录，独立模式暂不支持"
                else -> body.string("message", "msg").ifBlank { "B站接口返回错误（$code）" }
            })
            return body
        }
    }
    private fun videoView(input: BiliInput.Video) = json(apiRoot, "x/web-interface/view",
        if (!input.bvid.isNullOrBlank()) mapOf("bvid" to input.bvid) else mapOf("aid" to requireNotNull(input.aid))).getAsJsonObject("data")
        ?: throw BilibiliException("没有找到视频信息")

    fun tracks(raw: String): List<WorkstationTrack> {
        val expanded = expandShortLink(raw)
        return when (val input = BiliInputs.parse(expanded)) {
            is BiliInput.Video -> {
                val view = videoView(input)
                val pages = view.getAsJsonArray("pages") ?: throw BilibiliException("没有找到视频分P")
                val part = pages.firstOrNull { it.asJsonObject.get("page")?.asInt == input.page }?.asJsonObject
                    ?: throw BilibiliException("没有找到第 ${input.page} 个分P")
                val bvid = view.string("bvid").ifBlank { input.bvid.orEmpty() }
                listOf(WorkstationTrack(id = "bili:$bvid:p${input.page}", kind = "bilibili", bvid = bvid,
                    aid = view.string("aid"), cid = part.string("cid"), title = view.string("title") +
                        if (pages.size() > 1) " - ${part.string("part")}" else "",
                    artist = view.getAsJsonObject("owner")?.string("name"), cover = view.string("pic")))
            }
            is BiliInput.Audio -> {
                val info = json(audioRoot, "audio/music-service-c/web/song/info", mapOf("sid" to input.sid)).getAsJsonObject("data")
                    ?: throw BilibiliException("没有找到音频信息")
                listOf(WorkstationTrack(id = "audio:${input.sid}", kind = "biliaudio", sid = input.sid,
                    title = info.string("title"), artist = info.string("uname"), cover = info.string("cover")))
            }
            is BiliInput.Favorite -> favorite(input.fid)
        }
    }

    private fun favorite(fid: String): List<WorkstationTrack> {
        val result = linkedMapOf<String, WorkstationTrack>()
        var page = 1
        while (page <= 1000) {
            val data = json(apiRoot, "x/v3/fav/resource/list", mapOf("media_id" to fid, "pn" to page.toString(), "ps" to "20", "platform" to "web"))
                .getAsJsonObject("data") ?: throw BilibiliException("收藏夹无法读取，可能为私密收藏夹")
            val group = data.getAsJsonObject("info")?.string("title")?.takeIf(String::isNotBlank) ?: "B站收藏夹"
            val media = data.getAsJsonArray("medias")
            media?.forEach { element ->
                val item = element.asJsonObject
                val type = item.get("type")?.asInt
                val bvid = item.string("bvid", "bv_id")
                val id = item.string("id")
                if (type == 2 && bvid.isNotBlank()) {
                    val track = WorkstationTrack(id = "bili:$bvid:p1", kind = "bilibili", bvid = bvid, aid = id,
                        title = item.string("title"), artist = item.getAsJsonObject("upper")?.string("name"), cover = item.string("cover"), group = group)
                    result[track.id] = track
                } else if (type == 12 && id.isNotBlank()) {
                    val track = WorkstationTrack(id = "audio:$id", kind = "biliaudio", sid = id, title = item.string("title"),
                        artist = item.getAsJsonObject("upper")?.string("name"), cover = item.string("cover"), group = group)
                    result[track.id] = track
                }
            }
            if (data.get("has_more")?.asBoolean != true || media == null || media.size() == 0) return result.values.toList()
            page++
        }
        throw BilibiliException("收藏夹过大，请拆分后导入")
    }

    fun resolve(track: WorkstationTrack): String {
        if (track.kind == "biliaudio") {
            val data = json(audioRoot, "audio/music-service-c/web/url", mapOf("sid" to requireNotNull(track.sid))).getAsJsonObject("data")
            return data?.getAsJsonArray("cdns")?.mapNotNull { it.asString.toHttpUrlOrNull() }?.firstOrNull { it.isHttps }?.toString()
                ?: throw BilibiliException("没有找到 B站音频地址")
        }
        val view = videoView(BiliInput.Video(track.bvid, track.aid))
        val pages = view.getAsJsonArray("pages") ?: throw BilibiliException("没有找到视频分P")
        val part = pages.firstOrNull { track.cid.isNullOrBlank() || it.asJsonObject.string("cid") == track.cid }?.asJsonObject
            ?: throw BilibiliException("该分P已失效")
        val play = json(apiRoot, "x/player/playurl", mapOf("bvid" to view.string("bvid"), "cid" to part.string("cid"),
            "qn" to "64", "fnval" to "16", "try_look" to "1", "voice_balance" to "1")).getAsJsonObject("data")
            ?: throw BilibiliException("没有找到音频信息")
        return pickAudio(play)
    }

    private fun expandShortLink(raw: String): String {
        val match = Regex("https?://b23\\.tv/[a-zA-Z0-9]+", RegexOption.IGNORE_CASE).find(raw) ?: return raw
        var url = match.value.toHttpUrl().newBuilder().scheme("https").port(443).build()
        val redirectClient = client.newBuilder().followRedirects(false).build()
        repeat(5) {
            require(url.host == "b23.tv" || url.host == "bilibili.com" || url.host.endsWith(".bilibili.com")) { "短链接跳转到了非 B站地址" }
            if (url.host != "b23.tv") return url.toString()
            redirectClient.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { response ->
                url = response.header("Location")?.let(url::resolve)?.takeIf { it.isHttps }
                    ?: throw BilibiliException("B站短链接无法展开，请复制完整视频链接")
            }
        }
        throw BilibiliException("B站短链接跳转次数过多")
    }
}

private fun JsonObject.string(vararg names: String): String = names.firstNotNullOfOrNull { name ->
    get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
}.orEmpty()
