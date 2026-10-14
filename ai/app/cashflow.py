"""Transaction categories, monthly cash flow, recurring income and EMIs, and statement risk signals."""
import re
from collections import Counter, defaultdict
from datetime import date, datetime
from statistics import mean, median

# order matters: the first matching rule wins
RULES: list[tuple[str, str, str]] = [
    # (category, direction: C/D/*, regex on the upper-cased narration)
    ("BOUNCE_CHARGE", "D", r"(RETURN|RTN|BOUNCE|DISHONOU?R).*(CHG|CHARGE)|(CHG|CHARGE).*(RETURN|RTN|BOUNCE)"),
    ("BOUNCE", "*", r"\b(RTN|RETURN(ED)?|BOUNCE[D]?|DISHONOU?R|UNPAID|INSUFF(ICIENT)?\s*(FUNDS|BAL)|INSUF\s*BAL|FUNDS\s*INSUFF)\b"),
    ("REVERSAL", "C", r"\b(REV(ERSAL)?|REFUND|RVSL|CHARGEBACK)\b"),
    ("GAMBLING", "*", r"(RUMMY|BETTING|\bBET\b|CASINO|POKER|DREAM\s?11|MPL\b|LOTTERY|FANTASY\s*(SPORTS|GAME)|TEEN\s*PATTI|1XBET|PARIMATCH)"),
    ("SALARY", "C", r"\b(SALARY|SAL\b|SAL\s|PAYROLL|WAGES|STIPEND)"),
    ("LOAN_CREDIT", "C", r"(LOAN\s*(DISB|CREDIT|A/?C)|DISBURS|\bDISB\b)"),
    ("EMI", "D", r"\b(EMI|NACH|ACH\s*D|ECS|LOAN|FINANCE|FINSERV|CAPITAL|HOUSING\s*FIN|BAJAJ\s*FIN|INSTALL?MENT|SI\s*DR)\b"),
    ("INTEREST", "C", r"\b(INT\.?\s*PD|INTEREST|INT\s*CREDIT|SB\s*INT)\b"),
    ("CHARGES", "D", r"\b(CHG|CHARGES?|FEE|SMS\s*ALERT|AMC|MIN\s*BAL|PENAL)\b"),
    ("TAX", "D", r"\b(GST|TDS|INCOME\s*TAX|CBIC|ADVANCE\s*TAX|CPIN)\b"),
    ("CASH_DEPOSIT", "C", r"(BY\s*CASH|CASH\s*DEP|CDM|CASH\s*CR)"),
    ("CASH_WITHDRAWAL", "D", r"\b(ATM|CASH\s*W(DL|ITHDRAWAL)|SELF\s*CHQ|CASH\s*PAID|NWD)\b"),
    ("RENT", "D", r"\bRENT\b"),
    ("UTILITIES", "D", r"(ELECTRICITY|BBPS|BILLPAY|WATER\s*BILL|GAS\s*BILL|BROADBAND|RECHARGE|DTH|MOBILE\s*BILL)"),
    ("INSURANCE", "D", r"(INSURANCE|LIC\b|PREMIUM)"),
    ("INVESTMENT", "D", r"(MUTUAL\s*FUND|\bSIP\b|\bMF\b|ZERODHA|GROWW|DEMAT)"),
    ("UPI", "*", r"\bUPI\b|UPI/"),
    ("CARD", "D", r"\b(POS|CARD|ECOM|DEBIT\s*CARD)\b"),
    ("TRANSFER_IN", "C", r"\b(NEFT|IMPS|RTGS|TRF|TRANSFER|FT)\b"),
    ("TRANSFER_OUT", "D", r"\b(NEFT|IMPS|RTGS|TRF|TRANSFER|FT)\b"),
    ("CHEQUE", "*", r"\b(CHQ|CHEQUE|CLG|CLEARING)\b"),
]
COMPILED = [(c, d, re.compile(rx)) for c, d, rx in RULES]
NON_INCOME = {"REVERSAL", "BOUNCE", "SELF_TRANSFER", "INTEREST", "LOAN_CREDIT"}  # credits that are not business or salary income


