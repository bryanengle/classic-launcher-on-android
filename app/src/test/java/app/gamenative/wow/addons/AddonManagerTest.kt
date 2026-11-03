package app.gamenative.wow.addons

import app.gamenative.wow.addons.provider.AddonProvider
import app.gamenative.wow.addons.provider.Fixtures
import app.gamenative.wow.addons.provider.Fixtures.base
import app.gamenative.wow.addons.provider.GitHubProvider
import app.gamenative.wow.addons.provider.RemoteAddon
import app.gamenative.wow.addons.provider.RemoteFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AddonManagerTest {
    private class FakeProvider(
        override val id: String,
        var addons: List<RemoteAddon> = emptyList(),
        var offline: Boolean = false,
    ) : AddonProvider {
        override val enabled = true
        val gets = mutableListOf<String>()

        private fun check() {
            if (offline) throw IOException("offline")
        }

        override suspend fun search(query: String, page: Int): List<RemoteAddon> {
            check()
            return addons.filter { it.name.contains(query, ignoreCase = true) }
        }

        override suspend fun popular(page: Int): List<RemoteAddon> {
            check()
            return addons
        }

        override suspend fun get(projectId: String): RemoteAddon? {
            check()
            gets += projectId
            return addons.firstOrNull { it.projectId == projectId }
        }

        override suspend fun getMany(ids: List<String>): List<RemoteAddon> {
            check()
            return addons.filter { it.projectId in ids }
        }
    }

    private val server = MockWebServer()
    private val zips = mutableMapOf<String, ByteArray>()
    private val jsonRoutes = mutableMapOf<String, String>()
    private val requested = mutableListOf<String>()
    private lateinit var root: File
    private lateinit var addonsDir: File
    private lateinit var db: AddonDb
    private val siteA = FakeProvider("site-a")
    private val siteB = FakeProvider("site-b")

    @Before
    fun setUp() {
        server.dispatcher = Fixtures.dispatcher { req ->
            val path = req.url.encodedPath
            synchronized(requested) { requested += path }
            zips[path]?.let { MockResponse.Builder().body(Buffer().write(it)).build() }
                ?: jsonRoutes[path]?.let { Fixtures.json(it) }
        }
        server.start()
        root = Files.createTempDirectory("manager").toFile()
        addonsDir = File(root, "_forever_/Interface/AddOns").apply { mkdirs() }
        db = AddonDb.forAddonsDir(addonsDir)
    }

    @After
    fun tearDown() {
        server.close()
        root.deleteRecursively()
    }

    private fun manager(vararg providers: AddonProvider = arrayOf(siteA, siteB)) =
        AddonManager(addonsDir, db, providers.toList(), OkHttpClient())

    @Test
    fun classicTargetInstallsClassicEvenWhenForeverIsAvailable() = runBlocking {
        val classicUrl = serveAddon("Era", "## Interface: 11509\n## Title: Era\n")
        val foreverUrl = serveAddon("Forever")
        val addon = remote("site-a", "both", "Both", latest = mapOf(
            AddonFlavor.CLASSIC to file("era", classicUrl),
            AddonFlavor.FOREVER to file("forever", foreverUrl),
        ))
        siteA.addons = listOf(addon)
        val era = AddonManager(addonsDir, db, listOf(siteA), OkHttpClient(), AddonFlavor.CLASSIC)
        assertEquals(AddonFlavor.CLASSIC, era.popular().single().bestFlavor)
        assertEquals(AddonFlavor.CLASSIC, era.install(addon).flavor)
        assertFalse(File(addonsDir, "Forever").exists())
        assertTrue(era.checkUpdates().isEmpty())
    }

    @Test
    fun classicTargetRejectsForeverOnlyAndUnlabelledArchives() = runBlocking {
        val addon = remote("site-a", "forever", "Forever", latest = mapOf(
            AddonFlavor.FOREVER to file("forever", serveAddon("Forever")),
        ))
        siteA.addons = listOf(addon)
        val era = AddonManager(addonsDir, db, listOf(siteA), OkHttpClient(), AddonFlavor.CLASSIC)
        assertTrue(era.popular().isEmpty())
        assertThrows(IOException::class.java) { runBlocking { era.install(addon) } }
        assertThrows(IOException::class.java) { runBlocking { era.installFromUrl(serveAddon("Unknown")) } }
        assertFalse(File(addonsDir, "Forever").exists())
        assertFalse(File(addonsDir, "Unknown").exists())
    }

    /** Serves a zip with one addon folder and returns its URL. */
    private fun serveAddon(folder: String, toc: String = "## Interface: 16001\n## Title: $folder\n"): String {
        zips["/zips/$folder.zip"] = TestZips.addon(folder, toc)
        return "${server.base()}/zips/$folder.zip"
    }

    private fun file(id: String, url: String = "", deps: List<SourceRef> = emptyList()) = RemoteFile(id, "v$id", url, deps)

    private fun remote(
        provider: String,
        projectId: String,
        name: String,
        author: String = "Author",
        downloads: Long = 0,
        latest: Map<AddonFlavor, RemoteFile> = emptyMap(),
    ) = RemoteAddon(provider, projectId, name, "", author, null, downloads, latest, null)

    private fun record(
        id: String,
        folders: List<String>,
        flavor: AddonFlavor,
        source: SourceRef?,
        version: String? = "1",
        name: String = folders.first(),
    ) = InstalledAddon(id, name, folders, version, flavor, source, 1)

    private fun installedOnDisk(record: InstalledAddon): InstalledAddon {
        record.folders.forEach {
            File(addonsDir, it).mkdirs()
            File(addonsDir, "$it/$it.toc").writeText("## Title: $it\n")
        }
        db.save(db.load() + record)
        return record
    }

    @Test
    fun searchMergesDuplicatesAcrossProviders() = runBlocking {
        siteA.addons = listOf(
            remote("site-a", "1", "Questie", "AeroScripts", 1_000, mapOf(AddonFlavor.CLASSIC to file("c1"))),
            remote("site-a", "2", "Bagnon", "Jaliborc", 500, mapOf(AddonFlavor.CLASSIC to file("c2"))),
        )
        siteB.addons = listOf(
            remote("site-b", "q", "Questie ", "aeroscripts, Logon", 10, mapOf(AddonFlavor.FOREVER to file("w1"))),
            remote("site-b", "b", "Bagnon", "Jaliborc", 900, mapOf(AddonFlavor.CLASSIC to file("w2"))),
        )

        val results = manager().search("")

        assertEquals(2, results.size)
        val questie = results.single { it.remote.name.trim() == "Questie" }
        assertEquals("site-b", questie.remote.provider) // the source with a Forever file wins
        assertEquals(listOf("site-b", "site-a"), questie.sources)
        assertEquals(AddonFlavor.FOREVER, questie.bestFlavor)
        val bagnon = results.single { it.remote.name == "Bagnon" }
        assertEquals("site-b", bagnon.remote.provider) // most downloads wins without Forever
        assertEquals(listOf("site-b", "site-a"), bagnon.sources)
        // Forever results are listed first.
        assertEquals(questie, results.first())
    }

    @Test
    fun searchMergesSameAddonDespiteAuthorAndSuffixDifferences() = runBlocking {
        siteA.addons = listOf(
            remote("site-a", "1", "BigWigs (Boss Timers & Tools)", "Funkydude", 900, mapOf(AddonFlavor.CLASSIC to file("c1"))),
            remote("site-a", "2", "Deadly Boss Mods", "MysticalOS", 800, mapOf(AddonFlavor.CLASSIC to file("c2"))),
        )
        siteB.addons = listOf(
            remote("site-b", "bw", "BigWigs", "BigWigsMods", 10, mapOf(AddonFlavor.FOREVER to file("w1"))),
            remote("site-b", "dbm", "Deadly Boss Mods", "DeadlyBossMods", 10, mapOf(AddonFlavor.FOREVER to file("w2"))),
            remote("site-b", "dbmd", "Deadly Boss Mods - Dungeons", "DeadlyBossMods", 5, mapOf(AddonFlavor.FOREVER to file("w3"))),
        )
        val results = manager().search("")
        assertEquals(3, results.size)
        assertEquals(listOf("site-b", "site-a"), results.single { it.remote.projectId == "bw" }.sources)
        assertEquals(listOf("site-b", "site-a"), results.single { it.remote.projectId == "dbm" }.sources)
    }

    @Test
    fun classicOnlyAddonIsMarkedClassic() = runBlocking {
        siteA.addons = listOf(remote("site-a", "1", "OldThing", latest = mapOf(AddonFlavor.CLASSIC to file("c1"))))
        val listing = manager().search("old").single()
        assertEquals(AddonFlavor.CLASSIC, listing.bestFlavor)
        assertNull(listing.installed)
        assertFalse(listing.updateAvailable)
    }

    @Test
    fun includeClassicFalseHidesClassic() = runBlocking {
        siteA.addons = listOf(
            remote("site-a", "1", "OldThing", latest = mapOf(AddonFlavor.CLASSIC to file("c1"))),
            remote("site-a", "2", "NewThing", latest = mapOf(AddonFlavor.FOREVER to file("f2"), AddonFlavor.CLASSIC to file("c2"))),
        )
        assertEquals(listOf("NewThing"), manager().search("thing", includeClassic = false).map { it.remote.name })
        assertEquals(listOf("NewThing"), manager().popular(includeClassic = false).map { it.remote.name })
        assertEquals(2, manager().popular().size)
    }

    @Test
    fun installResolvesDependencies() = runBlocking {
        installedOnDisk(record("site-a:libd", listOf("LibD"), AddonFlavor.FOREVER, SourceRef("site-a", "libd", "d1")))
        zips["/zips/LibD.zip"] = TestZips.addon("LibD")
        siteA.addons = listOf(
            remote(
                "site-a", "a", "AddonA",
                latest = mapOf(
                    AddonFlavor.FOREVER to file(
                        "a1", serveAddon("AddonA"),
                        listOf(SourceRef("site-a", "libb"), SourceRef("site-a", "libd"), SourceRef("site-a", "a")),
                    ),
                ),
            ),
            remote(
                "site-a", "libb", "LibB",
                latest = mapOf(
                    AddonFlavor.FOREVER to file("b1", serveAddon("LibB"), listOf(SourceRef("site-a", "libc"))),
                ),
            ),
            // LibC depends back on LibB: a cycle.
            remote(
                "site-a", "libc", "LibC",
                latest = mapOf(
                    AddonFlavor.CLASSIC to file("c1", serveAddon("LibC", "## Interface: 11507\n"), listOf(SourceRef("site-a", "libb"))),
                ),
            ),
        )

        val result = manager().install(siteA.addons.first())

        assertEquals("site-a:a", result.id)
        assertEquals(listOf("AddonA"), result.folders)
        assertEquals(SourceRef("site-a", "a", "a1"), result.source)
        assertEquals(AddonFlavor.FOREVER, result.flavor)
        val records = db.load().associateBy { it.id }
        assertEquals(setOf("site-a:libd", "site-a:a", "site-a:libb", "site-a:libc"), records.keys)
        assertEquals(AddonFlavor.CLASSIC, records.getValue("site-a:libc").flavor)
        listOf("AddonA", "LibB", "LibC").forEach { assertTrue(it, File(addonsDir, "$it/$it.toc").isFile) }
        assertFalse("/zips/LibD.zip" in requested)
        assertFalse("libd" in siteA.gets)
        // No download leftovers.
        assertTrue(root.walkTopDown().none { it.name.endsWith(".zip") })
    }

    @Test
    fun installRefusesDisabledDistribution() = runBlocking {
        val locked = remote("site-a", "x", "Locked", latest = mapOf(AddonFlavor.CLASSIC to file("x1", "")))
        val error = runCatching { manager().install(locked) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(db.load().isEmpty())
    }

    @Test
    fun updateDetectsClassicToForeverSwitch() = runBlocking {
        val questie = installedOnDisk(
            record("site-a:1", listOf("Questie"), AddonFlavor.CLASSIC, SourceRef("site-a", "1", "5000"), version = "v5000"),
        )
        siteA.addons = listOf(remote("site-a", "1", "Questie", latest = mapOf(AddonFlavor.CLASSIC to file("5000", serveAddon("Questie")))))
        assertTrue(manager().checkUpdates().isEmpty())

        siteA.addons = listOf(
            remote(
                "site-a", "1", "Questie",
                latest = mapOf(AddonFlavor.CLASSIC to file("5000"), AddonFlavor.FOREVER to file("5001", serveAddon("Questie"))),
            ),
        )
        val updates = manager().checkUpdates()
        val listing = updates.single()
        assertTrue(listing.updateAvailable)
        assertEquals(questie, listing.installed)
        assertEquals(AddonFlavor.FOREVER, listing.bestFlavor)

        val updated = manager().update(questie)
        assertEquals(AddonFlavor.FOREVER, updated.flavor)
        assertEquals("5001", updated.source!!.fileId)
        assertEquals(listOf(updated), db.load())
        assertTrue(manager().checkUpdates().isEmpty())
    }

    @Test
    fun updateAllUpdatesEverythingOutdated() = runBlocking {
        installedOnDisk(InstalledAddon("site-a:1", "A", listOf("A"), "v1", AddonFlavor.FOREVER, SourceRef("site-a", "1", "1"), 1))
        installedOnDisk(InstalledAddon("site-a:2", "B", listOf("B"), "v1", AddonFlavor.FOREVER, SourceRef("site-a", "2", "1"), 1))
        siteA.addons = listOf(
            remote("site-a", "1", "A", latest = mapOf(AddonFlavor.FOREVER to file("2", serveAddon("A")))),
            remote("site-a", "2", "B", latest = mapOf(AddonFlavor.FOREVER to file("1", serveAddon("B")))),
        )
        val updated = manager().updateAll()
        assertEquals(listOf("site-a:1"), updated.map { it.id })
        assertEquals("2", db.load().single { it.id == "site-a:1" }.source!!.fileId)
    }

    @Test
    fun removeKeepsSharedFolders() = runBlocking {
        val a =
            installedOnDisk(record("site-a:a", listOf("A", "Libs", "!BugGrabber"), AddonFlavor.FOREVER, SourceRef("site-a", "a")))
        val b =
            installedOnDisk(record("site-a:b", listOf("B", "Libs", "!BugGrabber"), AddonFlavor.FOREVER, SourceRef("site-a", "b")))

        manager().remove(a)

        assertFalse(File(addonsDir, "A").exists())
        assertTrue(File(addonsDir, "Libs").isDirectory)
        assertTrue(File(addonsDir, "!BugGrabber").isDirectory)
        assertTrue(File(addonsDir, "B").isDirectory)
        assertEquals(listOf(b), db.load())

        manager().remove(b)
        assertTrue(addonsDir.listFiles()!!.isEmpty())
        assertTrue(db.load().isEmpty())
    }

    @Test
    fun removeNeverTouchesPathsOutsideAddons() = runBlocking {
        val outside = File(root, "_forever_/WTF").apply { mkdirs() }
        val evil = InstalledAddon("x", "X", listOf("../../WTF", "/abs"), null, AddonFlavor.OTHER, null, 1)
        db.save(listOf(evil))
        manager().remove(evil)
        assertTrue(outside.isDirectory)
    }

    @Test
    fun checkUpdatesOfflineReturnsEmpty() = runBlocking {
        installedOnDisk(InstalledAddon("site-a:1", "A", listOf("A"), "v1", AddonFlavor.FOREVER, SourceRef("site-a", "1", "1"), 1))
        siteA.addons = listOf(remote("site-a", "1", "A", latest = mapOf(AddonFlavor.FOREVER to file("2", serveAddon("A")))))
        siteA.offline = true
        siteB.offline = true

        assertTrue(manager().checkUpdates().isEmpty())
        assertTrue(manager().search("a").isEmpty())
        // Installed addons are still listed from disk.
        assertEquals(listOf("A"), manager().installed().map { it.installed!!.name })
    }

    @Test
    fun browseOutcomeTellsNoSourceReachableFromNothingFound() = runBlocking {
        // Codex UI16-05: the page showed "nothing found" when every source was offline.
        siteA.offline = true
        siteB.offline = true
        manager().searchOutcome("a").let { assertTrue(it.listings.isEmpty()); assertTrue(it.noSourceReachable); assertEquals(2, it.sourcesFailed) }
        assertTrue(manager().popularOutcome().noSourceReachable)

        siteB.offline = false
        manager().searchOutcome("a").let { assertTrue(it.listings.isEmpty()); assertFalse(it.noSourceReachable); assertEquals(1, it.sourcesFailed) }

        siteA.offline = false
        assertFalse(manager().searchOutcome("nothing like this").noSourceReachable)
    }

    @Test
    fun installedListsLocalAddonsFoundOnDisk() = runBlocking {
        File(addonsDir, "Loose").mkdirs()
        File(addonsDir, "Loose/Loose.toc").writeText("## Interface: 11507\n## Title: Loose Addon\n")
        val listing = manager().installed().single()
        assertEquals("Loose Addon", listing.installed!!.name)
        assertEquals(AddonFlavor.CLASSIC, listing.bestFlavor)
        assertFalse(listing.updateAvailable)
        assertEquals("local:Loose", db.load().single().id)
    }

    @Test
    fun installFromGitHubUrl() = runBlocking {
        jsonRoutes["/repos/me/Foo/releases/latest"] = """
            {"tag_name":"1.0","name":"Foo","assets":[
              {"id":11,"name":"Foo-1.0-classic.zip","content_type":"application/zip","browser_download_url":"${server.base()}/zips/Classic.zip"},
              {"id":12,"name":"Foo-1.0-forever.zip","content_type":"application/zip","browser_download_url":"${server.base()}/zips/Foo.zip"}]}
        """.trimIndent()
        serveAddon("Foo")
        val github = GitHubProvider(OkHttpClient(), apiBase = server.base(), catalogUrl = "${server.base()}/no-catalog.json")

        val result = manager(siteA, github).installFromUrl("https://github.com/me/Foo/releases")

        assertEquals("github:me/Foo", result.id)
        assertEquals(SourceRef("github", "me/Foo", "12"), result.source)
        assertEquals(listOf("Foo"), result.folders)
        assertEquals(AddonFlavor.FOREVER, result.flavor)
        assertTrue(File(addonsDir, "Foo/Foo.toc").isFile)
        assertFalse("/zips/Classic.zip" in requested)
    }

    @Test
    fun installFromDirectZipUrl(): Unit = runBlocking {
        val url = serveAddon("Zipped", "## Interface: 11507\n## Title: Zipped Thing\n## Version: 3.2\n")
        val result = manager().installFromUrl(url)
        assertEquals("Zipped Thing", result.name)
        assertEquals("3.2", result.version)
        assertEquals(SourceRef("url", url), result.source)
        assertEquals(AddonFlavor.CLASSIC, result.flavor)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager().installFromUrl("not a url") } }
    }
}
