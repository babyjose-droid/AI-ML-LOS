import numpy as np
from fastapi.testclient import TestClient

from app import train
from app.main import app


def book(n=3000, seed=1):
    rng = np.random.default_rng(seed)
    rows = []
    for _ in range(n):
        x = {"bounces": float(rng.poisson(0.3)), "stability": float(rng.uniform(0.4, 1)), "bureauScoreGap": float(rng.normal(0, 0.6)),
             "noise": float(rng.normal())}
        z = -3 + 0.8 * x["bounces"] - 2.0 * (x["stability"] - 0.7) + 1.1 * x["bureauScoreGap"]
        p = 1 / (1 + np.exp(-z))
        rows.append({"features": x, "bad": bool(rng.random() < p), "championPd": float(1 / (1 + np.exp(-(-3 + 0.5 * x["bounces"]))))})
    return rows


def test_recovers_the_true_signs_and_beats_a_weaker_champion():
    r = train.train(book(), ["bounces", "stability", "bureauScoreGap", "noise"])
    w = {t["feature"]: t["weight"] for t in r["terms"]}
    assert w["bounces"] > 0.4 and w["stability"] < -0.8 and w["bureauScoreGap"] > 0.6
    assert abs(w["noise"]) < 0.2
    assert r["holdout"]["auc"] > 0.65
    assert r["aucGain"] > 0.05
    assert not r["warnings"]
    assert r["holdoutRows"] + r["trainRows"] == 3000
    assert len(r["deciles"]) == 10
    # calibration: average PD close to the observed bad rate
    assert abs(r["holdout"]["avgPd"] - r["holdout"]["badRate"]) < 0.03


def test_metrics():
    y = np.array([0, 0, 1, 1])
    assert train.auc(y, np.array([0.1, 0.2, 0.8, 0.9])) == 1.0
    assert train.auc(y, np.array([0.5, 0.5, 0.5, 0.5])) == 0.5
    assert train.ks(y, np.array([0.1, 0.2, 0.8, 0.9])) == 1.0


def test_unexpected_sign_is_flagged():
    rows = book()
    for r in rows:
        r["features"]["vintage"] = 10.0 if r["bad"] else 2.0  # older businesses defaulting more: against expectation
    r = train.train(rows, ["bounces", "vintage"])
    assert any("vintage" in w for w in r["warnings"])


def test_api_and_too_little_data():
    c = TestClient(app)
    assert c.post("/v1/models/train", json={"features": ["bounces"], "rows": book(100)}).status_code == 422
    r = c.post("/v1/models/train", json={"features": ["bounces", "stability", "bureauScoreGap"], "rows": book(1000)})
    assert r.status_code == 200 and r.json()["holdout"]["auc"] > 0.55
