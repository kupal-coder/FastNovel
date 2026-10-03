package com.lagradost.quicknovel.ui.home

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Visual tokens scoped to the new Home and Novel Finder screens. */
internal object NovelHomeTokens {
    val background = Color(0xFF071321)
    val surface = Color(0xFF101F32)
    val elevatedSurface = Color(0xFF17283D)
    val chipSurface = Color(0xFF243850)
    val accent = Color(0xFF9BB8FF)
    val accentText = Color(0xFF09172A)
    val text = Color(0xFFF1F5FC)
    val mutedText = Color(0xFF9BAEC4)
    val error = Color(0xFFFFA3A3)

    val cardShape: Shape = RoundedCornerShape(22.dp)
    val smallCardShape: Shape = RoundedCornerShape(16.dp)
    val coverShape: Shape = RoundedCornerShape(13.dp)
    val pillShape: Shape = RoundedCornerShape(50)
    val chipShape: Shape = RoundedCornerShape(50)

    val pageHorizontal: Dp = 18.dp
    val sectionGap: Dp = 24.dp
    val cardGap: Dp = 12.dp
    val chipHorizontalPadding: Dp = 10.dp
    val chipVerticalPadding: Dp = 5.dp
}
