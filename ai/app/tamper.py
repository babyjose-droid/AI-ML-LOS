"""Signals that a file may have been edited. Signals are evidence for a reviewer, not a verdict."""
import re

EDITORS = ["photoshop", "gimp", "canva", "picsart", "snapseed", "pixlr", "paint.net", "affinity", "illustrator",
           "sejda", "pdfescape", "foxit phantompdf", "pdf-xchange editor", "smallpdf", "ilovepdf edit", "acrobat pro"]


def signals(ocr, text: str, allow_specimen: bool) -> dict:
    out: list[dict] = []
    sw = (ocr.exif_software or "").lower()
    if sw and any(e in sw for e in EDITORS):
        out.append({"signal": f"Image was saved by editing software ({ocr.exif_software})", "weight": 0.5})
    meta = {k.lower(): str(v) for k, v in (ocr.pdf_meta or {}).items()}
    prod = (meta.get("producer", "") + " " + meta.get("creator", "")).lower()
    if any(e in prod for e in EDITORS):
        out.append({"signal": f"PDF was produced by an editing tool ({prod.strip()[:60]})", "weight": 0.5})
    cd, md = meta.get("creationdate"), meta.get("moddate")
    if cd and md and cd[:16] != md[:16]:
        out.append({"signal": "PDF was modified after it was created", "weight": 0.25})
    if ocr.source_format == "pdf" and ocr.pdf_text.strip() and ocr.pages:
        # a statement or slip whose text layer and image disagree is a classic edit pattern
        pdf_tokens = set(re.findall(r"[0-9]{3,}", ocr.pdf_text))
        img_tokens = set(re.findall(r"[0-9]{3,}", "\n".join(ln for p in ocr.pages for ln in p.lines)))
        if len(img_tokens) >= 5 and pdf_tokens and len(pdf_tokens & img_tokens) / max(1, len(img_tokens)) < 0.5:
            out.append({"signal": "Numbers in the PDF text layer differ from what is printed on the page", "weight": 0.6})
    if re.search(r"S\s*P\s*E\s*C\s*I\s*M\s*E\s*N", text.upper()) and not allow_specimen:
        out.append({"signal": "Document is marked SPECIMEN", "weight": 1.0})
    score = min(1.0, sum(s["weight"] for s in out))
    return {"score": round(score, 2), "signals": [s["signal"] for s in out]}
