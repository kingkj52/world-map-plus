# Hosting the map pictures

World Map Plus no longer carries the detailed map pictures in its jar. It downloads them, the first time a view needs them, from a
GitHub release of its own and keeps them on the player's computer. This file says how the files are laid out, what to do once to
host them, and how an update works. Why it is built this way is in `docs/PLAN.md`, section 59H; what a reviewer may ask is in
`docs/RULES-AUDIT.md`, round 59H.

## What is hosted

One public repository, `KingKj52/world-map-plus-maps`, and in it one **release** at a time (the newest release is the one the
plugin reads). A release holds these assets, all made by `python tools/make_map_release.py`:

- `manifest.json` (about 8 KB): the format (1), a version number, and for each pack its name, exact size, SHA-256 and the squares it
  covers (rectangles of squares, per map). The plugin refuses a manifest that is not exactly this format.
- 26 pack files, `ground-01.zip` to `ground-13.zip` (the surface, 0.45 to 0.90 MB each), `floor1-01.zip`, `floor2-01.zip`,
  `floor3-01.zip` (0.74 to 0.82 MB), and `other-01.zip` to `other-10.zip` (dungeons and the other maps, 0.14 to 0.32 MB). A pack is a
  zip file whose entries are stored, not compressed, and named `<mapId>/<x>_<y>.png`. Together 12,446,622 bytes.

The plugin asks for `https://github.com/KingKj52/world-map-plus-maps/releases/latest/download/<file>`. GitHub redirects that to the
asset of the newest release. The address is one constant, `MapDownloader.BASE`, and `ComplianceTest` insists it is the only address
in the plugin. What stays in the plugin jar: the four overview pictures (`overview-0.png`, `-20000`, `-40000`, `-60000`, about 2.1 MB)
and `index.json`, the list of which squares exist.

GitHub states no bandwidth limit for release assets. The alternatives (raw.githubusercontent.com has rate limits that are not
published and 429 answers are reported; GitHub Pages has a 100 GB a month soft limit) were left out for that reason. To move to
another host change `MapDownloader.BASE` and the host test in `MapDownloader.trusted`, and say so in the README and `NOTICE`.

## One time: create the repository and the first release

