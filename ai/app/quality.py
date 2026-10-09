"""Image quality checks: blur, resolution, exposure and contrast."""
import cv2
import numpy as np
from PIL import Image

BLUR_THRESHOLD = 60.0  # variance of the Laplacian on a 1000 px wide grey image; lower means blurrier


def assess(img: Image.Image) -> dict:
    g = np.array(img.convert("L"))
    h, w = g.shape
    scale = 1000 / w
    g2 = cv2.resize(g, (1000, max(1, int(h * scale))), interpolation=cv2.INTER_AREA if scale < 1 else cv2.INTER_CUBIC)
    blur = float(cv2.Laplacian(g2, cv2.CV_64F).var())
    mean, std = float(g.mean()), float(g.std())
    issues = []
    if blur < BLUR_THRESHOLD:
        issues.append("Image is blurred")
    if w < 600 or h < 350:
        issues.append("Resolution is too low")
    if mean < 60:
        issues.append("Image is too dark")
    if mean > 245 and std < 20:
        issues.append("Image is washed out")
    if std < 25:
        issues.append("Contrast is too low")
    return {"width": w, "height": h, "sharpness": round(blur, 1), "brightness": round(mean, 1),
            "contrast": round(std, 1), "issues": issues, "ok": not issues}