def categorise(narration: str, is_credit: bool, holder: str | None) -> str:
    up = narration.upper()
    if holder:
        first = holder.split()[0].upper()
        if re.search(rf"\bSELF\b|\bOWN\s*A/?C\b|\b{re.escape(first)}\b.*(OTHER\s*BANK|OWN|SELF)", up):
            return "SELF_TRANSFER"
    for cat, direction, rx in COMPILED:
        if direction == "C" and not is_credit or direction == "D" and is_credit:
            continue
        if rx.search(up):
            return cat
    return "OTHER_CREDIT" if is_credit else "OTHER_DEBIT"


def counterparty(narration: str) -> str:
    up = narration.upper()
    m = re.search(r"UPI/\d+/([^/]+)", up)
    if m:
        return m.group(1).strip()
    m = re.search(r"(?:NEFT|IMPS|RTGS)\s*(?:CR|DR)?[-/ ]+([A-Z][A-Z .&]+?)(?:[-/]|$)", up)
    if m:
        return m.group(1).strip()
    m = re.search(r"(?:NACH|ACH|ECS)\s*(?:DR|CR)?\s+([A-Z][A-Z .&]+?)(?:\s+EMI|\s+LOAN|$)", up)
    if m:
        return m.group(1).strip()
    return re.sub(r"[^A-Z ]", " ", up).split("  ")[0].strip()[:30]


def _month(d: str) -> str:
    return d[:7]


