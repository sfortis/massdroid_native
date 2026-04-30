# Android Auto

MassDroid exposes a Media3 `MediaLibraryService` so Android Auto can show browse categories, search, queue playback, player controls, and now-playing metadata on the car screen.

## Supported Experience

- Browse root with **Playlists, Albums, Artists, and More**.
- More tab includes Smart Mix (one-tap personalised mix), Genre Radio (top library genres), Genres (browse library by genre), and Browse (server provider tree, mirrors the phone Library Browse hierarchy).
- Search with text and voice (Google Assistant: "Play X on MassDroid"), with results grouped by Artists, Albums, Tracks, and Playlists.
- Tap any row in the Now Playing queue to jump straight to that track.
- Playback controls for play, pause, next, previous, seek, favorite, and shuffle. Favorite and shuffle live in the Now Playing toolbar with optimistic UI and rollback on server failure.
- Sendspin auto-route: while Android Auto is connected the app locks to the Sendspin (phone-as-speaker) player so audio always comes out of the car speakers, regardless of which player you had selected before driving. The lock releases when Auto disconnects.
- Now Playing line shows "Artist • Album" together (Spotify/YouTube Music style).
- Artwork and metadata updates for the current track.

## Debug Builds And Unknown Sources

If you install MassDroid from a debug APK, GitHub release asset, or another source outside Google Play, Android Auto may hide it until you allow apps from unknown sources in Android Auto developer settings.

Google documents this as Android Auto's **Allow unknown sources** developer option for apps that are not installed from a trusted source. See the official Android for Cars testing guide: [Test Android apps for cars](https://developer.android.com/training/cars/testing#unknown-sources).

Typical steps:

1. Open Android Auto settings on the phone.
2. Scroll to the version/about area.
3. Tap the Android Auto version repeatedly until developer settings are enabled.
4. Open the three-dot menu and choose **Developer settings**.
5. Enable **Unknown sources**.
6. Reconnect Android Auto.
7. If MassDroid still does not appear, open **Customize launcher** in Android Auto settings and make sure MassDroid is enabled.

Exact menu names can vary by Android version and phone manufacturer.

## Common Quirks

- **App does not appear in Android Auto**: enable Android Auto Unknown sources for sideloaded/debug installs, then reconnect. Also check Customize launcher.
- **Old browse layout remains visible**: Android Auto can cache the media browse tree. Disconnect/reconnect Android Auto, force stop Android Auto, or restart the phone if the old tree persists.
- **Icons or rows render differently by car**: Android Auto host controls the final layout. MassDroid provides content style hints, but the host may still choose list/grid presentation differently depending on device, car, and Android Auto version.
- **Custom buttons may move to overflow**: favorite and shuffle are exposed as Media3 command buttons. Android Auto decides whether to show them directly or in overflow based on available slots.
- **Search behavior depends on host support**: MassDroid advertises search support, but the visible affordance and voice behavior can vary by Android Auto version.
- **Debug and release builds are separate apps**: debug builds use a different package ID and can be installed side by side with release builds.

## Testing Tips

- Test after a clean reconnect, not only after reinstalling the APK.
- If changing browse structure, open another media app and return to MassDroid, or restart Android Auto to clear stale browse state.
- For debug builds, verify both phone notification controls and Android Auto controls.
- When testing queue behavior, try next/previous, selecting a queue item, and opening the queue browse view.
