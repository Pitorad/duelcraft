#!/usr/bin/env bash
# Test bridge driver: tb.sh "cmd1" "cmd2" ... -> writes cmd.txt, waits for the game to answer, prints new out.txt lines.
D=${TBD:-/c/Users/pitor/OneDrive/Desktop/MergedGames/DuelCraft/build/testbridge}
touch $D/out.txt; before=$(wc -l < $D/out.txt)
printf '%s\n' "$@" > $D/cmd.tmp && mv $D/cmd.tmp $D/cmd.txt
for i in $(seq 1 150); do [ ! -f $D/cmd.txt ] && n=$(wc -l < $D/out.txt) && [ "$n" -gt "$before" ] && sleep 0.5 && break; sleep 0.2; done
tail -n +$((before+1)) $D/out.txt
