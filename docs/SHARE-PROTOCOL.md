# World Map Plus share codes, version 1

A share code carries a pin, a route or a set of places as a short text that a player types into the game's chat after
the command `!wmp`. Everything needed to rebuild it is in the code: no server, no network, no lookup table. The
implementation is `com.worldmapplus.helper.share.ShareCodec` (no RuneLite dependency) and the tests are `ShareCodecTest`
(golden vectors, round trips, damaged input), `ShareInboxTest`, `PasteReaderTest` and `chat/ShareChatTest`. Since round 56B all of
this lives in the World Map Plus Helper plugin (its own repository, `World Map Plus Helper`, next to this one); this plugin keeps
only the buttons and the saving of the places (`Partners`, the `create` kinds `shared_pins` and `shared_route`, tested by
`PartnersTest`). The wire format did not change in the move.

**Stability.** A version 1 code must always decode to the same value. Nothing in this document may change meaning. A
change of layout needs version 2 (the second character), which version 1 readers refuse with "made by a newer version".

## What is carried

| Kind | Letter | Holds |
|---|---|---|
| Pin | `p` | one point |
| Route | `r` | 1 to 64 points in order, and whether the route comes back to its first stop |
| Places | `s` | 1 to 64 points (the "Share ticked" of several places) |
| Part | `c` | one piece of a longer `r` or `s` code, see "Messages and parts" |

