package com.verdroid.app.ui.chats

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.verdroid.app.data.profiles.Profile
import com.verdroid.app.data.profiles.Profiles
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Home top bar at 360 dp with the update pill, long profile names and big fonts: nothing overlaps or leaves the bar. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
class HomeTopBarScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val long = Profile("p1", "Research and long-form investigations", "🔎")

    private fun render(name: String, width: Int, fontScale: Float, update: String?, profiles: List<Profile>, active: Profile, quota: Boolean) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                MaterialTheme { Surface { Box(Modifier.width(width.dp).testTag("bar")) {
                    HomeTopBar(update, update != null,
                        quota = if (quota) ({ androidx.compose.material3.IconButton(onClick = {}) { androidx.compose.material3.Text("•") } }) else null,
                        profiles = profiles, active = active, showAll = false, onSelect = {}, onShowAll = {}, onNew = {}, onManage = {}, onOpenSettings = {})
                } } }
            }
        }
        val bar = rule.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
        val gear = rule.onNode(hasContentDescription("Settings", substring = true)).fetchSemanticsNode().boundsInRoot
        val profile = rule.onNode(hasContentDescription("Profile:", substring = true)).fetchSemanticsNode().boundsInRoot
        val title = rule.onNodeWithText("Verdroid").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithContentDescription("Profile: ${active.name}").assertExists()
        assertTrue("$name: profile name shown", rule.onAllNodesWithText(active.name, substring = true).fetchSemanticsNodes().isEmpty())
        val out = File("build/screenshots").apply { mkdirs() }
        val root = rule.activity.window.decorView
        val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { root.draw(android.graphics.Canvas(full)) }
        val bmp = Bitmap.createBitmap(full, 0, 0, bar.right.toInt().coerceAtMost(full.width), bar.bottom.toInt().coerceIn(1, full.height))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$name: gear off screen $gear vs $bar", gear.right <= bar.right + 0.5f && gear.left >= bar.left)
        assertTrue("$name: profile overlaps gear", profile.right <= gear.left + 0.5f)
        assertTrue("$name: title overlaps actions", title.right <= profile.left + 0.5f)
        val pill = rule.onAllNodes(hasContentDescription("New version", substring = true)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
        if (pill != null) assertTrue("$name: update pill overlaps actions $pill / $profile", pill.right <= profile.left + 0.5f && pill.left >= title.right - 0.5f && pill.width > 20f)
        // title stays one line (26 sp bold): height well under two lines
        assertTrue("$name: title wrapped (${title.height})", title.height < 26 * fontScale * 3 * 1.9f)
    }

    @Test fun defaultOnly_noUpdate() = render("default_only_no_update", 360, 1f, null, listOf(Profiles.DEFAULT), Profiles.DEFAULT, false)
    @Test fun update_longProfile() = render("update_long_profile", 360, 1f, "1.0.66", listOf(Profiles.DEFAULT, long), long, true)
    @Test fun small_bigFont_update_longProfile() = render("small_font150_update_long_profile", 320, 1.5f, "1.0.66", listOf(Profiles.DEFAULT, long), long, true)
    @Test fun noUpdate_longProfile() = render("no_update_long_profile", 360, 1f, null, listOf(Profiles.DEFAULT, long), long, true)
    @Test fun emojiGrid() {
        rule.setContent { MaterialTheme { Surface { Box(Modifier.width(360.dp).testTag("bar")) {
            androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) { com.verdroid.app.ui.profiles.EmojiGrid("💻") {} }
        } } } }
        assertTrue(com.verdroid.app.ui.profiles.SUGGESTED_EMOJI.size in 16..24)
        rule.onNodeWithContentDescription("Icon 💻, selected").assertExists()
        shot("emoji_grid", rule.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot)
    }

    private fun shot(name: String, bar: androidx.compose.ui.geometry.Rect) {
        val root = rule.activity.window.decorView
        val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { root.draw(android.graphics.Canvas(full)) }
        val bmp = Bitmap.createBitmap(full, 0, 0, bar.right.toInt().coerceAtMost(full.width), bar.bottom.toInt().coerceIn(1, full.height))
        File("build/screenshots").apply { mkdirs() }.let { File(it, "$name.png") }.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun tiers() {
        assertTrue(HomeBarLayout.level(600f, 1f) == HomeBarLayout.FULL && HomeBarLayout.level(360f, 1f) == HomeBarLayout.SHORT &&
            HomeBarLayout.level(360f, 1.3f) == HomeBarLayout.TIGHT && HomeBarLayout.level(320f, 1.5f) == HomeBarLayout.TIGHT)
    }
    @Test fun font130_update() = render("font130_update_long_profile", 360, 1.3f, "1.0.66", listOf(Profiles.DEFAULT, long), long, true)
}
