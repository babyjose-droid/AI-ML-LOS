"""
Accuracy of the bank statement reader on generated SPECIMEN statements.

For each seed and layout it checks the clean PDF, a scanned copy, a password-locked copy and an edited copy:
transactions found, amounts exactly right, categories right, salary/EMI/bounce/gambling detected,
and whether edited statements are caught (and clean ones are not wrongly flagged).

    python -m tools.evaluate_statements [seeds] [--no-scan] [--out file.json]
"""
import json
import sys
import time

from app.statements_analyze import analyse_statement
from tools import statements_gen as g


def _align(got, truth):
    """Pairs read rows with true rows (a missed or extra row must not shift every later comparison)."""
    from difflib import SequenceMatcher
    key = lambda d, dr, cr: (d, round(dr, 2), round(cr, 2))
    a = [key(g["date"], g["debit"], g["credit"]) for g in got]
    b = [key(t.date.isoformat(), t.debit, t.credit) for t in truth]
    pairs = []
    for blk in SequenceMatcher(None, a, b, autojunk=False).get_matching_blocks():
        pairs += [(blk.a + k, blk.b + k) for k in range(blk.size)]
    return pairs


def compare(st, r):
    got = r["transactions"]
    truth = st.txns
    pairs = _align(got, truth)
    exact = sum(1 for i, j in pairs if got[i]["balance"] is not None and abs(got[i]["balance"] - truth[j].balance) < 0.01)
    cats = sum(1 for i, j in pairs if got[i].get("category") == truth[j].truth)
    s = r.get("summary") or {}
    sal = (s.get("salary") or {}).get("amount")
    return {
        "found": len(got), "expected": len(truth), "matched": len(pairs),
        "amountsExact": round(exact / len(truth), 4),
        "categoryAccuracy": round(cats / len(truth), 4),
        "salaryOk": (sal == st.truth["salary"]) if st.truth["salary"] else sal is None,
        "emiOk": abs((s.get("detectedEmi") or 0) - st.truth["emi"]) < 1,
        "bouncesOk": s.get("bounces") == st.truth["bounces"],
        "gamblingOk": s.get("gamblingCount") == st.truth["gambling"],
        "status": r["status"], "reconciliation": r["reconciliation"]["rate"], "tamper": r["tamper"]["score"],
    }


def run(seeds=3, scan=True):
    rows = []
    t0 = time.time()
    for seed in range(1, seeds + 1):
        for lay in "ABC":
            profile = "msme" if (seed + ord(lay)) % 3 == 0 else "salaried"
            st = g.generate(["Asha Kulkarni", "Imran Shaikh", "Joseph Dsouza", "Gurpreet Kaur", "Venkatesh Rao"][seed % 5], profile, 6, lay, seed=seed * 7 + ord(lay),
                            bounce_months=(seed % 6,) if seed % 2 else (), gambling=seed % 3 == 0)
            pdf = g.render_pdf(st)
            variants = [("clean", pdf, None), ("locked", g.locked(pdf, "PASS1234"), "PASS1234")]
            if scan:
                variants.append(("scanned", g.scanned(pdf, seed=seed), None))
            for name, data, pw in variants:
                r = analyse_statement(data, "application/pdf", pw)
                rows.append({"layout": lay, "variant": name, **compare(st, r)})
            ed = g.edited(st, seed=seed)
            r = analyse_statement(g.render_pdf(ed), "application/pdf")
            rows.append({"layout": lay, "variant": "edited", "caught": r["status"] != "OK" and r["tamper"]["score"] >= 0.5,
                         "status": r["status"], "tamper": r["tamper"]["score"]})
    return rows, time.time() - t0


def summarise(rows):
    out = {}
    for v in ("clean", "locked", "scanned"):
        sel = [r for r in rows if r["variant"] == v]
        if not sel:
            continue
        out[v] = {
            "statements": len(sel),
            "transactionsFound": round(sum(r["matched"] for r in sel) / sum(r["expected"] for r in sel), 4),
            "amountsExact": round(sum(r["amountsExact"] for r in sel) / len(sel), 4),
            "categoryAccuracy": round(sum(r["categoryAccuracy"] for r in sel) / len(sel), 4),
            "salaryDetected": round(sum(r["salaryOk"] for r in sel) / len(sel), 3),
            "emiDetected": round(sum(r["emiOk"] for r in sel) / len(sel), 3),
            "bouncesRight": round(sum(r["bouncesOk"] for r in sel) / len(sel), 3),
            "gamblingRight": round(sum(r["gamblingOk"] for r in sel) / len(sel), 3),
            "autoAccepted": round(sum(r["status"] == "OK" for r in sel) / len(sel), 3),
            "wronglyFlaggedAsEdited": sum(1 for r in sel if r["tamper"] >= 0.5),
        }
    ed = [r for r in rows if r["variant"] == "edited"]
    out["edited"] = {"statements": len(ed), "caught": sum(r["caught"] for r in ed)}
    return out


if __name__ == "__main__":
    seeds = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 3
    rows, secs = run(seeds, "--no-scan" not in sys.argv)
    s = summarise(rows)
    s["seconds"] = round(secs, 1)
    txt = json.dumps(s, indent=1)
    if "--out" in sys.argv:
        open(sys.argv[sys.argv.index("--out") + 1], "w").write(txt)
    print(txt)
