"""Regression tests for muscle normalisation.

These run against the artefacts committed under data/, so a regenerated catalogue that
silently goes back to trusting the upstream `muscle_group` field fails here.
"""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "import_exercises.py"
DRAWABLE = {
    "trapezius", "shoulders", "chest", "upper-back", "serratus",
    "biceps", "triceps", "forearms",
    "abs", "obliques", "lower-back",
    "glutes", "quadriceps", "hamstrings", "adductors", "hip-flexors",
    "calves", "tibialis",
}


def load_module():
    spec = importlib.util.spec_from_file_location("import_exercises", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class MuscleNormalisationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = load_module()
        cls.catalog = json.loads((ROOT / "exercise-catalog.json").read_bytes())["records"]
        cls.manifest = json.loads((ROOT / "source_manifest.json").read_bytes())
        cls.by_id = {record["id"]: record for record in cls.catalog}

    # ---------- the upstream field is not trusted ----------

    def test_upstream_muscle_group_is_recorded_as_a_secondary_echo(self):
        """Every upstream record's muscle_group equals secondary_muscles[0] verbatim."""
        stats = self.manifest["muscle_normalisation"]
        self.assertEqual(stats["muscle_group_is_secondary_echo_count"], stats["muscle_group_is_secondary_echo_of"])
        self.assertEqual(1324, stats["muscle_group_is_secondary_echo_of"])
        self.assertEqual(["muscle_group"], stats["source_fields_not_used"])

    def test_catalog_records_document_that_muscle_group_was_not_used(self):
        for record in self.catalog:
            muscles = record["muscles"]
            self.assertFalse(muscles["muscle_group_used"], record["id"])
            self.assertEqual(record["muscle_group"], muscles["upstream_muscle_group"], record["id"])
            # the raw upstream field is still preserved verbatim for auditability
            self.assertIn("muscle_group", record)

    def test_lateral_raise_has_shoulders_as_primary_not_traps(self):
        """The regression this whole change exists for: muscle_group said 'traps'."""
        record = self.by_id["0334"]
        self.assertEqual("dumbbell lateral raise", record["name"])
        self.assertEqual("delts", record["target"])
        self.assertEqual("traps", record["muscle_group"])
        self.assertEqual(["shoulders"], record["muscles"]["primary"])
        self.assertEqual(["trapezius"], record["muscles"]["secondary"])
        self.assertEqual(1.0, record["muscles"]["weights"]["shoulders"])
        self.assertEqual(self.module.SECONDARY_WEIGHT, record["muscles"]["weights"]["trapezius"])

    def test_potty_squat_primary_follows_target_not_the_echo_field(self):
        record = self.by_id["3119"]
        self.assertEqual("abs", record["target"])
        self.assertEqual("quadriceps", record["muscle_group"])
        self.assertEqual(["abs"], record["muscles"]["primary"])
        self.assertIn("quadriceps", record["muscles"]["secondary"])

    # ---------- weights are well formed ----------

    def test_every_catalog_record_has_drawable_muscle_weights(self):
        empty = self.manifest["muscle_normalisation"]["catalog_records_without_drawable_muscle"]
        self.assertEqual([], empty)
        for record in self.catalog:
            weights = record["muscles"]["weights"]
            self.assertTrue(weights, record["id"])
            for slug, weight in weights.items():
                self.assertIn(slug, DRAWABLE, record["id"])
                self.assertGreater(weight, 0.0, record["id"])
                self.assertLessEqual(weight, 1.0, record["id"])

    def test_primary_and_secondary_partition_the_weights(self):
        for record in self.catalog:
            muscles = record["muscles"]
            self.assertEqual(
                set(muscles["weights"]),
                set(muscles["primary"]) | set(muscles["secondary"]),
                record["id"],
            )
            self.assertFalse(set(muscles["primary"]) & set(muscles["secondary"]), record["id"])
            for slug in muscles["primary"]:
                self.assertEqual(1.0, muscles["weights"][slug], record["id"])

    def test_weights_are_ordered_head_to_toe(self):
        order = self.manifest["muscle_normalisation"]["drawable_muscles"]
        for record in self.catalog:
            positions = [order.index(slug) for slug in record["muscles"]["weights"]]
            self.assertEqual(sorted(positions), positions, record["id"])

    # ---------- the alias table itself ----------

    def test_alias_table_collapses_known_spelling_variants(self):
        canonical = self.module.canonical_muscle
        for spelling in ("delts", "deltoids", "shoulders", "rear deltoids", "rotator cuff"):
            self.assertEqual("shoulders", canonical(spelling), spelling)
        for spelling in ("quads", "quadriceps", "QUADRICEPS"):
            self.assertEqual("quadriceps", canonical(spelling), spelling)
        for spelling in ("lats", "latissimus dorsi", "upper back", "rhomboids", "back"):
            self.assertEqual("upper-back", canonical(spelling), spelling)

    def test_undrawable_and_unknown_names_map_to_none(self):
        canonical = self.module.canonical_muscle
        for name in ("cardiovascular system", "ankles", "hands", "feet", "sternocleidomastoid",
                     "ankle stabilizers", "forehead", "", None, "   "):
            self.assertIsNone(canonical(name), name)

    def test_every_drawable_slug_maps_to_itself(self):
        for slug in DRAWABLE:
            self.assertEqual(slug, self.module.canonical_muscle(slug))
        self.assertEqual(DRAWABLE, set(self.module.DRAWABLE))

    def test_body_part_fallback_weights_sum_to_one(self):
        for body_part, weights in self.module.BODY_PART_WEIGHTS.items():
            if not weights:
                continue
            self.assertAlmostEqual(1.0, sum(weights.values()), places=9, msg=body_part)
            self.assertTrue(set(weights).issubset(DRAWABLE), body_part)

    def test_body_part_fallback_only_applies_when_no_muscle_is_recognisable(self):
        # real muscles present -> body part ignored
        weights, _ = self.module.muscle_weights("abs", [], "upper legs")
        self.assertEqual({"abs": 1.0}, weights)
        # nothing recognisable -> body part used
        weights, dropped = self.module.muscle_weights("forehead", ["eyebrows"], "chest")
        self.assertEqual({"chest": 1.0}, weights)
        self.assertEqual(["forehead", "eyebrows"], dropped)

    def test_duplicate_target_and_secondary_keeps_the_higher_weight(self):
        weights, _ = self.module.muscle_weights("lats", ["lats", "biceps"], None)
        self.assertEqual(1.0, weights["upper-back"])
        self.assertEqual(self.module.SECONDARY_WEIGHT, weights["biceps"])
        self.assertEqual(2, len(weights))


if __name__ == "__main__":
    unittest.main()