def analyse(txns: list[dict], holder: str | None, opening: float | None, declared_income: float | None = None) -> dict:
    """txns: [{date, narration, debit, credit, balance}] in statement order. Adds 'category' to each."""
    for t in txns:
        t["category"] = categorise(t["narration"], t["credit"] > 0, holder)
        t["counterparty"] = counterparty(t["narration"])
        t["flags"] = []
    by_month: dict[str, list[dict]] = defaultdict(list)
    for t in txns:
        by_month[_month(t["date"])].append(t)
    months = sorted(by_month)
    rot = rotation(txns)

    # daily closing balances -> average and minimum balance per month (balance carried forward between txns)
    monthly = []
    prev_bal = opening
    for m in months:
        ts = by_month[m]
        y, mo = int(m[:4]), int(m[5:7])
        days_in = (date(y + (mo == 12), mo % 12 + 1, 1) - date(y, mo, 1)).days
        day_bal = {}
        for t in ts:
            if t.get("balance") is not None:
                day_bal[int(t["date"][8:10])] = t["balance"]
        bal, daily = prev_bal, []
        for dday in range(1, days_in + 1):
            if dday in day_bal:
                bal = day_bal[dday]
            if bal is not None:
                daily.append(bal)
        if daily:
            prev_bal = daily[-1]
        credits = sum(t["credit"] for t in ts)
        debits = sum(t["debit"] for t in ts)
        inflow = sum(t["credit"] for t in ts if t["category"] not in NON_INCOME)
        outflow = sum(t["debit"] for t in ts if t["category"] not in ("SELF_TRANSFER", "BOUNCE_CHARGE") and t["category"] != "BOUNCE")
        rotated = sum(t["credit"] for t in ts if t.get("rotated"))
        monthly.append({
            "month": m, "credits": round(credits, 2), "debits": round(debits, 2), "inflow": round(inflow, 2), "outflow": round(outflow, 2),
            "rotated": round(rotated, 2), "adjustedInflow": round(max(0.0, inflow - rotated), 2),
            "endBalance": round(daily[-1], 2) if daily else None, "avgBalance": round(mean(daily), 2) if daily else None,
            "minBalance": round(min(daily), 2) if daily else None, "bounces": sum(1 for t in ts if t["category"] == "BOUNCE"),
            "cashDeposits": round(sum(t["credit"] for t in ts if t["category"] == "CASH_DEPOSIT"), 2),
            "transactions": len(ts),
        })

    # recurring salary: a credit tagged SALARY, or the same payer crediting a similar amount in most months
    salary = None
    sal = [t for t in txns if t["category"] == "SALARY"]
    if sal:
        amts = [t["credit"] for t in sal]
        salary = {"amount": round(median(amts), 2), "employer": Counter(t["counterparty"] for t in sal).most_common(1)[0][0],
                  "months": len({_month(t["date"]) for t in sal})}

    # EMIs: debits tagged EMI that recur with a stable amount, grouped by counterparty
    emis = []
    groups = defaultdict(list)
    for t in txns:
        if t["category"] == "EMI":
            groups[(t["counterparty"], round(t["debit"], -1))].append(t)
    for (cp, amt), ts in groups.items():
        mths = {_month(t["date"]) for t in ts}
        if len(mths) >= 2 or len(months) <= 2:
            emis.append({"counterparty": cp, "amount": round(ts[0]["debit"], 2), "months": len(mths)})
    emis.sort(key=lambda e: -e["amount"])
    detected_emi = round(sum(e["amount"] for e in emis), 2)

    total_inflow = sum(m["inflow"] for m in monthly)
    cash_share = sum(m["cashDeposits"] for m in monthly) / total_inflow if total_inflow else 0.0
    big_credits = [t for t in txns if t["credit"] >= 5000 and t["category"] not in NON_INCOME]
    round_share = (sum(1 for t in big_credits if t["credit"] % 1000 == 0) / len(big_credits)) if big_credits else 0.0
    gambling = [t for t in txns if t["category"] == "GAMBLING"]
    bounces = [t for t in txns if t["category"] == "BOUNCE"]
    for t in gambling:
        t["flags"].append("Betting or gambling")
    for t in bounces:
        t["flags"].append("Payment bounced")
    for t in txns:
        if t["category"] == "CASH_DEPOSIT" and t["credit"] >= 50000:
            t["flags"].append("Large cash deposit")
    # duplicates: same date, amount and narration
    seen = Counter((t["date"], t["debit"], t["credit"], t["narration"]) for t in txns)
    dups = [k for k, n in seen.items() if n > 1 and "UPI" not in k[3]]

    last = max((t["date"] for t in txns), default=None)
    days_since = (date.today() - datetime.fromisoformat(last).date()).days if last else None
    missing = _missing_months(months)
    creditors = Counter()
    debtors = Counter()
    for t in txns:
        if t["credit"] and t["category"] not in NON_INCOME:
            creditors[t["counterparty"]] += t["credit"]
        if t["debit"] and t["category"] not in ("SELF_TRANSFER",):
            debtors[t["counterparty"]] += t["debit"]
    avg_bal = [m["avgBalance"] for m in monthly if m["avgBalance"] is not None]
    summary = {
        "months": len(months),
        "avgMonthlyInflow": round(total_inflow / len(months), 2) if months else 0.0,
        "avgMonthlyOutflow": round(sum(m["outflow"] for m in monthly) / len(months), 2) if months else 0.0,
        "avgBalance": round(mean(avg_bal), 2) if avg_bal else None,
        "minBalance": min((m["minBalance"] for m in monthly if m["minBalance"] is not None), default=None),
        "bounces": len(bounces),
        "bounceCharges": round(sum(t["debit"] for t in txns if t["category"] == "BOUNCE_CHARGE"), 2),
        "salary": salary,
        "emis": emis,
        "detectedEmi": detected_emi,
        "cashDepositShare": round(cash_share, 3),
        "roundAmountShare": round(round_share, 3),
        "gamblingCount": len(gambling),
        "gamblingAmount": round(sum(t["debit"] for t in gambling), 2),
        "duplicates": len(dups),
        "missingMonths": missing,
        "lastTransactionDate": last,
        "daysSinceLastTransaction": days_since,
        "topCreditors": [{"name": k, "amount": round(v, 2)} for k, v in creditors.most_common(5)],
        "topDebits": [{"name": k, "amount": round(v, 2)} for k, v in debtors.most_common(5)],
        "categories": _category_totals(txns),
        "rotation": rot,
        "adjustedTurnover": round(sum(m["adjustedInflow"] for m in monthly), 2),
        "avgMonthlyAdjustedInflow": round(sum(m["adjustedInflow"] for m in monthly) / len(months), 2) if months else 0.0,
    }
    if declared_income:
        summary["declaredVsObserved"] = round(declared_income / summary["avgMonthlyInflow"], 2) if summary["avgMonthlyInflow"] else None
    return {"monthly": monthly, "summary": summary}


