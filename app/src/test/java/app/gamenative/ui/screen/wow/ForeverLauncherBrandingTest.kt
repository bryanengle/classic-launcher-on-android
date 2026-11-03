package app.gamenative.ui.screen.wow

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.content.ContextCompat
import app.gamenative.BuildConfig
import app.gamenative.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.4.5: the app is "Forever Launcher" (forever.launcher) with its own icon and logo; the Blizzard icon art is gone from the APK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForeverLauncherBrandingTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun packageMetadataProvidesTheNewPlainBitmapIcon() {
        assertEquals(R.mipmap.ic_package_042, context.applicationInfo.icon)
        val icon = ContextCompat.getDrawable(context, context.applicationInfo.icon)
        assertTrue("APK viewers receive a plain bitmap, not an adaptive XML", icon is android.graphics.drawable.BitmapDrawable)
    }

    @Test fun selected042EmblemHasRealTransparencyAndIsUsedBySplashAndAdaptiveIcon() {
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.forever_emblem_042)
        assertNotNull(bitmap)
        assertTrue(bitmap.hasAlpha())
        assertEquals(bitmap.width, bitmap.height)
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        // Compose painterResource accepts a bitmap file or vector XML, not a <bitmap> XML wrapper.
        val value = android.util.TypedValue()
        context.resources.getValue(R.drawable.forever_emblem_042, value, true)
        assertTrue(value.string.toString().endsWith(".png"))
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val splash = File(res.parentFile, "java/app/gamenative/ui/components/BootingSplash.kt").readText()
        assertTrue(splash.contains("ImageBitmap.imageResource(R.drawable.forever_emblem_042)"))
        assertTrue(File(res, "drawable/forever_logo.xml").readText().contains("@drawable/forever_emblem_042"))
        val foreground = File(res, "drawable/ic_launcher_foreground.xml").readText()
        assertTrue(foreground.contains("@drawable/forever_icon_layer_042"))
        assertFalse("The safe margin must not depend on a wrapper", foreground.contains("<inset"))
        bitmap.recycle()
    }

    @Test fun monochromeAndNotificationResourcesKeepTransparentBackground() {
        for (id in listOf(R.drawable.forever_emblem_042_mono, R.drawable.forever_notification_042)) {
            val bitmap = BitmapFactory.decodeResource(context.resources, id)
            assertNotNull(bitmap)
            assertTrue(bitmap.hasAlpha())
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            bitmap.recycle()
        }
    }

    @Test fun packageAndOwnArtwork() {
        assertEquals("com.bryanengle.classicera.launcher", BuildConfig.APPLICATION_ID)
        listOf(R.drawable.forever_logo, R.drawable.ic_stat_forever, R.drawable.ic_launcher_foreground, R.drawable.ic_launcher_background,
            R.drawable.ic_launcher_monochrome, R.mipmap.ic_launcher, R.mipmap.ic_launcher_round).forEach { assertNotNull(ContextCompat.getDrawable(context, it)) }
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        // The old adaptive foreground was a bitmap of the Blizzard artwork.
        assertTrue(res.walkTopDown().none { it.name == "ic_launcher_foreground.png" })
        // The launcher icons use the standard layout: per-density foreground bitmaps (108 dp each), no nodpi/layer-list sizing,
        // which the RedMagic (ZTE) launcher drew enlarged and clipped.
        listOf("ic_launcher.xml", "ic_launcher_round.xml", "ic_launcher_042.xml", "ic_launcher_042_round.xml").forEach {
            val xml = File(res, "mipmap-anydpi-v26/$it").readText()
            assertTrue(it, xml.contains("@mipmap/ic_launcher_foreground_042") && xml.contains("@mipmap/ic_launcher_monochrome_042"))
        }
        mapOf("mdpi" to 108, "hdpi" to 162, "xhdpi" to 216, "xxhdpi" to 324, "xxxhdpi" to 432).forEach { (dens, px) ->
            listOf("ic_launcher_foreground_042.png", "ic_launcher_monochrome_042.png").forEach { name ->
                val header = File(res, "mipmap-$dens/$name").readBytes()   // PNG IHDR: width and height at bytes 16..23
                val w = java.nio.ByteBuffer.wrap(header, 16, 4).int; val h = java.nio.ByteBuffer.wrap(header, 20, 4).int
                assertTrue("$dens/$name ${w}x$h", w == px && h == px)
            }
        }
    }
}
