"""Number-format validators for Indian identity documents. Pure functions, no I/O."""
import re
from datetime import date, datetime

# --- Verhoeff checksum (used by Aadhaar) ---
_D = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 2, 3, 4, 0, 6, 7, 8, 9, 5], [2, 3, 4, 0, 1, 7, 8, 9, 5, 6],
    [3, 4, 0, 1, 2, 8, 9, 5, 6, 7], [4, 0, 1, 2, 3, 9, 5, 6, 7, 8], [5, 9, 8, 7, 6, 0, 4, 3, 2, 1],
    [6, 5, 9, 8, 7, 1, 0, 4, 3, 2], [7, 6, 5, 9, 8, 2, 1, 0, 4, 3], [8, 7, 6, 5, 9, 3, 2, 1, 0, 4],
    [9, 8, 7, 6, 5, 4, 3, 2, 1, 0]]
_P = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 5, 7, 6, 2, 8, 3, 0, 9, 4], [5, 8, 0, 3, 7, 9, 6, 1, 4, 2],
    [8, 9, 1, 6, 0, 4, 3, 5, 2, 7], [9, 4, 5, 3, 1, 2, 6, 8, 7, 0], [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
    [2, 7, 9, 3, 8, 0, 6, 4, 1, 5], [7, 0, 4, 6, 9, 1, 3, 2, 5, 8]]
_INV = [0, 4, 3, 2, 1, 5, 6, 7, 8, 9]


def verhoeff_valid(num: str) -> bool:
    c = 0
    for i, ch in enumerate(reversed(num)):
        c = _D[c][_P[i % 8][int(ch)]]
    return c == 0


def verhoeff_digit(num: str) -> str:
    c = 0
    for i, ch in enumerate(reversed(num)):
        c = _D[c][_P[(i + 1) % 8][int(ch)]]
    return str(_INV[c])


def aadhaar_valid(num: str) -> bool:
    n = re.sub(r"\D", "", num)
    return len(n) == 12 and n[0] not in "01" and verhoeff_valid(n)


def mask_aadhaar(num: str) -> str:
    n = re.sub(r"\D", "", num)
    return "XXXX XXXX " + n[-4:] if len(n) >= 4 else "XXXX XXXX XXXX"


# --- PAN ---
PAN_RE = re.compile(r"\b([A-Z]{3}[ABCFGHLJPT][A-Z][0-9]{4}[A-Z])\b")
PAN_HOLDER = {"P": "Individual", "C": "Company", "H": "HUF", "F": "Firm", "A": "AOP", "T": "Trust",
              "B": "BOI", "L": "Local authority", "J": "Artificial juridical person", "G": "Government"}


def pan_valid(pan: str) -> bool:
    return bool(PAN_RE.fullmatch(pan or ""))


# --- other IDs ---
EPIC_RE = re.compile(r"\b([A-Z]{3}[0-9]{7})\b")
DL_RE = re.compile(r"\b([A-Z]{2}[-\s]?[0-9]{2}[-\s]?(?:19|20)[0-9]{2}[-\s]?[0-9]{7})\b")
PASSPORT_RE = re.compile(r"\b([A-PR-WY][1-9][0-9]{5}[1-9])\b")
UDYAM_RE = re.compile(r"\b(UDYAM-[A-Z]{2}-[0-9]{2}-[0-9]{7})\b")
GSTIN_RE = re.compile(r"\b([0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z])\b")
DATE_RE = re.compile(r"\b([0-3]?[0-9])[/\-.]([01]?[0-9])[/\-.]((?:19|20)[0-9]{2})\b")
AADHAAR_RE = re.compile(r"(?<!\d)([2-9][0-9]{3})\s?([0-9]{4})\s?([0-9]{4})(?!\d)")


def parse_date(s: str):
    """Returns an ISO date for dd/mm/yyyy style dates, or None."""
    m = DATE_RE.search(s or "")
    if not m:
        return None
    try:
        return date(int(m.group(3)), int(m.group(2)), int(m.group(1))).isoformat()
    except ValueError:
        return None


def plausible_dob(iso: str) -> bool:
    try:
        d = datetime.fromisoformat(iso).date()
    except (TypeError, ValueError):
        return False
    age = (date.today() - d).days / 365.25
    return 16 <= age <= 100


# --- Passport MRZ (ICAO 9303) ---
_W = [7, 3, 1]


def _mrz_val(ch: str) -> int:
    if ch.isdigit():
        return int(ch)
    if ch.isalpha():
        return ord(ch) - 55
    return 0


def mrz_check(data: str, digit: str) -> bool:
    if not digit.isdigit():
        return False
    return sum(_mrz_val(c) * _W[i % 3] for i, c in enumerate(data)) % 10 == int(digit)


def parse_mrz(lines: list[str]):
    """Parses a TD3 passport MRZ (two lines of 44). Returns dict or None."""
    cand = [re.sub(r"\s", "", ln).upper().replace("«", "<") for ln in lines]
    cand = [c for c in cand if len(c) >= 40 and c.count("<") >= 3]
    for i in range(len(cand) - 1):
        l1, l2 = cand[i].ljust(44, "<")[:44], cand[i + 1].ljust(44, "<")[:44]
        if not l1.startswith("P"):
            continue
        names = l1[5:].split("<<", 1)
        surname = names[0].replace("<", " ").strip()
        given = names[1].split("<<")[0].replace("<", " ").strip() if len(names) > 1 else ""
        given = re.sub(r"[^A-Z ]", "", given).strip()
        number, dob, exp = l2[0:9], l2[13:19], l2[21:27]
        checks = {
            "number": mrz_check(number, l2[9]),
            "dob": mrz_check(dob, l2[19]),
            "expiry": mrz_check(exp, l2[27]),
        }

        def yymmdd(s, past):
            try:
                y = int(s[:2])
                y += 1900 if (past and y > date.today().year % 100) else 2000
                return date(y, int(s[2:4]), int(s[4:6])).isoformat()
            except ValueError:
                return None

        return {
            "country": l1[2:5].replace("<", ""), "surname": surname, "givenNames": given,
            "number": number.replace("<", ""), "nationality": l2[10:13].replace("<", ""),
            "dob": yymmdd(dob, True), "sex": l2[20], "expiry": yymmdd(exp, False), "checks": checks,
        }
    return None
