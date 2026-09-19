package com.mazhar.apkappstore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Bottom-navigation item used by the custom store UI.
 * Material3's NavigationBarItem is a RowScope extension, while our reusable
 * item lives in its own composable. This overload keeps the reusable component
 * independent from RowScope and gives it the compact Play-Store-style layout.
 */
@Composable
fun NavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    colors: Any? = null
) {
    val content = if (selected) Color(0xFF3398FF) else Color(0xFFA8AFBC)
    CompositionLocalProvider(LocalContentColor provides content) {
        Column(
            modifier = Modifier
                .width(72.dp)
                .clickable(onClick = onClick)
                .padding(vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            icon()
            label()
        }
    }
}
