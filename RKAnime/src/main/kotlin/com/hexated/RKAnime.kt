package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import org.json.JSONObject

class RKAnime : MainAPI() {
    override var mainUrl = "https://2rk.cc"
    override var name = "二矿动漫"
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

    override val mainPage = mainPageOf(
        "$mainUrl/all?p=" to "番剧",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val document = app.get(request.data + page).document
        val home = document.select("article.a").mapNotNull {
            it.toSearchResult()
        }
        items.add(HomePageList(request.name, home))
        return newHomePageResponse(items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val img = this.select("section a img")?: return null
        val title = img.attr("title").ifBlank { return null }
        val rname = this.attr("data-text")?: return null
        val posterUrl = img.attr("src").ifBlank { return null }

        val href = this.select("section a").attr("href").ifBlank { return null }
        val status: String = this.select("article > span:nth-child(4)").text()
        val epNum =
            this.select("article > span:nth-child(3)").text().filter { it.isDigit() }.toIntOrNull()
        return newAnimeSearchResponse(name= title, url= href, type= TvType.Anime) {
            this.posterUrl = posterUrl
            // this.otherName = rname
            this.addDubStatus(false, true, epNum, epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?w=$query").document
        return document.select("article.a").mapNotNull {
            it.toSearchResult()
        }
    }
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("article h2").text()
        val poster = document.selectFirst("article img")?.attr("src") ?: return null
        val description = document.selectFirst("section.ah article p")?.text()?: return null
        val tags = document.select("article.o div").dropLast(2).map {
            it.text().trim().trimEnd('/').replace("//+".toRegex(), "/")
        }
        val episodes = mutableListOf<Episode>()
        //获取剧集的个数
        val num = document.select("article.n ul li")?.size
        document.select("article.n ul li")?.forEach {
            val epsTitle = it.select("a").text()
            val link = fixUrl(it.select("a").attr("href"))
            val episode = epsTitle.substringBefore(":").filter { it.isDigit() }.toIntOrNull()
            episodes.add(Episode(link, name = epsTitle.substringAfter(":").trim(), episode = episode))
        }
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
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
        return "$hitokoto  $from"
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
            val jsonString = doc.selectFirst("body > script:nth-child(5)")?.data()?: return@apmap null
            val pattern = """"(http.*?.m3u8)"""".toRegex()
            val matchResult = pattern.find(jsonString)
            val source = matchResult?.groups?.get(1)?.value?.replace("\\", "")?: return@apmap null
            // 检查源是否为 MP4 格式
            if (source.endsWith(".mp4")) {
                // 如果是 MP4 格式，直接通过回调函数返回
                callback(ExtractorLink(url, fetchAndParseApiData(), source, "", 2, false))
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