A point is a world tile `x`, `y` (0 to 32767, RuneLite `WorldPoint` coordinates), a floor (0 to 3), and optionally a
World Map Plus map id (0 to 32767). Without a map id the receiving client puts the point on the map its own catalogue
gives that tile (`MapCatalog.mapFor`); the sender includes the id only when the place was saved on another map (the
wiki's extra maps overlap the standard ones). The coordinates always decide where the point is; the map id only decides
which picture shows it. Nothing else travels: no names, kinds, colours, sizes, visibility, paths, teleports or costs.

Pins are stored at doubles; a code holds the tile (`floor(x)`, `floor(y)`) and a saved shared pin is placed at the
tile's centre (`+ 0.5`), where a pin dropped by hand sits.

## Text

- Alphabet: Crockford base 32, `0123456789abcdefghjkmnpqrstvwxyz`, value = index. Written in lower case. Read case
  insensitively, with `o` read as `0` and `i`, `l` as `1`. `u` and every other character are refused.
- Five bits a character, most significant bit first. The first character is the kind letter's value, the second the
  version (`1`), so every code starts `p1`, `r1`, `s1` or `c1`.
- A chat message is `!wmp`, one space, the code. At most 76 characters in all (`ShareCodec.MAX_MESSAGE`), so a code of
  up to 71 characters goes in one message.

## Bit layout of `p1`, `r1`, `s1`

All fields are unsigned and most significant bit first unless said otherwise; signed fields are two's complement.

```
kind        5    value of p, r or s (22, 24, 25)
version     5    1
count-1     6    r and s only: number of points minus 1 (0..63)
loop        1    r only: 1 = comes back to the first stop
maps        1    1 = map parts follow the points (only when at least one point names a map)
point 0          ABSOLUTE body (below), no opcode
[map part]       when maps = 1
point 1..n-1     a record (opcode and body), then [map part] when maps = 1, except after EARLIER
crc         10   CRC-10/ATM of every bit before it, from the first bit of kind
padding     0-4  zero bits up to a whole character
```

### ABSOLUTE body (29 or 33 bits)

```
plane 2, wide 1, then wide = 0: x 12, y 14     (x < 4096 and y < 16384: every map of the plugin)
                      wide = 1: x 15, y 15     (only when the short form does not fit; a wide code that fits short is refused)
```

### Records for points after the first

The previous point is `a`, the new one `b`. "Region" is the 64 by 64 square, region x = `x >> 6`, region y = `y >> 6`,
compared separately (RuneLite's packed region id `(x >> 6) << 8 | y >> 6` is only unique for y below 16384).

| Opcode | Name | Body | Bits in all | Gives |
|---|---|---|---|---|
| `00` | SAME_REGION | local x 6, local y 6 | 14 | `a`'s region and floor, these local coordinates |
| `01` | SMALL_STEP | dx 4, dy 4 (signed, -8..7) | 10 | `a + (dx, dy)`, `a`'s floor |
| `100` | NEXT_REGION | neighbour 3, local x 6, local y 6 | 18 | the neighbouring region (below), `a`'s floor |
| `101` | MEDIUM_STEP | dx 8, dy 8 (signed, -128..127) | 19 | `a + (dx, dy)`, `a`'s floor |
| `110` | ABSOLUTE | ABSOLUTE body | 32 or 36 | the point as written |
| `1110` | EARLIER | index 6 | 10 | an exact copy of point `index` (which must be before this one), map included; no map part follows |
| `1111` | FLOOR | plane 2, local x 6, local y 6 | 18 | `a`'s region on another floor |

Neighbours, by number: 0 (-1, -1), 1 (0, -1), 2 (+1, -1), 3 (-1, 0), 4 (+1, 0), 5 (-1, +1), 6 (0, +1), 7 (+1, +1), as
(region x step, region y step). A record whose result is outside 0..32767 is refused.

### Map part

```
explicit 1      0 = the tile's own map (no more bits)
                1 = class 2, then: class 0: id 8 (0..255)   class 1: 10000 + 8 bits   class 2: 11000 + 8 bits
                                    class 3: id 15 (only for an id the other classes cannot hold, otherwise refused)
```

`maps = 1` with no point naming a map is refused.

### Choosing records (encoder)

For each point after the first, the encoder takes the record with the fewest bits (map part included), ties going to the
first in this order: SMALL_STEP, SAME_REGION, EARLIER, NEXT_REGION, MEDIUM_STEP, FLOOR, ABSOLUTE. EARLIER names the
earliest equal point before the previous one. Every record rebuilds exactly the same point, and the next record is
measured from that point whatever record was used, so choosing the cheapest record for each point alone gives the
cheapest code for the whole list; no search over the route is needed. Equal input always gives the same code.

## Checksum

CRC-10/ATM: width 10, polynomial `0x233` (x^10 + x^9 + x^5 + x^4 + x + 1), initial value 0, input and output not
reflected, final xor 0; check value `0x199` for the ASCII bytes `123456789` (tested). It is computed bit by bit over every
bit from the first bit of the kind field up to the checksum, most significant bit first. Padding is not covered and must
be zero; five or more bits after the checksum are refused.

It catches every single mistyped character and every swap of two neighbouring characters (bursts of up to 10 bits),
and other damage with odds of about 1 in 1024. It is a check against accidents and does not stop anyone from making a
code that says what they want.

## Messages and parts

A code longer than 71 characters is sent in parts, at most 8 (`MAX_PARTS`), so at most 528 characters (`MAX_CODE`).
With `L` the code's length: `parts = ceil(L / 66)`, `size = ceil(L / parts)`, part `i` carries characters
`i * size` up to `min(L, (i + 1) * size)` of the code. Each part is the message

```
!wmp c1 HHH DATA        (written without spaces after c1: "!wmp c1HHHDATA")
HHH = 15 bits: transfer 9, index 3 (0..7), total-1 3 (total 2..8)
```

`transfer` is the top 9 bits of the 32 bit FNV-1a hash (offset 0x811C9DC5, prime 0x01000193) of the whole code's
characters in canonical form. The receiver keeps parts apart by channel, sender and transfer, takes them in any order,
lets a part sent again replace the one before, and joins them only when all are there; the joined code must give the
same transfer and then be read like any other code. Waiting parts expire after 10 minutes, at most 8 codes wait at once,
and a part has no checksum of its own (the whole code's checksum and the transfer id check the join).

## Showing and typing a code (round 50)

Nothing here changes the format; it is how the plugin shows a code to the player who types it.

- The side panel shows each message whole, in a fixed width font: the command, a small mark for the space after it, then
  the code in groups of four characters with a thin gap between groups, wrapped at group edges to the side bar's width.
  The gaps are drawn only: the message is unchanged, and Copy copies it exactly. Each message has its number, its length
  and a state (not typed, typing, done).
- The typing guide (`typing/TypingGuide`) compares the chat input line with one message from its first character, after
  a channel prefix of up to four slashes. Case does not matter (the command is read in lower case, the code case
  insensitively), and in the code `o` counts as `0` and `i` or `l` as `1`, as the reader takes them. A message counts as
  sent when the line held it exactly and then became empty; the guide then moves on to the next part that is not done.
  A line that matches exactly is shown as "Matches, checksum ok": an exact match carries the code's own checksum.

## What chat shows (round 52; round 51 added labels after the code)

Nothing here changes the format; it is what a client with the plugin shows for a code in chat, on that client only.

- The line shows a description in place of the command and the code, as RuneLite's Chat Commands plugin shows the answer
  to `!lvl`: it is the line's RuneLite format message, which the client draws in place of the message while it builds the
  chat box. The message itself is kept, so chat history and other plugins see what was typed; nothing sent is changed.
- A whole code, sent in one message or completed by its last part: `World Map Plus pin: Farming Guild, 1249, 3720`,
  `World Map Plus route: 15 stops, starts at 1000, 2100` (", loops" after the count for a route that loops),
  `World Map Plus places: 4`, with "World Map Plus" in the chat's highlight colour. A place is named only when that is
  known for sure: a place the reader has saved at that very tile, floor and map (not one still called "Pin at ..."), or
  the name of a map other than the surface. Nothing is guessed from what is near. Every part line of a completed code
  shows exactly that text.
- A part whose code is not complete: `World Map Plus route, part 1 of 2 received, waiting for the next part` ("waiting
  for 2 more parts" when more are missing). The kind is known only when the first part is among those that arrived (its
  data starts with the code's kind letter); otherwise the line says "share". When the last part arrives each earlier part
  line shows the whole code's text. A part typed twice changes too; a part sent again after its code is whole shows the
  whole code's text at once. A line whose format message something else has set is left alone.
- A code that does not read: `World Map Plus code that could not be read: p10kgkm86b`, the code kept so the sender can
  check it; parts that do not join: `World Map Plus: the 2 parts do not fit together, one may be mistyped`.
- Parts that never complete keep their waiting text; they are forgotten after 10 minutes or when 8 other codes wait. At
  most 16 lines are kept for one code.
- The parts of the player's own code (their own lines, and the echo of a private message they sent, kept apart by who it
  went to) are joined and shown the same way and never listed as shared with them. The setting "Replace my own share
  lines too" (on) turns that off for the player's own lines, which then stay as typed.
- The right click entry Open in World Map Plus is on lines of other players only: it is added on the game's "Report"
  entry, which the game does not give the player's own lines. A description that stands for a share ends with two empty
  colour tags, `<col=7eHHHH><col=7fLLLL>`, which draw nothing and carry the line's message id (high and low 16 bits in
  hex). The entry reads the message widget's text as Chat History does, takes the id from those tags and opens the share
  decoded for that line, kept by message id (at most 256 lines). Two lines whose descriptions read the same open their
  own shares. A line this plugin did not change (another plugin set its text first) is read for a code as before.

## Codes outside the game (round 52)

Nothing here changes the format either.

- **Copy whole code** copies `!wmp` and the whole code as one, however long (up to 528 characters). It is the same code
  the parts are cut from: the data of the parts, in order, is the whole code (`PasteReaderTest.thePartsArePiecesOfTheWholeCode`).
  **Copy all parts** copies the messages of a long code, one a line, each with `!wmp`.
- **Import a code** (`share/PasteReader`, then `ShareInbox.receive` for each code, the reading chat uses) finds codes in
  any text: a run of ASCII letters and digits that starts with `p`, `r`, `s` or `c` and a version digit, at least 6 long,
  so the command, quotes, backticks, markdown, other words and line breaks are passed over; `!wmp` with the space left
  out is dropped; case does not matter. A version digit above 1 is answered "made by a newer version"; a letter codes
  never use is named with its position.
- Parts may come one a line, on one line, in any order, or one paste at a time: pasted parts wait under their own key, as
  chat parts do (10 minutes, 8 codes), and the answer says which are still missing ("Part 1 of 2 added, paste the next
  part (part 2 of 2 is missing)"). Parts run together with nothing between them are split from their heads: with `T`
  parts (from the first head) and the run `R` long, the code is `L = R - 5T` long, `T` must be `ceil(L / 66)`, every part
  but the last carries `ceil(L / T)` characters and the last the rest, so each piece's length follows from its own head
  and the pieces may be in any order. If the lengths do not add up or a head does not fit, the answer is "put each part on
  its own line".
- A code that does not read is searched for one mistyped character (codes up to 142 characters, every character after
  `p1`, `r1` or `s1` tried with every other letter, a few thousand tries, on the button press only): when one to three
  places make it read, the answer names them, "Checksum mismatch near character 11, check what you typed". A 10 bit check
  passes about one change in a thousand by chance, so for a long code more places may be named, or none.
- Bounded: 20,000 characters of a paste are looked at (the box holds no more), 32 codes taken from one paste, every
  share listed under Shared with you and nothing saved until Save.

## Golden vectors

Made by the finished encoder; `ShareCodecTest.goldenVectorsDecodeAndEncodeExactly` holds them.

| Code | Holds |
|---|---|
| `p10kgkm86a` | pin (1249, 3720), floor 0: Farming Guild, region 4922, local (33, 8). Message `!wmp p10kgkm86a`, 15 characters |
| `p11jb34j2v` | pin (3222, 3218), floor 0 |
| `p19j434mgz` | pin (3208, 3220), floor 2 |
| `p1h8m470p02d0` | pin (2600, 4320), floor 0, map 10128 |
| `p1000000aa` | pin (0, 0), floor 0 |
| `p1fzzzzzz9a` | pin (32767, 32767), floor 3 |
| `r120cjrs4g8a2651tg00j7w` | route (3222, 3218), (3208, 3220), (3235, 3205), (3214, 3240), (3200, 3204), floor 0 |
| `r11rce4tv129e0969rb2` | route that loops: (3185, 3436), (3170, 3410), (3185, 3436), (3250, 3420) |
| `r120cfrs02keb48699gs9ct1c2e2eh37m` | route (3198, 3200, 0), (3202, 3197, 0), (3208, 3220, 1), (3237, 9858, 0), (1249, 3720, 0) |
| `s118s5hj963a1jr380rc2gsarym` | places (3222, 3218, 0), (3393, 9601, 0, map 10000), (3092, 3243, 0) |

A route of 30 points scattered over the surface (`new Random(7)`, x 960 + nextInt(3072), y 2048 + nextInt(2176)) is the
three messages of `ShareCodecTest.goldenLongRouteSplitsIntoThreeParts`, starting `!wmp c1322`, `!wmp c132a`, `!wmp c132j`
(76, 76 and 75 characters).

## Why this format

`ShareBench` (test sources) prices candidate encodings over the same header, checksum and text. Characters after
`!wmp ` (the bench gives every scenario the route header; a real pin code is 10 characters, not 12):

| Scenario | stops | naive | A | B | C | D | E (chosen) |
|---|---|---|---|---|---|---|---|
| pin in region 4922 | 1 | 13 | 12 | 12 | 12 | 12 | 12 |
| 5 stops in one region | 5 | 40 | 26 | 22 | 23 | 23 | 23 |
| 10 stops in one region | 10 | 74 | 44 | 46 | 47 | 39 | 39 |
| 5 stops in one 8x8 chunk | 5 | 40 | 20 | 22 | 18 | 20 | 20 |
| nearby stops across region edges | 5 | 40 | 23 | 37 | 37 | 23 | 23 |
| across several cities | 5 | 40 | 34 | 33 | 33 | 34 | 34 |
| long jumps (herb run) | 8 | 60 | 55 | 55 | 55 | 58 | 57 |
| different floors (Lumbridge castle) | 5 | 40 | 32 | 33 | 32 | 34 | 25 |
| underground and surface | 4 | 33 | 25 | 23 | 24 | 24 | 24 |
| repeated places (bank between stops) | 7 | 54 | 33 | 42 | 42 | 27 | 28 |
| 12 random coordinates | 12 | 88 | 80 | 80 | 80 | 84 | 82 |
| 40 stops, 0 to 40 tiles apart | 40 | 278 | 152 | 182 | 186 | 132 | 136 |
| 40 stops scattered | 40 | 278 | 251 | 254 | 254 | 266 | 259 |
| total | | 1078 | 787 | 841 | 843 | 776 | 762 |

- naive: x and y as 16 bits each and the floor, 34 bits a point.
- A: absolute first point, then small (±8), medium (±128) or large (±32768) steps, or absolute, 2 bit opcodes.
- B: region id 16 + local 12 + floor 2; a point in the previous point's region sends 12 bits.
- C: B with an 8 by 8 chunk level (6 bits for a point in the previous point's chunk).
- D: E's records with a 4 bit ABSOLUTE and no FLOOR record.
- E: the format above. Variants measured: without SAME_REGION 810, without NEXT_REGION 786, without EARLIER 780 (all
  on D), D with a chunk record 774.

What was kept and why: the region records (SAME_REGION, NEXT_REGION, FLOOR) pay where stops cluster in a town and
share floors; steps pay for stops close together across region edges, where a region record would cost a full region;
EARLIER pays for routes that come back to a bank. The 8 by 8 chunk record was left out: it saved one character in its
best scenario and nothing elsewhere, since a step of up to 8 tiles costs 10 bits against its 9. Total, a 29 % saving over
naive coordinates; for clustered routes the codes are about half as long.

## Security

A code is data only: no field names a class, a file or an address, nothing is deserialised by reflection, and no
dynamic code is involved. Every field is range checked, the bit reader refuses to read past the end, sizes are bounded
(64 points, 528 characters, 8 parts, 8 codes waiting, 16 chat lines kept for each, 12 shares listed, 256 chat lines tied to their share, 32 codes and 20,000 characters a paste, 64 finished
parts remembered), coordinate
arithmetic is done in `int` on values that cannot overflow and checked for 0..32767, and every failure is a
`ShareException` with a sentence for the player.

## Old codes

Codes made before this version start with `wmp` (no `!`), use a different alphabet and carried names and colours. They
are not read; a pasted old code gets a sentence saying so. Saved pins and routes are unchanged.
