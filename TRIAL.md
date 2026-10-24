A trial build of the [Music Assistant mobile app](https://github.com/music-assistant/mobile-app) with changes that are not part of it (yet). Not an official release.

It installs as **MA Trial** next to the official app. It is signed with this fork's own key, so it never replaces or updates the official app, and the official app never replaces it. Install and update it with [Komi Store](https://komistore.app/) or by downloading the APK below (Android, arm64).

If the installation spins forever, Google Play Protect is holding it: turn off "Scan apps with Play Protect" in the Play Store, install, and turn it back on.

## In this build

- **Browse shows what radio stations play.** A station's row shows its description instead of "Radio". A provider with live data, like FIP, names the song on air there. While the list is open it follows the server's updates, so song and cover change with the music, and the item the selected player plays is highlighted.
- **Tap the cover to see it alone.** In the expanded player, a tap on the cover shows it as large as the screen allows, over a blurred copy of itself that slowly breathes while the music plays and rests when it pauses. The system bars hide, and the screen stays on while music plays. Tap anywhere or go back to return.
- **Song progress for radio.** When a radio stream reports its songs (FIP does), the full player shows the song's length and how far into it the music is, instead of how long the station has played.
