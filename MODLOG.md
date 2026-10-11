# DuelCraft (working name) - MODLOG

Minecraft: Java Edition (host) x Yu-Gi-Oh! Master Duel (secondary: the mod reads the player's own Steam copy).
Collect real Master Duel cards in survival (and from a Creative tab), build a deck, duel mobs/villagers/friends
under full official rules (EDOPro's ocgcore), on a board in the world and on a duel screen. v1 musts: working
duels + a starter deck on spawn. Multiplayer like SkyCraft (e4mc relay, Melty join link).

## Paths
- Project: C:\Users\pitor\OneDrive\Desktop\MergedGames\DuelCraft (design/*.json are the source of truth)
- Master Duel: C:\Program Files (x86)\Steam\steamapps\common\Yu-Gi-Oh!  Master Duel (two spaces!) - Steam app 1449850
  - LocalData\3e1c8259\0000\xx\<8 hex> : 40,197 UnityFS bundles (Unity 6000.0.61f1, LZ4HC, flags 0x243)
- Minecraft here: official launcher (MS Store), versions 26.2-26.4 snapshots.
- Reference: ..\_ref\SkyCraft (Prism bundle + e4mc multiplayer recipe), ..\_ref\universal-modder (toolkit)

## Findings (2026-10-08)
- Card tables: TextAssets at assets/resourcesassetbundle/card/data/<hash>/en-us/card_{prop,name,desc,indx,named,...}.bytes.
  Encrypted: out[i] ^= ((i + k + 0x23D) * k ^ (i % 7)) & 0xFF, then zlib. k = 0x2A in this build; brute force 0..255 (zlib succeeds).
  - card_indx: uint32 pairs (name offset, desc offset) into card_name / card_desc (UTF-8, NUL-terminated). Row i matches card_prop row i.
  - card_prop: 8 bytes per card, a1,a2 uint32 LE. cid = a1 & 0x3FFF; kind = (a1>>16)&0x3F (bit 14 = ?);
    attr = (a1>>22)&0xF; level = (a1>>26)&0xF; atk = (a2&0x1FF)*10 (0x1FF = "?"); def = ((a2>>9)&0x1FF)*10;
    icon(spell/trap) = (a2>>18)&7; race = (a2>>21)&0x1F. Engine type = table[kind(,icon)]. Fit against EDOPro cdb: >99%.
  - card_named: u32 header (u16 groups=672, u16 entries=10391), then per group (u16 count, u16 cumulative end), then u16 cids. = archetypes.
  - 14,327 cards; 14,265 match EDOPro card names exactly (rest: OCG-only JP names, a few renamed).
- Card art: assets/resources/card/images/illust/{common,tcg}/<cid/1000>/<cid>.bmp -> Texture2D 512x512, format 25 = BC7, data in .resS inside the bundle.
- Music: sound/audioclip/bgm/*.wav -> AudioClip FSB5 Vorbis (hard to rebuild Ogg; out of v1).
- Duel engine: github.com/edo9300/ygopro-core (AGPL-3.0) builds with MSVC via tools\build_ocgcore.bat (Lua compiled as C++). Scripts: ProjectIgnis/CardScripts (AGPL-3.0), c<passcode>.lua, header comment has JP + EN name.
- ProjectIgnis/BabelCDB has NO license -> never shipped. Used only at build time as an oracle to derive ID tables (cid -> passcode, MD archetype -> setcode, MD kind -> type flags).

## Decisions
- Route: Fabric mod (Loom), Minecraft 26.3 + Fabric Loader 0.19.5 + Fabric API 0.161.0+26.3 + e4mc 6.2.2 (same as SkyCraft, proven on Melty).
  Shipped as a portable Prism Launcher instance (one-click on Melty).
- Engine card data is built at runtime from the player's Master Duel card_prop + card_named through shipped ID tables; card names/text/art come only from Master Duel.

## 2026-10-08 engine bridge (verified)
- Java reads Master Duel directly: UnityBundle (LZ4 + LZMA via xz-java with size -1: Unity writes an end marker), SerializedFile (type trees, Unity common strings exported from UnityPy), MasterDuelIndex (40,204 assets, 27 s first run, cached in <game>/duelcraft/md_index.tsv), MasterDuelCardTables (key 0x2A found by brute force), CardDb (14,326 cards, 14,074 playable).
- LZMA blocks are decoded only as far as needed (card-art bundles are one 260 KB LZMA block; the index needs 4.6 KB): 170 s -> 27 s.
- Card art: BC7 via native dc_decode_bc7, pixel-identical to UnityPy. Master Duel downloads some art on demand: 191 cards have no local art at all; alternates (same passcode) are used first (e.g. BEWD 4007 -> 3801).
- duelcraft_native.dll = ocgcore 11.0 + bcdec, built by tools/build_native.bat; FFM binding in OcgCore (x64 struct offsets: DuelOptions 136 B, CardData 64 B, NewCardInfo 24 B, QueryInfo 20 B). Host must load constant.lua + utility.lua itself after OCG_CreateDuel.
- Soak: 30 AI-vs-AI duels (starter/dragons/undead) all end with MSG_WIN (LP), max 57 turns, 6.5 s total, no AI answer rejected, no undecoded prompt bytes, snapshot counts == OCG_DuelQueryCount.
- ocgcore does NOT shuffle starting decks; DuelEngine shuffles with the duel seed (found when two in-game duels opened with the same hand). Soak after fix: 30/30, 8-22 turns.

## 2026-10-08 in-game tests (dev client, test bridge)
- OS input can't reach the game (Claude desktop keeps the foreground), so fabric/src/client/.../TestBridge.java (only when DUELCRAFT_TEST_DIR is set) reads build/testbridge/cmd.txt: press/click/chat/duel/duelauto/widgets; tools/tb.sh drives it, tools/restart_client.sh restarts into the test world.
- Verified in game: starter deck on join, deck box editor (40 cards), creative tab "Duel Cards (from Master Duel)" + art tooltip, duel vs villager and vs zombie (frozen NoAI 1b during, restored after), full duels through the duel screen widgets, world board (mat, cards with Master Duel art, LP labels), HUD.
- Multiplayer: host with DUELCRAFT_LAN_PORT=25599 (+ DUELCRAFT_LAN_OFFLINE=1 for dev clients) -> e4mc logs "Domain assigned: gulf-flyover.na.e4mc.link"; second client (runClient2, run2/) with config/duelcraft.properties join=<that> auto-joined from the title screen; /duel challenge Rival + /duel accept -> full PvP duel to a win over the relay.
- Fixed on the way: deck shuffle, DXT1/DXT5 art (native dc_decode_bc), zone prompt mask uses all 32 bits, HINT_SELECTMSG before SELECT_PLACE carries a passcode, LP labels moved out of the duelists' faces.
- More in-game coverage (prompt test deck, /duel nearest prompts): card-name search, declare Type, optional effects, positions all answered through the duel screen; 4/4 duels ended. Card drops: 1 card from 50 player kills (8%). Master Duel missing -> red wrapped line on the title screen.
- Test client: options pauseOnLostFocus:false (an unfocused single-player game pauses its server); captures need the window visible (a hidden window keeps its last frame).

## 2026-10-10 Duel Links characters and voices (0.2.0)
- Ask: pick a Duel Links character (K) to duel as; your own deck, their voice: their lines for their own cards, generic lines for the rest; friends hear each other, mobs silent; face on the duel screen only; every character in the player's Duel Links.
- Duel Links (Steam 601510): LocalData/<hash>/xx/<bundle> UnityFS. Faces: characters/sn####/images/en-us/chara007_1..6 (ARGB4444; 1 normal 2 happy 3 shout 4 worried 5 pained 6 grin), icon chara001 (name drawn in). Voices: sound/audioclip/voice/en-us/v####/v####_<cat>_<sub>_<idx>_<part>.wav = AudioClip -> FSB5 Vorbis (no setup header). 193 characters, 54,516 clips.
- Category meanings worked out from Jaden's 428 transcripts -> design/voice_categories.json (08 card spell/trap, 09 card monster effect, 13 card summon chant, 19 card attack = 13's index, the rest generic triggers).
- FSB5 -> Ogg in Java (DuelLinksVoice): setup headers rebuilt once with libvorbis from python-fsb5's CRC lookup -> data/duelcraft/dl/vorbis_headers.bin (164 headers). Played through Minecraft's sound engine: VoiceSound + SoundBufferLibraryMixin serve duelcraft_dl:voice/<clip> as JOrbis streams. Exact sample counts vs FSB headers (DuelLinksDataTest).
- Card lines: 15,788 clips of 08/09/13 transcribed with faster-whisper base.en (CPU, ~2.5 h on this i5), tools/gen_voice_tables.py fuzzy-matches them to Master Duel card names (word-for-word name said last wins, else rapidfuzz partial_ratio >= 88 for multi-word names, playable cards only) -> dl/card_lines.tsv (numbers only).
- Verified in game: menu (all characters), Yugi's lines + portrait vs a villager, PvP over e4mc as Kaiba vs Yugi with both clients playing both voices and the joiner's saved pick sent on join. e4mc dropped twice while 7 whisper workers had the CPU.
- Melty test mode (MeltyTest): %LOCALAPPDATA%/Melty/test/duelcraft.json -> fresh peaceful world from the seed, seeded character, auto duel vs villager; local rehearsal (DUELCRAFT_MELTY_TEST) 6/6 checks, 10 turns, 37 lines.
