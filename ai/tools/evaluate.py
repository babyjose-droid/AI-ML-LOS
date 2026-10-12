"""
Accuracy and robustness evaluation of the document reader on generated SPECIMEN cards.

For each fictional person and document type it renders a clean card, applies real-world degradations
(phone photo on a table, rotation, skew, blur, noise, low resolution, heavy JPEG, poor light) and compares
what the reader returns with the ground truth.

The metric that matters most is "silent errors": the reader said OK but a field value was wrong.
Those would reach a credit decision without a person looking. Everything else is either correct or routed
to review.

    python -m tools.evaluate [people] [--out report.json]
"""
import io
import json
import random
import sys
import time
from collections import defaultdict

import numpy as np
from PIL import Image, ImageEnhance, ImageFilter

from app.analyze import analyze
from app.validators import verhoeff_digit
from tools import specimens as sp

FIRST = ["Aarav", "Vivaan", "Aditya", "Ishaan", "Rohan", "Sanjay", "Kiran", "Anita", "Kavya", "Meena", "Pooja", "Sunita",
         "Rekha", "Farhan", "Imran", "Joseph", "Gurpreet", "Lalitha", "Venkatesh", "Bhavna", "Harish", "Nirmala", "Tanvi", "Yusuf"]
LAST = ["Sharma", "Patil", "Reddy", "Iyer", "Khan", "Singh", "Gupta", "Nair", "Das", "Joshi", "Kulkarni", "Fernandes",
        "Mehta", "Bhat", "Pillai", "Chauhan", "Mishra", "Rao", "Shaikh", "Banerjee"]
CITIES = [("Pune", "Maharashtra", "4110"), ("Nashik", "Maharashtra", "4220"), ("Jaipur", "Rajasthan", "3020"),
          ("Lucknow", "Uttar Pradesh", "2260"), ("Kochi", "Kerala", "6820"), ("Indore", "Madhya Pradesh", "4520")]


def person(rng: random.Random):
    f, l = rng.choice(FIRST), rng.choice(LAST)
    y, m, d = rng.randint(1965, 2002), rng.randint(1, 12), rng.randint(1, 28)
    letters = "ABCDEFGHJKLMNPRSTUVWXYZ"
    pan = "".join(rng.choice(letters) for _ in range(3)) + "P" + l[0].upper() + f"{rng.randint(1000, 9999)}" + rng.choice(letters)
    base = str(rng.randint(2, 9)) + "".join(str(rng.randint(0, 9)) for _ in range(10))
    city, state, pin4 = rng.choice(CITIES)
    pin = pin4 + f"{rng.randint(10, 99)}"
    return {
        "name": f"{f} {l}", "father": f"{rng.choice(FIRST)} {l}", "dob": f"{d:02d}/{m:02d}/{y}", "dobIso": f"{y}-{m:02d}-{d:02d}",
        "pan": pan, "aadhaar": base + verhoeff_digit(base), "gender": rng.choice(["MALE", "FEMALE"]),
        "address": [f"{rng.randint(1, 99)} Station Road", f"{city}, {state} {pin}"], "pin": pin, "city": city,
        "epic": "".join(rng.choice(letters) for _ in range(3)) + f"{rng.randint(1000000, 9999999)}",
        "dl": f"MH{rng.randint(10, 50)} {rng.randint(2000, 2020)}{rng.randint(1000000, 9999999)}",
        "passport": rng.choice("JKLMNPRSTVWZ") + f"{rng.randint(1, 9)}{rng.randint(10000, 99999)}{rng.randint(1, 9)}",
        "udyam": f"UDYAM-MH-{rng.randint(10, 40)}-{rng.randint(1000000, 9999999)}",
        "net": rng.randint(20, 150) * 1000,
    }


