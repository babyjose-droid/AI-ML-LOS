"""Loan documents: HTML (from the LOS template engine) to PDF, and Word (.docx) templates to HTML.

PDF is made with WeasyPrint (Pango shapes Indian scripts correctly; Noto fonts are installed in the image). Nothing is
fetched from the network while rendering: only data: URLs (embedded logos) are allowed.
"""
import io
import re

BASE_CSS = """
@page { size: A4; margin: 20mm 18mm 22mm 18mm;
  @bottom-left { content: string(doctitle); font-size: 8pt; color: #666; }
  @bottom-right { content: "Page " counter(page) " of " counter(pages); font-size: 8pt; color: #666; } }
html { font-family: "Noto Sans", "Noto Sans Devanagari", "Noto Sans Tamil", "Noto Sans Telugu", "Noto Sans Kannada",
       "Noto Sans Malayalam", "Noto Sans Bengali", "Noto Sans Gujarati", "DejaVu Sans", sans-serif; font-size: 10pt; line-height: 1.45; color: #111; }
h1 { font-size: 15pt; margin: 0 0 8pt; string-set: doctitle content(); }
h2 { font-size: 12pt; margin: 14pt 0 6pt; }
h3 { font-size: 10.5pt; margin: 10pt 0 4pt; }
table { width: 100%; border-collapse: collapse; margin: 6pt 0 10pt; page-break-inside: auto; }
th, td { border: 0.6pt solid #999; padding: 3pt 5pt; text-align: left; vertical-align: top; }
th { background: #f1f1f1; }
tr { page-break-inside: avoid; }
.sign { margin-top: 28pt; display: flex; gap: 24pt; flex-wrap: wrap; }
.sign div { min-width: 150pt; border-top: 0.6pt solid #333; padding-top: 3pt; font-size: 9pt; }
.small { font-size: 8.5pt; color: #444; }
.pagebreak { page-break-before: always; }
"""


_EXTERNAL = re.compile(r"""(src|href)\s*=\s*(["'])(?!data:|#)[^"']*\2""", re.I)
_DANGEROUS = re.compile(r"<\s*(script|iframe|object|embed|link|meta|base|style)\b[^>]*>(.*?<\s*/\s*\1\s*>)?", re.I | re.S)


def sanitise(html: str) -> str:
    """No scripts and no external resources: only embedded (data:) images and in-page links survive."""
    return _EXTERNAL.sub(r'\1=""', _DANGEROUS.sub("", html))


def render_pdf(html: str, title: str | None = None) -> bytes:
    from weasyprint import CSS, HTML
    doc = f"<!doctype html><html><head><meta charset='utf-8'><title>{_esc(title or 'Loan document')}</title></head><body>{sanitise(html)}</body></html>"
    kwargs = {}
    try:
        from weasyprint.urls import URLFetcher
        kwargs["url_fetcher"] = URLFetcher(allowed_protocols={"data"})
    except Exception:  # noqa: BLE001 - older WeasyPrint: the HTML is already stripped of external references
        pass
    return HTML(string=doc, **kwargs).write_pdf(stylesheets=[CSS(string=BASE_CSS)])


def _esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


# Word sometimes splits a typed {{field}} across runs; mammoth joins runs, but smart quotes and stray tags inside the
# braces need cleaning so the template engine sees {{applicant.name}}.
_FIELD = re.compile(r"\{\{(.*?)\}\}", re.S)


def _clean_field(m: re.Match) -> str:
    tags = "".join(re.findall(r"<[^>]+>", m.group(1)))   # formatting Word put inside the braces moves after the field
    inner = re.sub(r"<[^>]+>", "", m.group(1))
    inner = inner.replace("“", '"').replace("”", '"').replace("’", "'").replace("\xa0", " ").strip()
    return "{{" + inner + "}}" + tags


def docx_to_html(data: bytes) -> dict:
    import mammoth
    style_map = "p[style-name='Title'] => h1:fresh\np[style-name='Heading 1'] => h1:fresh\np[style-name='Heading 2'] => h2:fresh\np[style-name='Heading 3'] => h3:fresh"
    result = mammoth.convert_to_html(io.BytesIO(data), style_map=style_map)
    html = _FIELD.sub(_clean_field, result.value)
    fields = sorted({m.group(1).strip() for m in _FIELD.finditer(html)})
    return {"html": html, "fields": fields, "messages": [str(m.message) for m in result.messages][:20]}
