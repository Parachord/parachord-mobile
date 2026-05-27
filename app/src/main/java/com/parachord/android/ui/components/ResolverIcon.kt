package com.parachord.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parachord.android.R
import com.parachord.android.resolver.ResolverScoring
import com.parachord.android.ui.theme.LocalResolverOrder

/**
 * Resolver icon colors matching the desktop's resolverIconColors.
 * These are the solid brand colors used as icon background squares.
 */
object ResolverIconColors {
    val spotify = Color(0xFF1DB954)
    val bandcamp = Color(0xFF629AA9)
    val qobuz = Color(0xFF4285F4)
    val youtube = Color(0xFFFF0000)
    val localfiles = Color(0xFFA855F7)
    val soundcloud = Color(0xFFFF5500)
    val applemusic = Color(0xFFFA243C)
    val lastfm = Color(0xFFD51007)
    val listenbrainz = Color(0xFF353070)
    val librefm = Color(0xFF4CAF50)
    val discogs = Color(0xFF333333)
    val wikipedia = Color(0xFF000000)
    val chatgpt = Color(0xFF10A37F)
    val claude = Color(0xFFD97757)
    val gemini = Color(0xFF4285F4)
    val ticketmaster = Color(0xFF026CDF)
    val seatgeek = Color(0xFFFC4C02)
    val bandsintown = Color(0xFF00B4B3)
    val songkick = Color(0xFFF80046)
    val achordion = Color(0xFF7C3AED)

    fun forResolver(resolver: String?): Color? = when (resolver?.lowercase()) {
        "spotify" -> spotify
        "bandcamp" -> bandcamp
        "qobuz" -> qobuz
        "youtube" -> youtube
        "localfiles" -> localfiles
        "soundcloud" -> soundcloud
        "applemusic" -> applemusic
        "lastfm" -> lastfm
        "listenbrainz" -> listenbrainz
        "librefm" -> librefm
        "discogs" -> discogs
        "wikipedia" -> wikipedia
        "chatgpt" -> chatgpt
        "claude" -> claude
        "gemini" -> gemini
        "ticketmaster" -> ticketmaster
        "seatgeek" -> seatgeek
        "bandsintown" -> bandsintown
        "songkick" -> songkick
        "achordion" -> achordion
        else -> null
    }
}

/**
 * Icon spec with SVG path data and optional custom viewport.
 * Multiple paths can be separated by "|".
 */
data class IconSpec(
    val path: String,
    val viewportWidth: Float = 24f,
    val viewportHeight: Float = 24f,
    val viewportOffsetX: Float = 0f,
    val viewportOffsetY: Float = 0f,
)

/**
 * SVG path data for resolver service logos, matching the desktop's SERVICE_LOGO_PATHS.
 * Most use viewBox 0 0 24 24; exceptions specify custom viewports via IconSpec.
 */
object ResolverIconPaths {
    val spotify = "M12 0C5.4 0 0 5.4 0 12s5.4 12 12 12 12-5.4 12-12S18.66 0 12 0zm5.521 17.34c-.24.359-.66.48-1.021.24-2.82-1.74-6.36-2.101-10.561-1.141-.418.122-.779-.179-.899-.539-.12-.421.18-.78.54-.9 4.56-1.021 8.52-.6 11.64 1.32.42.18.479.659.301 1.02zm1.44-3.3c-.301.42-.841.6-1.262.3-3.239-1.98-8.159-2.58-11.939-1.38-.479.12-1.02-.12-1.14-.6-.12-.48.12-1.021.6-1.141C9.6 9.9 15 10.561 18.72 12.84c.361.181.54.78.241 1.2zm.12-3.36C15.24 8.4 8.82 8.16 5.16 9.301c-.6.179-1.2-.181-1.38-.721-.18-.601.18-1.2.72-1.381 4.26-1.26 11.28-1.02 15.721 1.621.539.3.719 1.02.419 1.56-.299.421-1.02.599-1.559.3z"

    val youtube = "M23.498 6.186a3.016 3.016 0 0 0-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 0 0 .502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 0 0 2.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 0 0 2.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12l-6.273 3.568z"

    val bandcamp = "M0 18.75l7.437-13.5H24l-7.438 13.5H0z"

    val localfiles = "M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-6 10h-4v-4H8l4-4 4 4h-2v4z"

    // SoundCloud uses a PNG drawable — no SVG path needed

    val applemusic = "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"

    val qobuz = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14.5c-2.49 0-4.5-2.01-4.5-4.5S9.51 7.5 12 7.5s4.5 2.01 4.5 4.5-2.01 4.5-4.5 4.5zm0-7c-1.38 0-2.5 1.12-2.5 2.5s1.12 2.5 2.5 2.5 2.5-1.12 2.5-2.5-1.12-2.5-2.5-2.5z"

    val lastfm = "M10.584 17.209l-.88-2.392s-1.43 1.595-3.573 1.595c-1.897 0-3.244-1.65-3.244-4.289 0-3.381 1.704-4.591 3.382-4.591 2.419 0 3.188 1.567 3.849 3.574l.88 2.75c.879 2.667 2.528 4.811 7.284 4.811 3.409 0 5.719-1.044 5.719-3.793 0-2.227-1.265-3.381-3.629-3.932l-1.76-.385c-1.209-.275-1.566-.77-1.566-1.594 0-.935.742-1.485 1.952-1.485 1.319 0 2.034.495 2.144 1.677l2.749-.33c-.22-2.474-1.924-3.491-4.729-3.491-2.474 0-4.893.935-4.893 3.931 0 1.87.907 3.052 3.188 3.602l1.869.439c1.402.33 1.869.907 1.869 1.705 0 1.017-.989 1.43-2.858 1.43-2.776 0-3.932-1.457-4.591-3.464l-.907-2.749c-1.155-3.574-2.997-4.894-6.653-4.894-4.041-.001-6.186 2.556-6.186 6.899 0 4.179 2.145 6.433 5.993 6.433 3.107.001 4.591-1.457 4.591-1.457z"

    // ListenBrainz — official logo (two triangular shapes, viewBox 0 0 146 160)
    val listenbrainzSpec = IconSpec(
        path = "m75.354 7.823v144l61-35v-74z|m70.354 7.823-61 35v74l61 35z",
        viewportWidth = 146f,
        viewportHeight = 160f,
    )
    val listenbrainz = listenbrainzSpec.path

