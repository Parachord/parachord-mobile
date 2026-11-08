# Sign in on the car with a QR code

Status: design, not yet planned. Issue: #457 (Android Automotive). Date: 2026-11-07.

## Why

Parachord runs unchanged on Android Automotive (the #457 spike): the car's media screen, Drive View and the phone UI all work, the phone UI only while parked. Signing in is the gap. Spotify, SoundCloud and Last.fm sign in through the device's browser, and many cars have none; the emulator image had none. Typing passwords on a car screen is miserable even where it works.

So the car shows a QR code, the person scans it with their phone, signs in there, and the account arrives on the car. The person asked for this to work on every car, not only cars with a browser, and for it to be a general mechanism that iPad and desktop can use later.

## Goals

- A parked car can connect Spotify, ListenBrainz and Last.fm without typing a password on the car and without a browser on the car.
- Any phone camera can sign the car in to Spotify and Last.fm; Parachord doesn't have to be installed on the phone.
- When the phone has Parachord installed and signed in, it can hand over the sign-ins that are safe to share (ListenBrainz, Last.fm) in one tap.
- **ListenBrainz reaches the car only by handover** (the person's decision). It has no sign-in page, only a token pasted from listenbrainz.org/settings. A car that needs ListenBrainz says to send it from Parachord on the phone. Scanned without Parachord, the `/link` page says the same.
- The car ends up with its own sign-in for Spotify, whose sign-ins can't be shared.

## Non-goals

- **SoundCloud** (the person's decision, 2026-11-07). SoundCloud allows one redirect address per app, and Parachord's built-in SoundCloud app (`O2Hc…`) uses its one for desktop's `http://127.0.0.1:8888/callback/soundcloud`. Registering the car's address would break desktop's sign-in. SoundCloud playback goes through its widget with no sign-in; only search and resolution use the sign-in. On the car, SoundCloud keeps today's browser path. Revisit with a second SoundCloud app if SoundCloud allows creating one.
- iPad or desktop showing a QR code. The protocol and relay should not rule them out, but only the car shows one in this version.
- Apple Music. Its sign-in is Apple's MusicKit page in a WebView, which needs no browser, so first try the existing sign-in on the car while parked. Revisit only if that fails.
- Signing in while driving. The car blocks Parachord's own screens then.
- The Android Automotive release itself (manifest, Play's Automotive track): #457's later phases.

## Anti-goals

- **No Parachord user account.** There is no sign-up, login, profile or device list, and nothing persistent about a person on any server. The person asked this explicitly.
- **The relay never sees anything it could use.** Tokens and keys pass through it only end-to-end encrypted for the receiving device, with one bounded exception for PKCE authorization codes, described below.
- **No copying of sign-ins that rotate.** Spotify and SoundCloud refresh tokens rotate (`SoundCloudAuth` already guards against a rotated refresh token), so two devices sharing one would sign each other out. Each device gets its own sign-in for these.

## How it works

### The pairing

1. On the car, the person picks a service and taps **Sign in with phone**. The car creates:
   - a session id: 128 random bits, base64url;
   - a read secret: 128 random bits, which never leaves the car;
   - an ephemeral key pair, used once;
   - for Spotify, a PKCE verifier, kept only on the car.

   It registers the session with the relay, sending a hash of the read secret, before showing anything (my call, so only the car can collect the result; see "The relay").
2. The car shows a QR code for `https://parachord.com/link/<session>?s=<service>&c=<PKCE challenge>#k=<public key>`. The key is in the fragment, so it's never sent to any server. The screen says which service it's for, counts down to expiry, and waits.
3. The phone scans it. There are two paths, and they're split on purpose (my call, from the review): `/link/*` is claimed as an Android App Link and iOS Universal Link, while the browser sign-in and its return page live under `/pair/*`, which no app claims. Otherwise a phone with Parachord would bounce the service's return into the app, and the page that holds the key would never run.
   - **Without Parachord on the phone,** the browser opens `/link/<session>`, which forwards to `/pair/<session>` with the same query and fragment. That page stores the public key in `sessionStorage` and sends the person to the service's sign-in page, with `state` set to the session id.
   - **With Parachord on the phone,** `/link/<session>` opens the app. The app offers:
     - **Sign this car in to <service>** (Spotify or Last.fm), which opens `/pair/<session>…` with the fragment in a Custom Tab. From there the flow is the browser flow above.
     - **Send to this car**, a handover with a checkbox for each account the phone has signed in that can be shared: ListenBrainz and Last.fm. This is offered on every QR code, whichever service it names.
4. The result reaches the relay as one record per service, under the session id (my call: one scan can deliver a Spotify sign-in and a handover together):
   - **After a service sign-in,** the service sends the browser back to `https://parachord.com/pair/callback`. That page reads the public key from `sessionStorage`, encrypts what came back (Spotify's authorization code or Last.fm's token), and posts it under `<session>/<service>`.
   - **After a handover,** the phone app encrypts each ticked account and posts it under its own service: the ListenBrainz token and username, the Last.fm session key and username.
5. The car polls the relay with its read secret, receives each record, decrypts it and finishes:
   - **Spotify:** exchanges the code with its PKCE verifier, using `https://parachord.com/pair/callback` as the redirect URI.
   - **Last.fm:** calls `auth.getSession` with the token, or stores the handed-over session key.
   - **ListenBrainz:** stores the token.

   The car accepts every service that arrives, not only the one the QR named. It stores each through the same `SettingsStore` and `SecureTokenStore` paths the existing sign-ins use, so nothing downstream changes. The QR screen stays open until the named service arrives or the code expires, listing anything else that came in. The relay deletes a record on read. Unread records expire after 10 minutes.

### Encryption

ECDH on P-256, HKDF, then AES-GCM. That combination is available in every browser's WebCrypto, on Android, and in CryptoKit on iOS. One JSON envelope format, defined once in `shared/commonMain` with test vectors that the browser page's tests also run.

Each record is its own envelope for one service (`spotify`, `lastfm`, `listenbrainz`), holding either an authorization code, a token, or a session key with a username.

**Exception (my call):** if the callback page finds no key, for example because the service's own phone app handled the sign-in and returned in a fresh tab, it may post a PKCE authorization code unencrypted. Such a code is useless without the verifier, which never leaves the car. It never posts a Last.fm token unencrypted, because a Last.fm token plus the app's API key, which is in the binary, yields a session. In that case the page asks the person to try again in the browser.

### The relay

A small API on `parachord.com`, in the website's Cloudflare edge worker (`parachord-website/worker/`), which already serves `parachord.com`'s Universal Link routes and `/.well-known/`. Smart links (`go.parachord.com`) is in maintenance mode, so it's not the place.

- `POST /api/link/<session>` registers the session with a hash of the car's read secret. It's refused if the session exists. Unwritten sessions expire after 10 minutes.
- `PUT /api/link/<session>/<service>` is write-once per service: a second write for the same service is refused. It fails if the session isn't registered. Body cap: a few KB.
- `GET /api/link/<session>` needs the read secret (in a header, never in the URL). It returns the records written so far, then deletes them.
- **Why a read secret (my call, from the review):** the session id appears in the QR, in the URL the phone sends to `parachord.com`, in the `state` sent to Spotify, and in the phone's browser history. Without a separate secret, anyone who saw it could collect the result first, or delete it to block the sign-in.
- Records expire after 10 minutes. Payloads are never logged. Writes and reads are rate-limited per IP.
- The car must see a write within about 5 seconds. Cloudflare KV can take up to about a minute to show a write elsewhere, so a Durable Object, or anything else with read-your-writes, is preferred. The builder should check the plan's limits before choosing.
- `/link/<session>`, `/pair/<session>` and `/pair/callback` are static pages served by the same worker. Only `/link/*` goes in the app-link files.

### On the car

- **Settings → Spotify, Last.fm and ListenBrainz:** on an automotive device (`FEATURE_AUTOMOTIVE`), **Connect** opens the QR screen. SoundCloud and Apple Music keep their existing Connect. When a browser resolves, a secondary **Sign in on this screen** keeps today's path. Phones keep today's flow unchanged.
- **Status:** the QR screen shows waiting, signed in (with the account name) or expired (with a new-code button). It closes on success.
- **While driving:** Parachord's screens are blocked. When nothing can play because no source is signed in, the car's media screen should say to park and sign in, through the playback-error reporting #450 added. Exact wording is the builder's call.
- **Apple Music:** try the existing sign-in on the car while parked as part of this work, and record the result on #457.

### On the phone

Android and iOS together, per the project rule, since the phone side is the same feature on both:

- Handle `parachord.com/link/*`, and only that, as an App Link and Universal Link. This adds the path to the existing `/.well-known` files on `parachord.com`.
- The link screen names the service and offers its sign-in, plus the handover when the phone has a shareable account signed in. With no shareable account, it explains that ListenBrainz can only be sent from a phone signed in to it.
- The handover has a checkbox per account and names what will be sent ("your ListenBrainz token", "your Last.fm session").
- Shared code holds the envelope, the relay client and the handover payload. Each platform has only its UI and link handling.

### Registrations the owner has to do

These are outside the code:

- **Spotify developer dashboard:** add `https://parachord.com/pair/callback` as a redirect URI on the shared client id.
- **Last.fm:** check that `cb=https://parachord.com/pair/callback` is accepted for the app's API account. It's believed to be, but that's untested.

## Constraints

- Shared logic in `shared/commonMain`, as with every feature here: models, envelope, relay client, the decision of which services can be handed over.
- Car-side UI is Android only, since Automotive is Android. Phone-side UI is Android and iOS.
- No new Parachord account or server-side identity. The relay holds at most minutes of opaque data.
- Existing phone sign-in flows must not change behavior.

## Left to the builder

- The crypto library in `commonMain` (or `expect`/`actual` over platform crypto), as long as the envelope matches the browser's WebCrypto output.
- Relay storage choice, within the 5-second visibility requirement.
- Polling interval and backoff, within reason (about 1-2 s while the QR screen is open).
- Screen layouts. Follow the existing design system: purple accent, dark car theme.
- Exact copy, apart from the handover confirmation naming what is sent.
- How the car's media screen surfaces "park to sign in".

## Open risks

- A service's phone app may intercept its sign-in URL and return to a different browser tab, losing the stored key. That's covered for PKCE codes by the exception above, not for Last.fm.
- Last.fm's `cb` override is untested.
- Sound on the car is still unverified (#457). This feature makes signing in possible; it doesn't prove that Spotify or SoundCloud play there.
