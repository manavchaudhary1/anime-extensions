package eu.kanade.tachiyomi.animeextension.en.hentaimama

import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HentaiMama :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "HentaiMama"

    override val baseUrl = "https://hentaimama.io"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)

    // Popular Anime

    override fun popularAnimeSelector(): String = "article.series-card"

    override fun popularAnimeRequest(page: Int): Request = GET(
        if (page == 1) "$baseUrl/hentai-series/?filter=weekly" else "$baseUrl/hentai-series/page/$page/?filter=weekly",
    )

    private fun animeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a.sc-poster, div.poster a")!!.absUrl("href"))
        anime.title = element.select("h3.sc-title a, div.data h3 a").text()
        anime.thumbnail_url = element.selectFirst("a.sc-poster img, div.poster img")?.absUrl("src")
        return anime
    }

    override fun popularAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    override fun popularAnimeNextPageSelector(): String = "a.dt-pg-next"

    // episodes

    override fun episodeListParse(response: Response): List<SEpisode> = super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "#episodes a.dt-se-item"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.absUrl("href"))
        episode.name = element.select(".dt-se-title").text()
        episode.date_upload = SimpleDateFormat("MMM dd, yyyy", Locale.US)
            .tryParse(element.select(".dt-se-date").text())
        episode.episode_number = element.select(".dt-se-num").text().removePrefix("EP ").toFloatOrNull() ?: 1F

        return episode
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val postId = document.selectFirst("#post_report input[name=idpost]")?.attr("value")
            ?: return emptyList()
        val referer = response.request.url.toString()
        val ajaxHeaders = Headers.headersOf("Referer", referer)
        return document.select(".dt-mi-tabs a[href^='#option-']").flatMap { option ->
            val optionId = option.attr("href").substringAfter("#option-").toIntOrNull()
                ?: return@flatMap emptyList()
            val body = FormBody.Builder()
                .add("action", "get_player_contents")
                .add("a", postId)
                .add("i", optionId.toString())
                .build()
            val playerHtml = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, body))
                .execute().parseAs<List<String>>().getOrNull(optionId - 1)
                ?: return@flatMap emptyList()
            val embedUrl = Jsoup.parseBodyFragment(playerHtml, baseUrl)
                .selectFirst("iframe[src]")?.absUrl("src")
                ?: return@flatMap emptyList()
            val embedDocument = client.newCall(GET(embedUrl)).execute().asJsoup()
            val sources = embedDocument.select("script").firstNotNullOfOrNull {
                SOURCES_ARRAY_REGEX.find(it.data())?.groupValues?.get(1)
            }?.parseAs<List<PlayerSource>>() ?: return@flatMap emptyList()

            sources.flatMap { source ->
                val title = listOfNotNull(option.text(), source.label).joinToString(" - ")
                val videoHeaders = Headers.headersOf("Referer", embedUrl)
                val fallback = listOf(
                    Video(source.file, title, source.file, headers = videoHeaders),
                )

                if (source.type.equals("hls", ignoreCase = true) || ".m3u8" in source.file) {
                    runCatching {
                        playlistUtils.extractFromHls(
                            playlistUrl = source.file,
                            referer = embedUrl,
                            videoNameGen = { quality -> "${option.text()} - $quality" },
                        )
                    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: fallback
                } else {
                    fallback
                }
            }
        }
    }

    @Serializable
    private class PlayerSource(
        val file: String,
        val label: String? = null,
        val type: String? = null,
        val default: Boolean? = null,
    )

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val preferredQuality = preferences.getString(PREF_VIDEO_QUALITY_KEY, PREF_VIDEO_QUALITY_DEFAULT)
            ?: PREF_VIDEO_QUALITY_DEFAULT
        val preferredMirror = preferences.getString(PREF_MIRROR_KEY, PREF_MIRROR_DEFAULT)
            ?: PREF_MIRROR_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(preferredQuality, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(preferredMirror, ignoreCase = true) },
        )
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    // Search
    override fun searchAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = "a.dt-pg-next"

    override fun searchAnimeSelector(): String = "article.series-card"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val parameters = getSearchParameters(filters)
        return if (query.isNotEmpty()) {
            GET("$baseUrl/page/$page/?s=${query.replace(Regex("[\\W]"), " ")}") // regular search
        } else {
            GET("$baseUrl/advance-search/page/$page/?$parameters") // filter search
        }
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst(".dt-show-card .dsc-poster img")?.absUrl("src")
        anime.title = document.select(".dt-show-card h1.dsc-title").text()
        anime.genre = document.select(".dt-show-card .dsc-genres a")
            .joinToString(", ") { it.text() }
        anime.description = document.select(".dt-show-card .dsc-desc p").text()
        anime.author = document.select(".dt-show-card .dsc-stat")
            .firstOrNull { it.select("span").text() == "Studio" }
            ?.select("b")?.text()
            ?.takeUnless { it.isBlank() || it == "—" }
        anime.status = if (document.selectFirst(".dt-show-card .dsc-chip.is-airing") != null) {
            SAnime.ONGOING
        } else {
            SAnime.COMPLETED
        }
        return anime
    }

    // Latest

    override fun latestUpdatesSelector(): String = "article.series-card"

    override fun latestUpdatesRequest(page: Int): Request = GET(
        if (page == 1) "$baseUrl/hentai-series/?filter=recent" else "$baseUrl/hentai-series/page/$page/?filter=recent",
    )

    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = "a.dt-pg-next"

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_VIDEO_QUALITY_KEY,
            title = "Preferred Quality",
            entries = PREF_VIDEO_QUALITY_ENTRIES,
            entryValues = PREF_VIDEO_QUALITY_ENTRIES,
            default = PREF_VIDEO_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_MIRROR_KEY,
            title = "Preferred Mirror",
            entries = PREF_MIRROR_ENTRIES,
            entryValues = PREF_MIRROR_ENTRIES,
            default = PREF_MIRROR_DEFAULT,
            summary = "%s",
        )
    }

    // Filters

    internal class Genre(val id: String) : AnimeFilter.CheckBox(id)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Genre", genres)
    private fun getGenres() = listOf(
        Genre("3D"),
        Genre("Action"),
        Genre("Adventure"),
        Genre("Ahegao"),
        Genre("Anal"),
        Genre("Animal Ears"),
        Genre("Beastiality"),
        Genre("Blackmail"),
        Genre("Blowjob"),
        Genre("Bondage"),
        Genre("Brainwashed"),
        Genre("Bukakke"),
        Genre("Cat Girl"),
        Genre("Comedy"),
        Genre("Cosplay"),
        Genre("Creampie"),
        Genre("Cross-dressing"),
        Genre("Dark Skin"),
        Genre("DeepThroat"),
        Genre("Demons"),
        Genre("Doctor"),
        Genre("Double Penatration"),
        Genre("Drama"),
        Genre("Dubbed"),
        Genre("Ecchi"),
        Genre("Elf"),
        Genre("Eroge"),
        Genre("Facesitting"),
        Genre("Facial"),
        Genre("Fantasy"),
        Genre("Female Doctor"),
        Genre("Female Teacher"),
        Genre("Femdom"),
        Genre("Footjob"),
        Genre("Futanari"),
        Genre("Gangbang"),
        Genre("Gore"),
        Genre("Gyaru"),
        Genre("Harem"),
        Genre("Historical"),
        Genre("Horny Slut"),
        Genre("Housewife"),
        Genre("Humiliation"),
        Genre("Incest"),
        Genre("Inflation"),
        Genre("Internal Cumshot"),
        Genre("Lactation"),
        Genre("Large Breasts"),
        Genre("Lolicon"),
        Genre("Magical Girls"),
        Genre("Maid"),
        Genre("Martial Arts"),
        Genre("Megane"),
        Genre("MILF"),
        Genre("Mind Break"),
        Genre("Molestation"),
        Genre("Non-Japanese"),
        Genre("NTR"),
        Genre("Nuns"),
        Genre("Nurses"),
        Genre("Office Ladies"),
        Genre("Police"),
        Genre("POV"),
        Genre("Pregnant"),
        Genre("Princess"),
        Genre("Public Sex"),
        Genre("Rape"),
        Genre("Rim job"),
        Genre("Romance"),
        Genre("Scat"),
        Genre("School Girls"),
        Genre("Sci-Fi"),
        Genre("Shimapan"),
        Genre("Short"),
        Genre("Shoutacon"),
        Genre("Slaves"),
        Genre("Sports"),
        Genre("Squirting"),
        Genre("Stocking"),
        Genre("Strap-on"),
        Genre("Strapped On"),
        Genre("Succubus"),
        Genre("Super Power"),
        Genre("Supernatural"),
        Genre("Swimsuit"),
        Genre("Tentacles"),
        Genre("Three some"),
        Genre("Tits Fuck"),
        Genre("Torture"),
        Genre("Toys"),
        Genre("Train Molestation"),
        Genre("Tsundere"),
        Genre("Uncensored"),
        Genre("Urination"),
        Genre("Vampire"),
        Genre("Vanilla"),
        Genre("Virgins"),
        Genre("Widow"),
        Genre("X-Ray"),
        Genre("Yuri"),
    )

    internal class Year(val id: String) : AnimeFilter.CheckBox(id)
    private class YearList(years: List<Year>) : AnimeFilter.Group<Year>("Year", years)
    private fun getYears(): List<Year> {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        return (currentYear downTo 1987).map { Year(it.toString()) }
    }

    internal class Producer(val id: String) : AnimeFilter.CheckBox(id)
    private class ProducerList(producers: List<Producer>) : AnimeFilter.Group<Producer>("Producer", producers)
    private fun getProducer() = listOf(
        Producer("8bit"),
        Producer("Actas"),
        Producer("Active"),
        Producer("AIC"),
        Producer("AIC A.S.T.A."),
        Producer("Alice Soft"),
        Producer("An DerCen"),
        Producer("Angelfish"),
        Producer("Animac"),
        Producer("AniMan"),
        Producer("Animax"),
        Producer("Antechinus"),
        Producer("APPP"),
        Producer("Armor"),
        Producer("Arms"),
        Producer("Asahi Production"),
        Producer("AT-2"),
        Producer("Blue Eyes"),
        Producer("BOMB! CUTE! BOMB!"),
        Producer("BOOTLEG"),
        Producer("Bunnywalker"),
        Producer("Central Park Media"),
        Producer("CherryLips"),
        Producer("ChiChinoya"),
        Producer("Chippai"),
        Producer("ChuChu"),
        Producer("Circle Tribute"),
        Producer("CLOCKUP"),
        Producer("Collaboration Works"),
        Producer("Comic Media"),
        Producer("Cosmic Ray"),
        Producer("Cosmo"),
        Producer("Cotton Doll"),
        Producer("Cranberry"),
        Producer("D3"),
        Producer("Daiei"),
        Producer("Digital Works"),
        Producer("Discovery"),
        Producer("Dream Force"),
        Producer("Dubbed"),
        Producer("Easy Film"),
        Producer("Echo"),
        Producer("EDGE"),
        Producer("Filmlink International"),
        Producer("Five Ways"),
        Producer("Front Line"),
        Producer("Frontier Works"),
        Producer("Godoy"),
        Producer("Gold Bear"),
        Producer("Green Bunny"),
        Producer("Himajin Planning"),
        Producer("Hokiboshi"),
        Producer("Hoods Entertainment"),
        Producer("Horipro"),
        Producer("Hot Bear"),
        Producer("HydraFXX"),
        Producer("Innocent Grey"),
        Producer("Jam"),
        Producer("JapanAnime"),
        Producer("King Bee"),
        Producer("Kitty Films"),
        Producer("Kitty Media"),
        Producer("Knack Productions"),
        Producer("KSS"),
        Producer("Lemon Heart"),
        Producer("Lune Pictures"),
        Producer("Majin"),
        Producer("Marvelous Entertainment"),
        Producer("Mary Jane"),
        Producer("Media"),
        Producer("Media Blasters"),
        Producer("Milkshake"),
        Producer("Mitsu"),
        Producer("Moonstone Cherry"),
        Producer("Mousou Senka"),
        Producer("MS Pictures"),
        Producer("Nihikime no Dozeu"),
        Producer("Nur"),
        Producer("NuTech Digital"),
        Producer("Obtain Future"),
        Producer("Office Take Off"),
        Producer("OLE-M"),
        Producer("Oriental Light and Magic"),
        Producer("Oz"),
        Producer("Pashmina"),
        Producer("Pink Pineapple"),
        Producer("Pixy"),
        Producer("PoRO"),
        Producer("Production I.G"),
        Producer("Queen Bee"),
        Producer("Sakura Purin Animation"),
        Producer("Schoolzone"),
        Producer("Selfish"),
        Producer("Seven"),
        Producer("Shelf"),
        Producer("Shinkuukan"),
        Producer("Shinyusha"),
        Producer("Shouten"),
        Producer("Silky’s"),
        Producer("Soft Garage"),
        Producer("SoftCel Pictures"),
        Producer("SPEED"),
        Producer("Studio 9 Maiami"),
        Producer("Studio Eromatick"),
        Producer("Studio Fantasia"),
        Producer("Studio Jack"),
        Producer("Studio Kyuuma"),
        Producer("Studio Matrix"),
        Producer("Studio Sign"),
        Producer("Studio Tulip"),
        Producer("Studio Unicorn"),
        Producer("Suzuki Mirano"),
        Producer("T-Rex"),
        Producer("The Right Stuf International"),
        Producer("Toho Company"),
        Producer("Top-Marschal"),
        Producer("Toranoana"),
        Producer("Toshiba Entertainment"),
        Producer("Triangle Bitter"),
        Producer("Triple X"),
        Producer("Union Cho"),
        Producer("Valkyria"),
        Producer("White Bear"),
        Producer("Y.O.U.C"),
        Producer("ZIZ Entertainment"),
        Producer("Zyc"),

    )

    private data class Order(val name: String, val id: String)
    private class OrderList(Orders: Array<String>) : AnimeFilter.Select<String>("Order", Orders)
    private val orderName = getOrder().map {
        it.name
    }.toTypedArray()
    private fun getOrder() = listOf(
        Order("Weekly Views", "weekly"),
        Order("Monthly Views", "monthly"),
        Order("Alltime Views", "alltime"),
        Order("A-Z", "alphabet"),
        Order("Rating", "rating"),

    )

    private fun getSearchParameters(filters: AnimeFilterList): String {
        var totalstring = ""
        var sortBy = ""

        filters.forEach { filter ->
            when (filter) {
                is GenreList -> { // ---Genre
                    filter.state.forEach { Genre ->
                        if (Genre.state) {
                            totalstring =
                                totalstring + "&genres_filter%5B" + "%5D=" + Genre.id
                        }
                    }
                }

                is YearList -> { // ---Year
                    filter.state.forEach { Year ->
                        if (Year.state) {
                            totalstring =
                                totalstring + "&years_filter%5B" + "%5D=" + Year.id
                        }
                    }
                }

                is ProducerList -> { // ---Producer
                    filter.state.forEach { Producer ->
                        if (Producer.state) {
                            totalstring =
                                totalstring + "&studios_filter%5B" + "%5D=" + Producer.id
                        }
                    }
                }

                is OrderList -> { // ---Order
                    sortBy = getOrder()[filter.state].id
                }

                else -> {}
            }
        }

        return "$totalstring&submit=Submit&filter=$sortBy"
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Ignored if using Text Search"),
        AnimeFilter.Separator(),
        OrderList(orderName),
        GenreList(getGenres()),
        YearList(getYears()),
        ProducerList(getProducer()),
    )

    companion object {
        private const val PREF_VIDEO_QUALITY_KEY = "preferred_video_quality"
        private const val PREF_VIDEO_QUALITY_DEFAULT = "1080p"
        private val PREF_VIDEO_QUALITY_ENTRIES = listOf("1080p", "720p", "480p")
        private const val PREF_MIRROR_KEY = "preferred_quality"
        private const val PREF_MIRROR_DEFAULT = "mi-1"
        private val PREF_MIRROR_ENTRIES = listOf("mi-1", "mi-2", "mi-3")
        private val SOURCES_ARRAY_REGEX = Regex("""sources:\s*(\[.+?])""", RegexOption.DOT_MATCHES_ALL)
    }
}