    // Libre.fm — official wordmark logo (viewBox 0 0 240 80)
    val librefmSpec = IconSpec(
        path = "m 19.97125,26.36764 c 0,-1.23526 0.41912,-2.20585 1.25736,-2.91176 0.70587,-0.70585 1.63234,-1.05879 2.77941,-1.05883 0.88234,4e-5 1.76469,0.37504 2.64706,1.125 0.7941,0.70592 1.19116,1.65445 1.19117,2.84559 l -0.46323,23.69118 9.92647,-0.46323 c 1.01468,0 1.8088,0.39706 2.38235,1.19117 0.57351,0.75001 0.86027,1.78677 0.8603,3.1103 -3e-5,1.05882 -0.28679,1.94117 -0.8603,2.64705 -0.52943,0.70589 -1.32355,1.05883 -2.38235,1.05883 l -13.96324,0 c -0.79412,0 -1.56618,-0.30882 -2.31617,-0.92647 -0.70589,-0.57353 -1.05883,-1.27941 -1.05883,-2.11765 l 0,-28.19118 z m 24.90915,27.26471 -0.2647,-26.93382 c -1e-5,-1.27938 0.39705,-2.31615 1.19117,-3.1103 0.75,-0.66173 1.7647,-0.99261 3.04412,-0.99265 1.2794,4e-5 2.27205,0.33092 2.97794,0.99265 0.70587,0.6618 1.05881,1.56621 1.05883,2.71324 l -0.33089,27.33088 c -1e-5,1.2353 -0.37501,2.22794 -1.125,2.97794 -0.75001,0.70588 -1.63236,1.05883 -2.64706,1.05882 -1.14706,1e-5 -2.07353,-0.35294 -2.77941,-1.05882 -0.75,-0.70588 -1.125,-1.69853 -1.125,-2.97794 l 0,0 z m 13.31406,-27.26471 c 0,-0.88232 0.30882,-1.72055 0.92647,-2.5147 0.61764,-0.88232 1.32353,-1.3235 2.11765,-1.32353 l 12.04412,0 c 2.16174,3e-5 4.03674,0.9265 5.625,2.77941 1.58821,1.85297 2.38232,3.9265 2.38235,6.22059 -3e-5,2.86767 -1.27944,5.09561 -3.83823,6.68382 2.24997,0.35296 3.97056,1.36767 5.16176,3.04412 1.0588,1.50001 1.58821,3.35295 1.58824,5.55882 -3e-5,2.82354 -0.79415,5.29413 -2.38236,7.41177 -1.76473,2.33823 -3.97061,3.50735 -6.61764,3.50735 l -13.96324,0 c -0.79412,0 -1.50001,-0.24265 -2.11765,-0.72794 -0.61765,-0.48529 -0.92647,-1.125 -0.92647,-1.91912 l 0,-28.72059 z m 13.89706,15.81618 -5.82353,0 0,8.60294 5.82353,0 c 1.80881,1e-5 3.13233,-0.37499 3.97059,-1.125 0.70586,-0.70587 1.0588,-1.52205 1.05882,-2.44853 -2e-5,-0.57352 -0.11031,-1.30146 -0.33088,-2.18382 -0.22061,-0.88234 -0.48531,-1.47793 -0.79412,-1.78677 -0.7059,-0.70586 -2.00737,-1.0588 -3.90441,-1.05882 l 0,0 z m -1.19117,-11.97794 -4.63236,0 0,7.34559 5.82353,0 c 1.32351,2e-5 1.98528,-0.97057 1.9853,-2.91177 -2e-5,-1.58821 -0.22061,-2.73527 -0.66177,-3.44117 -0.39708,-0.66174 -1.23531,-0.99262 -2.5147,-0.99265 l 0,0 z m 17.02995,-5.09559 c -10e-6,-0.61761 0.2647,-1.30144 0.79412,-2.05147 0.61764,-0.70585 1.23528,-1.05879 1.85294,-1.05882 l 13.10294,0 c 2.60292,3e-5 4.87498,1.08091 6.81618,3.24264 1.89703,2.25003 2.84556,4.6765 2.84559,7.27941 -3e-5,2.02944 -0.61768,3.86032 -1.85295,5.49265 -1.36767,1.72061 -3.4412,3.0662 -6.22058,4.03677 l 7.875,7.94117 c 0.8382,0.9706 1.25732,2.00736 1.25735,3.1103 -3e-5,1.05883 -0.35297,1.94118 -1.05882,2.64706 -0.26474,0.35294 -0.69489,0.79412 -1.29045,1.32353 -0.59561,0.52941 -1.11399,0.79411 -1.55514,0.79411 -1.50003,0 -2.86767,-0.74999 -4.10294,-2.25 l -10.45589,-12.83823 0,10.52206 c -1e-5,1.27941 -0.41913,2.38235 -1.25735,3.30882 -0.79413,0.79412 -1.76472,1.19118 -2.91177,1.19118 -1.10294,0 -2.00735,-0.39706 -2.71323,-1.19118 -0.75001,-0.70588 -1.12501,-1.80882 -1.125,-3.30882 l 0,-28.19118 z m 8.00735,3.77206 0,8.13971 5.88971,0 c 1.63233,2e-5 2.75733,-0.39704 3.375,-1.19118 0.66174,-0.7941 1.01468,-1.85292 1.05882,-3.17647 -2e-5,-0.74998 -0.35296,-1.61027 -1.05882,-2.58088 -0.61767,-0.79409 -1.74267,-1.19115 -3.375,-1.19118 l -5.88971,0 z m 22.69014,-3.04412 c -1e-5,-1.05879 0.30881,-1.89702 0.92647,-2.51471 0.70587,-0.61761 1.52205,-0.92643 2.44853,-0.92647 l 12.83823,0 c 1.19116,4e-5 2.09557,0.44122 2.71324,1.32353 0.61762,0.83827 0.92644,1.7868 0.92647,2.84559 -3e-5,1.19121 -0.24267,2.13974 -0.72794,2.84559 -0.52944,0.70591 -1.27944,1.05885 -2.25,1.05882 l -10.58824,-0.39705 0,6.75 9.92647,-0.39706 c 0.92645,2e-5 1.65439,0.37502 2.18383,1.125 0.5735,0.75002 0.86027,1.78678 0.86029,3.11029 -2e-5,1.05884 -0.22061,1.96325 -0.66176,2.71324 -0.52944,0.70589 -1.21326,1.05883 -2.05148,1.05882 l -10.25735,-0.33088 0,6.22059 9.92647,-0.46324 c 1.23527,1e-5 2.1838,0.39707 2.84559,1.19118 0.66174,0.70589 0.99262,1.74265 0.99265,3.11029 -3e-5,0.92648 -0.26473,1.72059 -0.79412,2.38235 -0.48532,0.70589 -1.12502,1.05883 -1.91912,1.05883 l -14.69118,0 c -0.52941,0 -1.10294,-0.30882 -1.72058,-0.92647 -0.61766,-0.48529 -0.92648,-1.08088 -0.92647,-1.78677 l 0,-29.05147 z m 29.13024,23.42647 c 1.23528,1e-5 2.20587,0.41913 2.91176,1.25736 0.70587,0.92647 1.05881,1.94118 1.05882,3.04411 -1e-5,1.10295 -0.33089,2.05148 -0.99264,2.84559 -0.75001,0.88236 -1.74266,1.32353 -2.97794,1.32353 -1.32354,0 -2.38236,-0.44117 -3.17648,-1.32353 -0.70588,-0.92647 -1.05882,-1.91911 -1.05882,-2.97794 0,-1.10294 0.375,-2.07352 1.125,-2.91176 0.70588,-0.83823 1.74264,-1.25735 3.1103,-1.25736 l 0,0 z m 8.83665,4.63236 0,-27.8603 c 0,-2.29408 0.99264,-3.44114 2.97794,-3.44118 l 13.56618,0 c 1.10292,4e-5 1.98527,0.3971 2.64706,1.19118 0.61762,0.83827 0.92645,1.76474 0.92647,2.77941 -2e-5,1.32356 -0.22061,2.25003 -0.66176,2.77941 -0.48532,0.6618 -1.16915,0.99268 -2.05148,0.99265 l -10.78676,-0.39706 0,5.625 9.52941,-0.39706 c 1.01469,3e-5 1.78674,0.35297 2.31618,1.05883 0.5735,0.7059 0.86027,1.72061 0.86029,3.04412 -2e-5,0.92648 -0.26473,1.7206 -0.79412,2.38235 -0.57355,0.79413 -1.36766,1.19119 -2.38235,1.19117 l -9.52941,0 0,11.05148 c -1e-5,1.23529 -0.39707,2.18382 -1.19118,2.84558 -0.75001,0.66177 -1.63236,0.99265 -2.64706,0.99265 -0.44118,0 -0.76103,-0.0772 -0.95956,-0.23162 -0.19853,-0.15441 -0.43015,-0.40808 -0.69485,-0.76103 -0.75,-0.61764 -1.125,-1.56617 -1.125,-2.84558 l 0,0 z m 37.4617,-27.00001 5.36029,15.88236 5.75736,-15.88236 c 0.52938,-1.32349 1.27938,-2.3382 2.25,-3.04411 0.97055,-0.70585 2.02937,-1.05879 3.17647,-1.05883 1.19114,4e-5 2.24996,0.3971 3.17647,1.19118 1.01466,0.88239 1.65437,2.11768 1.91911,3.70588 l 4.36765,25.94118 0,0.86029 c -4e-5,1.05883 -0.33093,1.91912 -0.99265,2.58089 -0.75004,0.61764 -1.87504,0.92647 -3.375,0.92647 -0.97062,0 -1.83092,-0.30883 -2.58088,-0.92647 -0.48533,-0.39706 -0.99268,-2.22794 -1.52206,-5.49265 l -1.98529,-16.21324 -0.0662,0 c -0.13239,3e-5 -0.2868,0.0441 -0.46323,0.13236 l -5.02941,16.08088 c -1.36768,4.2353 -2.77944,6.35294 -4.2353,6.35294 -1.36767,0 -2.80149,-2.11764 -4.30147,-6.35294 l -5.22794,-15.08824 -0.19853,-0.33088 -0.0662,0 c -0.13237,2e-5 -0.33089,0.0883 -0.59559,0.26471 l -0.99264,13.96323 c -0.26472,1.94118 -0.77207,3.69486 -1.52206,5.26103 -0.75001,1.56618 -1.67648,2.34927 -2.77941,2.34927 -1.85295,0 -3.15442,-0.375 -3.90442,-1.125 -0.79412,-0.70588 -1.19118,-1.61029 -1.19117,-2.71324 l 0,-0.59559 4.5,-25.875 c 0.2647,-1.54408 0.9044,-2.75732 1.91912,-3.6397 1.10293,-0.88232 2.24998,-1.3235 3.44117,-1.32353 2.47057,3e-5 4.19116,1.38974 5.16177,4.16911 l 0,0 z",
        viewportWidth = 240f,
        viewportHeight = 80f,
    )
    val librefm = librefmSpec.path