def documents(p):
    """(type, image, expected fields) for one person."""
    yymmdd = p["dobIso"][2:4] + p["dobIso"][5:7] + p["dobIso"][8:10]
    surname, given = p["name"].split(" ")[1].upper(), p["name"].split(" ")[0].upper()
    return [
        ("PAN", sp.pan_card(p["name"], p["father"], p["dob"], p["pan"]),
         {"pan": p["pan"], "name": p["name"].upper(), "dob": p["dobIso"]}),
        ("AADHAAR", sp.aadhaar_card(p["name"], p["dob"], p["gender"], p["aadhaar"], p["address"]),
         {"aadhaarLast4": p["aadhaar"][-4:], "name": p["name"], "dob": p["dobIso"], "pincode": p["pin"]}),
        ("VOTER_ID", sp.voter_card(p["name"], p["father"], p["dob"], p["epic"]),
         {"epicNumber": p["epic"], "name": p["name"], "dob": p["dobIso"]}),
        ("DRIVING_LICENCE", sp.driving_licence(p["name"], p["dob"], p["dl"], p["address"][0]),
         {"dlNumber": p["dl"].replace(" ", ""), "name": p["name"], "dob": p["dobIso"]}),
        ("PASSPORT", sp.passport(surname, given, yymmdd, "330101", p["passport"], "M" if p["gender"] == "MALE" else "F"),
         {"passportNumber": p["passport"], "surname": surname, "givenNames": given, "dob": p["dobIso"]}),
        ("UDYAM", sp.udyam_certificate(p["name"], p["name"].split()[1].upper() + " TRADERS", p["udyam"]),
         {"udyamNumber": p["udyam"], "ownerName": p["name"]}),
        ("SALARY_SLIP", sp.salary_slip(p["name"], "Acme Industries Ltd", "August 2026", p["net"] + 15000, p["net"]),
         {"employeeName": p["name"], "netPay": str(p["net"])}),
    ]


# ---------------- degradations ----------------

