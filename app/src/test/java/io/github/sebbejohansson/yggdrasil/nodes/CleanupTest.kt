package io.github.sebbejohansson.yggdrasil.nodes

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CleanupTest {
    private val json = """
        {"afterDays":8,"bytes":1610612736,
         "projects":[
           {"name":"small","path":"/p/small","lastActive":1,"inactive":false,"targets":[{"path":"/p/small/node_modules","bytes":1048576}],"bytes":1048576},
           {"name":"old-site","path":"/p/old-site","lastActive":1,"inactive":true,"targets":[{"path":"a","bytes":1},{"path":"b","bytes":1}],"bytes":1610612736},
           {"name":"clean","path":"/p/clean","lastActive":1,"inactive":true,"targets":[],"bytes":0}],
         "last":{"at":1700000000000,"removed":[{"path":"x","bytes":5}],"bytes":5}}
    """.trimIndent()

    @Test
    fun readsWhatCanGo() {
        val s = Cleanup.parseStatus(json)
        assertEquals(8, s.afterDays)
        assertEquals(listOf("old-site", "small", "clean"), s.projects.map { it.name })
        // Quiet with something to remove: what Clean now takes.
        assertEquals(listOf("old-site"), s.quiet.map { it.name })
        assertEquals(2, s.projects.first().folders)
        assertEquals(1, s.last!!.folders)
        assertEquals("1 quiet project · 1.5 GB to free · cleans projects quiet for 8 days", Cleanup.summary(s))
    }

    @Test
    fun sizesAndOldMachines() {
        assertEquals("230 MB", Cleanup.size(230L shl 20))
        assertEquals("12 KB", Cleanup.size(12L shl 10))
        assertTrue(Cleanup.unsupported("VPS's sindri answered 404: not found"))
        assertEquals("Nothing to clean · automatic cleaning off",
            Cleanup.summary(CleanupStatus(0, emptyList(), 0, null)))
    }
}