    // Discogs — concentric circles logo (matching desktop SERVICE_LOGO_PATHS)
    val discogs = "M12 0C5.372 0 0 5.372 0 12s5.372 12 12 12 12-5.372 12-12S18.628 0 12 0zm0 21.6c-5.304 0-9.6-4.296-9.6-9.6S6.696 2.4 12 2.4s9.6 4.296 9.6 9.6-4.296 9.6-9.6 9.6zm0-16.8c-3.972 0-7.2 3.228-7.2 7.2s3.228 7.2 7.2 7.2 7.2-3.228 7.2-7.2-3.228-7.2-7.2-7.2zm0 12c-2.652 0-4.8-2.148-4.8-4.8s2.148-4.8 4.8-4.8 4.8 2.148 4.8 4.8-2.148 4.8-4.8 4.8zm0-7.2c-1.326 0-2.4 1.074-2.4 2.4s1.074 2.4 2.4 2.4 2.4-1.074 2.4-2.4-1.074-2.4-2.4-2.4z"

    // Wikipedia — W wordmark logo (matching desktop SERVICE_LOGO_PATHS)
    val wikipedia = "M12.09 13.119c-.936 1.932-2.217 4.548-2.853 5.728-.616 1.074-1.127.931-1.532.029-1.406-3.321-4.293-9.144-5.651-12.409-.251-.601-.441-.987-.619-1.139-.181-.15-.554-.24-1.122-.271C.103 5.033 0 4.982 0 4.898v-.455l.052-.045c.924-.005 5.401 0 5.401 0l.051.045v.434c0 .119-.075.176-.225.176l-.564.031c-.485.029-.727.164-.727.436 0 .135.053.33.166.601 1.082 2.646 4.818 10.521 4.818 10.521l2.681-5.476-2.607-5.24c-.237-.477-.42-.752-.545-.825-.126-.073-.437-.123-.934-.147l-.356-.022c-.152 0-.228-.053-.228-.166v-.457c0-.119.085-.17.253-.15l4.834.045.042.045v.447c0 .119-.07.176-.212.176l-.453.022c-.454.022-.681.155-.681.4 0 .106.043.274.133.502l2.008 4.097 1.905-3.971c.09-.183.137-.38.137-.597 0-.243-.233-.383-.7-.424l-.453-.022c-.152 0-.228-.058-.228-.176v-.457c0-.085.058-.134.176-.15l4.063-.045.042.045v.457c0 .106-.07.164-.212.164l-.534.031c-.391.022-.681.142-.863.36-.182.218-.404.573-.668 1.068l-2.388 4.786 2.715 5.455s3.767-7.894 4.916-10.442c.15-.326.223-.586.223-.78 0-.263-.233-.405-.7-.427l-.534-.022c-.152 0-.228-.058-.228-.176v-.457c0-.085.058-.129.176-.129h4.863l.033.045v.457c0 .106-.07.164-.212.164-.609.014-1.055.089-1.34.22-.285.133-.542.398-.767.792-.346.6-4.608 9.075-5.906 11.667-.377.755-.882.939-1.268.047-.54-1.254-2.7-5.471-2.7-5.471l-2.625 5.42c-.27.549-.748.704-1.14.013-.54-1.125-2.841-5.773-2.841-5.773z"

