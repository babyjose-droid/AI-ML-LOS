"""
Generates SPECIMEN identity documents for fictional people, for tests and demos.

They copy only the text layout that matters for OCR (labels and number formats). They carry no emblems,
logos or security features, and are stamped SPECIMEN, so they cannot be mistaken for real documents.

    python -m tools.specimens out_dir
"""
import io
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

from app.validators import verhoeff_digit, _mrz_val, _W


def _font(size: int, bold: bool = False):
    names = ["DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"]
    for d in ["/usr/share/fonts/truetype/dejavu/", "/usr/share/fonts/dejavu/", ""]:
        for n in names:
            try:
                return ImageFont.truetype(d + n, size)
            except OSError:
                continue
    return ImageFont.load_default(size=size)


def _card(w=1000, h=630, bg=(246, 244, 238)):
    img = Image.new("RGB", (w, h), bg)
    d = ImageDraw.Draw(img)
    d.rectangle([8, 8, w - 9, h - 9], outline=(120, 120, 120), width=3)
    return img, d


def _stamp(img: Image.Image):
    d = ImageDraw.Draw(img)
    w, h = img.size
    d.text((w - 260, h - 60), "SPECIMEN", font=_font(40, True), fill=(205, 60, 60))


def _photo(d: ImageDraw.ImageDraw, x, y, w=180, h=220):
    d.rectangle([x, y, x + w, y + h], fill=(210, 214, 222), outline=(150, 150, 150), width=2)
    d.ellipse([x + 50, y + 30, x + w - 50, y + 110], fill=(170, 176, 190))
    d.rectangle([x + 30, y + 120, x + w - 30, y + h - 20], fill=(170, 176, 190))


def aadhaar_number(seed: str) -> str:
    base = ("2" + "".join(str((ord(c) * 7) % 10) for c in seed) + "0" * 11)[:11]
    return base + verhoeff_digit(base)


def pan_card(name, father, dob, pan) -> Image.Image:
    img, d = _card()
    d.text((40, 30), "INCOME TAX DEPARTMENT", font=_font(34, True), fill=(30, 30, 30))
    d.text((40, 80), "Permanent Account Number Card", font=_font(26), fill=(30, 30, 30))
    d.text((40, 150), pan, font=_font(46, True), fill=(10, 10, 10))
    d.text((40, 240), "Name", font=_font(22), fill=(80, 80, 80))
    d.text((40, 270), name.upper(), font=_font(34, True), fill=(10, 10, 10))
    d.text((40, 330), "Father's Name", font=_font(22), fill=(80, 80, 80))
    d.text((40, 360), father.upper(), font=_font(32), fill=(10, 10, 10))
    d.text((40, 420), "Date of Birth", font=_font(22), fill=(80, 80, 80))
    d.text((40, 450), dob, font=_font(34, True), fill=(10, 10, 10))
    _photo(d, 770, 150)
    _stamp(img)
    return img


def aadhaar_card(name, dob, gender, number, address=None) -> Image.Image:
    img, d = _card(1000, 760 if address else 630)
    d.text((40, 30), "Government of India", font=_font(32, True), fill=(30, 30, 30))
    _photo(d, 40, 100)
    d.text((260, 110), name, font=_font(34, True), fill=(10, 10, 10))
    d.text((260, 170), f"DOB: {dob}", font=_font(30), fill=(10, 10, 10))
    d.text((260, 220), gender.upper(), font=_font(30), fill=(10, 10, 10))
    if address:
        d.text((260, 280), "Address:", font=_font(24), fill=(80, 80, 80))
        y = 315
        for line in address:
            d.text((260, y), line, font=_font(26), fill=(10, 10, 10))
            y += 36
    n = number
    d.text((300, (650 if address else 480)), f"{n[0:4]} {n[4:8]} {n[8:12]}", font=_font(48, True), fill=(10, 10, 10))
    d.text((340, (710 if address else 545)), "Aadhaar - Mera Aadhaar, Meri Pehchaan", font=_font(20), fill=(90, 90, 90))
    _stamp(img)
    return img


def voter_card(name, relation, dob, epic) -> Image.Image:
    img, d = _card()
    d.text((40, 30), "ELECTION COMMISSION OF INDIA", font=_font(32, True), fill=(30, 30, 30))
    d.text((40, 80), "Elector Photo Identity Card", font=_font(26), fill=(30, 30, 30))
    d.text((40, 140), epic, font=_font(42, True), fill=(10, 10, 10))
    d.text((40, 230), f"Elector's Name: {name}", font=_font(30), fill=(10, 10, 10))
    d.text((40, 290), f"Father's Name: {relation}", font=_font(30), fill=(10, 10, 10))
    d.text((40, 350), f"Date of Birth: {dob}", font=_font(30), fill=(10, 10, 10))
    _photo(d, 770, 150)
    _stamp(img)
    return img


