"""Builds the Vorbis setup-header table the mod needs to turn Duel Links' FMOD voice clips into Ogg.

FMOD strips each Vorbis stream's codebooks and keeps only a CRC32 naming the encoder settings. python-fsb5
(HearthSim, MIT) maps each CRC to (quality, channels, rate); the codebooks are what libvorbis' encoder (Xiph, BSD)
emits for those settings. This script runs libvorbis once here and writes them to
fabric/src/main/resources/data/duelcraft/dl/vorbis_headers.bin (no game data):
  u32 count, then per entry: u32 crc, u8 channels, u32 rate, u16 blocksize_short, u16 blocksize_long, u32 len, setup packet.

    .venv/Scripts/python -I tools/gen_vorbis_headers.py   (needs libvorbis/libogg DLLs in .tools/vorbisdll, e.g. from PyOgg)
"""
import os, struct, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DLL = os.path.join(ROOT, ".tools", "vorbisdll")
os.add_dll_directory(DLL)
os.environ["PATH"] = DLL + os.pathsep + os.environ["PATH"]
import ctypes.util  # noqa: E402

_find = ctypes.util.find_library
ctypes.util.find_library = lambda n: os.path.join(DLL, n + ".dll") if os.path.exists(os.path.join(DLL, n + ".dll")) else _find(n)
from fsb5.vorbis import get_header_info  # noqa: E402
from fsb5.vorbis_headers import lookup  # noqa: E402

out = os.path.join(ROOT, "fabric", "src", "main", "resources", "data", "duelcraft", "dl", "vorbis_headers.bin")
os.makedirs(os.path.dirname(out), exist_ok=True)
entries = []
for crc, (quality, channels, rate) in sorted(lookup.items()):
    bs_short, bs_long, setup = get_header_info(quality, channels, rate)
    entries.append(struct.pack("<IBIHHI", crc, channels, rate, bs_short, bs_long, len(setup)) + setup)
with open(out, "wb") as f:
    f.write(struct.pack("<I", len(entries)))
    for e in entries:
        f.write(e)
print(len(entries), "setup headers,", os.path.getsize(out), "bytes ->", out)
