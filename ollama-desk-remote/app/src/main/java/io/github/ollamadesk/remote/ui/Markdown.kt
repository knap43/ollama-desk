package io.github.ollamadesk.remote.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val indent: Int, val text: String) : Block
    data class Quote(val text: String) : Block
    data class Code(val lang: String, val code: String) : Block
    data class Table(val lines: List<String>) : Block
}

private fun parseBlocks(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = mutableListOf<String>()
    val table = mutableListOf<String>()
    fun flush() {
        if (paragraph.isNotEmpty()) blocks.add(Block.Paragraph(paragraph.joinToString(" ")))
        paragraph.clear()
        if (table.isNotEmpty()) blocks.add(Block.Table(table.toList()))
        table.clear()
    }
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val lang = trimmed.removePrefix("```").trim()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.add(lines[i])
                    i++
                }
                blocks.add(Block.Code(lang, code.joinToString("\n")))
            }
            trimmed.isEmpty() -> flush()
            trimmed.startsWith("|") -> {
                if (paragraph.isNotEmpty()) {
                    blocks.add(Block.Paragraph(paragraph.joinToString(" ")))
                    paragraph.clear()
                }
                if (!Regex("^\\|?[\\s:|-]+\\|?$").matches(trimmed)) table.add(trimmed)
            }
            Regex("^#{1,6}\\s+.*").matches(trimmed) -> {
                flush()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks.add(Block.Heading(level, trimmed.drop(level).trim()))
            }
            Regex("^\\s*([-*+]|\\d+[.)])\\s+.*").matches(line) -> {
                flush()
                val indent = line.length - line.trimStart().length
                val marker = Regex("^\\s*([-*+]|\\d+[.)])\\s+").find(line)!!
                val bullet = marker.groupValues[1]
                val body = line.substring(marker.range.last + 1)
                blocks.add(Block.Bullet(indent / 2, if (bullet[0].isDigit()) "$bullet $body" else body))
            }
            trimmed.startsWith(">") -> {
                flush()
                blocks.add(Block.Quote(trimmed.removePrefix(">").trim()))
            }
            Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(trimmed) -> flush()
            else -> {
                if (table.isNotEmpty()) flush()
                paragraph.add(trimmed)
            }
        }
        i++
    }
    flush()
    return blocks
}

/** Inline **bold**, *italic*, `code`, ~~strike~~ and [links](…) as styled text. */
fun inline(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("`([^`]+)`|\\*\\*(.+?)\\*\\*|__(.+?)__|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])|~~(.+?)~~|\\[([^\\]]+)]\\(([^)\\s]+)\\)")
    var last = 0
    for (m in pattern.findAll(text)) {
        append(text.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(g[1]) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[2]) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[5]) }
            else -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(g[6]) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val type = MaterialTheme.typography
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (block in blocks) {
                when (block) {
                    is Block.Paragraph -> Text(inline(block.text, codeBg), style = type.bodyLarge)
                    is Block.Heading -> Text(
                        inline(block.text, codeBg),
                        style = when (block.level) {
                            1 -> type.titleLarge
                            2 -> type.titleMedium
                            else -> type.titleSmall
                        },
                    )
                    is Block.Bullet -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                        Text("•  ", style = type.bodyLarge)
                        Text(inline(block.text, codeBg), style = type.bodyLarge)
                    }
                    is Block.Quote -> Text(
                        inline(block.text, codeBg),
                        style = type.bodyLarge.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is Block.Code -> CodeBlock(block.lang, block.code)
                    is Block.Table -> Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            block.lines.joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            style = type.bodySmall,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (lang.isNotEmpty()) {
                Text(lang, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            )
        }
    }
}