def driving_licence(name, dob, dl_no, address) -> Image.Image:
    img, d = _card()
    d.text((40, 30), "DRIVING LICENCE", font=_font(34, True), fill=(30, 30, 30))
    d.text((40, 85), "Union of India", font=_font(24), fill=(30, 30, 30))
    d.text((40, 140), f"DL No: {dl_no}", font=_font(36, True), fill=(10, 10, 10))
    d.text((40, 220), f"Name: {name}", font=_font(30), fill=(10, 10, 10))
    d.text((40, 280), f"DOB: {dob}", font=_font(30), fill=(10, 10, 10))
    d.text((40, 340), f"Address: {address}", font=_font(26), fill=(10, 10, 10))
    d.text((40, 400), "Valid Till: 31/12/2040", font=_font(26), fill=(10, 10, 10))
    _photo(d, 770, 150)
    _stamp(img)
    return img


def _check(s: str) -> str:
    return str(sum(_mrz_val(c) * _W[i % 3] for i, c in enumerate(s)) % 10)


def passport(surname, given, dob_yymmdd, exp_yymmdd, number, sex="F") -> Image.Image:
    img, d = _card(1100, 700)
    d.text((40, 30), "REPUBLIC OF INDIA", font=_font(32, True), fill=(30, 30, 30))
    d.text((40, 80), "PASSPORT", font=_font(28), fill=(30, 30, 30))
    _photo(d, 40, 140)
    d.text((260, 140), f"Passport No. {number}", font=_font(30, True), fill=(10, 10, 10))
    d.text((260, 200), f"Surname: {surname}", font=_font(28), fill=(10, 10, 10))
    d.text((260, 250), f"Given Name(s): {given}", font=_font(28), fill=(10, 10, 10))
    l1 = ("P<IND" + surname.replace(" ", "<") + "<<" + given.replace(" ", "<")).ljust(44, "<")[:44]
    num = number.ljust(9, "<")
    body = num + _check(num) + "IND" + dob_yymmdd + _check(dob_yymmdd) + sex + exp_yymmdd + _check(exp_yymmdd)
    l2 = (body + "<" * 14 + "0").ljust(42, "<")[:42]
    l2 = l2 + _check(l2[0:10] + l2[13:20] + l2[21:42]) + "0"
    l2 = l2[:44]
    mono = None
    for path in ["/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"]:
        try:
            mono = ImageFont.truetype(path, 34)
            break
        except OSError:
            continue
    mono = mono or _font(30)
    d.rectangle([20, 520, 1080, 680], fill=(255, 255, 255))
    d.text((30, 540), l1, font=mono, fill=(0, 0, 0))
    d.text((30, 600), l2, font=mono, fill=(0, 0, 0))
    _stamp(img)
    return img


def udyam_certificate(name, enterprise, udyam_no) -> Image.Image:
    img, d = _card(1000, 700, (255, 255, 255))
    d.text((40, 30), "UDYAM REGISTRATION CERTIFICATE", font=_font(32, True), fill=(30, 30, 30))
    d.text((40, 120), f"UDYAM REGISTRATION NUMBER {udyam_no}", font=_font(28, True), fill=(10, 10, 10))
    d.text((40, 200), f"NAME OF ENTERPRISE {enterprise}", font=_font(28), fill=(10, 10, 10))
    d.text((40, 260), "TYPE OF ENTERPRISE Micro", font=_font(28), fill=(10, 10, 10))
    d.text((40, 320), f"NAME OF ENTREPRENEUR {name}", font=_font(28), fill=(10, 10, 10))
    d.text((40, 380), "MAJOR ACTIVITY Trading", font=_font(28), fill=(10, 10, 10))
    _stamp(img)
    return img


def salary_slip(name, employer, month, gross, net) -> Image.Image:
    img, d = _card(1000, 700, (255, 255, 255))
    d.text((40, 30), employer, font=_font(32, True), fill=(30, 30, 30))
    d.text((40, 90), f"Salary Slip for {month}", font=_font(28), fill=(10, 10, 10))
    d.text((40, 170), f"Employee Name: {name}", font=_font(28), fill=(10, 10, 10))
    d.text((40, 230), f"Gross Earnings: {gross:,}", font=_font(28), fill=(10, 10, 10))
    d.text((40, 290), f"Total Deductions: {gross - net:,}", font=_font(28), fill=(10, 10, 10))
    d.text((40, 350), f"Net Pay: {net:,}", font=_font(30, True), fill=(10, 10, 10))
    _stamp(img)
    return img


