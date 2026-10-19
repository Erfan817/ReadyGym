"""Build the app's exercise library from data/exercise-library.source.json.

Validates every entry, copies each demo GIF into the app assets as
`exercises/<id>.gif` (gitignored: the GIFs come from the local gifdb/ folder
and have no redistribution license), and writes `exercise-library.json`.

Run from anywhere:  python data/scripts/build_exercise_library.py
"""
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "data" / "exercise-library.source.json"
GIFDB = ROOT / "gifdb"
LEGACY = ROOT / "android" / "app" / "src" / "main" / "assets" / "exercise-demos"
ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"
OUT_GIFS = ASSETS / "exercises"
OUT_JSON = ASSETS / "exercise-library.json"

MUSCLES = {"TRAPEZIUS", "SHOULDERS", "CHEST", "UPPER_BACK", "SERRATUS", "BICEPS", "TRICEPS", "FOREARMS",
           "ABS", "OBLIQUES", "LOWER_BACK", "GLUTES", "QUADRICEPS", "HAMSTRINGS", "ADDUCTORS", "HIP_FLEXORS",
           "CALVES", "TIBIALIS"}
EQUIPMENT = {"BODYWEIGHT", "DUMBBELL", "BARBELL", "MACHINE"}
LOCATIONS = {"HOME", "GYM"}
PATTERNS = {"SQUAT", "HINGE", "LUNGE", "HORIZONTAL_PUSH", "VERTICAL_PUSH", "HORIZONTAL_PULL", "VERTICAL_PULL",
            "CORE", "ISOLATION", "CARDIO"}
LEVELS = {"BEGINNER", "INTERMEDIATE", "ADVANCED"}
MEASURES = {"REPS", "SECONDS"}


def resolve_gif(ref: str) -> Path:
    if ref.startswith("legacy:"):
        return LEGACY / ref.removeprefix("legacy:")
    return GIFDB / ref


def validate(ex: dict) -> list[str]:
    errs = []
    def check(field, allowed):
        if ex.get(field) not in allowed:
            errs.append(f"{field}={ex.get(field)!r}")
    check("primary", MUSCLES); check("equipment", EQUIPMENT); check("pattern", PATTERNS)
    check("level", LEVELS); check("measure", MEASURES)
    errs += [f"secondary={m!r}" for m in ex.get("secondary", []) if m not in MUSCLES]
    if not ex.get("locations") or any(l not in LOCATIONS for l in ex["locations"]):
        errs.append(f"locations={ex.get('locations')!r}")
    if not ex.get("name") or not ex.get("nameEn"):
        errs.append("missing name")
    if len(ex.get("tips", [])) < 2:
        errs.append("needs >=2 tips")
    return errs


def main() -> int:
    source = json.loads(SOURCE.read_text(encoding="utf-8"))
    problems, seen, out = [], set(), []
    for ex in source["exercises"]:
        eid = ex.get("id", "?")
        if eid in seen:
            problems.append(f"{eid}: duplicate id")
        seen.add(eid)
        problems += [f"{eid}: {e}" for e in validate(ex)]
        gif = resolve_gif(ex["gif"])
        if not gif.is_file():
            problems.append(f"{eid}: gif not found {gif}")
        entry = {k: v for k, v in ex.items() if k != "gif"}
        entry["gifAsset"] = f"exercises/{eid}.gif"
        out.append((entry, gif))
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1

    if OUT_GIFS.exists():
        shutil.rmtree(OUT_GIFS)
    OUT_GIFS.mkdir(parents=True)
    total = 0
    for entry, gif in out:
        dest = ASSETS / entry["gifAsset"]
        shutil.copyfile(gif, dest)
        total += dest.stat().st_size
    library = {"version": source["version"], "notice": source["notice"], "exercises": [e for e, _ in out]}
    OUT_JSON.write_text(json.dumps(library, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(out)} exercises, {total / 1e6:.1f} MB of GIFs -> {OUT_GIFS.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
