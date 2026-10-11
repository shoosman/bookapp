package com.shoosman.bookapp.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shoosman.bookapp.data.AppSettings
import com.shoosman.bookapp.text.ParaKind

/** Как выглядит абзац: стиль текста и отступы. Общее для прокрутки, страниц и разметки страниц. */
class ParaLook(val style: TextStyle, val top: Dp, val bottom: Dp, val start: Dp)

/**
 * @param continuation абзац продолжается с предыдущей страницы — без красной строки и отступа сверху
 */
fun paraLook(kind: ParaKind, s: AppSettings, color: Color, continuation: Boolean = false): ParaLook {
    val size = s.fontSize
    val base = TextStyle(
        color = color,
        fontFamily = if (s.serif) FontFamily.Serif else FontFamily.SansSerif,
        fontSize = size.sp,
        lineHeight = (size * s.lineHeight).sp,
        hyphens = Hyphens.Auto,
        lineBreak = LineBreak.Paragraph,
    )
    return when (kind) {
        ParaKind.TITLE -> ParaLook(
            base.copy(
                fontSize = (size * 1.25f).sp,
                lineHeight = (size * 1.25f * 1.3f).sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                hyphens = Hyphens.None,
            ),
            top = if (continuation) 0.dp else 28.dp, bottom = 14.dp, start = 0.dp,
        )
        ParaKind.SUBTITLE -> ParaLook(
            base.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
            top = if (continuation) 0.dp else 10.dp, bottom = 10.dp, start = 0.dp,
        )
        ParaKind.EPIGRAPH -> ParaLook(
            base.copy(fontStyle = FontStyle.Italic, fontSize = (size * 0.92f).sp, lineHeight = (size * 0.92f * s.lineHeight).sp),
            top = 0.dp, bottom = 4.dp, start = 48.dp,
        )
        ParaKind.POEM -> ParaLook(base.copy(fontStyle = FontStyle.Italic), top = 0.dp, bottom = 0.dp, start = 32.dp)
        ParaKind.AUTHOR -> ParaLook(
            base.copy(fontStyle = FontStyle.Italic, textAlign = TextAlign.End),
            top = 0.dp, bottom = 10.dp, start = 0.dp,
        )
        ParaKind.TEXT -> ParaLook(
            base.copy(
                textAlign = if (s.justify) TextAlign.Justify else TextAlign.Start,
                textIndent = if (continuation) null else TextIndent(firstLine = (size * 1.5f).sp),
            ),
            top = 0.dp, bottom = (size * 0.35f).dp, start = 0.dp,
        )
    }
}
