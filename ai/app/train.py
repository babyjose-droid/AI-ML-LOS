"""
Trains a probability-of-default (PD) model: L2-regularised logistic regression, fitted by iteratively re-weighted
least squares on standardised features. Pure numpy, so it is small, deterministic and auditable.

Input rows carry the engine's features, the 12-month outcome (bad = 1) and the PD of the model currently in use
(championPd), so the new model is compared with the current one on the same hold-out loans.

The result is expressed so the engine can explain every decision: z = intercept + sum(weight * (x - mean)),
where mean is the training average, so each term is that feature's contribution against an average applicant.
"""
from __future__ import annotations

import math

import numpy as np

# expected direction of each feature on default risk (+1 raises risk); a fitted sign against this is flagged
EXPECTED_SIGN = {
    "stability": -1, "foirExisting": +1, "bounces": +1, "negMonths": +1, "balanceBuffer": -1, "vintage": -1,
    "thinFile": +1, "bureauScoreGap": +1, "dpd12": +1, "ever90": +1, "enquiries6m": +1, "historyYears": -1,
    "gstFilingGap": +1, "gstTurnoverGap": +1, "internalBad": +1, "loanToIncome": +1, "declaredInflation": +1,
}


def _sigmoid(z):
    return 1.0 / (1.0 + np.exp(-np.clip(z, -35, 35)))


def fit(X: np.ndarray, y: np.ndarray, l2: float = 1.0, iters: int = 50) -> tuple[float, np.ndarray]:
    """IRLS for logistic regression with an L2 penalty on the weights (not the intercept). X must be standardised."""
    n, k = X.shape
    A = np.hstack([np.ones((n, 1)), X])
    beta = np.zeros(k + 1)
    beta[0] = math.log((y.mean() + 1e-6) / (1 - y.mean() + 1e-6))
    pen = np.eye(k + 1) * l2
    pen[0, 0] = 0
    for _ in range(iters):
        p = _sigmoid(A @ beta)
        w = np.clip(p * (1 - p), 1e-6, None)
        grad = A.T @ (y - p) - pen @ beta
        hess = (A * w[:, None]).T @ A + pen
        step = np.linalg.solve(hess, grad)
        beta += step
        if np.max(np.abs(step)) < 1e-8:
            break
    return float(beta[0]), beta[1:]


def auc(y: np.ndarray, s: np.ndarray) -> float:
    """Area under the ROC curve by ranks (ties averaged)."""
    pos, neg = y == 1, y == 0
    if pos.sum() == 0 or neg.sum() == 0:
        return float("nan")
    order = s.argsort(kind="mergesort")
    ranks = np.empty(len(s))
    ranks[order] = np.arange(1, len(s) + 1)
    # average ranks of ties
    _, inv, counts = np.unique(s, return_inverse=True, return_counts=True)
    sums = np.bincount(inv, weights=ranks)
    ranks = (sums / counts)[inv]
    return float((ranks[pos].sum() - pos.sum() * (pos.sum() + 1) / 2) / (pos.sum() * neg.sum()))


def ks(y: np.ndarray, s: np.ndarray) -> float:
    order = np.argsort(-s)
    yb = y[order]
    cb = np.cumsum(yb) / max(1, yb.sum())
    cg = np.cumsum(1 - yb) / max(1, (1 - yb).sum())
    return float(np.max(np.abs(cb - cg)))


def deciles(y: np.ndarray, p: np.ndarray, bins: int = 10) -> list[dict]:
    order = np.argsort(p)
    out = []
    for i, idx in enumerate(np.array_split(order, bins)):
        if len(idx) == 0:
            continue
        out.append({"decile": i + 1, "loans": int(len(idx)), "avgPd": round(float(p[idx].mean()), 4),
                    "badRate": round(float(y[idx].mean()), 4)})
    return out


def psi(a: np.ndarray, b: np.ndarray, bins: int = 10) -> float:
    """Population stability index of scores between two samples (train vs hold-out)."""
    edges = np.unique(np.quantile(a, np.linspace(0, 1, bins + 1)))
    if len(edges) < 3:
        return 0.0
    ca = np.histogram(a, edges)[0] / len(a) + 1e-6
    cb = np.histogram(b, edges)[0] / len(b) + 1e-6
    return float(np.sum((ca - cb) * np.log(ca / cb)))


def train(rows: list[dict], features: list[str], l2: float = 1.0, holdout: float = 0.3, seed: int = 7) -> dict:
    if len(rows) < 200:
        raise ValueError("At least 200 loans with outcomes are needed to train a model")
    X = np.array([[float(r["features"].get(f, 0.0)) for f in features] for r in rows])
    y = np.array([1.0 if r["bad"] else 0.0 for r in rows])
    champ = np.array([float(r.get("championPd") or 0.0) for r in rows])
    if y.sum() < 20:
        raise ValueError("At least 20 bad loans are needed to train a model")
    rng = np.random.default_rng(seed)
    idx = rng.permutation(len(rows))
    # stratified split so the hold-out has a representative bad rate
    bad_idx, good_idx = idx[y[idx] == 1], idx[y[idx] == 0]
    nb, ng = int(round(len(bad_idx) * holdout)), int(round(len(good_idx) * holdout))
    test = np.concatenate([bad_idx[:nb], good_idx[:ng]])
    train_ = np.concatenate([bad_idx[nb:], good_idx[ng:]])

    mu = X[train_].mean(axis=0)
    sd = X[train_].std(axis=0)
    sd[sd < 1e-9] = 1.0
    b0, w_std = fit((X[train_] - mu) / sd, y[train_], l2)
    w = w_std / sd
    # z = b0 + sum(w_std * (x - mu)/sd) = b0 + sum(w * (x - mu)): the intercept is already centred on the training mean
    p_train = _sigmoid(b0 + (X[train_] - mu) @ w)
    p_test = _sigmoid(b0 + (X[test] - mu) @ w)

    def summary(yy, pp):
        return {"auc": round(auc(yy, pp), 4), "gini": round(2 * auc(yy, pp) - 1, 4), "ks": round(ks(yy, pp), 4),
                "brier": round(float(np.mean((pp - yy) ** 2)), 5), "avgPd": round(float(pp.mean()), 4), "badRate": round(float(yy.mean()), 4)}

    has_champ = bool(np.any(champ > 0))
    warnings = []
    terms = []
    for f, wi, m, s in zip(features, w, mu, sd):
        exp = EXPECTED_SIGN.get(f)
        flag = exp is not None and abs(wi * s) > 0.05 and np.sign(wi) != exp
        if flag:
            warnings.append(f"{f}: fitted effect is the opposite of the expected direction")
        terms.append({"feature": f, "weight": round(float(wi), 6), "mean": round(float(m), 6),
                      "standardisedWeight": round(float(wi * s), 4), "unexpectedSign": bool(flag)})
    challenger = summary(y[test], p_test)
    result = {
        "intercept": round(float(b0), 6),
        "terms": terms,
        "trainRows": int(len(train_)), "holdoutRows": int(len(test)),
        "train": summary(y[train_], p_train),
        "holdout": challenger,
        "deciles": deciles(y[test], p_test),
        "psi": round(psi(p_train, p_test), 4),
        "l2": l2,
        "warnings": warnings,
    }
    if has_champ:
        result["champion"] = summary(y[test], champ[test])
        result["championDeciles"] = deciles(y[test], champ[test])
        result["aucGain"] = round(challenger["auc"] - result["champion"]["auc"], 4)
    return result
