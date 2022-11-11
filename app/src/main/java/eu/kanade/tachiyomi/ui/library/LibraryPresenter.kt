package eu.kanade.tachiyomi.ui.library

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastMap
import eu.kanade.core.prefs.CheckboxState
import eu.kanade.core.prefs.PreferenceMutableState
import eu.kanade.domain.UnsortedPreferences
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.category.interactor.GetCategories
import eu.kanade.domain.category.interactor.SetMangaCategories
import eu.kanade.domain.category.model.Category
import eu.kanade.domain.chapter.interactor.GetChapterByMangaId
import eu.kanade.domain.chapter.interactor.GetMergedChapterByMangaId
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.model.Chapter
import eu.kanade.domain.history.interactor.GetNextChapters
import eu.kanade.domain.library.model.LibraryDisplayMode
import eu.kanade.domain.library.model.LibraryGroup
import eu.kanade.domain.library.model.LibraryManga
import eu.kanade.domain.library.model.LibrarySort
import eu.kanade.domain.library.model.display
import eu.kanade.domain.library.model.sort
import eu.kanade.domain.library.service.LibraryPreferences
import eu.kanade.domain.manga.interactor.GetIdsOfFavoriteMangaWithMetadata
import eu.kanade.domain.manga.interactor.GetLibraryManga
import eu.kanade.domain.manga.interactor.GetMergedMangaById
import eu.kanade.domain.manga.interactor.GetSearchTags
import eu.kanade.domain.manga.interactor.GetSearchTitles
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.Manga
import eu.kanade.domain.manga.model.MangaUpdate
import eu.kanade.domain.manga.model.isLocal
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.GetTracks
import eu.kanade.domain.track.interactor.GetTracksPerManga
import eu.kanade.domain.track.model.Track
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.library.LibraryState
import eu.kanade.presentation.library.LibraryStateImpl
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.launchNonCancellable
import eu.kanade.tachiyomi.util.lang.withIOContext
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.TriStateGroup.State
import exh.favorites.FavoritesSyncHelper
import exh.md.utils.FollowStatus
import exh.md.utils.MdUtil
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import exh.search.Namespace
import exh.search.QueryComponent
import exh.search.SearchEngine
import exh.search.Text
import exh.source.EH_SOURCE_ID
import exh.source.MERGED_SOURCE_ID
import exh.source.isMetadataSource
import exh.util.cancellable
import exh.util.isLewd
import exh.util.nullIfBlank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.Collator
import java.util.Collections
import java.util.Locale

/**
 * Class containing library information.
 */
private data class Library(val categories: List<Category>, val mangaMap: LibraryMap)

/**
 * Typealias for the library manga, using the category as keys, and list of manga as values.
 */
typealias LibraryMap = Map<Long, List<LibraryItem>>