    // ChatGPT — OpenAI logo (hexagonal flower)
    val chatgpt = "M22.282 9.821a5.985 5.985 0 0 0-.516-4.91 6.046 6.046 0 0 0-6.51-2.9A6.065 6.065 0 0 0 4.981 4.18a5.998 5.998 0 0 0-3.998 2.9 6.046 6.046 0 0 0 .743 7.097 5.98 5.98 0 0 0 .51 4.911 6.051 6.051 0 0 0 6.515 2.9A5.985 5.985 0 0 0 13.26 24a6.056 6.056 0 0 0 5.772-4.206 5.99 5.99 0 0 0 3.997-2.9 6.056 6.056 0 0 0-.747-7.073zM13.26 22.43a4.476 4.476 0 0 1-2.876-1.04l.141-.081 4.779-2.758a.795.795 0 0 0 .392-.681v-6.737l2.02 1.168a.071.071 0 0 1 .038.052v5.583a4.504 4.504 0 0 1-4.494 4.494zM3.6 18.304a4.47 4.47 0 0 1-.535-3.014l.142.085 4.783 2.759a.771.771 0 0 0 .78 0l5.843-3.369v2.332a.08.08 0 0 1-.033.062L9.74 19.95a4.5 4.5 0 0 1-6.14-1.646zM2.34 7.896a4.485 4.485 0 0 1 2.366-1.973V11.6a.766.766 0 0 0 .388.676l5.815 3.355-2.02 1.168a.076.076 0 0 1-.071 0l-4.83-2.786A4.504 4.504 0 0 1 2.34 7.872zm16.597 3.855l-5.833-3.387L15.119 7.2a.076.076 0 0 1 .071 0l4.83 2.791a4.494 4.494 0 0 1-.676 8.105v-5.678a.79.79 0 0 0-.407-.667zm2.01-3.023l-.141-.085-4.774-2.782a.776.776 0 0 0-.785 0L9.409 9.23V6.897a.066.066 0 0 1 .028-.061l4.83-2.787a4.5 4.5 0 0 1 6.68 4.66zm-12.64 4.135l-2.02-1.164a.08.08 0 0 1-.038-.057V6.075a4.5 4.5 0 0 1 7.375-3.453l-.142.08L8.704 5.46a.795.795 0 0 0-.393.681zm1.097-2.365l2.602-1.5 2.607 1.5v2.999l-2.597 1.5-2.607-1.5z"

