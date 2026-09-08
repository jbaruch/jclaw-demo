package com.jbaruch.jclaw.tui

import dev.tamboui.buffer.Buffer
import dev.tamboui.layout.Rect
import dev.tamboui.markdown.MarkdownStyles
import dev.tamboui.markdown.MarkdownView
import dev.tamboui.style.Color
import dev.tamboui.style.Style
import dev.tamboui.text.Line
import dev.tamboui.text.Span

/** Parse whole replies before wrapping, then keep one styled row per scrollable list item. */
internal fun markdownLines(message: String, width: Int): List<Line> {
    // The app adds this label outside the model response. Put it on its own row so
    // a heading, list, or code fence at the start of the reply still starts a block.
    val labelled = message.startsWith("j-claw: ")
    val source = if (labelled) message.removePrefix("j-claw: ") else message
    val markdown = MarkdownView.builder()
        .source(source)
        .style(REPLY_STYLE)
        .styles(REPLY_MARKDOWN_STYLES)
        .build()
    val area = Rect(0, 0, width, markdown.computeHeight(width))
    val buffer = Buffer.empty(area)
    markdown.render(area, buffer)

    val rows = (0 until buffer.height()).map { y ->
        val spans = (0 until buffer.width()).mapNotNull { x ->
            val cell = buffer.get(x, y)
            // A wide glyph owns its continuation cell; adding it again changes width.
            if (cell.isContinuation) null else Span.styled(cell.symbol(), cell.style())
        }
        Line.from(spans)
    }
    return if (labelled) listOf(Line.from(Span.styled("j-claw:", REPLY_STYLE.bold()))) + rows else rows
}

private val REPLY_STYLE = Style.EMPTY.fg(Color.BLUE)

// Preserve the chat role's blue foreground. Weight and emphasis come from Markdown,
// and block quotes/code must remain legible on the presentation terminal.
private val REPLY_MARKDOWN_STYLES = MarkdownStyles.builder().apply {
    (1..6).forEach { heading(it, REPLY_STYLE.bold()) }
    inlineCode(REPLY_STYLE.underlined())
    codeBlock(REPLY_STYLE)
    link(REPLY_STYLE.underlined())
    blockquote(REPLY_STYLE.italic())
    listMarker(REPLY_STYLE)
    html(REPLY_STYLE)
    horizontalRule(REPLY_STYLE)
    taskChecked(REPLY_STYLE)
    taskUnchecked(REPLY_STYLE)
}.build()
