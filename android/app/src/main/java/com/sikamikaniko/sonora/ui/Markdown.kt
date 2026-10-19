package com.sikamikaniko.sonora.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/** Renders light Markdown (bold, italic, `code`, #/##/### headings, - bullets) that the AI emits. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val accent = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    Text(text = renderMarkdown(text, accent, codeBg), modifier = modifier, style = style)
}

private fun renderMarkdown(src: String, accent: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    val lines = src.split("\n")
    lines.forEachIndexed { i, raw ->
        var line = raw
        var headingSize = 0f
        when {
            line.startsWith("### ") -> { headingSize = 17f; line = line.removePrefix("### ") }
            line.startsWith("## ") -> { headingSize = 19f; line = line.removePrefix("## ") }
            line.startsWith("# ") -> { headingSize = 21f; line = line.removePrefix("# ") }
        }
        val bullet = line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")
        if (bullet) { append("•  "); line = line.substring(2) }

        if (headingSize > 0f) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingSize.sp)) {
                appendInline(line, accent, codeBg)
            }
        } else {
            appendInline(line, accent, codeBg)
        }
        if (i < lines.lastIndex) append("\n")
    }
}

/** Inline bold **…**, italic *…* / _…_, and `code`. Single-level (no nesting) — enough for AI prose. */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(line: String, accent: Color, codeBg: Color) {
    var i = 0
    while (i < line.length) {
        val c = line[i]
        // inline code
        if (c == '`') {
            val end = line.indexOf('`', i + 1)
            if (end > i) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = accent)) {
                    append(line.substring(i + 1, end))
                }
                i = end + 2; continue
            }
        }
        // bold **…**
        if (c == '*' && i + 1 < line.length && line[i + 1] == '*') {
            val end = line.indexOf("**", i + 2)
            if (end > i + 1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line.substring(i + 2, end)) }
                i = end + 2; continue
            }
        }
        // italic *…* or _…_
        if (c == '*' || c == '_') {
            val end = line.indexOf(c, i + 1)
            if (end > i && line.substring(i + 1, end).isNotBlank()) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(line.substring(i + 1, end)) }
                i = end + 1; continue
            }
        }
        append(c); i++
    }
}