ROUND_TRIP_DAYS = 7
CASH_CYCLE_DAYS = 2


def _d(s: str) -> date:
    return datetime.fromisoformat(s[:10]).date()


def rotation(txns: list[dict]) -> dict:
    """Money rotation: credits that are not real turnover because the same money goes straight back out.

    - round trip: a credit from a party followed within 7 days by a debit to the same party of 80-120% of it
      (or a debit that comes back from the same party within 7 days)
    - cash cycle: a cash deposit and a cash withdrawal of about the same amount (within 10%) within 2 days
    Credits already outside income (self transfers, reversals, loan credits) are reported separately.
    Each rotated credit is marked rotated=True and flagged, so the monthly adjusted inflow can leave it out."""
    used: set[int] = set()
    trips, cash_cycles = [], []
    idx = list(range(len(txns)))
    for i in idx:
        t = txns[i]
        if not t["credit"] or t["category"] in NON_INCOME or t.get("rotated"):
            continue
        for j in idx:
            if j == i or j in used:
                continue
            u = txns[j]
            if not u["debit"]:
                continue
            gap = abs((_d(u["date"]) - _d(t["date"])).days)
            ratio = u["debit"] / t["credit"]
            same_party = t["counterparty"] and t["counterparty"] == u["counterparty"] and len(t["counterparty"]) >= 3
            if same_party and gap <= ROUND_TRIP_DAYS and 0.8 <= ratio <= 1.2:
                t["rotated"] = True
                used.add(j)
                t["flags"].append("Round trip with " + t["counterparty"])
                u["flags"].append("Round trip with " + t["counterparty"])
                trips.append({"party": t["counterparty"], "credit": round(t["credit"], 2), "debit": round(u["debit"], 2),
                              "creditDate": t["date"], "debitDate": u["date"]})
                break
            if t["category"] == "CASH_DEPOSIT" and u["category"] == "CASH_WITHDRAWAL" and gap <= CASH_CYCLE_DAYS and 0.9 <= ratio <= 1.1:
                t["rotated"] = True
                used.add(j)
                t["flags"].append("Cash in and out")
                u["flags"].append("Cash in and out")
                cash_cycles.append({"amount": round(t["credit"], 2), "depositDate": t["date"], "withdrawalDate": u["date"]})
                break
    credits = sum(t["credit"] for t in txns)
    rotated = sum(t["credit"] for t in txns if t.get("rotated"))
    self_in = sum(t["credit"] for t in txns if t["category"] == "SELF_TRANSFER")
    loans_in = sum(t["credit"] for t in txns if t["category"] == "LOAN_CREDIT")
    reversals = sum(t["credit"] for t in txns if t["category"] in ("REVERSAL", "BOUNCE"))
    excluded = rotated + self_in + loans_in + reversals
    return {
        "grossCredits": round(credits, 2),
        "roundTrips": len(trips),
        "cashCycles": len(cash_cycles),
        "rotatedAmount": round(rotated, 2),
        "selfTransfersIn": round(self_in, 2),
        "loanCredits": round(loans_in, 2),
        "reversals": round(reversals, 2),
        "rotationShare": round(rotated / credits, 3) if credits else 0.0,
        "excludedShare": round(excluded / credits, 3) if credits else 0.0,
        "examples": (trips + [dict(c, party="CASH") for c in cash_cycles])[:10],
    }


def _category_totals(txns):
    tot = defaultdict(lambda: {"count": 0, "credit": 0.0, "debit": 0.0})
    for t in txns:
        c = tot[t["category"]]
        c["count"] += 1
        c["credit"] = round(c["credit"] + t["credit"], 2)
        c["debit"] = round(c["debit"] + t["debit"], 2)
    return dict(sorted(tot.items(), key=lambda kv: -(kv[1]["credit"] + kv[1]["debit"])))


def _missing_months(months: list[str]) -> list[str]:
    if len(months) < 2:
        return []
    out = []
    y, m = int(months[0][:4]), int(months[0][5:7])
    end = months[-1]
    while f"{y:04d}-{m:02d}" <= end:
        key = f"{y:04d}-{m:02d}"
        if key not in months:
            out.append(key)
        m += 1
        if m == 13:
            y, m = y + 1, 1
    return out