    // Claude — Anthropic asterisk logo (viewBox 100 80 312 350, matching desktop)
    val claudeSpec = IconSpec(
        path = "M142.27 316.619l73.655-41.326 1.238-3.589-1.238-1.996-3.589-.001-12.31-.759-42.084-1.138-36.498-1.516-35.361-1.896-8.897-1.895-8.34-10.995.859-5.484 7.482-5.03 10.717.935 23.683 1.617 35.537 2.452 25.782 1.517 38.193 3.968h6.064l.86-2.451-2.073-1.517-1.618-1.517-36.776-24.922-39.81-26.338-20.852-15.166-11.273-7.683-5.687-7.204-2.451-15.721 10.237-11.273 13.75.935 3.513.936 13.928 10.716 29.749 23.027 38.848 28.612 5.687 4.727 2.275-1.617.278-1.138-2.553-4.271-21.13-38.193-22.546-38.848-10.035-16.101-2.654-9.655c-.935-3.968-1.617-7.304-1.617-11.374l11.652-15.823 6.445-2.073 15.545 2.073 6.547 5.687 9.655 22.092 15.646 34.78 24.265 47.291 7.103 14.028 3.791 12.992 1.416 3.968 2.449-.001v-2.275l1.997-26.641 3.69-32.707 3.589-42.084 1.239-11.854 5.863-14.206 11.652-7.683 9.099 4.348 7.482 10.716-1.036 6.926-4.449 28.915-8.72 45.294-5.687 30.331h3.313l3.792-3.791 15.342-20.372 25.782-32.227 11.374-12.789 13.27-14.129 8.517-6.724 16.1-.001 11.854 17.617-5.307 18.199-16.581 21.029-13.75 17.819-19.716 26.54-12.309 21.231 1.138 1.694 2.932-.278 44.536-9.479 24.062-4.347 28.714-4.928 12.992 6.066 1.416 6.167-5.106 12.613-30.71 7.583-36.018 7.204-53.636 12.689-.657.48.758.935 24.164 2.275 10.337.556h25.301l47.114 3.514 12.309 8.139 7.381 9.959-1.238 7.583-18.957 9.655-25.579-6.066-59.702-14.205-20.474-5.106-2.83-.001v1.694l17.061 16.682 31.266 28.233 39.152 36.397 1.997 8.999-5.03 7.102-5.307-.758-34.401-25.883-13.27-11.651-30.053-25.302-1.996-.001v2.654l6.926 10.136 36.574 54.975 1.895 16.859-2.653 5.485-9.479 3.311-10.414-1.895-21.408-30.054-22.092-33.844-17.819-30.331-2.173 1.238-10.515 113.261-4.929 5.788-11.374 4.348-9.478-7.204-5.03-11.652 5.03-23.027 6.066-30.052 4.928-23.886 4.449-29.674 2.654-9.858-.177-.657-2.173.278-22.37 30.71-34.021 45.977-26.919 28.815-6.445 2.553-11.173-5.789 1.037-10.337 6.243-9.2 37.257-47.392 22.47-29.371 14.508-16.961-.101-2.451h-.859l-98.954 64.251-17.618 2.275-7.583-7.103.936-11.652 3.589-3.791 29.749-20.474-.101.102.024.101z",
        viewportWidth = 312f,
        viewportHeight = 350f,
        viewportOffsetX = 100f,
        viewportOffsetY = 80f,
    )
    val claude = claudeSpec.path

    // Gemini — Google AI curved sparkle (matching desktop)
    val gemini = "M12 0C12 6.627 6.627 12 0 12c6.627 0 12 5.373 12 12 0-6.627 5.373-12 12-12-6.627 0-12-5.373-12-12z"

    // Bandsintown — official logo (from desktop app.js)
    val bandsintown = "M6.399 12.8v4.8H19.2v1.6H4.799V0H0v24h24V12.8H6.399Zm4.801-8H6.399v6.4H11.2V4.8Zm6.4 0h-4.8v6.4h4.8V4.8ZM24 0h-4.8v11.2H24V0Z"

    // Songkick — official "sk" logo (from desktop app.js)
    val songkick = "M6.55 18.779c-1.855 0-3.372-.339-4.598-1.602l1.92-1.908c.63.631 1.74.853 2.715.853 1.186 0 1.739-.391 1.739-1.089 0-.291-.06-.529-.239-.717-.15-.154-.404-.273-.795-.324l-1.455-.205c-1.064-.152-1.891-.51-2.43-1.072-.555-.578-.84-1.395-.84-2.434C2.536 8.066 4.2 6.45 6.96 6.45c1.74 0 3.048.407 4.086 1.448L9.171 9.77c-.765-.766-1.77-.715-2.295-.715-1.039 0-1.465.597-1.465 1.125 0 .152.051.375.24.561.15.153.404.307.832.359l1.467.203c1.09.153 1.875.495 2.385 1.005.645.63.9 1.53.9 2.655 0 2.47-2.127 3.819-4.68 3.819l-.005-.003zM20.813 2.651C19.178 1.432 17.37.612 15.089.237v10.875l3.261-4.539h3.565l-4.095 5.72s.944 1.51 1.515 2.405c.586.899 1.139 1.14 1.965 1.14h.57v2.806h-.872c-1.812 0-2.9-.33-3.72-1.575-.504-.811-2.175-3.436-2.175-3.436v4.995H12.12V-.001H12c-3.852 0-6.509.931-8.811 2.652C-.132 5.137.001 8.451.001 11.997c0 3.547-.133 6.867 3.188 9.352C5.491 23.074 8.148 24 12 24s6.51-.927 8.812-2.651C24.131 18.865 24 15.544 24 11.997c0-3.546.132-6.859-3.188-9.346h.001z"

    // Ticketmaster — official "T" wordmark logo
    val ticketmaster = "M7.5 7.5h3.2v-4h2.6v4H17v2.4h-3.7V16c0 1.1.4 1.6 1.4 1.6.7 0 1.4-.2 2.1-.5l.7 2.2c-1 .5-2.1.8-3.3.8-2.5 0-3.5-1.4-3.5-3.8V9.9H7.5V7.5z"

    // SeatGeek — official wordmark logo
    val seatgeek = "M11.866 11.277h-.177l-.703-.001v-.001c-1.337-.002-3.426-.009-3.845-.011v-7.37c2.111.089 4.044.121 4.044.121l.304 1.556c.001 0-.745.007-2.361-.03v1.394l1.486.022v1.31L9.128 8.25v1.452c.832.008 1.595.013 2.411.014l1.99-5.615c.3-.008 1.573-.041 1.886-.054l2.637 7.225c-.661.003-1.331-.009-1.993-.006l-.448-1.302c-.76.008-1.52.013-2.281.016l-.445 1.293c-.355 0-.685 0-1.019.004m2.607-4.625l-.693 2.015c.461-.004.921-.009 1.38-.016zm4.389-1.197c-.719.044-1.438.081-2.157.112l.307-1.594c1.904-.105 3.8-.271 5.694-.497l.306 1.645c-.719.071-1.439.134-2.16.192l-.01 5.953c-.66.006-1.32-.001-1.98.004zM6.533 9.069c0 1.246-.901 2.401-2.674 2.401c-1.61 0-2.42-.752-2.42-.752V8.699c1.101 1.043 3.266 1.745 3.266.482c0-.96-3.266-1.125-3.266-3.518c0-1.342 1.247-2.186 2.675-2.186c1.009 0 1.855.193 2.065.258l-.083 1.772c-.884-.521-2.801-.763-2.801.134c0 .992 3.239 1.002 3.238 3.428m14.861 11.155l-1.957-3.596v3.433c-.673-.053-1.982-.133-1.982-.133V12.5l1.982.004c-.007 1.059.008 2.118 0 3.176l2.028-3.18h2.233l-2.314 3.569L24 20.525a90.598 90.598 0 0 0-2.606-.301M9.132 18.231c.892-.019 1.785-.029 2.678-.029l-.307 1.561c-.869.003-3.428.062-4.358.122v-7.374h4.038l.307 1.536s-.973-.007-2.358-.008v1.399l1.481-.013v1.323l-1.481.018zm5.162-.001c.707.015 1.905.054 2.682.082l-.32 1.573a87.388 87.388 0 0 0-4.349-.121v-7.253l4.051.002l.306 1.551l-2.371-.015v1.389c.461.005.92.009 1.379.017v1.321c-.459-.011-.919-.018-1.379-.025zM3.617 15.549l2.604-.059v4.445s-.7.032-2.26.178C1.746 20.321 0 19.022 0 16.468c0-3.034 2.222-3.993 4.225-3.993c.868 0 1.379.016 1.667.031l.328 1.723s-.58-.122-1.673-.122c-1.24 0-2.585.415-2.585 2.078c0 1.791.745 2.392 2.556 2.228l-.001-1.536l-1.206.059z"

