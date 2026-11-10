package com.webview.builder.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File

//========== SHARED UI PIECES ==========

/** Expressive shape set, cycled per app so neighbours in a list look different. */
val AvatarShapes: List<Shape> = listOf(
    CircleShape,
    RoundedCornerShape(14.dp),
    RoundedCornerShape(50, 50, 50, 25),
    RoundedCornerShape(topStart = 22.dp, topEnd = 8.dp, bottomEnd = 22.dp, bottomStart = 8.dp),
)

/**
 * The app's icon on its icon background color, in [shape]. Falls back to the
 * initials when the image can't be read. [stamp] forces a reload after an edit.
 */
@Composable
fun AppAvatar(
    iconFile: File,
    background: String,
    initials: String,
    shape: Shape,
    size: Dp = 44.dp,
    stamp: Long = iconFile.lastModified(),
) {
    val bitmap = remember(iconFile.path, stamp) {
        runCatching { BitmapFactory.decodeFile(iconFile.path)?.asImageBitmap() }.getOrNull()
    }
    val color = remember(background) { runCatching { Color(android.graphics.Color.parseColor(background)) }.getOrDefault(Color.White) }
    Box(
        modifier = Modifier.size(size).clip(shape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            // 70% of the avatar, like the 20% inset of the adaptive icon on each side.
            Image(bitmap, contentDescription = null, modifier = Modifier.size(size * 0.7f), contentScale = ContentScale.Fit)
        } else {
            Text(initials, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** A tinted rounded box with an icon and text, for warnings and notes. */
@Composable
fun Banner(
    icon: ImageVector,
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(container).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = content)
        Column {
            if (title != null) Text(title, color = content, style = MaterialTheme.typography.titleSmall)
            Text(text, color = content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Rounded corners for an item in a connected list: big outer corners on the
 * first and last item, small inner ones between them.
 */
fun groupShape(index: Int, count: Int, outer: Dp = 24.dp, inner: Dp = 6.dp): Shape = when {
    count == 1 -> RoundedCornerShape(outer)
    index == 0 -> RoundedCornerShape(outer, outer, inner, inner)
    index == count - 1 -> RoundedCornerShape(inner, inner, outer, outer)
    else -> RoundedCornerShape(inner)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
fun VSpace(height: Dp) = Spacer(Modifier.size(height))
