package com.parachord.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.parachord.shared.home.CONCERT_PHOTO_FOCUS_Y

/**
 * A concert card's photo (Home's Concerts near you and the Concerts page): the
 * URL from the shared `concertPhotoUrl` (the artist's photo, else the event's
 * image), cropped to fill and held a quarter of the way down, desktop's
 * `object-position: center 25%`, so faces aren't cut off. Draw the card's
 * placeholder under it: nothing shows while it loads or when it fails.
 */
@Composable
fun ConcertPhoto(url: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = BiasAlignment(0f, CONCERT_PHOTO_FOCUS_Y * 2f - 1f),
        modifier = modifier,
    )
}
