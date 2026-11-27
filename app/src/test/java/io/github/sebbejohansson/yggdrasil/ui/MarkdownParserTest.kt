package io.github.sebbejohansson.yggdrasil.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownParserTest {
    @Test fun blocksAsClaudeWritesThem() {
        val md = """
            **Yggdrasil Mobile** is an app.

            ## What it does
            - **On-device AI:** Gemma
            - The look: a HUD
              that wraps

            1. one
            2. two

            | Package | Role |
            |---|---|
            | `norn/` | Chat |

            ```kotlin
            val x = 1
            ```
            > quoted
            ---
        """.trimIndent()
        assertEquals(
            listOf(
                MdBlock.Paragraph("**Yggdrasil Mobile** is an app."),
                MdBlock.Heading(2, "What it does"),
                MdBlock.Items(listOf("**On-device AI:** Gemma", "The look: a HUD that wraps"), ordered = false),
                MdBlock.Items(listOf("one", "two"), ordered = true),
                MdBlock.Table(listOf(listOf("Package", "Role"), listOf("`norn/`", "Chat"))),
                MdBlock.Code("val x = 1"),
                MdBlock.Quote("quoted"),
                MdBlock.Rule,
            ),
            MarkdownParser.blocks(md),
        )
    }

    @Test fun inlineSpans() {
        assertEquals(
            listOf(
                MdSpan("Runs "),
                MdSpan("Claude", bold = true),
                MdSpan(" in "),
                MdSpan("herdr", code = true),
                MdSpan(", "),
                MdSpan("quietly", italic = true),
                MdSpan(": "),
                MdSpan("docs", link = true),
            ),
            MarkdownParser.spans("Runs **Claude** in `herdr`, *quietly*: [docs](https://herdr.dev)"),
        )
        // Underscores and stars inside words are text.
        assertEquals(listOf(MdSpan("ygg_bridge.py and 2*3*4")), MarkdownParser.spans("ygg_bridge.py and 2*3*4"))
    }

    @Test
    fun plainLeavesTheWordsForAPreview() {
        assertEquals(
            "Yggdrasil Mobile is an Android app (Kotlin). How it works Content: in app/storyblok/ docs",
            MarkdownParser.plain("**Yggdrasil Mobile** is an Android app (Kotlin).\n\n## How it works\n- **Content:** in `app/storyblok/`\n- [docs](https://x.y)"),
        )
    }

    @Test
    fun theEndOfAListIsTheLastItemShownWhole() {
        assertEquals(true, FollowBottom.atEnd(null, 0, 0, 100))
        assertEquals(true, FollowBottom.atEnd(9, 10, 100, 100))
        assertEquals(false, FollowBottom.atEnd(9, 10, 400, 100))
        assertEquals(false, FollowBottom.atEnd(7, 10, 90, 100))
    }
}
