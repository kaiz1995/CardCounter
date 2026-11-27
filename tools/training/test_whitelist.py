"""Proof that blind/tuning recordings cannot be opened by training scripts.

Run: python tools/training/test_whitelist.py
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import common  # noqa: E402
import check_isolation  # noqa: E402


class WhitelistGateTest(unittest.TestCase):
    def test_allowed_nine_games_clip(self):
        path = common.ensure_allowed(common.NINE_GAMES / "SVID_20260728_145252_1.mp4")
        self.assertEqual(path.name, "SVID_20260728_145252_1.mp4")

    def test_allowed_dev_source(self):
        path = common.ensure_allowed(common.SOURCE / "SVID_20260810_102658_1.mp4")
        self.assertEqual(path.parent.name, "source")

    def test_blind_202234_denied(self):
        self.assertDenied(common.SOURCE / "SVID_20260815_202234_1.mp4")

    def test_blind_210429_denied(self):
        self.assertDenied(common.SOURCE / "SVID_20260815_210429_1.mp4")

    def test_tuning_164333_denied(self):
        self.assertDenied(common.SOURCE / "SVID_20260813_164333_1.mp4")

    def test_unknown_video_denied(self):
        self.assertDenied(common.SOURCE / "SVID_20990101_000000_1.mp4")

    def test_same_name_outside_whitelisted_dir_denied(self):
        # A copy of an allowed clip name outside 9局/ must not slip through.
        self.assertDenied(common.DATA_ROOT / "_scratch" / "SVID_20260728_145252_1.mp4")

    def test_any_dir_named_source_denied(self):
        # Folder name alone is not enough: the full parent directory must match.
        self.assertDenied(Path(r"D:\CardCounterData\source\SVID_20260810_102658_1.mp4"))
        self.assertDenied(Path(r"C:\tmp\source\SVID_20260727_201930_1.mp4"))
        self.assertDenied(common.DATA_ROOT / "source" / "SVID_20260811_075639_1.mp4")

    def test_any_dir_named_nine_games_denied(self):
        self.assertDenied(Path(r"C:\tmp\9局\SVID_20260728_145252_1.mp4"))
        self.assertDenied(Path(r"D:\CardCounterData\9局\SVID_20260728_145637_1.mp4"))
        self.assertDenied(common.DATA_ROOT / "9局" / "SVID_20260728_145704_1.mp4")

    def test_nested_or_sibling_dir_denied(self):
        self.assertDenied(common.SOURCE / "copy" / "SVID_20260810_102658_1.mp4")
        self.assertDenied(common.NINE_GAMES / "source" / "SVID_20260728_145252_1.mp4")
        self.assertDenied(common.SOURCE / "9局" / "SVID_20260728_145252_1.mp4")
        # whitelisted 9局 name inside source/ and vice versa
        self.assertDenied(common.SOURCE / "SVID_20260728_145252_1.mp4")
        self.assertDenied(common.NINE_GAMES / "SVID_20260810_102658_1.mp4")

    def test_real_dir_matches_case_insensitively(self):
        # normcase: a differently cased spelling of the real directory still resolves to it.
        lowered = Path(str(common.SOURCE).lower()) / "svid_20260810_102658_1.mp4"
        self.assertEqual(common.ensure_allowed(lowered).name.lower(), "svid_20260810_102658_1.mp4")

    def test_play_geometry_videos_are_allowed_and_disjoint_from_eval(self):
        for name in common.PLAY_GEOMETRY_VIDEOS:
            common.ensure_allowed(common.SOURCE / name)
        self.assertFalse(set(common.PLAY_GEOMETRY_VIDEOS) & set(common.EMPTY_ZONE_EVAL_VIDEOS))

    def test_open_video_denied_before_capture(self):
        with self.assertRaises(common.NotWhitelisted):
            common.open_video(common.SOURCE / "SVID_20260815_202234_1.mp4")
        with self.assertRaises(common.NotWhitelisted):
            common.open_video(common.SOURCE / "SVID_20260813_164333_1.mp4")

    def test_grab_frames_denied(self):
        with self.assertRaises(common.NotWhitelisted):
            common.grab_frames(common.SOURCE / "SVID_20260815_210429_1.mp4", [1.0])

    def test_empty_zone_eval_never_feeds_training(self):
        overlap = set(common.EMPTY_ZONE_EVAL_VIDEOS) & set(common.NEGATIVE_SOURCE_VIDEOS)
        self.assertFalse(overlap, f"empty play-zone eval must not use negative-source recordings: {overlap}")
        for name in common.EMPTY_ZONE_EVAL_VIDEOS:
            common.ensure_allowed(common.SOURCE / name)

    def test_negative_sources_exclude_all_dev_tuning_blind_eval(self):
        forbidden = check_isolation.build_forbidden_map()
        for token in ("102658", "143651", "075639", "164333", "202234", "210429"):
            self.assertIn(token, forbidden)
        for name in common.NEGATIVE_SOURCE_VIDEOS:
            src = check_isolation.vid(name)
            self.assertNotIn(src, forbidden, f"NEGATIVE_SOURCE_VIDEOS must not include {name} ({forbidden.get(src)})")

    def test_train_split_excludes_eval_clips(self):
        names = [path.name for path in common.nine_games(train_only=True)]
        for clip in common.EVAL_CLIPS:
            self.assertNotIn(clip, names)
        self.assertEqual(len(names), len(common.ALLOWED_NINE_GAMES) - len(common.EVAL_CLIPS))

    def assertDenied(self, path: Path) -> None:
        with self.assertRaises(common.NotWhitelisted):
            common.ensure_allowed(path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
