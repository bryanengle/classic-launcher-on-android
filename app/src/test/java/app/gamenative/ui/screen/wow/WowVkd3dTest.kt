package app.gamenative.ui.screen.wow

import android.app.Application
import android.content.ContextWrapper
import android.net.Uri
import app.gamenative.PluviaApp
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import org.apache.commons.compress.archivers.tar.*
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowVkd3dTest {
    @get:Rule val temp = TemporaryFolder()
    private val files by lazy { temp.newFolder("files") }
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = files
        override fun getCacheDir() = File(files, "cache").apply { mkdirs() }
    } }
    private var previousImage: Any? = null
    @Before fun before() {
        val field = ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        previousImage = field.get(null); field.set(null, ImageFs.find(File(files, "imagefs")))
        PluviaApp.keepAlive = false
    }
    @After fun after() {
        ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, previousImage)
        PluviaApp.keepAlive = false
    }
    private fun pe(names: Set<String>, machine: Int = 0xaa64): ByteArray {
        val data = ByteArray(4096); val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0, 0x5a4d); b.putInt(60, 64); b.putInt(64, 0x4550)
        b.putShort(68, machine.toShort()); b.putShort(70, 1); b.putShort(84, 240); b.putShort(86, 0x2002)
        b.putShort(88, 0x20b); b.putInt(148, 512); b.putInt(196, 16)
        b.putInt(200, 0x1000); b.putInt(204, 1024)
        b.putInt(340, 0x1000); b.putInt(344, 3584); b.putInt(348, 512)
        b.putInt(536, names.size); b.putInt(544, 0x1040)
        var text = 640
        names.forEachIndexed { i, name -> b.putInt(576 + i * 4, 0x1000 + text - 512)
            (name + '\u0000').toByteArray().copyInto(data, text); text += name.length + 1 }
        return data
    }
    private fun metadata(type: String = "VKD3D", name: String = "VKD3D-test", code: Int = 1, block: String = "wowforever") = JSONObject().apply {
        put("type", type); put("versionName", name); put("versionCode", code); put("description", "Synthetic ARM64 test profile")
        put(block, JSONObject().put("schema", 1).put("architecture", "windows-arm64").put("upstreamVersion", "3.0.1"))
        put("files", JSONArray().apply { WowVkd3dComponents.exports.keys.forEach { name ->
            put(JSONObject().put("source", "system32/$name").put("target", "\${system32}/$name")) } })
    }
    private fun pack(profile: JSONObject? = metadata(), machine: Int = 0xaa64, extra: String? = null): Uri {
        val file = temp.newFile("${System.nanoTime()}.wcp")
        TarArchiveOutputStream(XZCompressorOutputStream(file.outputStream())).use { tar ->
            fun add(name: String, bytes: ByteArray) {
                tar.putArchiveEntry(TarArchiveEntry(name, true).apply { size = bytes.size.toLong() }); tar.write(bytes); tar.closeArchiveEntry()
            }
            if (profile != null) add("profile.json", profile.toString().toByteArray())
            WowVkd3dComponents.exports.forEach { (name, exports) -> add("system32/$name", pe(exports, machine)) }
            if (extra != null) add(extra, byteArrayOf(1))
            tar.finish()
        }
        return Uri.fromFile(file)
    }
    private fun import(uri: Uri = pack()) = WowVkd3dComponents.importWcp(context, uri) { false }
    private fun container(id: String = "wow_forever") = Container(id).apply {
        rootDir = File(ImageFs.find(context).rootDir, "home/xuser-$id").apply { mkdirs() }
        loadData(WowContainerProfile.defaultsForNewContainer("/games/wow", true, false)); saveData()
    }
    private fun passing(): JSONObject {
        val f = JSONObject()
        for (key in listOf("shaderDrawParameters", "samplerMirrorClampToEdge", "descriptorIndexing",
            "shaderSampledImageArrayNonUniformIndexing", "shaderStorageBufferArrayNonUniformIndexing", "shaderStorageImageArrayNonUniformIndexing",
            "shaderUniformTexelBufferArrayNonUniformIndexing", "shaderStorageTexelBufferArrayNonUniformIndexing",
            "descriptorBindingSampledImageUpdateAfterBind", "descriptorBindingStorageImageUpdateAfterBind", "descriptorBindingStorageBufferUpdateAfterBind",
            "descriptorBindingUniformTexelBufferUpdateAfterBind", "descriptorBindingStorageTexelBufferUpdateAfterBind", "descriptorBindingPartiallyBound",
            "descriptorBindingVariableDescriptorCount", "runtimeDescriptorArray", "bufferDeviceAddress", "timelineSemaphore", "synchronization2",
            "dynamicRendering", "robustBufferAccess2", "robustImageAccess2", "nullDescriptor", "robustBufferAccessUpdateAfterBind")) f.put(key, true)
        val l = JSONObject()
        for (key in listOf("maxPerStageDescriptorUpdateAfterBindSampledImages", "maxPerStageDescriptorUpdateAfterBindStorageImages",
            "maxPerStageDescriptorUpdateAfterBindStorageBuffers", "maxDescriptorSetUpdateAfterBindSampledImages",
            "maxDescriptorSetUpdateAfterBindStorageImages", "maxDescriptorSetUpdateAfterBindStorageBuffers", "maxUpdateAfterBindDescriptorsInAllPools")) l.put(key, 1000000)
        l.put("maxPerStageDescriptorUpdateAfterBindSamplers", 2048); l.put("maxDescriptorSetUpdateAfterBindSamplers", 2048)
        return JSONObject().put("deviceName", "Mock Vulkan device; not hardware validation").put("apiVersion", (1 shl 22) + (3 shl 12))
            .put("features", f).put("limits", l).put("extensions", JSONArray(listOf("VK_EXT_robustness2", "VK_KHR_push_descriptor"))).put("createDeviceResult", 0)
    }
    private fun dxgi() {
        val p = ContentProfile().apply { type = ContentProfile.ContentType.CONTENT_TYPE_DXVK; verName = WowBundledComponents.DXVK_NAME; verCode = WowBundledComponents.DXVK_VERSION_CODE }
        val directory = ContentsManager.getInstallDir(context, p).apply { mkdirs() }
        File(directory, "dxgi.dll").writeBytes(pe(setOf("CreateDXGIFactory1")))
        File(directory, "profile.json").writeText(JSONObject().put("type", "DXVK").put("versionName", p.verName).put("versionCode", p.verCode).put("description", "Synthetic DXGI fixture")
            .put("files", JSONArray().put(JSONObject().put("source", "dxgi.dll").put("target", "\${system32}/dxgi.dll"))).toString())
    }
    @Test fun acceptsNativeArm64AndRejectsX64EcElfTruncatedAndMissingExports() {
        val file = temp.newFile()
        file.writeBytes(pe(setOf("D3D12CreateDevice"))); WowArm64Pe.validate(file, setOf("D3D12CreateDevice"))
        for (machine in listOf(0x8664, 0xa641, 0xa64e, 0x14c)) {
            file.writeBytes(pe(setOf("D3D12CreateDevice"), machine)); assertThrows(Exception::class.java) { WowArm64Pe.validate(file) }
        }
        file.writeBytes(byteArrayOf(127, 69, 76, 70)); assertThrows(Exception::class.java) { WowArm64Pe.validate(file) }
        file.writeBytes(pe(setOf("other"))); assertThrows(Exception::class.java) { WowArm64Pe.validate(file, setOf("D3D12CreateDevice")) }
        file.writeBytes(pe(setOf("other")).copyOf(700)); assertThrows(Exception::class.java) { WowArm64Pe.validate(file, setOf("other")) }
    }
    @Test fun importsIntoExistingCatalogWithoutAutomaticSelectionOrContainerEdits() {
        val c = container(); val before = c.configFile.readBytes()
        val p = import(); assertEquals("vkd3d-VKD3D-test-1", WowVkd3dComponents.id(p))
        assertEquals(1, WowVkd3dComponents.profiles(context).size); assertArrayEquals(before, c.configFile.readBytes())
        assertTrue(ContentsManager.getTmpDir(context).listFiles().orEmpty().isEmpty())
        WowVkd3dComponents.applyProfile(context, WowVkd3dComponents.id(p)) { false }
        val saved = ContainerManager(context).getContainerById(c.id)!!
        assertEquals(WowVkd3dComponents.id(p), saved.getExtra(WowVkd3dComponents.PROFILE_KEY))
        assertEquals(WowVkd3dComponents.DX11, WowVkd3dComponents.api(saved))
        val a = JSONObject(before.toString(Charsets.UTF_8)); val b = JSONObject(c.configFile.readText())
        a.keys().forEach { key -> if (key != "extraData") assertEquals("Unrelated $key", a.get(key).toString(), b.get(key).toString()) }
        assertThrows(Exception::class.java) { WowVkd3dComponents.delete(context, WowVkd3dComponents.id(p), active = { false }) }
    }
    @Test fun rejectsInvalidProfileWrongTypeArchitectureAndTraversalWithoutInstalledResidue() {
        val cases = listOf(pack(null), pack(metadata("DXVK")), pack(metadata(code = -1)), pack(machine = 0xa641), pack(extra = "../escaped"),
            pack(metadata().apply { remove("files") }), pack(metadata().apply { getJSONObject("wowforever").put("architecture", "x64") }))
        cases.forEach { uri -> assertThrows(Exception::class.java) { import(uri) }; assertTrue(WowVkd3dComponents.profiles(context).isEmpty()) }
        assertFalse(File(files, "escaped").exists())
    }
    @Test fun rejectsNonWcpAndDuplicateWithoutOverwriting() {
        val original = import(); val dir = ContentsManager.getInstallDir(context, original)
        val before = File(dir, "profile.json").readBytes()
        assertThrows(Exception::class.java) { import() }
        assertThrows(Exception::class.java) { import(pack(metadata(code = 2))) }
        assertArrayEquals(before, File(dir, "profile.json").readBytes())
        val f = temp.newFile("wrong.txt").apply { writeBytes(File(pack().path!!).readBytes()) }
        assertThrows(Exception::class.java) { import(Uri.fromFile(f)) }
    }
    @Test fun importsPackagesWithTheOldCompatibilityBlockName() {
        // VKD3D packages made before the rename carry the block as "frontmage"; they still import and apply.
        val p = import(pack(metadata(name = "FrontMage-test", block = "frontmage")))
        assertEquals(2, WowVkd3dComponents.dlls(ContentsManager.getInstallDir(context, p), p).size)
        val unknown = pack(metadata(name = "Other-test", block = "other"))
        assertThrows(Exception::class.java) { val q = import(unknown); WowVkd3dComponents.dlls(ContentsManager.getInstallDir(context, q), q) }
    }
    @Test fun importsRealBundledVkd3dDllsAndExports() {
        val file = temp.newFile("real.wcp")
        val bundle = WowBundledComponents.bundles.single { it.name == WowBundledComponents.VKD3D_NAME }
        context.assets.open("bundled_components/${bundle.asset}").use { input -> file.outputStream().use { input.copyTo(it) } }
        val p = import(Uri.fromFile(file)); assertEquals(WowBundledComponents.VKD3D_NAME, p.verName)
        assertEquals(WowBundledComponents.VKD3D_VERSION_CODE, p.verCode)
        assertEquals(2, WowVkd3dComponents.dlls(ContentsManager.getInstallDir(context, p), p).size)
    }
    @Test fun rejectsActiveOperationsAndMissingSelectionsWithoutChangingConfig() {
        val c = container(); val before = c.configFile.readBytes()
        assertThrows(Exception::class.java) { WowVkd3dComponents.importWcp(context, pack()) { true } }
        assertThrows(Exception::class.java) { WowVkd3dComponents.applyProfile(context, "missing") { true } }
        assertThrows(Exception::class.java) { WowVkd3dComponents.applyProfile(context, "missing") { false } }
        assertThrows(Exception::class.java) { WowVkd3dComponents.applyApi(context, WowVkd3dComponents.DX12, { false }) { _, _ -> error("Should not probe a missing profile") } }
        assertArrayEquals(before, c.configFile.readBytes())
    }
    @Test fun dx12SwitchRequiresCapabilitiesAndAuditedDxgiAndKeepsAllOtherSettings() {
        val p = import(); val c = container(); dxgi()
        val runtime = temp.newFile("runtime.wcp")
        context.assets.open("bundled_components/${WowBundledComponents.bundles.single { it.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON }.asset}").use { input -> runtime.outputStream().use { input.copyTo(it) } }
        WowDeveloperComponents.importRuntime(context, Uri.fromFile(runtime))
        WowVkd3dComponents.applyProfile(context, WowVkd3dComponents.id(p)) { false }
        val before = JSONObject(c.configFile.readText())
        assertThrows(Exception::class.java) { WowVkd3dComponents.applyApi(context, WowVkd3dComponents.DX12, { false }) { _, _ -> WowVkd3dCapabilities.evaluate(JSONObject()) } }
        assertEquals(before.toString(), JSONObject(c.configFile.readText()).toString())
        WowVkd3dComponents.applyApi(context, WowVkd3dComponents.DX12, { false }) { _, _ -> WowVkd3dCapabilities.evaluate(passing()) }
        val saved = ContainerManager(context).getContainerById(c.id)!!
        assertEquals("-d3d12", saved.execArgs); assertEquals("dx12", WowVkd3dComponents.api(saved))
        val after = JSONObject(c.configFile.readText())
        before.keys().forEach { key -> if (key !in setOf("extraData", "execArgs")) assertEquals(key, before.get(key).toString(), after.get(key).toString()) }
        WowVkd3dComponents.applyApi(context, WowVkd3dComponents.DX11, { false }) { _, _ -> error("DX11 must not probe Vulkan") }
        assertEquals("-d3d11", ContainerManager(context).getContainerById(c.id)!!.execArgs)
    }
    @Test fun dx12SwitchAppliesTheInstalledVkd3dWhenNoneIsSelected() {
        val p = import(); val c = container(); dxgi()
        val runtime = temp.newFile("runtime2.wcp")
        context.assets.open("bundled_components/${WowBundledComponents.bundles.single { it.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON }.asset}").use { input -> runtime.outputStream().use { input.copyTo(it) } }
        WowDeveloperComponents.importRuntime(context, Uri.fromFile(runtime))
        assertEquals("", ContainerManager(context).getContainerById(c.id)!!.getExtra(WowVkd3dComponents.PROFILE_KEY))
        WowVkd3dComponents.applyApi(context, WowVkd3dComponents.DX12, { false }) { _, _ -> WowVkd3dCapabilities.evaluate(passing()) }
        val saved = ContainerManager(context).getContainerById(c.id)!!
        assertEquals("dx12", WowVkd3dComponents.api(saved)); assertEquals(WowVkd3dComponents.id(p), saved.getExtra(WowVkd3dComponents.PROFILE_KEY))
    }
    @Test fun qualifiedR13ToR17AreAcceptedWithoutFallback() {
        fun profile(name: String, code: Int) = ContentProfile().apply { verName = name; verCode = code }
        assertTrue(WowVkd3dComponents.isAuditedDxgi(profile("Vegas-2.7.3-wow-arm64-async-gamenative-r13", 13)))
        assertTrue(WowVkd3dComponents.isAuditedDxgi(profile("Vegas-2.7.3-wow-arm64-hybrid-gamenative-r14", 14)))
        assertTrue(WowVkd3dComponents.isAuditedDxgi(profile("Vegas-2.7.3-wow-arm64-hybrid-gamenative-r15", 15)))
        assertTrue(WowVkd3dComponents.isAuditedDxgi(profile("Vegas-2.7.3-wow-arm64-hybrid-gamenative-r16", 16)))
        assertTrue(WowVkd3dComponents.isAuditedDxgi(profile("Vegas-2.7.3-wow-arm64-hybrid-gamenative-r17", 17)))
        assertTrue(WowVkd3dComponents.isAuditedDxgi(profile("Vegas-2.7.3-wow-arm64-hybrid-gamenative-r30", 30)))
        assertTrue(WowVkd3dComponents.isAuditedDxgi(profile("Vegas-2.7.3-wow-arm64-hybrid-gamenative-r43", 43)))
        assertFalse(WowVkd3dComponents.isAuditedDxgi(profile("Vegas-2.7.3-wow-arm64-hybrid-gamenative-r43", 42)))
        assertFalse(WowVkd3dComponents.isAuditedDxgi(profile("Vegas-2.7.3-wow-arm64-hybrid-gamenative-r30", 29)))
        assertFalse(WowVkd3dComponents.isAuditedDxgi(profile("Vegas-2.7.3-wow-arm64-hybrid-gamenative-r17", 16)))
        assertFalse(WowVkd3dComponents.isAuditedDxgi(profile("Vegas-2.7.3-wow-arm64-hybrid-gamenative-r16", 15)))
        assertFalse(WowVkd3dComponents.isAuditedDxgi(profile("Vegas-2.7.3-wow-arm64-hybrid-gamenative-r15", 14)))
        assertFalse(WowVkd3dComponents.isAuditedDxgi(profile("Vegas-2.7.3-wow-arm64-async-gamenative-r13", 14)))
    }
    @Test fun rejectsAlternativeDxgiInsteadOfFallingBack() {
        val p = ContentProfile().apply { type = ContentProfile.ContentType.CONTENT_TYPE_DXVK; verName = "unqualified"; verCode = 1 }
        assertThrows(IllegalArgumentException::class.java) { WowVkd3dComponents.validateDxgi(context, p, "dxgi.dll") }
    }
    @Test fun featureNamesAloneOrLowLimitsOrFailedDeviceDoNotPass() {
        assertTrue(WowVkd3dCapabilities.evaluate(passing()).compatible)
        val featureNames = passing().getJSONObject("features").keys().asSequence().toList()
        featureNames.forEach { key -> val json = passing(); json.getJSONObject("features").put(key, false); assertFalse("Missing $key", WowVkd3dCapabilities.evaluate(json).compatible) }
        passing().getJSONObject("limits").keys().asSequence().toList().forEach { key ->
            val json = passing(); json.getJSONObject("limits").put(key, 0); assertFalse("Low $key", WowVkd3dCapabilities.evaluate(json).compatible) }
        assertFalse(WowVkd3dCapabilities.evaluate(passing().put("extensions", JSONArray())).compatible)
        assertFalse(WowVkd3dCapabilities.evaluate(passing().put("createDeviceResult", -7)).compatible)
        assertFalse(WowVkd3dCapabilities.evaluate(passing().put("apiVersion", (1 shl 22) + (2 shl 12))).compatible)
    }
    @Test fun apiArgumentsPreserveUnrelatedArgsAndRemoveContradictoryRendererFlags() {
        assertEquals("-foo=1 -d3d12", WowVkd3dComponents.arguments("-d3d11 -foo=1 \"-d3d12\"", "dx12"))
        assertEquals("--custom=-d3d12 -d3d11", WowVkd3dComponents.arguments("--custom=-d3d12", "dx11"))
        assertThrows(IllegalArgumentException::class.java) { WowVkd3dComponents.arguments("", "invalid") }
    }
    @Test fun cachedGameGraphicsAndFsrPersistThroughDx12ThenDx11() {
        val root = temp.newFolder(); val config = File(root, "_classic_era_/WTF/Config.wtf").apply { parentFile.mkdirs() }
        config.writeText("SET gxApi \"D3D11\"\nSET RenderScale \"0.75\"\nSET ResampleQuality \"3\"\nSET uiScale \"0.65\"\n")
        WowGameConfig.prepare(root, "1344x608", "D3D12")
        assertTrue(config.readText().contains("SET gxApi \"D3D12\""))
        for (api in listOf("D3D12", "D3D11")) { WowGameConfig.prepare(root, "1344x608", api)
            assertTrue(config.readText().contains("SET gxApi \"$api\"")); assertTrue(config.readText().contains("SET RenderScale \"0.75\""))
            assertTrue(config.readText().contains("SET ResampleQuality \"3\"")) }
    }
    @Test fun dx12NativeOverridesKeepUnrelatedRulesAndDoNotModifyDx11Environment() {
        assertEquals("d3d11=b;winemenubuilder=;d3d12,d3d12core,dxgi=n", WowVkd3dRuntime.nativeOverrides("d3d11,d3d12=b;winemenubuilder=;dxgi=b"))
        val c = container(); val env = EnvVars().apply { put("DXVK_ASYNC", "1"); put("DXVK_SHADER_CACHE_PATH", "G:\\old") }
        val before = env.toString(); WowVkd3dRuntime.environment(c, env); assertEquals(before, env.toString())
        c.putExtra(WowVkd3dComponents.API_KEY, "dx12"); env.put("VKD3D_SHADER_CACHE_PATH", "G:\\user-cache")
        WowVkd3dRuntime.environment(c, env); assertEquals("G:\\user-cache", env.get("VKD3D_SHADER_CACHE_PATH"))
        assertEquals("1", env.get("DXVK_ASYNC")); assertEquals("G:\\old", env.get("DXVK_SHADER_CACHE_PATH"))
    }
    @Test fun savedDllOverridesCannotDropTheDx12NativeRulesAtLaunch() {
        val c = container(); c.putExtra(WowVkd3dComponents.API_KEY, "dx12"); c.envVars = "WINEDLLOVERRIDES=winegstreamer=d"
        val env = EnvVars(); WowVkd3dRuntime.environment(c, env)
        // The launcher merges the saved variables again; before the fix this was the final value.
        WowLaunchEnvironment.mergeUserOverrides(c, env); assertEquals("winegstreamer=d", env.get("WINEDLLOVERRIDES"))
        WowVkd3dRuntime.environment(c, env)
        assertEquals("winegstreamer=d;d3d12,d3d12core,dxgi=n", env.get("WINEDLLOVERRIDES"))
        WowVkd3dRuntime.environment(c, env); assertEquals("winegstreamer=d;d3d12,d3d12core,dxgi=n", env.get("WINEDLLOVERRIDES"))
        c.putExtra(WowVkd3dComponents.API_KEY, "dx11"); val dx11 = EnvVars(); WowLaunchEnvironment.mergeUserOverrides(c, dx11)
        WowVkd3dRuntime.environment(c, dx11); assertEquals("winegstreamer=d", dx11.get("WINEDLLOVERRIDES"))
    }
    @Test fun launcherReappliesDx12RulesAfterItsLastUserMerge() {
        val source = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val text = File(source, "com/winlator/xenvironment/components/BionicProgramLauncherComponent.java").readText()
        val merge = text.lastIndexOf("WowLaunchEnvironment.mergeUserOverrides(container, envVars)")
        val dx12 = text.lastIndexOf("WowVkd3dRuntime.INSTANCE.environment(container, envVars)")
        val probe = text.indexOf("WowDriverProbe.prepare(")
        assertTrue(merge > 0 && dx12 > merge && probe > dx12)
    }
    @Test fun deleteUnusedProfileAndRejectUnreadableContainerDependency() {
        val p = import(); val broken = File(ImageFs.find(context).rootDir, "home/xuser-broken").apply { mkdirs() }
        File(broken, "config.json").writeText("invalid")
        assertThrows(Exception::class.java) { WowVkd3dComponents.delete(context, WowVkd3dComponents.id(p), active = { false }) }
        broken.deleteRecursively(); WowVkd3dComponents.delete(context, WowVkd3dComponents.id(p), active = { false })
        assertTrue(WowVkd3dComponents.profiles(context).isEmpty())
    }
    @Test fun configSaveFailureRestoresOriginalBytesAndInMemoryValues() {
        val c = container(); val before = c.configFile.readBytes()
        assertThrows(Exception::class.java) { WowVkd3dComponents.save(c) { c.putExtra(WowVkd3dComponents.API_KEY, "dx12"); error("Injected failure") } }
        assertArrayEquals(before, c.configFile.readBytes()); assertEquals("dx11", WowVkd3dComponents.api(c))
    }
    @Test fun partialDeleteNeverRepublishesABrokenProfileAsInstalled() {
        val p = import()
        assertThrows(Exception::class.java) {
            WowVkd3dComponents.delete(context, WowVkd3dComponents.id(p), active = { false }, cleanup = { dir ->
                File(dir, "system32/d3d12core.dll").delete(); false
            })
        }
        assertTrue(WowVkd3dComponents.profiles(context).isEmpty())
        assertFalse(ContentsManager.getInstallDir(context, p).exists())
        assertTrue(ContentsManager.getContentTypeDir(context, WowVkd3dComponents.type).listFiles().orEmpty().any { it.name.startsWith(".removed-") })
    }

    @Test fun rejectsMissingOrGlibcRuntimeBeforeDx12Activation() {
        val c = container()
        assertThrows(Exception::class.java) { WowVkd3dComponents.checkRuntime(context, c) }
        c.containerVariant = "glibc"
        assertThrows(Exception::class.java) { WowVkd3dComponents.checkRuntime(context, c) }
    }

    @Test fun dx12EnvironmentUsesRealDriverIdentityWithoutTouchingSavedConfigOrDx11() {
        val c = container(); val saved = c.configFile.readBytes()
        val keys = listOf("WRAPPER_VK_VERSION", "WRAPPER_DEVICE_NAME", "WRAPPER_DEVICE_ID", "WRAPPER_VENDOR_ID")
        val env = EnvVars().apply { keys.forEach { put(it, "configured-override") } }
        WowVkd3dRuntime.environment(c, env); keys.forEach { assertEquals("configured-override", env.get(it)) }
        c.putExtra(WowVkd3dComponents.API_KEY, "dx12"); WowVkd3dRuntime.environment(c, env)
        keys.forEach { assertFalse(env.has(it)) }; assertArrayEquals(saved, c.configFile.readBytes())
    }
    @Test fun forcedFeatureReportingAndBlacklistedRequiredExtensionAreRejected() {
        val c = container()
        for (key in listOf("VKD3D_FEATURE_LEVEL", "VKD3D_SHADER_MODEL")) {
            c.envVars = "$key=forced"; val failure = assertThrows(Exception::class.java) { WowVkd3dComponents.checkRuntime(context, c) }
            assertTrue(failure.message!!.contains("forced VKD3D"))
        }
        c.envVars = ""; c.graphicsDriverConfig = "blacklistedExtensions=VK_EXT_robustness2"
        assertTrue(assertThrows(Exception::class.java) { WowVkd3dComponents.checkRuntime(context, c) }.message!!.contains("blacklisted"))
    }

}
