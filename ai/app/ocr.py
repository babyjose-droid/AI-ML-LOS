"""Turns uploaded bytes into page images and OCR text with word boxes and confidences (Tesseract)."""
import io
from dataclasses import dataclass, field

import numpy as np
import pytesseract
from PIL import Image, ImageOps

MAX_PAGES = 3


@dataclass
class Word:
    text: str
    conf: float  # 0..1
    box: tuple[int, int, int, int]  # left, top, width, height (in page image pixels)
    line: tuple[int, int, int, int]  # (page, block, paragraph, line)


@dataclass
class Page:
    image: Image.Image
    words: list[Word] = field(default_factory=list)

    @property
    def lines(self) -> list[str]:
        out: dict[tuple, list[str]] = {}
        for w in self.words:
            out.setdefault(w.line, []).append(w.text)
        return [" ".join(v) for v in out.values()]


@dataclass
class OcrResult:
    pages: list[Page]
    pdf_text: str = ""
    pdf_meta: dict = field(default_factory=dict)
    exif_software: str | None = None
    source_format: str = "image"

    @property
    def text(self) -> str:
        if self.pdf_text.strip() and len(self.pdf_text.strip()) > 40:
            return self.pdf_text
        return "\n".join(ln for p in self.pages for ln in p.lines)

    @property
    def lines(self) -> list[str]:
        return [ln.strip() for ln in self.text.splitlines() if ln.strip()]

    @property
    def mean_conf(self) -> float:
        ws = [w.conf for p in self.pages for w in p.words if w.text.strip()]
        return float(np.mean(ws)) if ws else 0.0


def load_pages(data: bytes, content_type: str) -> tuple[list[Image.Image], str, dict, str | None, str]:
    """Returns page images, PDF text layer, PDF metadata, EXIF software tag and source format."""
    if content_type == "application/pdf" or data[:5] == b"%PDF-":
        import pypdfium2 as pdfium
        from pypdf import PdfReader

        text, meta = "", {}
        try:
            r = PdfReader(io.BytesIO(data))
            text = "\n".join((pg.extract_text() or "") for pg in r.pages[:MAX_PAGES])
            if r.metadata:
                meta = {k.lstrip("/"): str(v) for k, v in r.metadata.items()}
        except Exception:  # noqa: BLE001 - a broken text layer is not fatal; OCR still runs
            pass
        pages = []
        try:
            pdf = pdfium.PdfDocument(data)
            for i in range(min(len(pdf), MAX_PAGES)):
                pages.append(pdf[i].render(scale=200 / 72).to_pil().convert("RGB"))
        except Exception:  # noqa: BLE001
            pass
        return pages, text, meta, None, "pdf"
    img = Image.open(io.BytesIO(data))
    fmt = (img.format or "image").lower()
    software = None
    try:
        software = img.getexif().get(0x0131)
    except Exception:  # noqa: BLE001
        pass
    img = ImageOps.exif_transpose(img).convert("RGB")
    return [img], "", {}, software, fmt


def _read(gray: Image.Image, f: float, lang: str) -> list[Word]:
    d = pytesseract.image_to_data(gray, lang=lang, config="--psm 3", output_type=pytesseract.Output.DICT)
    words = []
    for i, t in enumerate(d["text"]):
        t = (t or "").strip()
        c = float(d["conf"][i])
        if not t or c < 0:
            continue
        box = tuple(int(round(v / f)) for v in (d["left"][i], d["top"][i], d["width"][i], d["height"][i]))
        words.append(Word(t, c / 100.0, box, (d["page_num"][i], d["block_num"][i], d["par_num"][i], d["line_num"][i])))
    return words


def _score(words: list[Word]) -> float:
    """Reading quality: confident words, rewarded for having enough of them."""
    good = [w for w in words if w.conf >= 0.6 and len(w.text) >= 2]
    if not good:
        return 0.0
    return float(np.mean([w.conf for w in good])) * min(1.0, len(good) / 12)