1. On GitHub, signed in as KingKj52: **New repository**, name `world-map-plus-maps`, **Public**, add a README (it can say that this
   holds the map pictures downloaded by the World Map Plus plugin, made from the Old School RuneScape Wiki's map tiles and credited as
   in the plugin's NOTICE). No code is needed here.
2. In the plugin repository, build and check the release:
   ```
   python tools/make_map_release.py --version 1
   python tools/verify/release.py
   python tools/make_map_release.py --check
   ```
   The folder is `build/map-release`. `release.py` must say 0 problems and `--check` must say the release equals a fresh build.
3. Create the release with every file of that folder as an asset. With the GitHub command line (installed and signed in):
   ```
   gh release create maps-1 build/map-release/manifest.json build/map-release/*.zip --repo KingKj52/world-map-plus-maps --title "Map pictures 1" --notes "Map pictures for World Map Plus, manifest version 1"
   ```
   Or on the web: **Releases**, **Draft a new release**, tag `maps-1` (create it), a title, drag all 27 files into the assets box, wait for
   every upload to finish, and **Publish release**. It must be a normal release, not a pre-release and not a draft: the plugin reads
   the one GitHub calls the latest.
4. Check that the files are served, from any shell:
   ```
   curl -sIL https://github.com/KingKj52/world-map-plus-maps/releases/latest/download/manifest.json
   curl -sL https://github.com/KingKj52/world-map-plus-maps/releases/latest/download/manifest.json -o manifest-check.json
   ```
   The first must end in `200` after a redirect; the second file must equal `build/map-release/manifest.json`. Then open a clean
   RuneLite profile (or delete the plugin's `maps` folder inside its data folder), open the map and look at an area: the line
   "Downloading map pictures..." shows in the lower left corner, the pictures fill in from the middle outwards, and the `maps` folder
   holds `manifest.json` and some packs.

## An update (a game update, or a fix to a picture)

1. Change the sources with the tools in `tools/README.md` (`fetch_tiles.py`, `pack_tiles.py`, `fill_tiles.py`, and so on), refresh the
   frozen references as `docs/VERIFY.md` says, and run `python tools/verify/acceptance.py --full`.
2. Build the next release with the next version number: `python tools/make_map_release.py --version 2`, then
   `python tools/verify/release.py`.
3. Create a **new** release (`maps-2`) with **all** the files again, not only the changed ones, because the plugin reads the newest
   release and finds every pack there. Unchanged packs are byte for byte the same, so uploading them again changes nothing for
   players. Use a draft release, upload everything, check the file count, then publish, so the manifest and the packs switch together.
   A player in the middle of a session can still ask for a pack under the old manifest: the plugin sees the hash does not match,
   drops the file and fetches the manifest again (at most every 10 minutes), so it corrects itself.
4. What a player's plugin does: once per session, at the first need, it fetches `manifest.json`. A cached pack is named after its
   SHA-256, so a pack whose hash did not change is still there and is not downloaded again; a pack whose hash changed is a different
   file and is downloaded when the view needs it. Cached files the new manifest no longer lists are deleted.
5. **A new or removed square** (not only a changed picture) changes `index.json`, which is inside the plugin jar. Run
   `python tools/make_map_release.py --resources`, commit, and publish a plugin update as usual. Until then the plugin ignores squares
   that its index does not list, and shows no picture for a square that its index lists and the manifest does not. Changed pictures
   alone need no plugin update.

To undo a bad release, delete it (or mark it a pre-release): the previous release becomes the latest again, and its manifest and packs
are consistent among themselves.

## What a player sees

- First run, online: the overview of the whole world at once, then sharp pictures filling in from the middle of the view outwards, with a
  small line in the lower left corner while packs download. A view of one town needs one to four packs (0.5 to 3.5 MB); the whole
  surface and its floors are 12 MB.
- Later runs: the cached packs are read from disk; nothing is downloaded. The manifest is fetched once a session when the first tile is read.
- Offline on a first run, or **Download map pictures** off: the overview, enlarged, with the icons, labels, pins and routes on it. The
  line in the corner says "Map pictures could not be downloaded, showing the overview" while the view waits for a pack that cannot be
  had. The plugin tries again later, waiting 30 seconds, then longer, up to 10 minutes between tries, and gives up on a pack for the
  rest of the session after 6 failures.
- Rate limited or a server error (429, 5xx), or no connection: nothing at all is sent to GitHub for 30 seconds (or what Retry-After
  says), then 1 minute, 2 and so on up to 15 minutes, until an answer comes.

## What is sent

A plain GET for a file of the release, through RuneLite's own HTTP client, which adds RuneLite's usual User-Agent. No cookie, no
query, no body, and nothing about the player, the account, the world or the game. GitHub sees the IP address of the player, as for
any download. The plugin description (`runelite-plugin.properties`), the README and the setting say so.

## The Plugin Hub warning (for the owner to add to the hub entry file)

The Plugin Hub shows a warning for a plugin that talks to a third party server. It is a `warning=` line in the plugin's entry file in the
plugin-hub repository (the file `plugins/world-map-plus`, next to `repository=` and `commit=`). The accepted plugin "Reimagined World Map"
does the same for its downloads. The text to use:

```
warning=This plugin downloads map pictures from GitHub (github.com/KingKj52/world-map-plus-maps) the first time you view an area and keeps them on your computer. GitHub sees your IP address. Nothing about you or your account is sent. The setting Download map pictures turns it off.
```

The same facts are in the plugin description in `runelite-plugin.properties`, in the README ("Map pictures") and in the description of the
setting, with the same meaning, so the three places and this line agree. Note for review: the downloader uses RuneLite's injected client as
it is (no derived client), asks for at most 3 files at once, and never retries in a loop; cached files are kept in the plugin's own
`Filepath` directory, named by pack name and hash, and the cache only ever drops a file after a new manifest no longer lists it.