def on_table(img: Image.Image, rng) -> Image.Image:
    """Card photographed on a wooden-ish table with a margin and a slight tilt."""
    w, h = img.size
    bg = Image.new("RGB", (int(w * 1.35), int(h * 1.45)), (rng.randint(120, 160), rng.randint(90, 120), rng.randint(60, 90)))
    noise = np.random.default_rng(rng.randint(0, 9999)).integers(-18, 18, (bg.height, bg.width, 1))
    bg = Image.fromarray(np.clip(np.array(bg).astype(int) + noise, 0, 255).astype("uint8"))
    card = img.rotate(rng.uniform(-4, 4), expand=True, fillcolor=bg.getpixel((0, 0)))
    bg.paste(card, ((bg.width - card.width) // 2, (bg.height - card.height) // 2))
    return bg


def uneven_light(img: Image.Image, rng) -> Image.Image:
    a = np.array(img).astype(float)
    h, w = a.shape[:2]
    gx = np.linspace(0.55, 1.05, w)[None, :, None]
    return Image.fromarray(np.clip(a * gx, 0, 255).astype("uint8"))


def noise(img, rng, sigma=18):
    a = np.array(img).astype(float) + np.random.default_rng(rng.randint(0, 9999)).normal(0, sigma, np.array(img).shape)
    return Image.fromarray(np.clip(a, 0, 255).astype("uint8"))


def jpeg(img, q=25):
    b = io.BytesIO()
    img.save(b, "JPEG", quality=q)
    return Image.open(io.BytesIO(b.getvalue())).convert("RGB")


def perspective(img, rng):
    w, h = img.size
    d = int(w * 0.06)
    coeffs = _persp_coeffs([(0, 0), (w, 0), (w, h), (0, h)],
                           [(rng.randint(0, d), rng.randint(0, d)), (w - rng.randint(0, d), 0), (w, h - rng.randint(0, d)), (0, h)])
    return img.transform((w, h), Image.PERSPECTIVE, coeffs, Image.BICUBIC, fillcolor=(200, 200, 200))


def _persp_coeffs(src, dst):
    m = []
    for (x, y), (u, v) in zip(dst, src):
        m.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        m.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    a = np.array(m, dtype=float)
    b = np.array(src, dtype=float).reshape(8)
    return np.linalg.solve(a, b).tolist()


DEGRADATIONS = {
    "clean": lambda im, r: im,
    "phone photo on table": on_table,
    "tilted 3 degrees": lambda im, r: im.rotate(3, expand=True, fillcolor=(230, 230, 230)),
    "perspective skew": perspective,
    "rotated 90 (sideways)": lambda im, r: im.rotate(90, expand=True),
    "upside down": lambda im, r: im.rotate(180),
    "mild blur": lambda im, r: im.filter(ImageFilter.GaussianBlur(1.6)),
    "heavy blur": lambda im, r: im.filter(ImageFilter.GaussianBlur(5)),
    "sensor noise": noise,
    "low resolution (480px)": lambda im, r: im.resize((480, int(480 * im.height / im.width))),
    "heavy JPEG (q=20)": lambda im, r: jpeg(im, 20),
    "uneven lighting": uneven_light,
    "dark photo": lambda im, r: ImageEnhance.Brightness(im).enhance(0.45),
}


def norm(k, v):
    v = (v or "").strip().upper()
    if k in ("name", "employeeName", "ownerName", "surname", "givenNames"):
        v = " ".join(v.replace(".", " ").split())
    return v


def evaluate(n_people=6, seed=7):
    rng = random.Random(seed)
    people = [person(rng) for _ in range(n_people)]
    rows = []
    t0 = time.time()
    for p in people:
        for dtype, img, expected in documents(p):
            for dname, fn in DEGRADATIONS.items():
                im = fn(img, rng)
                b = io.BytesIO()
                im.save(b, "PNG")
                r = analyze(b.getvalue(), "image/png", dtype)
                got = {k: (r["fields"].get(k) or {}).get("value") for k in expected}
                correct = {k: norm(k, got[k]) == norm(k, v) for k, v in expected.items()}
                wrong_present = [k for k, ok in correct.items() if not ok and got[k]]
                rows.append({
                    "type": dtype, "degradation": dname, "status": r["status"], "detected": r["documentType"],
                    "typeOk": r["documentType"] == dtype, "fieldsOk": sum(correct.values()), "fields": len(correct),
                    "allOk": all(correct.values()),
                    "silentError": r["status"] == "OK" and (not all(correct.values()) or r["documentType"] != dtype),
                    "wrong": {k: [expected[k], got[k]] for k in wrong_present}, "conf": r["overallConfidence"],
                })
    return rows, time.time() - t0


def summarise(rows):
    def agg(sel):
        n = len(sel)
        if not n:
            return {}
        return {
            "docs": n,
            "typeAccuracy": round(sum(r["typeOk"] for r in sel) / n, 3),
            "fieldAccuracy": round(sum(r["fieldsOk"] for r in sel) / sum(r["fields"] for r in sel), 3),
            "allFieldsCorrect": round(sum(r["allOk"] for r in sel) / n, 3),
            "autoAccepted": round(sum(r["status"] == "OK" for r in sel) / n, 3),
            "toReview": round(sum(r["status"] == "NEEDS_REVIEW" for r in sel) / n, 3),
            "rejectedUnreadable": round(sum(r["status"] == "UNREADABLE" for r in sel) / n, 3),
            "silentErrors": sum(r["silentError"] for r in sel),
        }
    by_type = {t: agg([r for r in rows if r["type"] == t]) for t in sorted({r["type"] for r in rows})}
    by_deg = {d: agg([r for r in rows if r["degradation"] == d]) for d in DEGRADATIONS}
    return {"overall": agg(rows), "byType": by_type, "byDegradation": by_deg,
            "silentErrorExamples": [r for r in rows if r["silentError"]][:20]}


if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 6
    out = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else None
    rows, secs = evaluate(n)
    s = summarise(rows)
    s["seconds"] = round(secs, 1)
    s["secondsPerDocument"] = round(secs / len(rows), 2)
    txt = json.dumps(s, indent=2)
    if out:
        with open(out, "w") as fh:
            fh.write(txt)
    print(json.dumps({k: s[k] for k in ("overall", "byDegradation", "byType", "secondsPerDocument")}, indent=1))
    print("silent errors:", json.dumps(s["silentErrorExamples"][:8], indent=1))