class LibraryPresenter(
    private val state: LibraryStateImpl = LibraryState() as LibraryStateImpl,
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracksPerManga: GetTracksPerManga = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val getChaptersByMangaId: GetChapterByMangaId = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val preferences: BasePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val trackManager: TrackManager = Injekt.get(),
    // SY -->
    private val unsortedPreferences: UnsortedPreferences = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val searchEngine: SearchEngine = SearchEngine(),
    private val getTracks: GetTracks = Injekt.get(),
    private val customMangaManager: CustomMangaManager = Injekt.get(),
    private val getMergedMangaById: GetMergedMangaById = Injekt.get(),
    private val getMergedChaptersByMangaId: GetMergedChapterByMangaId = Injekt.get(),
    private val getIdsOfFavoriteMangaWithMetadata: GetIdsOfFavoriteMangaWithMetadata = Injekt.get(),
    private val getSearchTags: GetSearchTags = Injekt.get(),
    private val getSearchTitles: GetSearchTitles = Injekt.get(),
    // SY <--
) : BasePresenter<LibraryController>(), LibraryState by state {

    private var loadedManga by mutableStateOf(emptyMap<Long, List<LibraryItem>>())

    val isLibraryEmpty by derivedStateOf { loadedManga.isEmpty() }

    val tabVisibility by libraryPreferences.categoryTabs().asState()
    val mangaCountVisibility by libraryPreferences.categoryNumberOfItems().asState()

    val showDownloadBadges by libraryPreferences.downloadBadge().asState()
    val showUnreadBadges by libraryPreferences.unreadBadge().asState()
    val showLocalBadges by libraryPreferences.localBadge().asState()
    val showLanguageBadges by libraryPreferences.languageBadge().asState()

    var activeCategory: Int by libraryPreferences.lastUsedCategory().asState()

    val showContinueReadingButton by libraryPreferences.showContinueReadingButton().asState()

    val isDownloadOnly: Boolean by preferences.downloadedOnly().asState()
    val isIncognitoMode: Boolean by preferences.incognitoMode().asState()

    private val _filterChanges: Channel<Unit> = Channel(Int.MAX_VALUE)
    private val filterChanges = _filterChanges.receiveAsFlow().onStart { emit(Unit) }

    private var librarySubscription: Job? = null

    // SY -->
    val favoritesSync = FavoritesSyncHelper(preferences.context)

    private val loggedServices by lazy { trackManager.services.filter { it.isLogged } }

    private val services by lazy {
        trackManager.services.associate { service ->
            service.id to preferences.context.getString(service.nameRes())
        }
    }
    // SY <--

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        // SY -->
        combine(
            unsortedPreferences.isHentaiEnabled().changes(),
            sourcePreferences.disabledSources().changes(),
            unsortedPreferences.enableExhentai().changes(),
        ) { isHentaiEnabled, disabledSources, enableExhentai ->
            state.showSyncExh = isHentaiEnabled && (EH_SOURCE_ID.toString() !in disabledSources || enableExhentai)
        }.flowOn(Dispatchers.IO).launchIn(presenterScope)
        // SY <--

        subscribeLibrary()
    }

    fun subscribeLibrary() {
        /**
         * TODO:
         * - Move filter and sort to getMangaForCategory and only filter and sort the current display category instead of whole library as some has 5000+ items in the library
         * - Create new db view and new query to just fetch the current category save as needed to instance variable
         * - Fetch badges to maps and retrieve as needed instead of fetching all of them at once
         */
        if (librarySubscription == null || librarySubscription!!.isCancelled) {
            librarySubscription = presenterScope.launchIO {
                combine(
                    getLibraryFlow(),
                    getTracksPerManga.subscribe(),
                    filterChanges,
                    // SY -->
                    libraryPreferences.groupLibraryBy().changes(),
                    libraryPreferences.librarySortingMode().changes(),
                    // SY <--
                ) { library, tracks, _, _, _ ->
                    library.mangaMap
                        // SY -->
                        .applyGrouping(library.categories).let {
                            it.copy(mangaMap = it.mangaMap.applyFilters(tracks).applySort(it.categories))
                        }
                    // SY <--
                }
                    .collectLatest {
                        // SY -->
                        state.groupType = libraryPreferences.groupLibraryBy().get()
                        state.categories = it.categories
                        // SY <--
                        state.isLoading = false
                        loadedManga = /* SY --> */ it.mangaMap /* SY <-- */
                    }
            }
        }
    }

    /**
     * Applies library filters to the given map of manga.
     */
    private fun LibraryMap.applyFilters(trackMap: Map<Long, List<Long>>): LibraryMap {
        val downloadedOnly = preferences.downloadedOnly().get()
        val filterDownloaded = libraryPreferences.filterDownloaded().get()
        val filterUnread = libraryPreferences.filterUnread().get()
        val filterStarted = libraryPreferences.filterStarted().get()
        val filterBookmarked = libraryPreferences.filterBookmarked().get()
        val filterCompleted = libraryPreferences.filterCompleted().get()

        val loggedInTrackServices = trackManager.services.filter { trackService -> trackService.isLogged }
            .associate { trackService ->
                trackService.id to libraryPreferences.filterTracking(trackService.id.toInt()).get()
            }
        val isNotLoggedInAnyTrack = loggedInTrackServices.isEmpty()

        val excludedTracks = loggedInTrackServices.mapNotNull { if (it.value == State.EXCLUDE.value) it.key else null }
        val includedTracks = loggedInTrackServices.mapNotNull { if (it.value == State.INCLUDE.value) it.key else null }
        val trackFiltersIsIgnored = includedTracks.isEmpty() && excludedTracks.isEmpty()

        // SY -->
        val filterLewd = libraryPreferences.filterLewd().get()
        // SY <--

        val filterFnDownloaded: (LibraryItem) -> Boolean = downloaded@{ item ->
            if (!downloadedOnly && filterDownloaded == State.IGNORE.value) return@downloaded true
            val isDownloaded = when {
                item.libraryManga.manga.isLocal() -> true
                item.downloadCount != -1L -> item.downloadCount > 0
                else -> downloadManager.getDownloadCount(item.libraryManga.manga) > 0
            }

            return@downloaded if (downloadedOnly || filterDownloaded == State.INCLUDE.value) {
                isDownloaded
            } else {
                !isDownloaded
            }
        }

        val filterFnUnread: (LibraryItem) -> Boolean = unread@{ item ->
            if (filterUnread == State.IGNORE.value) return@unread true
            val isUnread = item.libraryManga.unreadCount > 0

            return@unread if (filterUnread == State.INCLUDE.value) {
                isUnread
            } else {
                !isUnread
            }
        }

        val filterFnStarted: (LibraryItem) -> Boolean = started@{ item ->
            if (filterStarted == State.IGNORE.value) return@started true
            val hasStarted = item.libraryManga.hasStarted

            return@started if (filterStarted == State.INCLUDE.value) {
                hasStarted
            } else {
                !hasStarted
            }
        }

        val filterFnBookmarked: (LibraryItem) -> Boolean = bookmarked@{ item ->
            if (filterBookmarked == State.IGNORE.value) return@bookmarked true

            val hasBookmarks = item.libraryManga.hasBookmarks

            return@bookmarked if (filterBookmarked == State.INCLUDE.value) {
                hasBookmarks
            } else {
                !hasBookmarks
            }
        }

        val filterFnCompleted: (LibraryItem) -> Boolean = completed@{ item ->
            if (filterCompleted == State.IGNORE.value) return@completed true
            val isCompleted = item.libraryManga.manga.status.toInt() == SManga.COMPLETED

            return@completed if (filterCompleted == State.INCLUDE.value) {
                isCompleted
            } else {
                !isCompleted
            }
        }

        val filterFnTracking: (LibraryItem) -> Boolean = tracking@{ item ->
            if (isNotLoggedInAnyTrack || trackFiltersIsIgnored) return@tracking true

            val mangaTracks = trackMap[item.libraryManga.id].orEmpty()

            val exclude = mangaTracks.filter { it in excludedTracks }
            val include = mangaTracks.filter { it in includedTracks }

            // TODO: Simplify the filter logic
            if (includedTracks.isNotEmpty() && excludedTracks.isNotEmpty()) {
                return@tracking if (exclude.isNotEmpty()) false else include.isNotEmpty()
            }

            if (excludedTracks.isNotEmpty()) return@tracking exclude.isEmpty()

            if (includedTracks.isNotEmpty()) return@tracking include.isNotEmpty()

            return@tracking false
        }

        // SY -->
        val filterFnLewd: (LibraryItem) -> Boolean = lewd@{ item ->
            if (filterLewd == State.IGNORE.value) return@lewd true
            val isLewd = item.libraryManga.manga.isLewd()

            return@lewd if (filterLewd == State.INCLUDE.value) {
                isLewd
            } else {
                !isLewd
            }
        }
        // SY <--

        val filterFn: (LibraryItem) -> Boolean = filter@{ item ->
            return@filter !(
                !filterFnDownloaded(item) ||
                    !filterFnUnread(item) ||
                    !filterFnStarted(item) ||
                    !filterFnBookmarked(item) ||
                    !filterFnCompleted(item) ||
                    !filterFnTracking(item) ||
                    // SY -->
                    !filterFnLewd(item)
                // SY <--
                )
        }

        return this.mapValues { entry -> entry.value.filter(filterFn) }
    }

    /**
     * Applies library sorting to the given map of manga.
     */
    private fun LibraryMap.applySort(categories: List<Category>): LibraryMap {
        // SY -->
        val listOfTags by lazy {
            libraryPreferences.sortTagsForLibrary().get()
                .asSequence()
                .mapNotNull {
                    val list = it.split("|")
                    (list.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null) to (list.getOrNull(1) ?: return@mapNotNull null)
                }
                .sortedBy { it.first }
                .map { it.second }
                .toList()
        }
        // SY <--

        // SY -->
        val groupType = libraryPreferences.groupLibraryBy().get()
        val groupSort = libraryPreferences.librarySortingMode().get()
        // SY <--
        val sortModes = categories.associate { category ->
            // SY -->
            category.id to category.sort
            // SY <--
        }

        val locale = Locale.getDefault()
        val collator = Collator.getInstance(locale).apply {
            strength = Collator.PRIMARY
        }
        val sortAlphabetically: (LibraryItem, LibraryItem) -> Int = { i1, i2 ->
            collator.compare(i1.libraryManga.manga.title.lowercase(locale), i2.libraryManga.manga.title.lowercase(locale))
        }

        val sortFn: (LibraryItem, LibraryItem) -> Int = { i1, i2 ->
            // SY -->
            val sort = when (groupType) {
                LibraryGroup.BY_DEFAULT -> sortModes[i1.libraryManga.category]!!
                else -> groupSort
            }
            // SY <--
            when (sort.type) {
                LibrarySort.Type.Alphabetical -> {
                    sortAlphabetically(i1, i2)
                }
                LibrarySort.Type.LastRead -> {
                    i1.libraryManga.lastRead.compareTo(i2.libraryManga.lastRead)
                }
                LibrarySort.Type.LastUpdate -> {
                    i1.libraryManga.manga.lastUpdate.compareTo(i2.libraryManga.manga.lastUpdate)
                }
                LibrarySort.Type.UnreadCount -> when {
                    // Ensure unread content comes first
                    i1.libraryManga.unreadCount == i2.libraryManga.unreadCount -> 0
                    i1.libraryManga.unreadCount == 0L -> if (sort.isAscending) 1 else -1
                    i2.libraryManga.unreadCount == 0L -> if (sort.isAscending) -1 else 1
                    else -> i1.libraryManga.unreadCount.compareTo(i2.libraryManga.unreadCount)
                }
                LibrarySort.Type.TotalChapters -> {
                    i1.libraryManga.totalChapters.compareTo(i2.libraryManga.totalChapters)
                }
                LibrarySort.Type.LatestChapter -> {
                    i1.libraryManga.latestUpload.compareTo(i2.libraryManga.latestUpload)
                }
                LibrarySort.Type.ChapterFetchDate -> {
                    i1.libraryManga.chapterFetchedAt.compareTo(i2.libraryManga.chapterFetchedAt)
                }
                LibrarySort.Type.DateAdded -> {
                    i1.libraryManga.manga.dateAdded.compareTo(i2.libraryManga.manga.dateAdded)
                }
                // SY -->
                LibrarySort.Type.TagList -> {
                    val manga1IndexOfTag = listOfTags.indexOfFirst { i1.libraryManga.manga.genre?.contains(it) ?: false }
                    val manga2IndexOfTag = listOfTags.indexOfFirst { i2.libraryManga.manga.genre?.contains(it) ?: false }
                    manga1IndexOfTag.compareTo(manga2IndexOfTag)
                }
                // SY <--
                else -> throw IllegalStateException("Invalid SortModeSetting: ${sort.type}")
            }
        }

        return this.mapValues { entry ->
            // SY -->
            val isAscending = if (groupType == LibraryGroup.BY_DEFAULT) {
                sortModes[entry.key]!!.isAscending
            } else {
                groupSort.isAscending
            }
            // SY <--
            val comparator = if ( /* SY --> */ isAscending /* SY <-- */) {
                Comparator(sortFn)
            } else {
                Collections.reverseOrder(sortFn)
            }

            entry.value.sortedWith(comparator.thenComparator(sortAlphabetically))
        }
    }

    /**
     * Get the categories and all its manga from the database.
     *
     * @return an observable of the categories and its manga.
     */
    private fun getLibraryFlow(): Flow<Library> {
        val libraryMangasFlow = combine(
            getLibraryManga.subscribe(),
            libraryPreferences.downloadBadge().changes(),
            libraryPreferences.filterDownloaded().changes(),
            preferences.downloadedOnly().changes(),
            downloadCache.changes,
        ) { libraryMangaList, downloadBadgePref, filterDownloadedPref, downloadedOnly, _ ->
            libraryMangaList
                .map { libraryManga ->
                    val needsDownloadCounts = downloadBadgePref ||
                        filterDownloadedPref != State.IGNORE.value ||
                        downloadedOnly

                    // Display mode based on user preference: take it from global library setting or category
                    LibraryItem(libraryManga).apply {
                        downloadCount = if (needsDownloadCounts) {
                            // SY -->
                            if (libraryManga.manga.source == MERGED_SOURCE_ID) {
                                runBlocking {
                                    getMergedMangaById.await(libraryManga.manga.id)
                                }.sumOf { downloadManager.getDownloadCount(it) }.toLong()
                            } else {
                                downloadManager.getDownloadCount(libraryManga.manga).toLong()
                            }
                            // SY <--
                        } else {
                            0
                        }
                        unreadCount = libraryManga.unreadCount
                        isLocal = libraryManga.manga.isLocal()
                        sourceLanguage = sourceManager.getOrStub(libraryManga.manga.source).lang
                    }
                }
                .groupBy { it.libraryManga.category }
        }

        return combine(getCategories.subscribe(), libraryMangasFlow) { categories, libraryManga ->
            val displayCategories = if (libraryManga.isNotEmpty() && libraryManga.containsKey(0).not()) {
                categories.filterNot { it.isSystemCategory }
            } else {
                categories
            }

            // SY -->
            state.ogCategories = displayCategories
            // SY <--
            Library(displayCategories, libraryManga)
        }
    }

    // SY -->
    private fun LibraryMap.applyGrouping(categories: List<Category>): Library {
        val groupType = libraryPreferences.groupLibraryBy().get()
        var editedCategories = categories
        val items = when (groupType) {
            LibraryGroup.BY_DEFAULT -> this
            LibraryGroup.UNGROUPED -> {
                editedCategories = listOf(Category(0, "All", 0, 0))
                mapOf(
                    0L to this.values.flatten().distinctBy { it.libraryManga.manga.id },
                )
            }
            else -> {
                val (items, customCategories) = getGroupedMangaItems(
                    groupType = groupType,
                    libraryManga = this.values.flatten().distinctBy { it.libraryManga.manga.id },
                )
                editedCategories = customCategories
                items
            }
        }

        return Library(editedCategories, items)
    }
    // SY <--

    /**
     * Requests the library to be filtered.
     */
    suspend fun requestFilterUpdate() = withIOContext {
        _filterChanges.send(Unit)
    }

    /**
     * Called when a manga is opened.
     */
    fun onOpenManga() {
        // Avoid further db updates for the library when it's not needed
        librarySubscription?.cancel()
    }

    /**
     * Returns the common categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    suspend fun getCommonCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        return mangas
            .map { getCategories.await(it.id).toSet() }
            .reduce { set1, set2 -> set1.intersect(set2) }
    }

    suspend fun getNextUnreadChapter(manga: Manga): Chapter? {
        return getChaptersByMangaId.await(manga.id).getNextUnread(manga, downloadManager)
    }

    /**
     * Returns the mix (non-common) categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    suspend fun getMixCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        val mangaCategories = mangas.map { getCategories.await(it.id).toSet() }
        val common = mangaCategories.reduce { set1, set2 -> set1.intersect(set2) }
        return mangaCategories.flatten().distinct().subtract(common)
    }

    /**
     * Queues the amount specified of unread chapters from the list of mangas given.
     *
     * @param mangas the list of manga.
     * @param amount the amount to queue or null to queue all
     */
    fun downloadUnreadChapters(mangas: List<Manga>, amount: Int?) {
        presenterScope.launchNonCancellable {
            mangas.forEach { manga ->
                // SY -->
                if (manga.source == MERGED_SOURCE_ID) {
                    val mergedMangas = getMergedMangaById.await(manga.id)
                        .associateBy { it.id }
                    getNextChapters.await(manga.id)
                        .let { if (amount != null) it.take(amount) else it }
                        .groupBy { it.mangaId }
                        .forEach ab@{ (mangaId, chapters) ->
                            val mergedManga = mergedMangas[mangaId] ?: return@ab
                            val downloadChapters = chapters.filterNot { chapter ->
                                downloadManager.queue.any { chapter.id == it.chapter.id } ||
                                    downloadManager.isChapterDownloaded(
                                        chapter.name,
                                        chapter.scanlator,
                                        mergedManga.ogTitle,
                                        mergedManga.source,
                                    )
                            }

                            downloadManager.downloadChapters(mergedManga, downloadChapters)
                        }

                    return@forEach
                }

                // SY <--
                val chapters = getNextChapters.await(manga.id)
                    .filterNot { chapter ->
                        downloadManager.queue.any { chapter.id == it.chapter.id } ||
                            downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                // SY -->
                                manga.ogTitle,
                                // SY <--
                                manga.source,

                            )
                    }
                    .let { if (amount != null) it.take(amount) else it }

                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    // SY -->
    fun cleanTitles(mangas: List<LibraryManga>) {
        mangas.forEach { (manga) ->
            val editedTitle = manga.title.replace("\\[.*?]".toRegex(), "").trim().replace("\\(.*?\\)".toRegex(), "").trim().replace("\\{.*?\\}".toRegex(), "").trim().let {
                if (it.contains("|")) {
                    it.replace(".*\\|".toRegex(), "").trim()
                } else {
                    it
                }
            }
            if (manga.title == editedTitle) return@forEach
            val mangaJson = CustomMangaManager.MangaJson(
                id = manga.id,
                title = editedTitle.nullIfBlank(),
                author = manga.author.takeUnless { it == manga.ogAuthor },
                artist = manga.artist.takeUnless { it == manga.ogArtist },
                description = manga.description.takeUnless { it == manga.ogDescription },
                genre = manga.genre.takeUnless { it == manga.ogGenre },
                status = manga.status.takeUnless { it == manga.ogStatus }?.toLong(),
            )

            customMangaManager.saveMangaInfo(mangaJson)
        }
    }

    fun syncMangaToDex(mangaList: List<LibraryManga>) {
        launchIO {
            MdUtil.getEnabledMangaDex(unsortedPreferences, sourcePreferences, sourceManager)?.let { mdex ->
                mangaList.forEach { (manga) ->
                    mdex.updateFollowStatus(MdUtil.getMangaId(manga.url), FollowStatus.READING)
                }
            }
        }
    }
    // SY <--

    /**
     * Marks mangas' chapters read status.
     *
     * @param mangas the list of manga.
     */
    fun markReadStatus(mangas: List<Manga>, read: Boolean) {
        presenterScope.launchNonCancellable {
            mangas.forEach { manga ->
                setReadStatus.await(
                    manga = manga,
                    read = read,
                )
            }
        }
    }

    /**
     * Remove the selected manga.
     *
     * @param mangaList the list of manga to delete.
     * @param deleteFromLibrary whether to delete manga from library.
     * @param deleteChapters whether to delete downloaded chapters.
     */
    fun removeMangas(mangaList: List<Manga>, deleteFromLibrary: Boolean, deleteChapters: Boolean) {
        presenterScope.launchNonCancellable {
            val mangaToDelete = mangaList.distinctBy { it.id }

            if (deleteFromLibrary) {
                val toDelete = mangaToDelete.map {
                    it.removeCovers(coverCache)
                    MangaUpdate(
                        favorite = false,
                        id = it.id,
                    )
                }
                updateManga.awaitAll(toDelete)
            }

            if (deleteChapters) {
                mangaToDelete.forEach { manga ->
                    val source = sourceManager.get(manga.source) as? HttpSource
                    if (source != null) {
                        if (source is MergedSource) {
                            val mergedMangas = getMergedMangaById.await(manga.id)
                            val sources = mergedMangas.distinctBy { it.source }.map { sourceManager.getOrStub(it.source) }
                            mergedMangas.forEach merge@{ mergedManga ->
                                val mergedSource = sources.firstOrNull { mergedManga.source == it.id } as? HttpSource ?: return@merge
                                downloadManager.deleteManga(mergedManga, mergedSource)
                            }
                        } else {
                            downloadManager.deleteManga(manga, source)
                        }
                    }
                }
            }
        }
    }

    /**
     * Bulk update categories of manga using old and new common categories.
     *
     * @param mangaList the list of manga to move.
     * @param addCategories the categories to add for all mangas.
     * @param removeCategories the categories to remove in all mangas.
     */
    fun setMangaCategories(mangaList: List<Manga>, addCategories: List<Long>, removeCategories: List<Long>) {
        presenterScope.launchNonCancellable {
            mangaList.forEach { manga ->
                val categoryIds = getCategories.await(manga.id)
                    .map { it.id }
                    .subtract(removeCategories.toSet())
                    .plus(addCategories)
                    .toList()

                setMangaCategories.await(manga.id, categoryIds)
            }
        }
    }

    @Composable
    fun getMangaCountForCategory(categoryId: Long): androidx.compose.runtime.State<Int?> {
        return produceState<Int?>(initialValue = null, loadedManga) {
            value = loadedManga[categoryId]?.size
        }
    }

    fun getColumnsPreferenceForCurrentOrientation(isLandscape: Boolean): PreferenceMutableState<Int> {
        return (if (isLandscape) libraryPreferences.landscapeColumns() else libraryPreferences.portraitColumns()).asState()
    }

    // TODO: This is good but should we separate title from count or get categories with count from db
    @Composable
    fun getToolbarTitle(): androidx.compose.runtime.State<LibraryToolbarTitle> {
        val context = LocalContext.current
        val category = categories.getOrNull(activeCategory)

        val defaultTitle = stringResource(R.string.label_library)
        val categoryName = category?.visualName ?: defaultTitle

        val default = remember { LibraryToolbarTitle(defaultTitle) }

        return produceState(initialValue = default, category, loadedManga, mangaCountVisibility, tabVisibility, groupType, context) {
            val title = if (tabVisibility.not()) {
                getCategoryName(context, category, groupType, categoryName)
            } else {
                defaultTitle
            }
            val count = when {
                category == null || mangaCountVisibility.not() -> null
                tabVisibility.not() -> loadedManga[category.id]?.size
                else -> loadedManga.values.flatten().distinctBy { it.libraryManga.manga.id }.size
            }

            value = when (category) {
                null -> default
                else -> LibraryToolbarTitle(title, count)
            }
        }
    }

    fun getCategoryName(
        context: Context,
        category: Category?,
        groupType: Int,
        categoryName: String,
    ): String {
        return when (groupType) {
            LibraryGroup.BY_STATUS -> when (category?.id) {
                SManga.ONGOING.toLong() -> context.getString(R.string.ongoing)
                SManga.LICENSED.toLong() -> context.getString(R.string.licensed)
                SManga.CANCELLED.toLong() -> context.getString(R.string.cancelled)
                SManga.ON_HIATUS.toLong() -> context.getString(R.string.on_hiatus)
                SManga.PUBLISHING_FINISHED.toLong() -> context.getString(R.string.publishing_finished)
                SManga.COMPLETED.toLong() -> context.getString(R.string.completed)
                else -> context.getString(R.string.unknown)
            }
            LibraryGroup.BY_SOURCE -> if (category?.id == LocalSource.ID) {
                context.getString(R.string.local_source)
            } else {
                categoryName
            }
            LibraryGroup.BY_TRACK_STATUS -> TrackStatus.values()
                .find { it.int.toLong() == category?.id }
                .let { it ?: TrackStatus.OTHER }
                .let { context.getString(it.res) }
            LibraryGroup.UNGROUPED -> context.getString(R.string.ungrouped)
            else -> categoryName
        }
    }

    // SY -->
    @Composable
    fun getMangaForCategory(page: Int): List<LibraryItem> {
        val unfiltered = remember(categories, loadedManga, page) {
            val categoryId = categories.getOrNull(page)?.id ?: -1
            loadedManga[categoryId] ?: emptyList()
        }

        val items = produceState(initialValue = unfiltered, unfiltered, searchQuery) {
            val query = searchQuery
            value = withIOContext {
                if (unfiltered.isNotEmpty() && !query.isNullOrBlank()) {
                    // Prepare filter object
                    val parsedQuery = searchEngine.parseQuery(query)
                    val mangaWithMetaIds = getIdsOfFavoriteMangaWithMetadata.await()
                    val tracks = if (loggedServices.isNotEmpty()) {
                        getTracks.await(unfiltered.map { it.libraryManga.manga.id }.distinct())
                    } else {
                        emptyMap()
                    }
                    val sources = unfiltered
                        .distinctBy { it.libraryManga.manga.source }
                        .mapNotNull { sourceManager.get(it.libraryManga.manga.source) }
                        .associateBy { it.id }
                    unfiltered.asFlow().cancellable().filter { item ->
                        val mangaId = item.libraryManga.manga.id
                        val sourceId = item.libraryManga.manga.source
                        if (isMetadataSource(sourceId)) {
                            if (mangaWithMetaIds.binarySearch(mangaId) < 0) {
                                // No meta? Filter using title
                                filterManga(
                                    queries = parsedQuery,
                                    libraryManga = item.libraryManga,
                                    tracks = tracks[mangaId],
                                    source = sources[sourceId],
                                )
                            } else {
                                val tags = getSearchTags.await(mangaId)
                                val titles = getSearchTitles.await(mangaId)
                                filterManga(
                                    queries = parsedQuery,
                                    libraryManga = item.libraryManga,
                                    tracks = tracks[mangaId],
                                    source = sources[sourceId],
                                    checkGenre = false,
                                    searchTags = tags,
                                    searchTitles = titles,
                                )
                            }
                        } else {
                            filterManga(
                                queries = parsedQuery,
                                libraryManga = item.libraryManga,
                                tracks = tracks[mangaId],
                                source = sources[sourceId],
                            )
                        }
                    }.toList()
                } else {
                    unfiltered
                }
            }
        }

        return items.value
    }

    private fun filterManga(
        queries: List<QueryComponent>,
        libraryManga: LibraryManga,
        tracks: List<Track>?,
        source: Source?,
        checkGenre: Boolean = true,
        searchTags: List<SearchTag>? = null,
        searchTitles: List<SearchTitle>? = null,
    ): Boolean {
        val manga = libraryManga.manga
        val sourceIdString = manga.source.takeUnless { it == LocalSource.ID }?.toString()
        val genre = if (checkGenre) manga.genre.orEmpty() else emptyList()
        return queries.all { queryComponent ->
            when (queryComponent.excluded) {
                false -> when (queryComponent) {
                    is Text -> {
                        val query = queryComponent.asQuery()
                        manga.title.contains(query, true) ||
                            (manga.author?.contains(query, true) == true) ||
                            (manga.artist?.contains(query, true) == true) ||
                            (manga.description?.contains(query, true) == true) ||
                            (source?.name?.contains(query, true) == true) ||
                            (sourceIdString != null && sourceIdString == query) ||
                            (loggedServices.isNotEmpty() && tracks != null && filterTracks(query, tracks)) ||
                            (genre.any { it.contains(query, true) }) ||
                            (searchTags?.any { it.name.contains(query, true) } == true) ||
                            (searchTitles?.any { it.title.contains(query, true) } == true)
                    }
                    is Namespace -> {
                        searchTags != null && searchTags.any {
                            val tag = queryComponent.tag
                            (it.namespace.equals(queryComponent.namespace, true) && tag?.run { it.name.contains(tag.asQuery(), true) } == true) ||
                                (tag == null && it.namespace.equals(queryComponent.namespace, true))
                        }
                    }
                    else -> true
                }
                true -> when (queryComponent) {
                    is Text -> {
                        val query = queryComponent.asQuery()
                        query.isBlank() || (
                            (!manga.title.contains(query, true)) &&
                                (manga.author?.contains(query, true) != true) &&
                                (manga.artist?.contains(query, true) != true) &&
                                (manga.description?.contains(query, true) != true) &&
                                (source?.name?.contains(query, true) != true) &&
                                (sourceIdString != null && sourceIdString != query) &&
                                (loggedServices.isEmpty() || loggedServices.isNotEmpty() && tracks == null || tracks != null && !filterTracks(query, tracks)) &&
                                (genre.none { it.contains(query, true) }) &&
                                (searchTags?.any { it.name.contains(query, true) } != true) &&
                                (searchTitles?.any { it.title.contains(query, true) } != true)
                            )
                    }
                    is Namespace -> {
                        val searchedTag = queryComponent.tag?.asQuery()
                        searchTags == null || (queryComponent.namespace.isBlank() && searchedTag.isNullOrBlank()) || searchTags.all { mangaTag ->
                            if (queryComponent.namespace.isBlank() && !searchedTag.isNullOrBlank()) {
                                !mangaTag.name.contains(searchedTag, true)
                            } else if (searchedTag.isNullOrBlank()) {
                                mangaTag.namespace == null || !mangaTag.namespace.equals(queryComponent.namespace, true)
                            } else if (mangaTag.namespace.isNullOrBlank()) {
                                true
                            } else {
                                !mangaTag.name.contains(searchedTag, true) || !mangaTag.namespace.equals(queryComponent.namespace, true)
                            }
                        }
                    }
                    else -> true
                }
            }
        }
    }

    private fun filterTracks(constraint: String, tracks: List<Track>): Boolean {
        return tracks.any {
            val trackService = trackManager.getService(it.syncId)
            if (trackService != null) {
                val status = trackService.getStatus(it.status.toInt())
                val name = services[it.syncId]
                status.contains(constraint, true) || name?.contains(constraint, true) == true
            } else {
                false
            }
        }
    }

    private val libraryDisplayMode by libraryPreferences.libraryDisplayMode().asState()
    // SY <--

    @Composable
    fun getDisplayMode(index: Int): LibraryDisplayMode {
        val category = categories[index]
        return remember(groupType, libraryDisplayMode, category) {
            // SY -->
            if (groupType != LibraryGroup.BY_DEFAULT) {
                libraryDisplayMode
            } else {
                category.display
            }
            // SY <--
        }
    }

    fun clearSelection() {
        state.selection = emptyList()
    }

    fun toggleSelection(manga: LibraryManga) {
        state.selection = selection.toMutableList().apply {
            if (fastAny { it.id == manga.id }) {
                removeAll { it.id == manga.id }
            } else {
                add(manga)
            }
        }
    }

    /**
     * Selects all mangas between and including the given manga and the last pressed manga from the
     * same category as the given manga
     */
    fun toggleRangeSelection(manga: LibraryManga) {
        state.selection = selection.toMutableList().apply {
            val lastSelected = lastOrNull()
            if (lastSelected?.category != manga.category) {
                add(manga)
                return@apply
            }
            val items = loadedManga[manga.category].orEmpty().apply {
                if (searchQuery.isNullOrBlank()) toList() else filter { it.filter(searchQuery!!) }
            }.fastMap { it.libraryManga }
            val lastMangaIndex = items.indexOf(lastSelected)
            val curMangaIndex = items.indexOf(manga)
            val selectedIds = fastMap { it.id }
            val newSelections = when (lastMangaIndex >= curMangaIndex + 1) {
                true -> items.subList(curMangaIndex, lastMangaIndex)
                false -> items.subList(lastMangaIndex, curMangaIndex + 1)
            }.filterNot { it.id in selectedIds }
            addAll(newSelections)
        }
    }

    fun selectAll(index: Int) {
        state.selection = state.selection.toMutableList().apply {
            val categoryId = categories.getOrNull(index)?.id ?: -1
            val items = loadedManga[categoryId].orEmpty().apply {
                if (searchQuery.isNullOrBlank()) toList() else filter { it.filter(searchQuery!!) }
            }.fastMap { it.libraryManga }
            val selectedIds = fastMap { it.id }
            val newSelections = items.filterNot { it.id in selectedIds }
            addAll(newSelections)
        }
    }

    fun invertSelection(index: Int) {
        state.selection = selection.toMutableList().apply {
            val categoryId = categories[index].id
            val items = loadedManga[categoryId].orEmpty().apply {
                if (searchQuery.isNullOrBlank()) toList() else filter { it.filter(searchQuery!!) }
            }.fastMap { it.libraryManga }
            val selectedIds = fastMap { it.id }
            val (toRemove, toAdd) = items.partition { it.id in selectedIds }
            val toRemoveIds = toRemove.fastMap { it.id }
            removeAll { it.id in toRemoveIds }
            addAll(toAdd)
        }
    }

    sealed class Dialog {
        data class ChangeCategory(val manga: List<Manga>, val initialSelection: List<CheckboxState<Category>>) : Dialog()
        data class DeleteManga(val manga: List<Manga>) : Dialog()
        data class DownloadCustomAmount(val manga: List<Manga>, val max: Int) : Dialog()
    }

    // SY -->
    /** Returns first unread chapter of a manga */
    suspend fun getFirstUnread(manga: Manga): Chapter? {
        return getNextChapters.await(manga.id).firstOrNull()
    }

    private fun getGroupedMangaItems(groupType: Int, libraryManga: List<LibraryItem>): Pair<LibraryMap, List<Category>> {
        val manga = when (groupType) {
            LibraryGroup.BY_TRACK_STATUS -> {
                val tracks = runBlocking { getTracks.await() }.groupBy { it.mangaId }
                libraryManga.groupBy { item ->
                    val status = tracks[item.libraryManga.manga.id]?.firstNotNullOfOrNull { track ->
                        TrackStatus.parseTrackerStatus(track.syncId, track.status)
                    } ?: TrackStatus.OTHER

                    status.int
                }.mapKeys { it.key.toLong() }
            }
            LibraryGroup.BY_SOURCE -> {
                libraryManga.groupBy { item ->
                    item.libraryManga.manga.source
                }
            }
            else -> {
                libraryManga.groupBy { item ->
                    item.libraryManga.manga.status
                }
            }
        }

        val categories = when (groupType) {
            LibraryGroup.BY_SOURCE ->
                manga.keys
                    .map { Category(it, sourceManager.getOrStub(it).name, 0, 0) }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            LibraryGroup.BY_TRACK_STATUS, LibraryGroup.BY_STATUS ->
                manga.keys
                    .sorted()
                    .map { Category(it, "", 0, 0) }
            else -> throw IllegalStateException("Invalid group type $groupType")
        }

        return manga to categories
    }

    fun runSync() {
        favoritesSync.runSync(presenterScope)
    }
    // SY <--
}
