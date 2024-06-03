package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI


class MengAnime : MainAPI() {
    override var mainUrl = "https://www.mfan.tv"
    override var name = "萌番"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true

    private var type = "Movie"
    private var status = "Finished Airing"

    override val supportedSyncNames = setOf(
        SyncIdName.Anilist,
        SyncIdName.MyAnimeList
    )

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special", true)) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Finished Airing" -> ShowStatus.Completed
                "Currently Airing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/type/20/by/time/page/" to "新番",
        "$mainUrl/type/21/by/hits/page/" to "番剧",
        "$mainUrl/type/22/by/hits/page/" to "剧场",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val document = app.get(request.data + page).document
        val home = document.select("ul.hl-vod-list a.hl-item-thumb").mapNotNull {
            it.toSearchResult()
        }
        items.add(HomePageList(request.name, home))
        return newHomePageResponse(items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.attr("title").ifBlank { return null }
        val href = this.attr("href").ifBlank { return null }
        val posterUrl = this.attr("data-original").ifBlank { return null }
        val epNum = this.select("div.hl-pic-text span").text().filter { it.isDigit() }.toIntOrNull()
        return if (epNum == null) newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        } else newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            this.addDubStatus(dubExist = false, subExist = true, dubEpisodes = epNum, subEpisodes = epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/?wd=$query").document
        return document.select("li.hl-col-xs-12 a.hl-item-thumb").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("span.hl-item-thumb")?.attr("title") ?: return null
        val realName = getAnimeDetail(title)
        val poster = document.selectFirst("span.hl-item-thumb")?.attr("data-original") ?: return null
        val description = document.selectFirst(".hl-content-text")?.text()?: return null

        val status = getStatus(status)
        val type = getType(type)
        val tags = document.select("li.hl-col-xs-12").drop(1).dropLast(1).map {
            it.text().trim().trimEnd('/').replace("//+".toRegex(), "/")
        }
        val episodes = mutableListOf<Episode>()
        // 获取剧集的个数
        // val num = document.select("ul.hl-plays-list li")?.size
        document.select("ul.hl-plays-list li")?.forEach {
            val name = it.select("a").text()
            var epsTitle = name
            var link = fixUrl(it.select("a").attr("href"))
            when {
                name.matches("\\d+".toRegex()) -> {
                    // 纯数字的情况
                    epsTitle = "第${name}集"
                }
                else -> {
                    // 其他情况
                    epsTitle = name
                    link = fixUrl(it.select("a").attr("href"))
                }
            }
            // val episode = epsTitle.substringBefore(":").filter { it.isDigit() }.toIntOrNull()
            episodes.add(Episode(link, name = epsTitle.substringAfter(":").trim()))
        }
        return newAnimeLoadResponse(realName?:title, url, type) {
            engName = realName
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
        }
    }
    //辅助函数
    private fun Document.getExternalId(str: String): Int? {
        return this.select("div.anime-metadetails > div:contains(External Links) a:contains($str)")
            .attr("href").removeSuffix("/").split("/").lastOrNull()?.toIntOrNull()
    }

    private fun Document.getPageContent(str: String): String {
        return this.select("div.anime-metadetails > div:contains($str) span.description").text()
    }

    // 定义一个函数 getBaseUrl，该函数接收一个字符串类型的参数 url
    private fun getBaseUrl(url: String): String {
        // 使用 URI 类解析 url
        return URI(url).let {
            // 返回 url 的基础部分，即协议名和主机名
            "${it.scheme}://${it.host}"
        }
    }
    private suspend fun fetchAndParseApiData(): String {
        val url = "https://v1.hitokoto.cn/?c=a"
        val response = app.get(url).text
        val jsonObject = JSONObject(response)
        val hitokoto = jsonObject.getString("hitokoto")
        val from = jsonObject.getString("from")
        return "$hitokoto  $from "
    }

    private suspend fun getAnimeDetail(title: String): String {
        val Bangumi = "https://api.bgm.tv/search/subject/$title?type=2&responseGroup=small&max_results=1"
        var response = app.get(Bangumi).text
        var jsonObject: JSONObject? = null
        var name: String = title
        try {
            jsonObject = JSONObject(response)
            name = jsonObject.getJSONArray("list").getJSONObject(0).getString("name")
        } catch (e: JSONException) {
            return title
        }
        val jikan = "https://api.jikan.moe/v4/anime?q=${name}&limit=1"
        response = app.get(jikan).text
        try {
            jsonObject = JSONObject(response)
            type = jsonObject.getJSONArray("data").getJSONObject(0).getString("type")
            status = jsonObject.getJSONArray("data").getJSONObject(0).getString("status")
            val titles = jsonObject.getJSONArray("data").getJSONObject(0).getJSONArray("titles")
            for (i in 0 until titles.length()) {
                val obj = titles.getJSONObject(i)
                if (obj.getString("type") == "English") {
                    return obj.getString("title")
                }
            }
        } catch (e: JSONException) {
            return name
        }
        return name
    }
    // 重写 loadLinks 函数，该函数用于加载视频链接
    override suspend fun loadLinks(
        data: String,  // 输入的数据，通常是视频的 URL
        isCasting: Boolean,  // 是否正在投放视频
        subtitleCallback: (SubtitleFile) -> Unit,  // 字幕回调函数
        callback: (ExtractorLink) -> Unit  // 提取链接的回调
    ): Boolean {
        listOf(data).apmap { url ->
            val doc = app.get(url).document
            val jsonString = doc.selectFirst("div.hl-player-wrap > script:nth-child(2)")?.data()?: return@apmap null
            val pattern = """"url":"(.*?)"""".toRegex()
            val matchResult = pattern.find(jsonString)
            var source = matchResult?.groups?.get(1)?.value?.replace("\\", "")?: return@apmap null
            // 检查源是否为 MP4 格式
            if (source.endsWith(".png?.mp4")) {
                source = source.replace(".png?.mp4", ".png")
                callback(ExtractorLink(url, fetchAndParseApiData(), source, url, 0, false))
            } else if (source.endsWith(".mp4")) {
                // 如果是 MP4 格式，直接通过回调函数返回
                callback(ExtractorLink(url, fetchAndParseApiData(), source, url, 0, false))
            } else if (source.endsWith(".m3u8")) {
                // 生成 M3U8 链接，并通过回调函数返回
                M3u8Helper.generateM3u8(
                    fetchAndParseApiData(),
                    source,
                    "https://video1.beijcloud.com"
                ).forEach(callback)
            } else {
                val iframeSrc = "https://video1.beijcloud.com/player/?url=$source"
                val iframeDoc = app.get(iframeSrc, referer = url).document.data()
                val p = """url:\s*'([^']*)'""".toRegex()
                val res = p.find(iframeDoc)
                source = res?.groups?.get(1)?.value?.replace(".png?.mp4", ".png")?: return@apmap null
                callback(ExtractorLink(url, fetchAndParseApiData(), source, url, 0, false))
            }
        }
        // 返回 true，表示加载链接成功
        return true
    }
}
