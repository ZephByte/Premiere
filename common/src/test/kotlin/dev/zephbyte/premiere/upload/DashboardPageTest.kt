package dev.zephbyte.premiere.upload

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class DashboardPageTest {

    @Test
    fun `dashboard includes synchronized player controls`() {
        val html = DashboardPage.render("test-token")

        assertContains(html, """id="preview"""")
        assertContains(html, """id="timeline"""")
        assertContains(html, """/api/screen/seek""")
        assertContains(html, """positionMs""")
        assertContains(html, """/api/screen/volume""")
        assertContains(html, """id="screenRadius"""")
        assertContains(html, """/api/screen/radius""")
        assertContains(html, """id="resetRadius"""")
        assertContains(html, """object-fit: contain""")
        assertContains(html, """id="screenQueue"""")
        assertContains(html, """id="queueMovie"""")
        assertContains(html, """/api/screen/queue""")
        assertContains(html, """/api/screen/load""")
    }

    @Test
    fun `live preview is opt in and pauses when the dashboard is hidden`() {
        val html = DashboardPage.render("test-token")

        assertContains(html, """id="togglePreview"""")
        assertContains(html, """let livePreviewEnabled = false""")
        assertContains(html, """return livePreviewEnabled && !document.hidden && !el("tab-screens").hidden""")
        assertContains(html, """Live preview is off to protect the theater stream""")
    }

    @Test
    fun `movie chooser uses a searchable library instead of a name prompt`() {
        val html = DashboardPage.render("test-token")

        assertContains(html, """id="moviePicker"""")
        assertContains(html, """id="movieSearch"""")
        assertContains(html, """data-movie-key""")
        assertContains(html, """await loadMovies()""")
        assertFalse(html.contains("Movie name (or URL)"))
    }

    @Test
    fun `rename uses an in-page form and reports server errors`() {
        val html = DashboardPage.render("test-token")

        assertContains(html, """id="renameDialog"""")
        assertContains(html, """id="renameForm"""")
        assertContains(html, """await api("/api/rename", { key, newName })""")
        assertContains(html, """Rename failed: " + e.message""")
        assertFalse(html.contains("prompt(\"New name"))
    }

    @Test
    fun `session token cannot break out of the script`() {
        val malicious = """</script><script>alert("oops")</script>"""
        val html = DashboardPage.render(malicious)

        assertFalse(html.contains("const TOKEN = \"$malicious\""))
        assertContains(html, "\\u003c/script\\u003e")
    }
}
