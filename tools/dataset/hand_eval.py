"""手机同口径手牌评测：对每局开局段按手机 50ms(开局门) + 250ms(手牌采样/识别) 节拍回放整条手牌链路。

链路对齐 CaptureProbeService.kt：
1. 50ms 节拍：牌桌可见 + OUTSIDE 区三态/指纹/圆标 -> MidGameOpenDetector + OpeningPlayGate（只认外家出牌圆标）
2. 250ms 节拍：手牌 12x8 亮度指纹防抖（change <= 1.5%, stableHandSamples >= 3）
3. 250ms 节拍：HandCardPositionDetector（张数）+ ONNX 15类检测（870x870->320x320，不跑外家王纠偏）
4. HandFrameMerger（多帧牌位合并）+ HandPositionVoter（张数众数）+ HandAutoLock（连续 4 次稳定合格且非真子集）
5. 开局门打开时刻调用 handAutoLock.lock()，锁定失败或未开局门均记录原因与诊断信息。

输出：
- D:/CardCounterData/w13/hand_eval_v0/obs/<gameId>.jsonl（带 _meta.vision_rule_version）
- D:/CardCounterData/w13/hand_eval_v0/<gameId>.json
- D:/CardCounterData/w13/hand_eval_v0/summary.json
- D:/CardCounterData/w13/hand_eval_v0/summary.md

用法：
    python tools/dataset/hand_eval.py [--games g102658-1,g143651-1] [--split dev|tuning|clip] [--from-obs]
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dscommon as ds  # noqa: E402
import hand_vision as hv  # noqa: E402
import onnx_detect as od  # noqa: E402
import phone_vision as pv  # noqa: E402

MODEL_PATH = ds.REPO / "app" / "src" / "main" / "assets" / "poker_rank15_yolo11n_320.onnx"
OUT_DIR = ds.DATA_ROOT / "w13" / "hand_eval_v0"


def parse_self_hand(text: str) -> dict[str, int]:
    counts = hv.zero_counts()
    if not text.strip():
        return counts
    for part in text.split(";"):
        rank, qty = part.strip().split(":")
        counts[rank.strip()] = int(qty.strip())
    return counts


def rank_diff(truth: dict[str, int], pred: dict[str, int] | None) -> dict[str, dict[str, int]]:
    if pred is None:
        return {}
    diffs: dict[str, dict[str, int]] = {}
    for r in ds.RANK_ORDER:
        t_v = truth.get(r, 0)
        p_v = pred.get(r, 0)
        if t_v != p_v:
            diffs[r] = {"truth": t_v, "pred": p_v, "delta": p_v - t_v}
    return diffs


def classify_failure(
    game: dict,
    truth_counts: dict[str, int],
    truth_total: int | None,
    gate_opened: bool,
    mid_skipped: bool,
    locked_cards: dict[str, int] | None,
    locked_pos: int | None,
    end_qualified: tuple[dict[str, int], int] | None,
    probes: list[dict],
    last_reading: dict | None,
) -> tuple[str, str]:
    """对每局状态与错误做初步归因，返回 (category, detail)。"""
    if truth_total is None or hv.counts_total(truth_counts) != truth_total:
        return "NO_TRUTH", "真值未标全或存在 uncertain"

    if mid_skipped:
        return "MID_GAME_SKIPPED", "开局段触发 MidGameOpenDetector 跳过"

    if not gate_opened:
        if end_qualified is None:
            if not probes:
                return "NO_PLAY_PHASE_NOT_STABLE", "片段不含出牌阶段（开局门未开），未进入稳定探测（桌不可见或稳定帧不足）"
            return _diagnose_unqualified(
                truth_counts,
                truth_total,
                probes,
                last_reading,
                prefix="片段不含出牌阶段（开局门未开），未形成拟锁定: ",
                no_play_phase=True,
            )
        q_cards, q_pos = end_qualified
        if q_cards == truth_counts and q_pos == truth_total:
            return "NO_PLAY_PHASE_EXACT", "片段不含出牌阶段（开局门未开），片尾拟锁定完全正确"
        return _diagnose_mismatch(truth_counts, truth_total, q_cards, q_pos, probes, prefix="片段不含出牌阶段（开局门未开），片尾拟锁定有误: ")

    if locked_cards is None:
        if not probes:
            return "NOT_STABLE", "开局门打开前手牌未进入稳定探测（稳定帧 < 3 或桌被遮挡）"
        return _diagnose_unqualified(truth_counts, truth_total, probes, last_reading, prefix="锁定失败: ")

    if locked_cards == truth_counts and locked_pos == truth_total:
        return "EXACT", "锁定且 15 点数及总张数全对"

    return _diagnose_mismatch(truth_counts, truth_total, locked_cards, locked_pos, probes)


def _diagnose_unqualified(
    truth_counts: dict[str, int],
    truth_total: int,
    probes: list[dict],
    last_reading: dict | None,
    prefix: str = "",
    no_play_phase: bool = False,
) -> tuple[str, str]:
    voted_vals = [p["votedPosition"] for p in probes if p["votedPosition"] is not None]
    merged_totals = [p["mergedTotal"] for p in probes if p["mergedTotal"] is not None]
    streaks = [p["streak"] for p in probes]
    cat_prefix = "NO_PLAY_PHASE_" if no_play_phase else ""

    if not voted_vals:
        return f"{cat_prefix}POS_COUNT_FAILED", f"{prefix}张数检测全部为 null（探测 {len(probes)} 次，rows={[p['posRows'] for p in probes[-3:]]}）"

    last_p = probes[-1]
    last_voted = voted_vals[-1]
    last_merged = merged_totals[-1] if merged_totals else None
    if last_voted == last_merged and last_p.get("structureValid") is False:
        return (
            f"{cat_prefix}GUARD_BLOCKED",
            f"{prefix}总张数={last_merged} 凑平，但独立防线拦住（posRows={last_p['posRows']}, votedRows={last_p.get('votedRows')}）",
        )
    if last_voted != truth_total and last_merged == truth_total:
        return f"{cat_prefix}POS_COUNT_ERROR", f"{prefix}模型张数={last_merged} 正确，但张数检测={last_voted}!=真值{truth_total}"
    if last_voted == truth_total and last_merged != truth_total:
        return f"{cat_prefix}MODEL_ERROR", f"{prefix}张数检测={last_voted} 正确，但模型合并总数={last_merged}!=真值{truth_total}"
    if last_voted != truth_total and last_merged != truth_total:
        return f"{cat_prefix}BOTH_OR_ANIMATION", f"{prefix}张数检测={last_voted}, 模型合并={last_merged}, 真值={truth_total}"

    qual_streaks = [
        p["streak"] for p in probes
        if p["votedPosition"] == p["mergedTotal"] and p["mergedTotal"] is not None and p.get("structureValid", True)
    ]
    max_q_streak = max(qual_streaks, default=0)
    return f"{cat_prefix}NOT_STABLE", f"{prefix}连续稳定次数不足 {hv.HAND_LOCK_STABLE_FRAMES}（max_qualified_streak={max_q_streak}, max_streak={max(streaks, default=0)}）"


def _diagnose_mismatch(
    truth_counts: dict[str, int],
    truth_total: int,
    pred_counts: dict[str, int],
    pred_pos: int | None,
    probes: list[dict],
    prefix: str = "",
) -> tuple[str, str]:
    diffs = rank_diff(truth_counts, pred_counts)
    diff_str = ", ".join(f"{r}:{v['truth']}->{v['pred']}" for r, v in diffs.items())
    pred_total = hv.counts_total(pred_counts)
    if pred_pos != truth_total:
        return "POS_AND_MODEL_ERROR", f"{prefix}张数={pred_pos}(真值{truth_total}) 且点数差[{diff_str}]"
    if pred_total == truth_total:
        return "MODEL_MISCLASSIFY", f"{prefix}总张数={pred_total} 对，点数认错: {diff_str}"
    return "MODEL_ERROR", f"{prefix}差异: {diff_str}"


class HandGameRunner:
    """单局手机同口径手牌链路仿真器，可直接吃视频帧或 JSONL 观察记录。"""

    def __init__(
        self,
        game: dict,
        obs_path: Path | None,
        vision_rule_version: str,
        hand_vision_sha: str | None = None,
    ) -> None:
        self.game = game
        self.gid = game["gameId"]
        self.start_ms = int(game["startMs"])
        self.end_ms = int(game["endMs"])

        self.gate_tracker = hv.OpeningGateTracker()
        self.hand_merger = hv.HandFrameMerger()
        self.position_voter = hv.HandPositionVoter()
        self.hand_auto_lock = hv.HandAutoLock()

        self.hand_locked = False
        self.hand_probe_on_table = False
        self.last_hand_sig: np.ndarray | None = None
        self.last_hand_sample_at = 0
        self.stable_hand_samples = 0
        self.hand_scene = 0
        self.last_rank_probe_at = 0
        self.last_roi_sample_at = 0

        self.locked_data: tuple[dict[str, int], int] | None = None
        self.locked_at_ms: int | None = None
        self.probes: list[dict] = []
        self.skipped_zero_box_frames = 0
        self.table_visible_frames = 0
        self.total_frames = 0
        self.last_reading_dict: dict | None = None
        self.self_card_on_table = False

        self.obs_handle = None
        self.obs_tmp_path = None
        self.obs_path = obs_path
        if obs_path is not None:
            obs_path.parent.mkdir(parents=True, exist_ok=True)
            self.obs_tmp_path = obs_path.with_suffix(".jsonl.tmp")
            self.obs_handle = self.obs_tmp_path.open("w", encoding="utf-8", newline="\n")
            self.obs_handle.write(
                json.dumps(
                    {
                        "_meta": True,
                        "vision_rule_version": vision_rule_version,
                        "model_sha256": pv.MODEL_SHA256,
                        "hand_vision_sha256": hand_vision_sha or hand_vision_sha256(),
                    },
                    ensure_ascii=False,
                    separators=(",", ":"),
                )
                + "\n"
            )

    def done(self) -> bool:
        return self.hand_locked or self.gate_tracker.mid_skipped

    def step_video_frame(self, ts_ms: int, bgr: np.ndarray, detector: od.RankDetector) -> None:
        if self.done() or ts_ms < self.start_ms or ts_ms > self.end_ms:
            return
        self.total_frames += 1
        screen = pv.Screen(bgr)

        # 1) 50ms ROI + 开局门采样（CaptureProbeService.captureRoiEvents）
        roi_event: dict | None = None
        table_vis = pv.is_table_visible(screen)
        self.hand_probe_on_table = table_vis
        if table_vis:
            self.table_visible_frames += 1
        else:
            self.stable_hand_samples = 0
            self.last_hand_sig = None

        if not table_vis:
            self.self_card_on_table = False

        if not self.gate_tracker.mid_skipped and (ts_ms - self.last_roi_sample_at >= hv.ROI_SAMPLE_INTERVAL_MS):
            self.last_roi_sample_at = ts_ms
            self.self_card_on_table = table_vis and (
                pv.zone_visibility(screen, pv.SPEC_BY_NAME["SELF"]) == pv.CARD
            )
            opened_now = self.gate_tracker.observe_50ms(screen, ts_ms)
            roi_event = {
                "table_visible": table_vis,
                "mid_skipped": self.gate_tracker.mid_skipped,
                "gate_opened_now": opened_now,
                "gate_opened_zone": self.gate_tracker.opened_zone if opened_now else None,
            }
            if opened_now:
                self._trigger_lock(ts_ms)
                self._write_obs(ts_ms, roi=roi_event, hand=None)
                return

        # 2) 250ms 手牌稳定性指纹采样
        hand_event: dict | None = None
        if not self.hand_locked and self.hand_probe_on_table and (ts_ms - self.last_hand_sample_at >= hv.HAND_SAMPLE_INTERVAL_MS):
            sig = hv.hand_signature(bgr)
            chg = hv.hand_change_percent(self.last_hand_sig, sig)
            still_scene = chg is not None and chg <= hv.STABLE_CHANGE_PERCENT
            self.stable_hand_samples = self.stable_hand_samples + 1 if still_scene else 0
            if not still_scene:
                self.hand_scene += 1
            self.last_hand_sig = sig
            self.last_hand_sample_at = ts_ms
            hand_event = {
                "sig_change": round(chg, 4) if chg is not None else None,
                "still_scene": still_scene,
                "stable_samples": self.stable_hand_samples,
                "scene": self.hand_scene,
            }

        # 3) 250ms 手牌识别探测
        if (
            not self.gate_tracker.mid_skipped
            and hv.should_probe_hand(self.hand_locked, self.hand_probe_on_table, self.stable_hand_samples)
            and (ts_ms - self.last_rank_probe_at >= hv.RANK_PROBE_INTERVAL_MS)
        ):
            self.last_rank_probe_at = ts_ms
            pos_rows, pos_count = hv.detect_hand_positions(bgr)
            dets = hv.detect_hand_boxes(detector, screen)
            self_card_visible = table_vis and self.self_card_on_table
            probe_obs = {
                "scene": self.hand_scene,
                "pos_rows": [pos_rows[0], pos_rows[1]],
                "pos_count": pos_count,
                "self_card_visible": self_card_visible,
                "dets": [
                    {
                        "rank": ds.RANK_ORDER[c],
                        "conf": round(s, 6),
                        "cx": round(cx, 3),
                        "cy": round(cy, 3),
                        "w": round(w, 3),
                        "h": round(h, 3),
                    }
                    for c, s, cx, cy, w, h in dets
                ],
            }
            if hand_event is None:
                hand_event = {}
            hand_event["probe"] = probe_obs
            self._apply_probe(ts_ms, self.hand_scene, pos_rows, pos_count, dets, self_card_visible)

        if roi_event is not None or hand_event is not None:
            self._write_obs(ts_ms, roi=roi_event, hand=hand_event)

    def replay_obs_row(self, row: dict) -> None:
        if self.done():
            return
        ts_ms = int(row["ts_ms"])
        roi = row.get("roi")
        if roi is not None:
            if roi.get("mid_skipped"):
                self.gate_tracker.mid_skipped = True
                return
            if roi.get("gate_opened_now"):
                self.gate_tracker.entered_play_phase = True
                self.gate_tracker.opened_at_ms = ts_ms
                self.gate_tracker.opened_zone = roi.get("gate_opened_zone")
                self._trigger_lock(ts_ms)
                return
        hand = row.get("hand")
        if hand is not None and "probe" in hand:
            p = hand["probe"]
            dets = hv.filter_hand_boxes([
                (ds.RANK_INDEX[d["rank"]], float(d["conf"]), float(d["cx"]), float(d["cy"]), float(d["w"]), float(d["h"]))
                for d in p["dets"]
            ])
            pos_rows = (p["pos_rows"][0], p["pos_rows"][1])
            self._apply_probe(
                ts_ms,
                int(p["scene"]),
                pos_rows,
                p["pos_count"],
                dets,
                bool(p.get("self_card_visible", False)),
            )

    def _apply_probe(
        self,
        ts_ms: int,
        scene: int,
        pos_rows: tuple[int | None, int | None],
        pos_count: int | None,
        dets: list[tuple[int, float, float, float, float, float]],
        self_card_visible: bool = False,
    ) -> None:
        if not dets:
            self.skipped_zero_box_frames += 1
            return
        merge = self.hand_merger.add(scene, dets)
        valid_rows = hv.valid_row_counts(pos_rows)
        voted_rows = self.position_voter.add(scene, valid_rows)
        reading = hv.build_hand_reading(merge.merged, merge.slot_detections, voted_rows)
        voted_pos = reading.position_count
        self.hand_auto_lock.observe(reading, ts_ms=ts_ms, self_card_visible=self_card_visible)
        self.last_reading_dict = {
            "cards": hv.counts_to_str(merge.merged),
            "mergedTotal": hv.counts_total(merge.merged),
            "votedPosition": voted_pos,
            "votedRows": list(voted_rows) if voted_rows else None,
            "structureValid": reading.structure_valid,
        }
        self.probes.append({
            "ts_ms": ts_ms,
            "scene": scene,
            "posRows": list(pos_rows),
            "posCount": pos_count,
            "votedRows": list(voted_rows) if voted_rows else None,
            "votedPosition": voted_pos,
            "structureValid": reading.structure_valid,
            "singleTotal": hv.counts_total(merge.single),
            "mergedTotal": hv.counts_total(merge.merged),
            "mergedStr": hv.counts_to_str(merge.merged),
            "frames": merge.frames,
            "fellBack": merge.fell_back,
            "minConf": round(min(d[1] for d in dets), 4),
            "streak": self.hand_auto_lock.streak,
            "qualifiedNow": self.hand_auto_lock.qualified is not None,
        })

    def _trigger_lock(self, ts_ms: int) -> None:
        self.hand_locked = True
        self.locked_at_ms = ts_ms
        self.locked_data = self.hand_auto_lock.lock()
        self.hand_merger.reset()
        self.position_voter.reset()

    def _write_obs(self, ts_ms: int, roi: dict | None, hand: dict | None) -> None:
        if self.obs_handle is None:
            return
        obj = {"ts_ms": int(ts_ms)}
        if roi is not None:
            obj["roi"] = roi
        if hand is not None:
            obj["hand"] = hand
        self.obs_handle.write(json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n")

    def close(self) -> None:
        if self.obs_handle is not None:
            self.obs_handle.close()
            self.obs_tmp_path.replace(self.obs_path)

    def build_report(self) -> dict:
        truth_str = self.game.get("selfHand") or ""
        truth_counts = parse_self_hand(truth_str)
        truth_total = self.game.get("selfHandCount")

        gate_opened = self.gate_tracker.entered_play_phase
        locked_cards = self.locked_data[0] if self.locked_data is not None else None
        locked_pos = self.locked_data[1] if self.locked_data is not None else None
        end_qualified = self.hand_auto_lock.qualified if not gate_opened else None

        eval_cards = locked_cards if gate_opened else (end_qualified[0] if end_qualified else None)
        eval_pos = locked_pos if gate_opened else (end_qualified[1] if end_qualified else None)

        exact = (
            eval_cards is not None
            and truth_total is not None
            and hv.counts_total(truth_counts) == truth_total
            and eval_cards == truth_counts
            and eval_pos == truth_total
        )
        locked_and_exact = gate_opened and locked_cards is not None and exact

        category, detail = classify_failure(
            self.game,
            truth_counts,
            truth_total,
            gate_opened,
            self.gate_tracker.mid_skipped,
            locked_cards,
            locked_pos,
            end_qualified,
            self.probes,
            self.last_reading_dict,
        )

        # 统计探测期张数检测值分布
        pos_hist: dict[str, int] = {}
        for p in self.probes:
            key = "null" if p["posCount"] is None else str(p["posCount"])
            pos_hist[key] = pos_hist.get(key, 0) + 1

        return {
            "gameId": self.gid,
            "split": self.game["split"],
            "complete": self.game["complete"],
            "startMs": self.start_ms,
            "prePlayEndMs": self.game["prePlayEndMs"],
            "firstPlayMs": self.game["firstPlayMs"],
            "endMs": self.end_ms,
            "truthSelfHand": truth_str,
            "truthCount": truth_total,
            "truthAtMs": self.game.get("selfHandAtMs"),
            "midGameSkipped": self.gate_tracker.mid_skipped,
            "gateOpened": gate_opened,
            "gateOpenedAtMs": self.gate_tracker.opened_at_ms,
            "gateOpenedZone": self.gate_tracker.opened_zone,
            "locked": locked_cards is not None,
            "lockedAtMs": self.locked_at_ms if locked_cards is not None else None,
            "lockedCount": locked_pos,
            "lockedHand": hv.counts_to_str(locked_cards) if locked_cards is not None else None,
            "endQualified": end_qualified is not None,
            "endQualifiedAtMs": self.hand_auto_lock.qualified_at_ms if end_qualified is not None else None,
            "endQualifiedCount": end_qualified[1] if end_qualified is not None else None,
            "endQualifiedHand": hv.counts_to_str(end_qualified[0]) if end_qualified is not None else None,
            "evalExact": exact,
            "lockedAndExact": locked_and_exact,
            "rankDiffs": rank_diff(truth_counts, eval_cards),
            "posCountHistogram": pos_hist,
            "lastProbe": self.probes[-1] if self.probes else None,
            "probeCount": len(self.probes),
            "skippedZeroBoxFrames": self.skipped_zero_box_frames,
            "category": category,
            "detail": detail,
            "probes": self.probes,
        }


def hand_vision_sha256(repo_root: Path = ds.REPO) -> str:
    """计算 tools/dataset/hand_vision.py 文件内容的 SHA-256（换行符先归一为 \\n）。"""
    path = repo_root / "tools" / "dataset" / "hand_vision.py"
    text = path.read_text(encoding="utf-8").replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_obs_checked(
    path: Path,
    expected_version: str,
    expected_hand_vision_sha: str | None = None,
) -> list[dict]:
    """读取观察 JSONL，强校验首行 _meta.vision_rule_version、model_sha256 与 hand_vision_sha256。"""
    if not path.is_file():
        raise SystemExit(f"找不到观察文件: {path}")
    lines = [ln for ln in path.read_text(encoding="utf-8").splitlines() if ln.strip()]
    if not lines:
        raise SystemExit(f"{path}: 观察文件为空，缺少 vision_rule_version")
    hv_sha = expected_hand_vision_sha or hand_vision_sha256()
    first = json.loads(lines[0])
    if (
        not isinstance(first, dict)
        or first.get("_meta") is not True
        or first.get("vision_rule_version") != expected_version
        or first.get("model_sha256") != pv.MODEL_SHA256
        or first.get("hand_vision_sha256") != hv_sha
    ):
        got_v = first.get("vision_rule_version") if isinstance(first, dict) else None
        got_m = first.get("model_sha256") if isinstance(first, dict) else None
        got_hv = first.get("hand_vision_sha256") if isinstance(first, dict) else None
        raise SystemExit(
            f"{path}: 视觉规则、模型或 hand_vision 不一致"
            f"（观察={got_v or '缺失'}/{got_m or '缺失'}/{got_hv or '缺失'}，"
            f"当前={expected_version}/{pv.MODEL_SHA256}/{hv_sha}），请重跑 hand_eval.py（不加 --from-obs）"
        )
    return [json.loads(ln) for ln in lines[1:]]


def run_from_videos(games: list[dict], detector: od.RankDetector, out_dir: Path, version: str) -> list[dict]:
    hv_sha = hand_vision_sha256()
    by_video: dict[str, list[dict]] = {}
    for g in games:
        by_video.setdefault(g["video"], []).append(g)

    runners_by_id: dict[str, HandGameRunner] = {}
    for video, vgames in by_video.items():
        path = ds.SOURCE_BASE / video
        cap = cv2.VideoCapture(str(path))
        if not cap.isOpened():
            raise SystemExit(f"打不开视频: {path}")
        fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        runners = [
            HandGameRunner(g, out_dir / "obs" / f"{g['gameId']}.jsonl", version, hv_sha)
            for g in vgames
        ]
        for r in runners:
            runners_by_id[r.gid] = r
        last_end = max(r.end_ms for r in runners)
        started = time.time()
        index = 0
        while True:
            if all(r.done() for r in runners):
                break
            if not cap.grab():
                break
            t = cap.get(cv2.CAP_PROP_POS_MSEC)
            if index == 0 or t <= 0:
                t = index * 1000.0 / fps
            index += 1
            if t > last_end + 100:
                break
            ts_ms = int(round(t))
            active = [r for r in runners if not r.done() and r.start_ms <= ts_ms <= r.end_ms]
            if not active:
                continue
            ok, frame = cap.retrieve()
            if not ok:
                break
            for r in active:
                r.step_video_frame(ts_ms, frame, detector)
        cap.release()
        for r in runners:
            r.close()
        print(f"视频 {video} 完成（{len(runners)} 局，{time.time() - started:.1f}s）", flush=True)

    return [runners_by_id[g["gameId"]].build_report() for g in games]


def run_from_obs(games: list[dict], out_dir: Path, version: str) -> list[dict]:
    hv_sha = hand_vision_sha256()
    reports = []
    for g in games:
        obs_rows = load_obs_checked(out_dir / "obs" / f"{g['gameId']}.jsonl", version, hv_sha)
        runner = HandGameRunner(g, None, version, hv_sha)
        for row in obs_rows:
            runner.replay_obs_row(row)
        reports.append(runner.build_report())
    return reports


def write_summary(reports: list[dict], out_dir: Path, version: str) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    for rep in reports:
        slim = {k: v for k, v in rep.items() if k != "probes"}
        slim["probesTail"] = rep["probes"][-10:]
        (out_dir / f"{rep['gameId']}.json").write_text(
            json.dumps(slim, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    dev_tuning = [r for r in reports if r["split"] in ("dev", "tuning")]
    clips = [r for r in reports if r["split"] == "clip"]

    def stats_block(items: list[dict]) -> dict:
        n = len(items)
        if n == 0:
            return {"total": 0}
        with_gate = [r for r in items if r["gateOpened"]]
        no_gate = [r for r in items if not r["gateOpened"]]
        opened = len(with_gate)
        locked = sum(1 for r in with_gate if r["locked"])
        locked_exact = sum(1 for r in with_gate if r["lockedAndExact"])
        wrong_lock = locked - locked_exact
        no_gate_qualified = sum(1 for r in no_gate if r["endQualified"])
        no_gate_exact = sum(1 for r in no_gate if r["evalExact"])
        return {
            "total": n,
            "gateOpened": opened,
            "locked": locked,
            "lockedAndExact": locked_exact,
            "wrongLock": wrong_lock,
            "noPlayPhase": len(no_gate),
            "noPlayPhaseQualified": no_gate_qualified,
            "noPlayPhaseExact": no_gate_exact,
        }

    cat_counts: dict[str, int] = {}
    for r in reports:
        cat_counts[r["category"]] = cat_counts.get(r["category"], 0) + 1

    summary_json = {
        "vision_rule_version": version,
        "all": stats_block(reports),
        "dev_tuning": stats_block(dev_tuning),
        "clip": stats_block(clips),
        "categories": cat_counts,
        "games": [{k: v for k, v in r.items() if k != "probes"} for r in reports],
    }
    (out_dir / "summary.json").write_text(
        json.dumps(summary_json, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    lines: list[str] = []
    lines.append("# 手牌识别手机同口径评测总表")
    lines.append("")
    lines.append(f"- 视觉规则版本：`{version}`")
    lines.append(f"- 评测范围：dev ({sum(1 for r in reports if r['split']=='dev')}) + tuning ({sum(1 for r in reports if r['split']=='tuning')}) + clip ({len(clips)}) = 共 {len(reports)} 局（blind 局已硬排除）")
    lines.append("")
    lines.append("## 汇总指标（含出牌阶段 vs 片段不含出牌阶段单列）")
    lines.append("| 子集 | 总局数 | 含出牌阶段 | 真锁定成功(锁定率) | 真锁定全对 | 错锁 | 片段不含出牌阶段 | 拟锁定合格 / 全对 |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for label, blk in (
        ("dev + tuning", summary_json["dev_tuning"]),
        ("clip", summary_json["clip"]),
        ("全部 33 局", summary_json["all"]),
    ):
        n = blk["total"]
        if not n:
            continue
        go = blk["gateOpened"]
        lock_pct = f"{blk['locked']*100/go:.1f}%" if go else "-"
        exact_pct = f"{blk['lockedAndExact']*100/go:.1f}%" if go else "-"
        np_n = blk["noPlayPhase"]
        np_pct = f"{blk['noPlayPhaseExact']*100/np_n:.1f}%" if np_n else "-"
        lines.append(
            f"| {label} | {n} | {go} | {blk['locked']}/{go} ({lock_pct}) | "
            f"{blk['lockedAndExact']}/{go} ({exact_pct}) | {blk['wrongLock']} | "
            f"{np_n} | {blk['noPlayPhaseQualified']}/{np_n} / {blk['noPlayPhaseExact']}/{np_n} ({np_pct}) |"
        )
    lines.append("")
    all_b = summary_json["all"]
    if all_b["gateOpened"]:
        go = all_b["gateOpened"]
        lk = all_b["locked"]
        le = all_b["lockedAndExact"]
        wl = all_b["wrongLock"]
        lines.append(f"- **含出牌阶段（开局门打开）的 {go} 局**：锁定率 {lk}/{go} ({lk*100/go:.1f}%)，锁定全对 {le}/{go} ({le*100/go:.1f}%)，错锁 {wl}。")
    if all_b["noPlayPhase"]:
        np_n = all_b["noPlayPhase"]
        np_e = all_b["noPlayPhaseExact"]
        lines.append(f"- **片段不含出牌阶段（开局门未开）的 {np_n} 局**（不计入锁定率，仅核对片尾拟锁定结果）：拟锁定全对 {np_e}/{np_n} ({np_e*100/np_n:.1f}%)。")
    lines.append("")
    lines.append("## 结果分类统计")
    lines.append("| 类别 | 局数 |")
    lines.append("|---|---|")
    for k, v in sorted(cat_counts.items(), key=lambda kv: (-kv[1], kv[0])):
        lines.append(f"| {k} | {v} |")
    lines.append("")
    lines.append("## 每局明细")
    lines.append("| gameId | split | 开局门(ms/zone) | 锁定 | 锁定/拟锁定时刻 | 真值张数 | 识别张数(pos/rank) | 结果 | 逐点数差异 | 分类/说明 |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|")
    for r in reports:
        gate_str = f"{r['gateOpenedAtMs']} ({r['gateOpenedZone']})" if r["gateOpened"] else ("MID_SKIP" if r["midGameSkipped"] else "未开")
        if r["gateOpened"]:
            lock_str = "是" if r["locked"] else "否"
            t_str = str(r["lockedAtMs"]) if r["locked"] else "-"
            pred_pos = r["lockedCount"]
            pred_rank = sum(parse_self_hand(r["lockedHand"]).values()) if r["lockedHand"] else None
        else:
            lock_str = "拟" if r["endQualified"] else "否"
            t_str = str(r["endQualifiedAtMs"]) if r["endQualified"] else "-"
            pred_pos = r["endQualifiedCount"]
            pred_rank = sum(parse_self_hand(r["endQualifiedHand"]).values()) if r["endQualifiedHand"] else None
        ok_str = "全对" if r["evalExact"] else "错/未锁"
        diff_str = ", ".join(f"{k}:{v['truth']}->{v['pred']}" for k, v in r["rankDiffs"].items()) or "-"
        lines.append(
            f"| {r['gameId']} | {r['split']} | {gate_str} | {lock_str} | {t_str} | "
            f"{r['truthCount']} | {pred_pos}/{pred_rank} | {ok_str} | {diff_str} | {r['category']}: {r['detail']} |"
        )

    (out_dir / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--games", help="逗号分隔的 gameId，缺省=全部 dev+tuning+clip")
    parser.add_argument("--split", choices=["dev", "tuning", "clip"], help="仅评测指定 split")
    parser.add_argument("--out-dir", type=Path, default=OUT_DIR)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--from-obs", action="store_true", help="直接从已生成的 obs JSONL 回放（强校验版本）")
    args = parser.parse_args()

    version = pv.verify_vision_rule_version(ds.REPO)
    all_games = ds.load_games()["games"]
    if args.games:
        wanted = args.games.split(",")
        by_id = {g["gameId"]: g for g in all_games}
        missing = [gid for gid in wanted if gid not in by_id]
        if missing:
            raise SystemExit(f"games.json 中不存在: {','.join(missing)}")
        selected = [by_id[gid] for gid in wanted]
    else:
        selected = [g for g in all_games if g["split"] in hv.HAND_ALLOWED_SPLITS]
    if args.split:
        selected = [g for g in selected if g["split"] == args.split]
    for g in selected:
        hv.assert_hand_allowed(g)
    if not selected:
        raise SystemExit("没有可评测的牌局")

    if args.from_obs:
        reports = run_from_obs(selected, args.out_dir, version)
    else:
        detector = od.RankDetector(MODEL_PATH, args.threads)
        reports = run_from_videos(selected, detector, args.out_dir, version)

    write_summary(reports, args.out_dir, version)
    print(f"评测完成：{args.out_dir / 'summary.md'}", flush=True)


if __name__ == "__main__":
    main()