def normalise_light(gray: Image.Image) -> Image.Image:
    """Flattens uneven or dim lighting: divide by a heavily blurred copy (the background), then stretch."""
    import cv2
    a = np.array(gray).astype(np.float32)
    bg = cv2.GaussianBlur(a, (0, 0), sigmaX=max(15, a.shape[1] / 30))
    flat = np.clip(a / (bg + 1) * 235, 0, 255)
    lo, hi = np.percentile(flat, 1), np.percentile(flat, 99)
    flat = np.clip((flat - lo) / max(1, hi - lo) * 255, 0, 255)
    return Image.fromarray(flat.astype("uint8"))


def deskew_angle(gray: Image.Image) -> float:
    """Small skew angle in degrees from the dominant direction of text pixels (0 if unsure)."""
    import cv2
    a = np.array(gray)
    th = cv2.threshold(a, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)[1]
    th = cv2.dilate(th, np.ones((3, 25), np.uint8))
    lines = cv2.HoughLinesP(cv2.Canny(th, 50, 150), 1, np.pi / 720, threshold=120, minLineLength=a.shape[1] // 6, maxLineGap=20)
    if lines is None:
        return 0.0
    angles = [np.degrees(np.arctan2(y2 - y1, x2 - x1)) for x1, y1, x2, y2 in lines.reshape(-1, 4) if abs(x2 - x1) > 0]
    angles = [x for x in angles if abs(x) < 15]
    if len(angles) < 3:
        return 0.0
    ang = float(np.median(angles))
    return ang if 0.4 < abs(ang) < 15 else 0.0


def osd_rotation(gray: Image.Image) -> int:
    try:
        out = pytesseract.image_to_osd(gray, config="--psm 0")
        for ln in out.splitlines():
            if ln.startswith("Rotate:"):
                return int(ln.split(":")[1]) % 360
    except Exception:  # noqa: BLE001 - OSD fails on sparse text; the caller then tries every rotation
        return -1
    return 0


GOOD_ENOUGH = 0.82


def ocr_page(img: Image.Image, lang: str = "eng") -> Page:
    """
    Reads one page. Clean, upright scans take a single pass. Weak reads are retried with lighting
    normalisation, deskew and orientation correction, and the best attempt wins. The returned page image
    is the corrected one, so word boxes line up with it.
    """
    def prep(im: Image.Image):
        f = 1200 / im.width if im.width < 1200 else 1.0
        work = im.resize((1200, int(im.height * f)), Image.LANCZOS) if f != 1.0 else im
        return ImageOps.grayscale(work), f

    gray, f = prep(img)
    best_img, best_words = img, _read(gray, f, lang)
    best = _score(best_words)
    if best >= GOOD_ENOUGH:
        return Page(best_img, best_words)

    def attempt(candidate: Image.Image, normalise: bool):
        nonlocal best, best_img, best_words
        g, ff = prep(candidate)
        if normalise:
            g = normalise_light(g)
        w = _read(g, ff, lang)
        sc = _score(w)
        if sc > best + 0.02:
            best, best_img, best_words = sc, candidate, w

    # 1. lighting
    attempt(img, True)
    # 2. orientation
    if best < GOOD_ENOUGH:
        rot = osd_rotation(normalise_light(gray))
        rotations = [rot] if rot > 0 else ([90, 180, 270] if rot < 0 or best < 0.4 else [])
        for r in rotations:
            cand = img.rotate(-r, expand=True)  # OSD reports clockwise rotation needed
            attempt(cand, False)
            attempt(cand, True)
            if best >= GOOD_ENOUGH:
                break
    # 3. small skew
    if best < GOOD_ENOUGH:
        g, _ = prep(best_img)
        ang = deskew_angle(g)
        if ang:
            cand = best_img.rotate(ang, expand=True, fillcolor=(255, 255, 255), resample=Image.BICUBIC)
            attempt(cand, False)
            attempt(cand, True)
    return Page(best_img, best_words)


def run_ocr(data: bytes, content_type: str, lang: str = "eng") -> OcrResult:
    images, pdf_text, meta, software, fmt = load_pages(data, content_type)
    return OcrResult([ocr_page(im, lang) for im in images], pdf_text, meta, software, fmt)
