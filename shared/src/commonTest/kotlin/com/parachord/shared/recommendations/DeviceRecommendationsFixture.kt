package com.parachord.shared.recommendations

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A device's saved provider answers (Nov 2026, trimmed to 3 items a section): Apple Music's titled sections with no category, Last.fm's untitled songs. */
internal object DeviceRecommendationsFixture {
    private const val JSON = """{
 "applemusic": [
  {
   "id": "applemusic:6-27s5hU6azhJY",
   "title": "Playlists Made for You",
   "items": [
    {
     "type": "playlist",
     "id": "applemusic-playlist-pl.pm-personal3",
     "name": "Get Up!",
     "url": "https://music.apple.com/us/playlist/get-up/pl.pm-personal3",
     "category": "personal",
     "resolverId": "applemusic"
    },
    {
     "type": "playlist",
     "id": "applemusic-playlist-pl.pm-personal2",
     "name": "Chill",
     "url": "https://music.apple.com/us/playlist/chill/pl.pm-personal2",
     "category": "personal",
     "resolverId": "applemusic"
    },
    {
     "type": "playlist",
     "id": "applemusic-playlist-pl.pm-personal1",
     "name": "New Music",
     "url": "https://music.apple.com/us/playlist/new-music/pl.pm-personal1",
     "category": "personal",
     "resolverId": "applemusic"
    }
   ]
  },
  {
   "id": "applemusic:9-2TsPwCU9OMXk53coBQtHfe",
   "title": "New Releases for You",
   "items": [
    {
     "type": "album",
     "id": "applemusic-album-6805610367",
     "name": "Reveal (25th Anniversary Edition)",
     "artist": "R.E.M.",
     "url": "https://music.apple.com/us/album/reveal-25th-anniversary-edition/6805610367",
     "releaseDate": "2026-11-20",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-6805129290",
     "name": "Sweet Forever",
     "artist": "Mondo Cozmo",
     "url": "https://music.apple.com/us/album/sweet-forever/6805129290",
     "releaseDate": "2026-11-20",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-6833635303",
     "name": "Sessions",
     "artist": "Death Cab for Cutie",
     "url": "https://music.apple.com/us/album/sessions/6833635303",
     "releaseDate": "2026-11-16",
     "resolverId": "applemusic"
    }
   ]
  },
  {
   "id": "applemusic:15-16xrk6RgLGzN2spu0joFNf",
   "title": "More from Cage the Elephant",
   "items": [
    {
     "type": "album",
     "id": "applemusic-album-488271914",
     "name": "Live from the Vic In Chicago",
     "artist": "Cage the Elephant",
     "url": "https://music.apple.com/us/album/live-from-the-vic-in-chicago/488271914",
     "releaseDate": "2012-01-17",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-1051876257",
     "name": "Tell Me I'm Pretty",
     "artist": "Cage the Elephant",
     "url": "https://music.apple.com/us/album/tell-me-im-pretty/1051876257",
     "releaseDate": "2015-12-18",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-1450676554",
     "name": "Social Cues",
     "artist": "Cage the Elephant",
     "url": "https://music.apple.com/us/album/social-cues/1450676554",
     "releaseDate": "2019-04-19",
     "resolverId": "applemusic"
    }
   ]
  },
  {
   "id": "applemusic:15-FibtAUYr1HQ7xLpbLpK51",
   "title": "Chill Music",
   "items": [
    {
     "type": "album",
     "id": "applemusic-album-1876846303",
     "name": "Train on the Island",
     "artist": "Aldous Harding",
     "url": "https://music.apple.com/us/album/train-on-the-island/1876846303",
     "releaseDate": "2026-05-08",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-1446481440",
     "name": "How Do You Let the Love Into the Heart That Isn't Split Wide Open - EP",
     "artist": "Wednesday & MJ Lenderman",
     "url": "https://music.apple.com/us/album/how-do-you-let-the-love-into-the-heart-that-isnt/1446481440",
     "releaseDate": "2018-12-13",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-6789809506",
     "name": "Roses",
     "artist": "Widowspeak",
     "url": "https://music.apple.com/us/album/roses/6789809506",
     "releaseDate": "2026-06-05",
     "resolverId": "applemusic"
    }
   ]
  },
  {
   "id": "applemusic:15-3iZPotgmG53IgGksgBhpQR",
   "title": "New Country Releases",
   "items": [
    {
     "type": "album",
     "id": "applemusic-album-1895672454",
     "name": "Pictures of You",
     "artist": "Buffalo Traffic Jam",
     "url": "https://music.apple.com/us/album/pictures-of-you/1895672454",
     "releaseDate": "2026-07-31",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-6802901441",
     "name": "O My Beloved",
     "artist": "Kings of Leon",
     "url": "https://music.apple.com/us/album/o-my-beloved/6802901441",
     "releaseDate": "2026-11-06",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-6799165244",
     "name": "Paniolo Country",
     "artist": "Josh Tatofi",
     "url": "https://music.apple.com/us/album/paniolo-country/6799165244",
     "releaseDate": "2026-10-02",
     "resolverId": "applemusic"
    }
   ]
  },
  {
   "id": "applemusic:15-4DdpsCryAaeuV6ijaUrRIZ",
   "title": "More from The Dears",
   "items": [
    {
     "type": "album",
     "id": "applemusic-album-1770396478",
     "name": "Thank You Goodnight Sold Out",
     "artist": "The Dears",
     "url": "https://music.apple.com/us/album/thank-you-goodnight-sold-out/1770396478",
     "releaseDate": "2004-10-26",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-1829799294",
     "name": "Life Is Beautiful! Life Is Beautiful! Life Is Beautiful!",
     "artist": "The Dears",
     "url": "https://music.apple.com/us/album/life-is-beautiful-life-is-beautiful-life-is-beautiful/1829799294",
     "releaseDate": "2025-11-07",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-1770399742",
     "name": "Live at Pasaguero",
     "artist": "The Dears",
     "url": "https://music.apple.com/us/album/live-at-pasaguero/1770399742",
     "releaseDate": "2012-11-27",
     "resolverId": "applemusic"
    }
   ]
  },
  {
   "id": "applemusic:15-M0rorBSMObaC0kDNiNufK",
   "title": "Alternative Hits by Year",
   "items": [
    {
     "type": "playlist",
     "id": "applemusic-playlist-pl.2f7f769d09be4dbdbda62bd3ed6602e8",
     "name": "Alternative Hits: 2022",
     "url": "https://music.apple.com/us/playlist/alternative-hits-2022/pl.2f7f769d09be4dbdbda62bd3ed6602e8",
     "category": "editorial",
     "resolverId": "applemusic"
    },
    {
     "type": "playlist",
     "id": "applemusic-playlist-pl.4fa7aa838a464b1cbbf38443dfd645e8",
     "name": "Alternative Hits: 2025",
     "url": "https://music.apple.com/us/playlist/alternative-hits-2025/pl.4fa7aa838a464b1cbbf38443dfd645e8",
     "category": "editorial",
     "resolverId": "applemusic"
    },
    {
     "type": "playlist",
     "id": "applemusic-playlist-pl.f06efcb68aff463b975ed48a04fd5210",
     "name": "Alternative Hits: 2024",
     "url": "https://music.apple.com/us/playlist/alternative-hits-2024/pl.f06efcb68aff463b975ed48a04fd5210",
     "category": "editorial",
     "resolverId": "applemusic"
    }
   ]
  },
  {
   "id": "applemusic:15-1ImzJ4ZX6hNc8MjXtO9iaC",
   "title": "More from Young Jesus",
   "items": [
    {
     "type": "album",
     "id": "applemusic-album-977275099",
     "name": "Grow / Decompose",
     "artist": "Young Jesus",
     "url": "https://music.apple.com/us/album/grow-decompose/977275099",
     "releaseDate": "2015-05-13",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-498100173",
     "name": "Home",
     "artist": "Young Jesus",
     "url": "https://music.apple.com/us/album/home/498100173",
     "releaseDate": "2012-01-24",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-1725545080",
     "name": "The Fool",
     "artist": "Young Jesus",
     "url": "https://music.apple.com/us/album/the-fool/1725545080",
     "releaseDate": "2024-05-24",
     "resolverId": "applemusic"
    }
   ]
  },
  {
   "id": "applemusic:15-53Qkmhz6Ec4k7IVXV9L8FZ",
   "title": "Emo Essentials",
   "items": [
    {
     "type": "album",
     "id": "applemusic-album-1440898333",
     "name": "Pinkerton",
     "artist": "Weezer",
     "url": "https://music.apple.com/us/album/pinkerton/1440898333",
     "releaseDate": "1996-09-24",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-1646482253",
     "name": "Four Minute Mile",
     "artist": "The Get Up Kids",
     "url": "https://music.apple.com/us/album/four-minute-mile/1646482253",
     "releaseDate": "1997-09-30",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-1651333864",
     "name": "American Football",
     "artist": "American Football",
     "url": "https://music.apple.com/us/album/american-football/1651333864",
     "releaseDate": "1999-09-14",
     "resolverId": "applemusic"
    }
   ]
  },
  {
   "id": "applemusic:15-4Rra29WVGmMFcR9P9kohmr",
   "title": "Classic British Rock",
   "items": [
    {
     "type": "album",
     "id": "applemusic-album-1065973699",
     "name": "The Dark Side of the Moon",
     "artist": "Pink Floyd",
     "url": "https://music.apple.com/us/album/the-dark-side-of-the-moon/1065973699",
     "releaseDate": "1973-03-01",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-1525933483",
     "name": "(What's The Story) Morning Glory? [Deluxe Remastered Edition]",
     "artist": "Oasis",
     "url": "https://music.apple.com/us/album/whats-the-story-morning-glory-deluxe-remastered-edition/1525933483",
     "releaseDate": "1995-10-02",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-1474833332",
     "name": "Abbey Road (Super Deluxe Edition) [2019 Remix & Remaster]",
     "artist": "The Beatles",
     "url": "https://music.apple.com/us/album/abbey-road-super-deluxe-edition-2019-remix-remaster/1474833332",
     "releaseDate": "1969-09-26",
     "resolverId": "applemusic"
    }
   ]
  },
  {
   "id": "applemusic:15-6YIeU08y2aQKQoj2mGAAxs",
   "title": "Manchester Orchestra Fans Like",
   "items": [
    {
     "type": "playlist",
     "id": "applemusic-playlist-pl.b4318475e980445d887c57d03432ebbe",
     "name": "Death Cab for Cutie Essentials",
     "url": "https://music.apple.com/us/playlist/death-cab-for-cutie-essentials/pl.b4318475e980445d887c57d03432ebbe",
     "category": "editorial",
     "resolverId": "applemusic"
    },
    {
     "type": "playlist",
     "id": "applemusic-playlist-pl.15cf3025c8944cc392f1d8e587d5afef",
     "name": "Silversun Pickups Essentials",
     "url": "https://music.apple.com/us/playlist/silversun-pickups-essentials/pl.15cf3025c8944cc392f1d8e587d5afef",
     "category": "editorial",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-1278430230",
     "name": "The Albatross",
     "artist": "Foxing",
     "url": "https://music.apple.com/us/album/the-albatross/1278430230",
     "releaseDate": "2014-05-27",
     "resolverId": "applemusic"
    }
   ]
  },
  {
   "id": "applemusic:15-3Z6ovbXjdTbsfauWtnTMq5",
   "title": "2010s",
   "items": [
    {
     "type": "album",
     "id": "applemusic-album-1443091400",
     "name": "Pumarosa - EP",
     "artist": "Pumarosa",
     "url": "https://music.apple.com/us/album/pumarosa-ep/1443091400",
     "releaseDate": "2016-09-07",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-1412449273",
     "name": "This Is the Life",
     "artist": "Andrew Bryant",
     "url": "https://music.apple.com/us/album/this-is-the-life/1412449273",
     "releaseDate": "2015-01-27",
     "resolverId": "applemusic"
    },
    {
     "type": "album",
     "id": "applemusic-album-718727503",
     "name": "Future Self (Bonus Track Version)",
     "artist": "The Globes",
     "url": "https://music.apple.com/us/album/future-self-bonus-track-version/718727503",
     "releaseDate": "2011-05-10",
     "resolverId": "applemusic"
    }
   ]
  }
 ],
 "lastfm": [
  {
   "items": [
    {
     "type": "song",
     "id": "gregmendez-maria",
     "name": "Maria",
     "artist": "Greg Mendez",
     "duration": 133.0,
     "resolverId": "lastfm"
    },
    {
     "type": "song",
     "id": "ryandavistheroadhouseband-newthreatsfromthesoul",
     "name": "New Threats From the Soul",
     "artist": "Ryan Davis & the Roadhouse Band",
     "duration": 561.0,
     "resolverId": "lastfm"
    },
    {
     "type": "song",
     "id": "tvstar-outofmybag",
     "name": "Out Of My Bag",
     "artist": "TV Star",
     "duration": 263.0,
     "resolverId": "lastfm"
    }
   ]
  }
 ]
}"""

    val sections: Map<String, List<RecommendationSection>> = Json { ignoreUnknownKeys = true }.decodeFromString(
        MapSerializer(String.serializer(), ListSerializer(RecommendationSection.serializer())), JSON,
    )

    val providers: List<ProviderRecommendations> = listOf(
        ProviderRecommendations("applemusic", "Apple Music", sections.getValue("applemusic"), canExpand = true),
        ProviderRecommendations("lastfm", "Last.fm", sections.getValue("lastfm"), canExpand = true),
    )
}
