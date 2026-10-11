"""Build-time research: transcribe Duel Links voice clips locally (faster-whisper) so card-specific lines can be
matched to cards. Input: Ogg files exported from the player's install by DlExportProbeTest (research/dl/ogg, never
shipped). Output: a local TSV (clip id <tab> text) for tools/gen_voice_tables.py; only clip id -> card id ships.

    .venv/Scripts/python -I tools/transcribe_dl.py <ogg dir> <out.tsv> [workers] [model]
Resumable: clips already in out.tsv are skipped. Needs libvorbis/libogg in .tools/vorbisdll (PyOgg).
"""
import os, sys, time
from multiprocessing import Pool

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_model = None


def init(model_name):
    global _model
    os.add_dll_directory(os.path.join(ROOT, ".tools", "vorbisdll"))
    from faster_whisper import WhisperModel
    _model = WhisperModel(model_name, device="cpu", compute_type="int8", cpu_threads=1)


def work(path):
    import numpy as np
    import pyogg
    name = os.path.basename(path)[:-4]
    try:
        f = pyogg.VorbisFile(path)
        pcm = np.frombuffer(f.buffer, dtype=np.int16).astype(np.float32) / 32768.0
        if f.channels > 1:
            pcm = pcm.reshape(-1, f.channels).mean(axis=1)
        n16 = int(len(pcm) * 16000 / f.frequency)
        audio = np.interp(np.linspace(0, len(pcm) - 1, n16), np.arange(len(pcm)), pcm).astype(np.float32)
        segs, _ = _model.transcribe(audio, language="en", beam_size=1, vad_filter=False)
        return name, " ".join(s.text.strip() for s in segs).replace("\t", " ").replace("\n", " ")
    except Exception as e:
        return name, "!ERR " + repr(e)[:80]


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    src, out = sys.argv[1], sys.argv[2]
    workers = int(sys.argv[3]) if len(sys.argv) > 3 else 7
    model = sys.argv[4] if len(sys.argv) > 4 else "base.en"
    done = {l.split("\t")[0] for l in open(out, encoding="utf-8")} if os.path.exists(out) else set()
    # each line's first part first: it usually names the card, so a run cut short still covers most lines
    items = sorted((os.path.join(src, f) for f in os.listdir(src) if f.endswith(".ogg") and f[:-4] not in done),
                   key=lambda p: (not p.endswith("_0.ogg"), p))
    print(len(items), "clips to transcribe", flush=True)
    t0 = time.time()
    with Pool(workers, initializer=init, initargs=(model,)) as pool, open(out, "a", encoding="utf-8") as o:
        for i, (name, text) in enumerate(pool.imap_unordered(work, items, chunksize=16)):
            o.write(f"{name}\t{text}\n")
            if i % 500 == 0:
                o.flush()
                print(i, "done", round(time.time() - t0), "s", flush=True)
    print("finished", len(items), "in", round(time.time() - t0), "s")
