"""AI checks on field-visit photos (FSD FR-FV-05).

Per photo: when and where it was taken according to its EXIF data, the kind of scene (shop, livestock or farm,
vehicle, home) from an ImageNet MobileNetV2 classifier grouped into scene types, an estimate of how full the shelves
are for stock photos, and for the "owner with officer" photo whether a face matches the KYC photo. Photo reuse
across applications is checked by the LOS with perceptual hashes it keeps; nothing here stores images.
"""
import io
import os
from datetime import datetime
from functools import lru_cache

import cv2
import numpy as np
from PIL import ExifTags, Image

from . import vkyc

MODEL_DIR = vkyc.MODEL_DIR
CLASSIFIER = "image_classification_mobilenetv2_2022apr.onnx"

# ImageNet-1k class ids grouped into the scenes a field officer photographs
SCENES = {
    "SHOP": [415, 424, 453, 454, 467, 478, 480, 509, 519, 582, 692, 737, 762, 778, 788, 790, 791, 860, 865, 886, 898],
    "LIVESTOCK": [7, 8, 345, 346, 425, 595, 690, 730, 856, 866, 958],
    "VEHICLE": [468, 569, 612, 654, 656, 665, 670, 675, 717, 734, 864, 867, 870],
    "HOME": [495, 505, 532, 564, 598, 706, 738, 760, 789, 831, 894],
}
MEAN = np.array([0.485, 0.456, 0.406], np.float32)
STD = np.array([0.229, 0.224, 0.225], np.float32)


@lru_cache(maxsize=1)
def _classifier():
    p = os.path.join(MODEL_DIR, CLASSIFIER)
    if not os.path.exists(p):
        return None
    return cv2.dnn.readNet(p)


def classifier_available() -> bool:
    return _classifier() is not None


def scene(img: np.ndarray) -> dict:
    net = _classifier()
    if net is None:
        return {"scene": None, "confidence": 0.0, "scores": {}, "note": "Scene model not installed"}
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    rgb = cv2.resize(rgb, (256, 256))[16:240, 16:240].astype(np.float32) / 255.0
    blob = ((rgb - MEAN) / STD).transpose(2, 0, 1)[np.newaxis].astype(np.float32)
    net.setInput(blob)
    logits = net.forward().reshape(-1)
    p = np.exp(logits - logits.max())
    p /= p.sum()
    scores = {k: round(float(p[ids].sum()), 3) for k, ids in SCENES.items()}
    best = max(scores, key=scores.get)
    conf = scores[best]
    return {"scene": best if conf >= 0.15 else "UNCLEAR", "confidence": conf, "scores": scores}


def shelf_fill(img: np.ndarray) -> dict:
    """Share of an 8x8 grid that is busy with edges: an estimate of how stocked the shelves look, not a count."""
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    g = cv2.resize(g, (512, int(512 * g.shape[0] / g.shape[1])) if g.shape[1] > 512 else (g.shape[1], g.shape[0]))
    edges = cv2.Canny(cv2.GaussianBlur(g, (3, 3), 0), 60, 160)
    h, w = edges.shape
    busy = 0
    for i in range(8):
        for j in range(8):
            cell = edges[i * h // 8:(i + 1) * h // 8, j * w // 8:(j + 1) * w // 8]
            if cell.size and (cell > 0).mean() > 0.06:
                busy += 1
    ratio = busy / 64
    return {"fill": round(ratio, 2), "level": "HIGH" if ratio >= 0.6 else "MEDIUM" if ratio >= 0.3 else "LOW"}


def _ratio(x) -> float:
    try:
        return float(x)
    except TypeError:
        return float(x[0]) / float(x[1])


def exif(data: bytes) -> dict:
    out = {"takenAt": None, "lat": None, "lng": None, "make": None, "software": None}
    try:
        im = Image.open(io.BytesIO(data))
        ex = im.getexif()
    except Exception:
        return out
    if not ex:
        return out
    tags = {ExifTags.TAGS.get(k, k): v for k, v in ex.items()}
    sub = ex.get_ifd(0x8769) if hasattr(ex, "get_ifd") else {}
    dt = sub.get(0x9003) or tags.get("DateTime")
    if dt:
        try:
            out["takenAt"] = datetime.strptime(str(dt), "%Y:%m:%d %H:%M:%S").isoformat()
        except ValueError:
            pass
    out["make"] = tags.get("Make")
    out["software"] = tags.get("Software")
    gps = ex.get_ifd(0x8825) if hasattr(ex, "get_ifd") else {}
    try:
        if gps and 2 in gps and 4 in gps:
            lat = sum(_ratio(v) / d for v, d in zip(gps[2], (1, 60, 3600)))
            lng = sum(_ratio(v) / d for v, d in zip(gps[4], (1, 60, 3600)))
            if gps.get(1) == "S":
                lat = -lat
            if gps.get(3) == "W":
                lng = -lng
            out["lat"], out["lng"] = round(lat, 6), round(lng, 6)
    except Exception:
        pass
    return out


def face_vs_kyc(img: np.ndarray, id_images: list[tuple[str, np.ndarray]]) -> dict:
    if not vkyc.available():
        return {"checked": False, "note": "Face models not installed"}
    faces = vkyc.detect(img)
    if not faces:
        return {"checked": True, "faces": 0, "matched": False, "score": 0.0, "note": "No face in the photo"}
    embs = [vkyc.embedding(img, f) for f in faces[:4]]
    best, against = 0.0, None
    for name, idimg in id_images:
        fs = vkyc.detect(idimg)
        if not fs:
            continue
        e = vkyc.embedding(idimg, fs[0])
        s = max(vkyc.similarity(e, x) for x in embs)
        if s > best:
            best, against = s, name
    if against is None:
        return {"checked": True, "faces": len(faces), "matched": False, "score": 0.0, "note": "No face found on the KYC document photo"}
    return {"checked": True, "faces": len(faces), "matched": best >= vkyc.MATCH_THRESHOLD, "score": round(best, 3),
            "threshold": vkyc.MATCH_THRESHOLD, "against": against}


def analyse(photos: list[tuple[str, str, bytes]], id_images: list[tuple[str, bytes]]) -> dict:
    """photos: [(name, kind, bytes)] with kind front | inside | stock | owner | animals | vehicle | odometer | residence | other."""
    ids = [(n, im) for n, b in id_images if (im := vkyc.decode(b)) is not None]
    out = []
    for name, kind, data in photos:
        img = vkyc.decode(data)
        r = {"name": name, "kind": kind, "readable": img is not None, "exif": exif(data)}
        if img is not None:
            h, w = img.shape[:2]
            r["size"] = [w, h]
            r["scene"] = scene(img)
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            r["brightness"] = round(float(gray.mean()), 1)
            r["sharpness"] = round(float(cv2.Laplacian(gray, cv2.CV_64F).var()), 1)
            if kind in ("inside", "stock"):
                r["shelf"] = shelf_fill(img)
            if kind == "owner":
                r["face"] = face_vs_kyc(img, ids)
        out.append(r)
    return {"photos": out, "sceneModel": classifier_available(), "faceModel": vkyc.available()}
