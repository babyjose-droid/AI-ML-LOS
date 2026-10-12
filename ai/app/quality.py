"""Image quality checks: blur, resolution, exposure and contrast."""
import cv2
import numpy as np
from PIL import Image

BLUR_THRESHOLD = 20.0  # variance of the Laplacian on a 1000 px wide grey image; below this text is unreliable
SOFT_THRESHOLD = 60.0  # slightly soft: reported, but not a reason for review on its own


def assess(img: Image.Image) -> dict:
    g = np.array(img.convert("L"))
    h, w = g.shape
    scale = 1000 / w
    g2 = cv2.resize(g, (1000, max(1, int(h * scale))), interpolation=cv2.INTER_AREA if scale < 1 else cv2.INTER_CUBIC)
    blur = float(cv2.Laplacian(g2, cv2.CV_64F).var())
    mean, std = float(g.mean()), float(g.std())
    issues = []
    notes = []
    if blur < BLUR_THRESHOLD:
        issues.append("Image is blurred")
    elif blur < SOFT_THRESHOLD:
        notes.append("Image is slightly soft")
    if w < 400 or h < 250:
        issues.append("Resolution is too low")
    elif w < 700 or h < 400:
        notes.append("Resolution is low")
    # lighting problems are corrected before OCR, so they are notes unless the read itself is weak
    if mean < 60:
        notes.append("Image is dark")
    if mean > 245 and std < 20:
        notes.append("Image is washed out")
    if std < 25:
        notes.append("Contrast is low")
    return {"width": w, "height": h, "sharpness": round(blur, 1), "brightness": round(mean, 1),
            "contrast": round(std, 1), "issues": issues, "notes": notes, "ok": not issues}
