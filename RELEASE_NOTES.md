# Parachord v0.9.11

**Release date:** Unreleased

> Bandcamp now plays inside the app, CarPlay arrives on iOS, and Recommendations come from your Last.fm and ListenBrainz plug-ins, as on desktop. Spotify starts reliably on Android again, playback keeps going with the phone locked, and library sync no longer duplicates songs or empties playlists. Android and iOS now share one set of playback rules (repeat, Spinoff, Listen Along, and what happens when a track can't play), so a fix reaches both phones at once.

---

## Playing music

- **Spotify plays on Android again.** Spotify's Android app quietly ignored single-track plays; Parachord now plays each track inside its album. A play Spotify accepts but never starts falls back to your next source.
- **Spotify stops at the end of your track.** Even when Parachord can't reach Spotify, it stops the song before Spotify plays on into the rest of the album (Android).
- **Resume Spotify anywhere.** A Spotify track you resume from Spotify's own app or a headset shows as playing in Parachord, and the queue moves on when it ends.
- **Playback keeps going with the phone locked.** A local file followed by another track no longer goes silent, and Play after a long pause starts the song again instead of doing nothing (Android).
- **Repeat on Android.** Off, all or one, beside shuffle on Now Playing. The car, lock screen and headset now use Parachord's own repeat and shuffle, and repeat all wraps only the current queue.
- **Your queue picks up where you left off.** After a restart, Play resumes the song you were on from where you stopped, and the song isn't scrobbled twice.
- **Play tries again after a failure.** A track that couldn't start (offline, Apple Music not loading) starts on the next Play, and a track with no source says why instead of failing silently.
- **Next and Previous work everywhere.** From the lock screen, a headset or the car they now move the queue on local files too, a quick second tap no longer queues a track twice, and Previous reaches the song a single play replaced.
- **Play links behave.** A link opened with the phone locked asks you to unlock instead of failing silently, and a link that opens the app is no longer lost to the restored queue.

## Android Auto and CarPlay

- **CarPlay (iOS).** The same Playlists, Loved Songs and Collection Radio as Android Auto, with a heart and Spinoff on Now Playing and an Up Next list.
- **Spinoff on Android Auto,** next to the heart.
- **The car stays in charge.** A song started in the car isn't replaced when you unlock the phone, and a song still loading shows as loading, not Pause.
- **The car shows the right song.** Android Auto now shows the same title and artist as the phone, and the heart in the car or on Now Playing saves the song that's playing, not a different one.
- **No silent run to the end of the queue.** After three tracks in a row that can't play, the car stops and asks you to open Parachord on your phone.

## Listen Along and Spinoff

- **Listen Along works the same on both phones.** It keeps following your friend after you leave the app, survives a failed check or a radio station's gap between songs, and gives your queue back when it ends.
- **Spinoff works for very popular songs,** such as "Shape of You", by falling back to similar artists' top tracks. Its tracks are looked up as they play, so starting one is quick.

## Services

- **Bandcamp plays in the app** through Bandcamp's own player, on Android and iOS, instead of opening the browser. A track Bandcamp won't play falls through to your next source.
- **Subsonic and Navidrome (Android).** Stream from your own server through the Subsonic plug-in; set it up with your server, username and password in its settings. iOS follows in a later release.
- **SoundCloud sign-in just works** with Parachord's built-in SoundCloud app, on both phones, with no developer credentials needed.
- **The right song plays.** Better matching for songs with several artists, remasters, live versions and medleys, and the AI DJ never plays or saves a different song than it names.
- **Fewer false "not found"s.** A service that couldn't answer (offline, busy) is asked again later instead of being recorded as having no match.
- **Spotify and Apple Music rest when you turn them off.** Parachord no longer asks them for artist photos or details while their plug-ins are off.
- **Apple Music resolves the same way on both phones,** through one shared catalog search.

## Discover

- **Recommendations from your plug-ins.** The Songs and Artists lists now come from your Last.fm and ListenBrainz plug-ins, as on desktop, and keep their last picks if a service is down.
- **Lists that hold up.** Albums open their standard digital edition, Fresh Drops shows the last three months, and Pop of the Tops keeps its last chart when Apple's feed fails.
- **Quieter lists.** Track lists look up songs as they come on screen, and the rest of your library quietly in the background, instead of firing hundreds of lookups at once.

## Your Collection and sync

- **Spotify sync no longer empties playlists.** A ListenBrainz or Apple Music playlist with the same name as a Spotify playlist keeps its own tracks.
- **No more doubles.** A song you hearted no longer shows up twice after a library sync; earlier doubles merge into the copy you saved, without unliking anything.
- **Your music files aren't loves.** Scanned local files belong to your library, and are no longer hearted or sent to Last.fm and ListenBrainz as loves.
- **Sync never deletes a playlist on its own.** A copy on Spotify or Apple Music is removed only when you choose it in the delete dialog.
- **Playlists look right.** Covers follow their tracks, and importing a playlist link works for every service with a plug-in, SoundCloud included.
- **Sharing a playlist checks ListenBrainz first,** and asks before making a private playlist public.

## Look and feel

- **A design refresh.** Shared colours across both apps, a darker purple behind white text so it stays readable, and each service in its own colour.
- **Now Playing in landscape** puts the artwork beside the controls.
- **Play Album from any album menu,** not just in your Library.
- **The home-screen widget** shows the Parachord logo when nothing is playing.

## iOS

- **Caught up with Android.** Sharing, removing from playlists, sending loves to Last.fm and ListenBrainz, background lookups, chart and critics filters, and the same error messages as Android.
- **Offline, the queue stops instead of emptying itself.** An offline banner shows, and a song that can't start stops on its track instead of skipping through the queue.

## Security

- **Links can't lead Parachord onto your home network.** A playlist link can no longer redirect the app to an address on your local network, and oversized responses are cut off ([GHSA-gfvj-rh43-vmp9](https://github.com/Parachord/parachord-mobile/security/advisories/GHSA-gfvj-rh43-vmp9)).
