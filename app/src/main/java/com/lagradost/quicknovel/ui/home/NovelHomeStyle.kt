package com.lagradost.quicknovel.ui.home

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shapes and spacing for the Home screen. All colors come from CloudStreamTheme.colors, so the
 * screen follows the theme mode and accent color picked in Settings like every other screen.
 */
internal object NovelHomeStyle {
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
