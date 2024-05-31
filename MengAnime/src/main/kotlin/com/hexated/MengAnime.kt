package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import org.json.JSONObject

class MengAnime : MainAPI() {
    override var mainUrl = "https://www.mfan.tv"
    override var name = "萌番"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true

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
            return if(t.contains("日语", true)) TvType.Anime
            else TvType.TvSeries
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "已完结" -> ShowStatus.Completed
                else -> ShowStatus.Ongoing
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/type/20/page/" to "新番",
        "$mainUrl/type/21/page/" to "番剧",
        "$mainUrl/type/22/page/" to "剧场",
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
        val status = this.select(".state").text()
        val epNum =
            this.select(".hl-pic-text").select(".hl-lc-1 remarks").text().filter { it.isDigit() }.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(status, epNum)
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
        val poster = document.selectFirst("span.hl-item-thumb")?.attr("data-original") ?: return null
        val description = document.selectFirst(".hl-content-text")?.text()?: return null
        val status = getStatus(document.selectFirst("#conch-content > div.conch-ctwrap-auto > div > div.container > div > div.hl-col-xs-12.hl-col-md-70w.hl-col-lg-9 > div > div.hl-dc-content > div.hl-vod-data.hl-full-items > div.hl-data-sm.hl-full-alert.hl-full-x100 > div.hl-full-box.clearfix > ul > li:nth-child(2) > span").text())?:return null
        val type = getType(document.selectFirst("#conch-content > div.conch-ctwrap-auto > div > div.container > div > div.hl-col-xs-12.hl-col-md-70w.hl-col-lg-9 > div > div.hl-dc-content > div.hl-vod-data.hl-full-items > div.hl-data-sm.hl-full-alert.hl-full-x100 > div.hl-full-box.clearfix > ul > li:nth-child(10)").text())?:return null
        val tags = document.select("li.hl-col-xs-12").drop(1).dropLast(1).map {
            it.text().trim().trimEnd('/').replace("//+".toRegex(), "/")
        }
        val episodes = mutableListOf<Episode>()
        //获取剧集的个数
        val num = document.select("ul.hl-plays-list li")?.size
        document.select("ul.hl-plays-list li")?.forEach {
            val epsTitle = it.select("a").text()
            val link = fixUrl(it.select("a").attr("href"))
            val episode = epsTitle.substringBefore(":").filter { it.isDigit() }.toIntOrNull()
            episodes.add(Episode(link, name = epsTitle.substringAfter(":").trim(), episode = episode))
        }
        return newAnimeLoadResponse(title, url, type) {
            engName = title
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
        return "$hitokoto From: $from"
    }
    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        val url = "${mainUrl}${id}"
        return url
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
            val jsonString = doc.selectFirst("#conch-content > div.conch-ctwrap-auto > div > div > div.hl-col-xs-12.hl-col-md-70w.hl-col-lg-9 > div > script:nth-child(2)")?.data()?: return@apmap null
            val pattern = """"url":"(.*?)"""".toRegex()
            val matchResult = pattern.find(jsonString)
            val source = matchResult?.groups?.get(1)?.value?.replace("\\", "")?: return@apmap null
            // 检查源是否为 MP4 格式
            if (source.endsWith(".mp4")) {
                // 如果是 MP4 格式，直接通过回调函数返回
                callback(ExtractorLink("Mp4", fetchAndParseApiData(), source, "", 0))
            } else if (source.endsWith(".m3u8")) {
                // 生成 M3U8 链接，并通过回调函数返回
                M3u8Helper.generateM3u8(
                    fetchAndParseApiData(),
                    source,
                    ""
                ).forEach(callback)
            } else return@apmap null
        }
        // 返回 true，表示加载链接成功
        return true
    }
}