    fun forResolver(resolver: String?): String? = when (resolver?.lowercase()) {
        "spotify" -> spotify
        "youtube" -> youtube
        "bandcamp" -> bandcamp
        // SoundCloud uses PNG drawable, handled separately in ResolverIconSquare
        "localfiles" -> localfiles
        "applemusic" -> applemusic
        "qobuz" -> qobuz
        "lastfm" -> lastfm
        "listenbrainz" -> listenbrainz
        "librefm" -> librefm
        "discogs" -> discogs
        "wikipedia" -> wikipedia
        "chatgpt" -> chatgpt
        "claude" -> claude
        "gemini" -> gemini
        "ticketmaster" -> ticketmaster
        "seatgeek" -> seatgeek
        "bandsintown" -> bandsintown
        "songkick" -> songkick
        else -> null
    }
}

/**
 * Build an ImageVector from SVG path string(s).
 * Supports custom viewBox via [viewportWidth]/[viewportHeight] and [viewportOffsetX]/[viewportOffsetY].
 * Supports multiple paths for icons like ListenBrainz.
 */
private fun resolverImageVector(
    name: String,
    pathData: String,
    viewportWidth: Float = 24f,
    viewportHeight: Float = 24f,
    viewportOffsetX: Float = 0f,
    viewportOffsetY: Float = 0f,
): ImageVector {
    // Split multiple paths if pathData contains them (separated by "|")
    val paths = pathData.split("|")
    return ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
    ).apply {
        // Apply offset translation if needed
        if (viewportOffsetX != 0f || viewportOffsetY != 0f) {
            addGroup(
                translationX = -viewportOffsetX,
                translationY = -viewportOffsetY,
            )
        }
        for (p in paths) {
            path(fill = SolidColor(Color.White)) {
                val commands = parseSvgPath(p.trim())
                commands.forEach { it(this) }
            }
        }
    }.build()
}

/**
 * Simple SVG path parser supporting M, m, L, l, H, h, V, v, C, c, S, s, A, a, Z, z commands.
 */
