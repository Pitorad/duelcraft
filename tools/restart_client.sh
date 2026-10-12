#!/usr/bin/env bash
# Restart the dev client with the test bridge, load the first world, wait until the player has joined.
cd /c/Users/pitor/OneDrive/Desktop/MergedGames/DuelCraft
um win ps java | awk '{print $1}' | xargs -r -n1 um win kill >/dev/null
sleep 2
L=fabric/run/logs/latest.log
mv $L $L.prev 2>/dev/null
(cd fabric && DUELCRAFT_TEST_DIR='C:\Users\pitor\OneDrive\Desktop\MergedGames\DuelCraft\build\testbridge' DUELCRAFT_LAN_PORT=${DUELCRAFT_LAN_PORT:-} DUELCRAFT_LAN_OFFLINE=${DUELCRAFT_LAN_OFFLINE:-} nohup ./gradlew.bat runClient --no-configuration-cache > ../build/runClient.log 2>&1 &)
until [ -f $L ] && grep -q "Sound engine started" $L && grep -q "DuelCraft ready" $L; do sleep 2; done
tools/tb.sh "press Singleplayer" "wait 60" > /dev/null
# the world list fills in late: click the first world until "Play Selected World" is enabled
for i in 1 2 3 4 5 6 7 8; do tools/tb.sh widgets | grep -q "Play Selected World @ [0-9,]*$" && break; tools/tb.sh "click 267 67" "wait 10" > /dev/null; done
tools/tb.sh "press Play Selected World" > /dev/null
until grep -q "joined the game" $L; do sleep 2; done
tools/tb.sh "wait 60" screen
