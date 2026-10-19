package com.sikamikaniko.sonora.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ServerConnection(val baseUrl: HttpUrl, val password: String) {
    override fun toString() = "ServerConnection(${baseUrl}, password=redacted)"

    companion object {
        const val DEFAULT_URL = ""

        fun create(address: String, password: String): ServerConnection {
            val url = address.trim().toHttpUrlOrNull()
                ?: throw IllegalArgumentException("请输入完整的服务器地址（含 http:// 或 https://）")
            require(url.isHttps || allowsLocalHttp(url)) { "公网服务器需要使用 HTTPS；局域网可使用 HTTP IP 地址" }
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "地址不能包含账号、密码、查询参数或片段"
            }
            require(password.isNotBlank()) { "请输入访问密码" }
            val normalized = url.newBuilder().apply {
                if (!url.encodedPath.endsWith('/')) addPathSegment("")
            }.build()
            return ServerConnection(normalized, password)
        }

        /** Literal private IPv4 addresses and loopback only; no DNS lookup. */
        internal fun allowsLocalHttp(url: HttpUrl): Boolean {
            if (url.scheme != "http") return false
            if (url.host == "localhost" || url.host == "::1") return true
            val parts = url.host.split('.').map { it.toIntOrNull() ?: return false }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            return parts[0] == 10 || parts[0] == 127 ||
                parts[0] == 192 && parts[1] == 168 ||
                parts[0] == 172 && parts[1] in 16..31
        }
    }
}

data class WorkstationTrack(
    val id: String = "",
    val kind: String = "",
    val title: String? = null,
    val artist: String? = null,
    val group: String? = null,
    val cover: String? = null,
    val url: String? = null,
    val bvid: String? = null,
    val aid: String? = null,
    val cid: String? = null,
    val sid: String? = null,
    val audioId: String? = null,
    val blobId: String? = null,
    val filename: String? = null
) {
    /** Stable server proxy, never a short-lived Bilibili CDN URL. */
    fun streamUrl(base: HttpUrl): HttpUrl? { return when (kind) {
        "bilibili" -> {
            val builder = base.resolve("api/bilibili/audio")!!.newBuilder()
            when {
                !bvid.isNullOrBlank() -> builder.addQueryParameter("bvid", bvid)
                !aid.isNullOrBlank() -> builder.addQueryParameter("aid", aid)
                else -> return null
            }
            cid?.takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("cid", it) }
            builder.build()
        }
        "biliaudio" -> sid?.takeIf { it.isNotBlank() }?.let {
            base.resolve("api/bilibili/audio")!!.newBuilder().addQueryParameter("sid", it).build()
        }
        "local" -> (audioId?.takeIf { it.isNotBlank() } ?: blobId?.takeIf { it.isNotBlank() })?.let {
            base.resolve("api/audio/")!!.newBuilder().addPathSegment(it).addPathSegment("open").build()
        } ?: url?.let { base.resolve(it) }?.takeIf {
            WorkstationAuth.sameOrigin(base, it) && it.encodedPath.startsWith(base.resolve("api/audio/")!!.encodedPath) &&
                it.encodedPath.endsWith("/open")
        }
        "url" -> url?.let { base.resolve(it) }?.takeIf {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty()
        }
        else -> null
    } }

    fun cacheKey(base: HttpUrl): String = com.google.gson.Gson().toJson(
        listOf(base.toString(), id, kind, bvid, aid, cid, sid, audioId, blobId, if (kind in listOf("url", "local")) url else null)
    )

    val displayTitle: String get() = title?.takeIf { it.isNotBlank() }
        ?: filename?.takeIf { it.isNotBlank() } ?: "未命名歌曲"
    val groupName: String get() = group?.trim()?.takeIf { it.isNotEmpty() } ?: when (kind) {
        "bilibili", "biliaudio" -> "B站"
        "local" -> "服务器音乐"
        "url" -> "网络音频"
        else -> "其他来源"
    }
    val sourceName: String get() = when (kind) {
        "bilibili" -> "B站视频"
        "biliaudio" -> "B站音频"
        "local" -> "服务器文件"
        "url" -> "网络音频"
        else -> "暂不支持的来源"
    }

    fun matches(query: String): Boolean = query.trim().let { needle ->
        needle.isEmpty() || listOf(title, artist, groupName, filename, bvid, aid, sid)
            .any { it?.contains(needle, ignoreCase = true) == true }
    }

    fun coverUrl(base: HttpUrl): String? {
        val raw = cover?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val resolved = base.resolve(raw) ?: return null
        if (resolved.host.endsWith(".hdslb.com") || resolved.host == "hdslb.com" ||
            resolved.host.endsWith(".bilibili.com") || resolved.host == "bilibili.com") {
            val secureCover = if (resolved.isHttps) resolved
                else resolved.newBuilder().scheme("https").port(443).build()
            return base.resolve("api/bilibili/cover")!!.newBuilder()
                .addQueryParameter("url", secureCover.toString()).build().toString()
        }
        return resolved.takeIf { it.isHttps ||
            ServerConnection.allowsLocalHttp(base) && WorkstationAuth.sameOrigin(base, it)
        }?.toString()
    }
}

data class WorkstationLibrary(val server: String, val refreshedAt: Long, val tracks: List<WorkstationTrack>)