private fun parseSvgPath(d: String): List<androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit> {
    val result = mutableListOf<androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit>()
    // Tokenize: split on command letters, keeping the letter
    val regex = Regex("([MmLlHhVvCcSsAaZz])")
    val parts = mutableListOf<String>()
    var lastIndex = 0
    for (match in regex.findAll(d)) {
        if (match.range.first > lastIndex) {
            // append numbers before this command to previous part
            if (parts.isNotEmpty()) {
                parts[parts.size - 1] = parts.last() + d.substring(lastIndex, match.range.first)
            }
        }
        parts.add(d.substring(match.range.first, match.range.first + 1))
        lastIndex = match.range.first + 1
    }
    if (lastIndex < d.length && parts.isNotEmpty()) {
        parts[parts.size - 1] = parts.last() + d.substring(lastIndex)
    }

    var i = 0
    // Track the previous command for SVG-spec-compliant 's'/'S' handling.
    // Per SVG spec, when 's' (or 'S') follows a non-curve command (anything
    // other than C/c/S/s), the implicit first control point is the current
    // point — NOT a reflection of any earlier curve. Compose's PathBuilder
    // doesn't reset its cached-control-point tracking when a non-curve
    // command is emitted, so calling reflectiveCurveTo* in that case uses
    // a stale control point and produces wrong curvature. The Wikipedia W
    // icon hits this exact pattern (`s` after `l`); without this fix, the
    // bottom-left vertex of the W renders with a malformed curve.
    var prevCmd: Char? = null
    val curveCmds = setOf('C', 'c', 'S', 's')
    while (i < parts.size) {
        val cmd = parts[i][0]
        val nums = if (parts[i].length > 1) parseNumbers(parts[i].substring(1)) else emptyList()
        val prevWasCurve = prevCmd in curveCmds
        when (cmd) {
            'M' -> {
                var j = 0
                while (j + 1 < nums.size) {
                    if (j == 0) {
                        val x = nums[j]; val y = nums[j + 1]
                        result.add { moveTo(x, y) }
                    } else {
                        val x = nums[j]; val y = nums[j + 1]
                        result.add { lineTo(x, y) }
                    }
                    j += 2
                }
            }
            'm' -> {
                var j = 0
                while (j + 1 < nums.size) {
                    if (j == 0) {
                        val dx = nums[j]; val dy = nums[j + 1]
                        result.add { moveToRelative(dx, dy) }
                    } else {
                        val dx = nums[j]; val dy = nums[j + 1]
                        result.add { lineToRelative(dx, dy) }
                    }
                    j += 2
                }
            }
            'L' -> {
                var j = 0
                while (j + 1 < nums.size) {
                    val x = nums[j]; val y = nums[j + 1]
                    result.add { lineTo(x, y) }
                    j += 2
                }
            }
            'l' -> {
                var j = 0
                while (j + 1 < nums.size) {
                    val dx = nums[j]; val dy = nums[j + 1]
                    result.add { lineToRelative(dx, dy) }
                    j += 2
                }
            }
            'H' -> nums.forEach { x -> result.add { horizontalLineTo(x) } }
            'h' -> nums.forEach { dx -> result.add { horizontalLineToRelative(dx) } }
            'V' -> nums.forEach { y -> result.add { verticalLineTo(y) } }
            'v' -> nums.forEach { dy -> result.add { verticalLineToRelative(dy) } }
            'C' -> {
                var j = 0
                while (j + 5 < nums.size) {
                    val x1 = nums[j]; val y1 = nums[j + 1]
                    val x2 = nums[j + 2]; val y2 = nums[j + 3]
                    val x = nums[j + 4]; val y = nums[j + 5]
                    result.add { curveTo(x1, y1, x2, y2, x, y) }
                    j += 6
                }
            }
            'c' -> {
                var j = 0
                while (j + 5 < nums.size) {
                    val dx1 = nums[j]; val dy1 = nums[j + 1]
                    val dx2 = nums[j + 2]; val dy2 = nums[j + 3]
                    val dx = nums[j + 4]; val dy = nums[j + 5]
                    result.add { curveToRelative(dx1, dy1, dx2, dy2, dx, dy) }
                    j += 6
                }
            }
            'S' -> {
                var j = 0
                while (j + 3 < nums.size) {
                    val x2 = nums[j]; val y2 = nums[j + 1]
                    val x = nums[j + 2]; val y = nums[j + 3]
                    if (j == 0 && !prevWasCurve) {
                        // First sub-curve of an 'S' after a non-curve command.
                        // SVG spec: implicit first control point = current point.
                        // We don't track absolute current position in this parser,
                        // so emit a degenerate curveTo where (x1,y1) coincides
                        // with (x2,y2) — produces the same visual as reflecting
                        // off the current point in practice for the icons we
                        // ship today (none use 'S' after non-curve as of writing,
                        // but kept symmetric with the lowercase fix below).
                        result.add { curveTo(x2, y2, x2, y2, x, y) }
                    } else {
                        result.add { reflectiveCurveTo(x2, y2, x, y) }
                    }
                    j += 4
                }
            }
            's' -> {
                var j = 0
                while (j + 3 < nums.size) {
                    val dx2 = nums[j]; val dy2 = nums[j + 1]
                    val dx = nums[j + 2]; val dy = nums[j + 3]
                    if (j == 0 && !prevWasCurve) {
                        // First sub-curve of an 's' after a non-curve command.
                        // SVG spec: implicit first control point = current point,
                        // which in relative coordinates is (0, 0). Emit an
                        // explicit cubic with that first control point instead
                        // of letting Compose's reflectiveCurveToRelative use
                        // its stale cached control point from earlier curves.
                        // This is the Wikipedia W's bottom-left-vertex fix.
                        result.add { curveToRelative(0f, 0f, dx2, dy2, dx, dy) }
                    } else {
                        result.add { reflectiveCurveToRelative(dx2, dy2, dx, dy) }
                    }
                    j += 4
                }
            }
            'A' -> {
                var j = 0
                while (j + 6 < nums.size) {
                    val rx = nums[j]; val ry = nums[j + 1]
                    val rotation = nums[j + 2]
                    val largeArc = nums[j + 3] != 0f
                    val sweep = nums[j + 4] != 0f
                    val x = nums[j + 5]; val y = nums[j + 6]
                    result.add { arcTo(rx, ry, rotation, largeArc, sweep, x, y) }
                    j += 7
                }
            }
            'a' -> {
                var j = 0
                while (j + 6 < nums.size) {
                    val rx = nums[j]; val ry = nums[j + 1]
                    val rotation = nums[j + 2]
                    val largeArc = nums[j + 3] != 0f
                    val sweep = nums[j + 4] != 0f
                    val dx = nums[j + 5]; val dy = nums[j + 6]
                    result.add { arcToRelative(rx, ry, rotation, largeArc, sweep, dx, dy) }
                    j += 7
                }
            }
            'Z', 'z' -> result.add { close() }
        }
        // Remember the current command so the next iteration can detect
        // 's'/'S' after non-curve and apply the spec-correct workaround.
        prevCmd = cmd
        i++
    }
    return result
}

private fun parseNumbers(s: String): List<Float> {
    // Handle negative numbers and comma/space separation
    val nums = mutableListOf<Float>()
    val pattern = Regex("-?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?")
    for (match in pattern.findAll(s)) {
        match.value.toFloatOrNull()?.let { nums.add(it) }
    }
    return nums
}

/**
 * Small colored square with white service logo icon.
 * Matches the desktop's 20x20 colored squares with 4px border radius.
 *
 * @param showBackground If true (default), shows the colored square background.
 *   If false, renders just the white icon (useful for overlaying on a colored tile).
 * @param confidence Match confidence (0.0–1.0). Matches with confidence ≤ 0.8 are
 *   dimmed to 60% opacity, matching the desktop's behavior.
 */
@Composable
fun ResolverIconSquare(
    resolver: String,
    size: Dp = 20.dp,
    showBackground: Boolean = true,
    confidence: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val bgColor = ResolverIconColors.forResolver(resolver)
    val iconAlpha = if (confidence > 0.8f) 1f else 0.6f

    // SoundCloud uses a PNG drawable instead of an SVG path
    if (resolver.lowercase() == "soundcloud") {
        if (showBackground && bgColor != null) {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(RoundedCornerShape(4.dp))
                    .alpha(iconAlpha)
                    .background(bgColor),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.soundcloud_icon_white),
                    contentDescription = "SoundCloud",
                    modifier = Modifier.size(size * 0.65f),
                )
            }
        } else {
            Image(
                painter = painterResource(R.drawable.soundcloud_icon_white),
                contentDescription = "SoundCloud",
                modifier = modifier.size(size).alpha(iconAlpha),
            )
        }
        return
    }

    // Achordion uses a multi-color VectorDrawable (dark circle + white C-ring
    // + white tag bar + small purple cap) — can't go through the path-data
    // system which only handles white-fill paths. Render the drawable at full
    // size; its built-in dark circle background sits inside the chip (if a
    // chip is drawn upstream by PluginTile etc.).
    if (resolver.lowercase() == "achordion") {
        Image(
            painter = painterResource(R.drawable.achordion_icon),
            contentDescription = "Achordion",
            modifier = modifier.size(size).alpha(iconAlpha),
        )
        return
    }

    val pathData = ResolverIconPaths.forResolver(resolver) ?: return
    // Use IconSpec viewport params for icons with non-standard viewBoxes
    val spec = when (resolver.lowercase()) {
        "listenbrainz" -> ResolverIconPaths.listenbrainzSpec
        "librefm" -> ResolverIconPaths.librefmSpec
        "claude" -> ResolverIconPaths.claudeSpec
        else -> null
    }
    val iconVector = if (spec != null) {
        resolverImageVector(
            resolver, pathData,
            viewportWidth = spec.viewportWidth,
            viewportHeight = spec.viewportHeight,
            viewportOffsetX = spec.viewportOffsetX,
            viewportOffsetY = spec.viewportOffsetY,
        )
    } else {
        resolverImageVector(resolver, pathData)
    }

    if (showBackground) {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(4.dp))
                .alpha(iconAlpha)
                .background(bgColor ?: return),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = resolver,
                modifier = Modifier.size(size * 0.65f),
                tint = Color.White,
            )
        }
    } else {
        // No background — just the white icon
        Icon(
            imageVector = iconVector,
            contentDescription = resolver,
            modifier = modifier.size(size).alpha(iconAlpha),
            tint = Color.White,
        )
    }
}

