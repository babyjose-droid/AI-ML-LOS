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


def ocr_page(img: Image.Image, lang: str = "eng") -> Page:
    work = img
    if img.width < 1200:  # Tesseract reads small text better when upscaled
        f = 1200 / img.width
        work = img.resize((1200, int(img.height * f)), Image.LANCZOS)
    else:
        f = 1.0
    gray = ImageOps.grayscale(work)
    d = pytesseract.image_to_data(gray, lang=lang, config="--psm 3", output_type=pytesseract.Output.DICT)
    words = []
    for i, t in enumerate(d["text"]):
        t = (t or "").strip()
        c = float(d["conf"][i])
        if not t or c < 0:
            continue
        box = tuple(int(round(v / f)) for v in (d["left"][i], d["top"][i], d["width"][i], d["height"][i]))
        words.append(Word(t, c / 100.0, box, (d["page_num"][i], d["block_num"][i], d["par_num"][i], d["line_num"][i])))
    return Page(img, words)


def run_ocr(data: bytes, content_type: str, lang: str = "eng") -> OcrResult:
    images, pdf_text, meta, software, fmt = load_pages(data, content_type)
    return OcrResult([ocr_page(im, lang) for im in images], pdf_text, meta, software, fmt)
