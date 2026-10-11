# DuelCraft

**Minecraft: Java Edition × Yu-Gi-Oh! Master Duel × Yu-Gi-Oh! Duel Links.** Collect real Yu-Gi-Oh! cards in your
Minecraft world, build a deck, and duel villagers, mobs and friends under the full official rules, as your favourite
Duel Links character, in their voice.

The cards come from **your own copy of Yu-Gi-Oh! Master Duel** (free on Steam): DuelCraft finds it on your PC and reads
the card names, effect text, stats and artwork from Master Duel's own files each time you play. Nothing from Master Duel
is included in DuelCraft. The characters (faces and voice lines) come the same way from **your own copy of Yu-Gi-Oh! Duel
Links** (also free on Steam). Duels run on the open-source EDOPro rules engine, so almost every card works like it does in
Master Duel.

## What you get
- **Real Master Duel cards as items**: every card from your Master Duel (over 14,000) with its art, name, type, ATK/DEF and
  full effect text in the tooltip. A creative tab lists them all.
- **Starter deck on spawn**: your first time in a world you get a ready 40-card deck box (Dark Magician, Summoned Skull,
  Mirror Force and friends), so you can duel within the first minute.
- **Deck box**: use it to edit your deck (40-60 Main Deck cards, up to 15 Extra Deck cards); craft more with a book and leather.
- **Duel mobs and villagers**: right-click a villager or mob while holding your deck box. Villagers duel with a
  Blue-Eyes deck, zombies and skeletons with an Undead deck (with Synchro Summons). Win to take one of their cards.
- **Full official rules**: chains, Spell Speeds, Tributes, Fusion/Ritual/Synchro/Xyz/Pendulum/Link summons, the
  Extra Monster Zones, every prompt the real game asks.
- **Duel on a board in the world, or on a duel screen**: cards appear on a duel mat between the duelists for everyone
  to see; press **V** to open the duel screen with your hand, the field, the log and the card text.
- **Duel as a Duel Links character**: press **K** and pick anyone from your Duel Links (Yugi, Kaiba, Jaden, Yusei and
  every other character in it). You keep your own deck; they lend their voice: their intro, "my turn", draws, attacks,
  damage, wins and losses, and their own lines for their signature cards ("I summon Dark Magician!"), with generic
  lines for every other card. Their face shows next to the duel screen, changing expression as they speak.
  Friends you duel hear your character and you hear theirs; mobs stay silent.
- **Collect in survival**: hostile mobs sometimes drop a random card.
- **Multiplayer**: play together with friends. Open your world (Melty does it for you) and friends join with Melty's join
  link through e4mc's relay, no port forwarding; then right-click a friend with your deck box (or `/duel challenge <name>`)
  and they accept in chat.

## Commands
`/duel accept` · `/duel decline` · `/duel forfeit` · `/duel status` · `/duel challenge <player>` · `/duel nearest`

## Requirements
- Minecraft: Java Edition (Melty installs DuelCraft with Fabric through Prism Launcher; sign in once).
- Yu-Gi-Oh! Master Duel installed from Steam and started at least once. If it isn't found, the title screen says so.
- Yu-Gi-Oh! Duel Links installed from Steam and started at least once (its data download finished) for the characters
  and voices. Without it DuelCraft still works, just without characters.

## Building
See `MODLOG.md` (journal) and `design/*.json` (the design sheets every part is generated/checked from).
`python -I tools/preflight.py` then `powershell -File tools/package.ps1`.

## Credits and licence
DuelCraft is AGPL-3.0-or-later. It includes EDOPro's duel engine (edo9300/ygopro-core, AGPL-3.0), Project Ignis
CardScripts (AGPL-3.0), Lua (MIT), bcdec (MIT), XZ for Java (0BSD) and a table from UnityPy (MIT), Vorbis setup headers made with libvorbis (BSD-3-Clause) from python-fsb5's lookup (MIT); card-line matching
was worked out at build time with faster-whisper (MIT, not shipped); it ships Prism Launcher
(GPL-3.0), Fabric API (Apache-2.0) and e4mc (MIT). Built with AI (Claude) for Pitorad.
Yu-Gi-Oh!, Master Duel and Duel Links are trademarks of Konami; Minecraft is a trademark of Mojang/Microsoft. Fan-made, not
affiliated with or endorsed by them.