/**
 * Row of resolver icon squares for a track.
 * Shows all available resolvers as small colored squares.
 *
 * @param confidences Optional map of resolver → confidence (0.0–1.0).
 *   Resolvers with confidence ≤ 0.8 are dimmed to match the desktop.
 */
@Composable
fun ResolverIconRow(
    resolvers: List<String>,
    size: Dp = 20.dp,
    confidences: Map<String, Float>? = null,
    modifier: Modifier = Modifier,
) {
    if (resolvers.isEmpty()) return
    val resolverOrder = LocalResolverOrder.current
    // Sort by priority first, then confidence descending (matching desktop).
    // This ensures the highest-scoring resolver (the one that will actually play)
    // appears first when priority is the same.
    val sorted = if (resolverOrder.isNotEmpty()) {
        resolvers.sortedWith(compareBy<String> { r ->
            resolverOrder.indexOf(r).let { if (it == -1) resolverOrder.size else it }
        }.thenByDescending { r -> confidences?.get(r) ?: 1f })
    } else {
        resolvers.sortedByDescending { r -> confidences?.get(r) ?: 1f }
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sorted.forEachIndexed { index, resolver ->
            if (index > 0) Spacer(modifier = Modifier.width(3.dp))
            ResolverIconSquare(
                resolver = resolver,
                size = size,
                confidence = confidences?.get(resolver) ?: 1f,
            )
        }
    }
}

/** Human-readable display names for resolvers. */
private fun resolverDisplayName(resolver: String): String = when (resolver.lowercase()) {
    "spotify" -> "Spotify"
    "applemusic" -> "Apple Music"
    "soundcloud" -> "SoundCloud"
    "localfiles" -> "Local Files"
    "youtube" -> "YouTube"
    "bandcamp" -> "Bandcamp"
    "qobuz" -> "Qobuz"
    else -> resolver.replaceFirstChar { it.uppercase() }
}

/**
 * Resolver source dropdown for the Now Playing screen.
 * Shows the currently active resolver icon + chevron. Tapping opens a dropdown
 * listing all available sources, matching the desktop's "Play from" menu.
 *
 * Only shows the chevron affordance when multiple sources are available.
 * Sources below the confidence threshold (noMatch) are filtered out.
 *
 * @param currentResolver The resolver currently playing this track
 * @param availableSources All resolved sources for this track (from TrackResolverCache)
 * @param onSwitchSource Called with the resolver ID when the user picks a different source
 */
@Composable
fun ResolverSourceDropdown(
    currentResolver: String,
    availableSources: List<com.parachord.android.resolver.ResolvedSource>,
    onSwitchSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolverOrder = LocalResolverOrder.current
    // Filter out noMatch sources (below confidence threshold)
    val validSources = remember(availableSources) {
        availableSources.filter {
            (it.confidence ?: 0.0) >= ResolverScoring.MIN_CONFIDENCE_THRESHOLD
        }
    }
    val hasMultipleSources = validSources.size > 1

    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron",
    )

    Box(modifier = modifier) {
        // Trigger button: resolver icon + optional chevron
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (hasMultipleSources) {
                        Modifier
                            .clickable { expanded = !expanded }
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    } else {
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    }
                ),
        ) {
            ResolverIconSquare(resolver = currentResolver, size = 24.dp)
            if (hasMultipleSources) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Switch source",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevronRotation),
                )
            }
        }

        // Dropdown menu — dark glassmorphic style matching desktop
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(Color(0xFA1E1E23))
                .widthIn(min = 160.dp),
        ) {
            // "Play from" header
            Text(
                text = "PLAY FROM",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )

            // Sort sources: by user resolver order first, then confidence descending
            val sorted = remember(validSources, resolverOrder) {
                if (resolverOrder.isNotEmpty()) {
                    validSources.sortedWith(compareBy<com.parachord.android.resolver.ResolvedSource> { s ->
                        resolverOrder.indexOf(s.resolver).let { if (it == -1) resolverOrder.size else it }
                    }.thenByDescending { s -> s.confidence ?: 0.9 })
                } else {
                    validSources.sortedByDescending { s -> s.confidence ?: 0.9 }
                }
            }
            // Deduplicate by resolver (keep first/best per resolver)
            val deduped = remember(sorted) {
                sorted.distinctBy { it.resolver }
            }

            deduped.forEach { source ->
                val isActive = source.resolver == currentResolver
                val iconColor = ResolverIconColors.forResolver(source.resolver)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable {
                            if (!isActive) {
                                onSwitchSource(source.resolver)
                            }
                            expanded = false
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .widthIn(min = 140.dp),
                ) {
                    ResolverIconSquare(
                        resolver = source.resolver,
                        size = 16.dp,
                        confidence = source.confidence?.toFloat() ?: 1f,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = resolverDisplayName(source.resolver),
                        color = iconColor ?: Color.White,
                        fontSize = 13.sp,
                        fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Active",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}