def blurred(img: Image.Image, radius=6) -> Image.Image:
    return img.filter(ImageFilter.GaussianBlur(radius))


def png(img: Image.Image) -> bytes:
    b = io.BytesIO()
    img.save(b, "PNG")
    return b.getvalue()


def jpeg(img: Image.Image, software: str | None = None) -> bytes:
    b = io.BytesIO()
    exif = Image.Exif()
    if software:
        exif[0x0131] = software
    img.save(b, "JPEG", quality=92, exif=exif)
    return b.getvalue()


def demo_set() -> dict[str, bytes]:
    """Specimen documents for the demo personas and tests."""
    out = {}
    people = [
        ("ramesh", "Ramesh Patil", "Vitthal Patil", "15/06/1985", "RAMPP1001R", "MALE", ["12 Main Road, Gangapur", "Nashik, Maharashtra 422013"]),
        ("priya", "Priya Nair", "Gopalan Nair", "02/03/1992", "PRIPN1002P", "FEMALE", ["4 Lake View, Kadavanthra", "Kochi, Kerala 682020"]),
        ("lakshmi", "Lakshmi Devi", "Murugan K", "20/01/1990", "LAKPD1003L", "FEMALE", ["Door 7, Anna Nagar", "Madurai, Tamil Nadu 625020"]),
        ("suresh", "Suresh Yadav", "Ram Yadav", "11/09/1981", "SURPY1004S", "MALE", ["Plot 7, Transport Nagar", "Kanpur, Uttar Pradesh 208023"]),
        ("meera", "Meera Shah", "Dinesh Shah", "05/12/1988", "MEEPS9201M", "FEMALE", ["22 Market Lane", "Nashik, Maharashtra 422001"]),
        ("deepa", "Deepa Menon", "Ravi Menon", "15/04/1990", "DEEPM4321D", "FEMALE", ["9 Hill Road, Kothrud", "Pune, Maharashtra 411038"]),
        ("anil", "Anil Kumar", "Suresh Kumar", "10/07/1997", "ANIPK1005A", "MALE", ["5 Station Road, Shivajinagar", "Pune, Maharashtra 411005"]),
    ]
    for key, name, father, dob, pan, gender, addr in people:
        out[f"{key}-pan.png"] = png(pan_card(name, father, dob, pan))
        out[f"{key}-aadhaar.png"] = png(aadhaar_card(name, dob, gender, aadhaar_number(pan), addr))
    out["ramesh-udyam.png"] = png(udyam_certificate("Ramesh Patil", "PATIL KIRANA STORES", "UDYAM-MH-20-0012345"))
    out["suresh-udyam.png"] = png(udyam_certificate("Suresh Yadav", "YADAV TRANSPORT", "UDYAM-UP-43-0054321"))
    out["meera-udyam.png"] = png(udyam_certificate("Meera Shah", "SHAH TAILORING", "UDYAM-MH-20-0098765"))
    out["priya-salary.png"] = png(salary_slip("Priya Nair", "Coastal Systems Pvt Ltd", "August 2026", 92000, 72000))
    out["deepa-salary.png"] = png(salary_slip("Deepa Menon", "Western Tools Ltd", "August 2026", 98000, 80000))
    # an edited salary slip: saved from photo-editing software, which the tamper check picks up
    out["anil-salary-slip.jpg"] = jpeg(salary_slip("Anil Kumar", "Bright Sparks Pvt Ltd", "August 2026", 120000, 95000), "Adobe Photoshop 25.0 (Windows)")
    out["priya-voter.png"] = png(voter_card("Priya Nair", "Gopalan Nair", "02/03/1992", "KLX1234567"))
    out["ramesh-dl.png"] = png(driving_licence("Ramesh Patil", "15/06/1985", "MH15 20050012345", "12 Main Road, Nashik"))
    out["priya-passport.png"] = png(passport("NAIR", "PRIYA", "920302", "320915", "Z1234567"))
    return out


if __name__ == "__main__":
    target = Path(sys.argv[1] if len(sys.argv) > 1 else "specimens")
    target.mkdir(parents=True, exist_ok=True)
    for name, data in demo_set().items():
        (target / name).write_bytes(data)
    print(f"wrote {len(list(target.iterdir()))} files to {target}")
