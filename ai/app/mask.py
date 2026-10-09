"""Aadhaar masking: black out the first 8 digits on the image and in all text."""
import io
import re

from PIL import Image, ImageDraw

from .ocr import OcrResult


def mask_image(ocr: OcrResult, number: str) -> tuple[bytes | None, bool]:
    """Returns PNG bytes of the first page with the first 8 Aadhaar digits covered, and whether masking succeeded."""
    if not ocr.pages:
        return None, False
    first8 = [number[0:4], number[4:8]]
    masked_any = False
    out_pages = []
    for page in ocr.pages:
        img = page.image.copy()
        d = ImageDraw.Draw(img)
        hits = 0
        for w in page.words:
            digits = re.sub(r"\D", "", w.text)
            if digits in first8 or (len(digits) >= 8 and digits[:8] == number[:8]):
                l, t, wd, ht = w.box
                d.rectangle([l - 4, t - 4, l + wd + 4, t + ht + 4], fill=(0, 0, 0))
                hits += 1
        masked_any = masked_any or hits >= 2 or any(len(re.sub(r"\D", "", w.text)) >= 8 for w in page.words if re.sub(r"\D", "", w.text)[:8] == number[:8])
        out_pages.append(img)
    b = io.BytesIO()
    out_pages[0].save(b, "PNG")
    return b.getvalue(), masked_any


def mask_text(text: str) -> str:
    return re.sub(r"(?<!\d)([2-9][0-9]{3})\s?([0-9]{4})\s?([0-9]{4})(?!\d)", r"XXXX XXXX \3", text)
