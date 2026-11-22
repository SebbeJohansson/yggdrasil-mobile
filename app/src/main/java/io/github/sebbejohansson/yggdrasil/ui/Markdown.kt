package io.github.sebbejohansson.yggdrasil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Markdown Claude writes, enough of it to read well: headings, lists,
 * code blocks, tables, quotes, rules, and inline bold, italic, code and links.
 * [MarkdownParser] is pure, for tests; [Markdown] draws it.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Items(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

/** A piece of a line: plain, bold, italic, code or a link. */
data class MdSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val link: Boolean = false)

object MarkdownParser {
    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val bullet = Regex("""^\s*[-*+]\s+(.*)$""")
    private val numbered = Regex("""^\s*\d+[.)]\s+(.*)$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val tableSep = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    fun blocks(text: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val lines = text.replace("\r\n", "\n").split("\n")
        var i = 0
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) out += MdBlock.Paragraph(para.joinToString(" ") { it.trim() })
            para.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    flush()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) code += lines[i++]
                    out += MdBlock.Code(code.joinToString("\n"))
                }
                line.isBlank() -> flush()
                heading.matches(line) -> { flush(); heading.find(line)!!.let { out += MdBlock.Heading(it.groupValues[1].length, it.groupValues[2].trim().trimEnd('#').trim()) } }
                rule.matches(line) -> { flush(); out += MdBlock.Rule }
                line.trimStart().startsWith("|") && i + 1 < lines.size && tableSep.matches(lines[i + 1]) -> {
                    flush()
                    val rows = mutableListOf(cells(line))
                    i += 2
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) rows += cells(lines[i++])
                    out += MdBlock.Table(rows)
                    continue
                }
                bullet.matches(line) || numbered.matches(line) -> {
                    flush()
                    val ordered = numbered.matches(line) && !bullet.matches(line)
                    val items = mutableListOf<String>()
                    while (i < lines.size) {
                        val l = lines[i]
                        val m = (if (ordered) numbered else bullet).find(l)
                        if (m != null && (ordered == (numbered.matches(l) && !bullet.matches(l)))) items += m.groupValues[1].trim()
                        else if (l.isNotBlank() && l.startsWith("  ") && items.isNotEmpty()) items[items.lastIndex] = items.last() + " " + l.trim()
                        else break
                        i++
                    }
                    out += MdBlock.Items(items, ordered)
                    continue
                }
                line.trimStart().startsWith(">") -> {
                    flush()
                    val q = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) q += lines[i++].trimStart().removePrefix(">").trim()
                    out += MdBlock.Quote(q.joinToString(" "))
                    continue
                }
                else -> para += line
            }
            i++
        }
        flush()
        return out
    }

    private fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    private val inline = Regex("""`([^`]+)`|\*\*(.+?)\*\*|__(.+?)__|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)|(?<![\w_])_(?!\s)(.+?)(?<!\s)_(?![\w])|\[([^\]]+)]\(([^)\s]+)\)""")

    /** Inline spans; bold may hold italic or code, one level deep. */
    fun spans(text: String, bold: Boolean = false): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        var at = 0
        for (m in inline.findAll(text)) {
            if (m.range.first > at) out += MdSpan(text.substring(at, m.range.first), bold = bold)
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> out += MdSpan(g[1], bold = bold, code = true)
                g[2].isNotEmpty() -> out += spans(g[2], bold = true)
                g[3].isNotEmpty() -> out += spans(g[3], bold = true)
                g[4].isNotEmpty() -> out += MdSpan(g[4], bold = bold, italic = true)
                g[5].isNotEmpty() -> out += MdSpan(g[5], bold = bold, italic = true)
                g[6].isNotEmpty() -> out += MdSpan(g[6], bold = bold, link = true)
            }
            at = m.range.last + 1
        }
        if (at < text.length) out += MdSpan(text.substring(at), bold = bold)
        return out
    }
}

private fun annotated(text: String): AnnotatedString = buildAnnotatedString {
    MarkdownParser.spans(text).forEach { s ->
        withStyle(
            SpanStyle(
                fontWeight = if (s.bold) FontWeight.SemiBold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                fontFamily = if (s.code) HudFonts.mono else null,
                background = if (s.code) Hud.surface0 else androidx.compose.ui.graphics.Color.Unspecified,
                color = if (s.link) Hud.cyan else androidx.compose.ui.graphics.Color.Unspecified,
                textDecoration = if (s.link) TextDecoration.Underline else null,
            ),
        ) { append(s.text) }
    }
}

/** Draws Markdown in Hud's look. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, style: TextStyle = HudText.small.copy(color = Hud.text)) {
    val blocks = remember(text) { MarkdownParser.blocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(annotated(b.text), style = style.copy(
                    fontWeight = FontWeight.Bold, color = Hud.bright,
                    fontSize = when (b.level) { 1 -> 20.sp; 2 -> 17.sp; else -> style.fontSize }), modifier = Modifier.padding(top = 4.dp))
                is MdBlock.Paragraph -> Text(annotated(b.text), style = style)
                is MdBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    b.items.forEachIndexed { n, item ->
                        Row {
                            Text(if (b.ordered) "${n + 1}." else "•", style = style.copy(color = Hud.cyan), modifier = Modifier.width(22.dp))
                            Text(annotated(item), style = style)
                        }
                    }
                }
                is MdBlock.Code -> Text(
                    b.text, style = style.copy(fontFamily = HudFonts.mono, fontSize = 12.sp), softWrap = false,
                    modifier = Modifier.fillMaxWidth().background(Hud.crust).horizontalScroll(rememberScrollState()).padding(8.dp),
                )
                is MdBlock.Table -> MdTable(b, style)
                is MdBlock.Quote -> Text(annotated(b.text), style = style.copy(color = Hud.subtext0, fontStyle = FontStyle.Italic),
                    modifier = Modifier.leftStripe(Hud.overlay0).padding(start = 10.dp))
                MdBlock.Rule -> Text("─".repeat(24), style = style.copy(color = Hud.line))
            }
        }
    }
}

/** A table whose columns line up: each column as wide as its widest cell, up to a cap; long cells wrap. */
@Composable
private fun MdTable(b: MdBlock.Table, style: TextStyle) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val maxCell = 240.dp
    val columns = b.rows.maxOf { it.size }
    val widths = remember(b, style) {
        val maxPx = with(density) { maxCell.roundToPx() }
        (0 until columns).map { col ->
            val px = b.rows.mapIndexed { r, row ->
                val cell = row.getOrNull(col) ?: ""
                val s = if (r == 0) style.copy(fontWeight = FontWeight.SemiBold) else style
                measurer.measure(annotated(cell), s, constraints = Constraints(maxWidth = maxPx)).size.width
            }.maxOrNull() ?: 0
            // Room for the text and the gap after it.
            with(density) { px.toDp() } + 14.dp
        }
    }
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Hud.crust).padding(6.dp)) {
        b.rows.forEachIndexed { r, row ->
            Row(Modifier.padding(vertical = 3.dp)) {
                (0 until columns).forEach { col ->
                    Text(annotated(row.getOrNull(col) ?: ""), style = style.copy(fontWeight = if (r == 0) FontWeight.SemiBold else null,
                        color = if (r == 0) Hud.bright else style.color), modifier = Modifier.width(widths[col]).padding(end = 12.dp))
                }
            }
            if (r == 0) Box(Modifier.width(widths.fold(0.dp) { a, w -> a + w }).height(1.dp).background(Hud.line))
        }
    }
}
