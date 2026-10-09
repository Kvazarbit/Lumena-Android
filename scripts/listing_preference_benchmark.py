#!/usr/bin/env python3
"""PC-only offline evaluation of owner-labelled Lumena listing preferences.

This intentionally does NOT update Android weights, the Constitution, or any
runtime setting. Source data stays in a user-selected local JSONL file.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

DRIVER_CUES = ("kierow", "воді", "води", "водит", "driver")
LEARNING_RATE = 0.2
EPOCHS = 60
MIN_PROMOTION_ITEMS = 50
MIN_PROMOTION_GROUPS = 30


def normalize(text: str) -> str:
    folded = unicodedata.normalize("NFKD", text.casefold())
    folded = "".join(ch for ch in folded if unicodedata.category(ch) != "Mn")
    return " ".join(re.findall(r"[\w]+", folded, flags=re.UNICODE))


def is_driver(text: str) -> bool:
    lower = normalize(text)
    return any(token.startswith(cue) for token in lower.split() for cue in DRIVER_CUES)


def features(title: str, description: str) -> dict[str, float]:
    """Bounded generic lexical representation; no raw text in the report."""
    tokens = normalize(title + " " + description).split()
    result = {"bias": 1.0, "driver": float(is_driver(title + " " + description))}
    for token in set(tokens):
        if len(token) >= 3 and len(token) <= 24:
            result["tok:" + token[:8]] = 1.0
    return result


def safe_label(value: Any) -> int:
    # Never use bool(value): bool("👎") would incorrectly become a positive label.
    if value in (1, "+1", "👍", "up", "useful", True):
        return 1
    if value in (-1, "-1", "👎", "down", "not_useful", False):
        return 0
    raise ValueError("feedback must be explicit 👍/👎 (+1/-1), never inferred")


def parse_lines(raw: str) -> list[dict[str, Any]]:
    items = []
    for index, line in enumerate(raw.splitlines(), 1):
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"line {index}: invalid JSON") from exc
        if not isinstance(obj, dict):
            raise ValueError(f"line {index}: expected JSON object")
        title = str(obj.get("title") or "").strip()
        description = str(obj.get("description") or obj.get("text") or "").strip()
        if not title or not description:
            raise ValueError(f"line {index}: title and description required")
        if "feedback" not in obj:
            raise ValueError(f"line {index}: feedback missing")
        label = safe_label(obj["feedback"])
        company = str(obj.get("company") or "")
        # A stable listing id or explicit group id makes republished copies
        # inseparable across train/test folds. Without it, conservatively
        # group same normalized title+company (not search-query or date).
        group = (str(obj.get("group_id") or obj.get("offer_id") or obj.get("listing_id") or "").strip()
                 or normalize(title + "|" + company))
        if not group:
            raise ValueError(f"line {index}: missing group identifier")
        items.append({"title": title, "description": description, "label": label,
                      "group": group, "features": features(title, description)})
    if not items:
        raise ValueError("dataset is empty; no synthetic labels may be assumed")
    return items


def sigmoid(value: float) -> float:
    z = max(-30.0, min(30.0, value))
    return 1.0 / (1.0 + math.exp(-z))


def train(rows: list[dict[str, Any]]) -> dict[str, float]:
    """Fixed-hyperparameter logistic learner; deterministic, train-only."""
    weights: dict[str, float] = {}
    if not rows:
        return weights
    for _ in range(EPOCHS):
        for row in rows:
            x = row["features"]
            pred = sigmoid(sum(weights.get(k, 0.0) * v for k, v in x.items()))
            err = row["label"] - pred
            for k, value in x.items():
                weights[k] = weights.get(k, 0.0) * (1.0 - 0.001) + LEARNING_RATE * err * value
    return weights


def predict(row: dict[str, Any], weights: dict[str, float]) -> float:
    return sigmoid(sum(weights.get(k, 0.0) * v for k, v in row["features"].items()))


def proxy_prior(row: dict[str, Any]) -> float:
    # Illustrative driver-skill-only proxy, NOT the full Android model:
    # Kotlin prior bias=-1.3 and one skill group contributes 2 * 0.5.
    return sigmoid(-1.3 + 1.0 * row["features"]["driver"])


def score(rows: list[dict[str, Any]], probabilities: list[float]) -> dict[str, Any]:
    assert len(rows) == len(probabilities)
    n = len(rows)
    answers = [r["label"] for r in rows]
    guessed = [int(p >= 0.5) for p in probabilities]
    pos = sum(answers)
    neg = n - pos
    true_positive = sum(y == 1 and p == 1 for y, p in zip(answers, guessed))
    true_negative = sum(y == 0 and p == 0 for y, p in zip(answers, guessed))
    return {
        "n": n, "positives": pos, "negatives": neg,
        "accuracy": round((true_positive + true_negative) / n, 5),
        "balanced_accuracy": (round((true_positive / pos + true_negative / neg) / 2, 5)
                              if pos and neg else None),
        "log_loss": round(-sum(y * math.log(max(1e-7, min(1 - 1e-7, p))) +
                                    (1 - y) * math.log(max(1e-7, min(1 - 1e-7, 1 - p)))
                                    for y, p in zip(answers, probabilities)) / n, 5),
    }


def evaluate(rows: list[dict[str, Any]]) -> dict[str, Any]:
    groups: dict[str, list[int]] = defaultdict(list)
    for i, row in enumerate(rows):
        groups[row["group"]].append(i)
    learned = [0.5] * len(rows)
    for holdout in groups.values():
        excluded = set(holdout)
        training = [row for i, row in enumerate(rows) if i not in excluded]
        # No held-out record can ever update the model for its own fold.
        model = train(training)
        for i in holdout:
            learned[i] = predict(rows[i], model)
    driver_rows = [r for r in rows if r["features"]["driver"]]
    all_groups = len(groups)
    positives = sum(r["label"] for r in rows)
    comparable = positives > 0 and positives < len(rows)
    return {
        "status": "EXPLORATORY_NOT_PROMOTION_PROOF",
        "n": len(rows), "independent_groups": all_groups,
        "labels": {"thumbs_up": positives, "thumbs_down": len(rows) - positives},
        "driver": {
            "n": len(driver_rows),
            "thumbs_up": sum(r["label"] for r in driver_rows),
            "thumbs_down": sum(not r["label"] for r in driver_rows),
        },
        "protocol": "leave-one-group-out; fixed hyperparameters; no holdout leakage",
        "models": {
            "always_reject": score(rows, [0.01] * len(rows)),
            "driver_prior_proxy_not_android_full_model": score(rows, [proxy_prior(r) for r in rows]),
            "contextual_lexical_logistic": score(rows, learned),
        },
        "comparable_class_support": comparable,
        "promotion_allowed": False,  # explicit owner review + larger holdout are still required
        "promotion_blockers": (["labels absent from one class"] if not comparable else []) + (
            [f"need >={MIN_PROMOTION_ITEMS} independently labeled listings"]
            if len(rows) < MIN_PROMOTION_ITEMS else []
        ) + (
            [f"need >={MIN_PROMOTION_GROUPS} independent groups"]
            if all_groups < MIN_PROMOTION_GROUPS else []
        ) + ["independent holdout + owner authorization not provided"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", required=True, type=Path, help="private owner-labeled JSONL")
    parser.add_argument("--report", type=Path, default=None, help="write aggregate JSON (no listing text)")
    args = parser.parse_args()
    if not args.data.is_file():
        parser.error("DATA_MISSING: provide actual owner-labeled JSONL; do not invent 24 ratings")
    rows = parse_lines(args.data.read_text(encoding="utf-8"))
    report = evaluate(rows)
    report["dataset_sha256"] = hashlib.sha256(args.data.read_bytes()).hexdigest()
    serialized = json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2)
    if args.report is not None:
        args.report.write_text(serialized + "\n", encoding="utf-8")
    print(serialized)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
