"""
Video KYC (V-CIP) assistance: face detection, liveness from the officer's challenges, face match with the ID photo,
and reading the PAN card the customer shows on camera.

Models (downloaded at image build time, see Dockerfile; both licensed for commercial use):
  - YuNet face detector (MIT), opencv_zoo face_detection_yunet_2023mar.onnx
  - SFace face recogniser (Apache 2.0), opencv_zoo face_recognition_sface_2021dec.onnx

The AI only assists. Under RBI's V-CIP rules the decision is taken by a trained official of the lender, who sees all
of these results next to the live video and the recording.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache

import cv2
import numpy as np

MODEL_DIR = os.environ.get("VKYC_MODEL_DIR", os.path.join(os.path.dirname(__file__), "..", "models"))
DETECTOR = "face_detection_yunet_2023mar.onnx"
RECOGNISER = "face_recognition_sface_2021dec.onnx"

# SFace cosine similarity: the model authors' threshold for "same person" is 0.363. We auto-accept only well above it.
MATCH_THRESHOLD = float(os.environ.get("VKYC_MATCH_THRESHOLD", "0.363"))
STRONG_MATCH = float(os.environ.get("VKYC_STRONG_MATCH", "0.45"))
TURN = 0.22          # |yaw ratio| that counts as a head turn
FRONT = 0.12         # |yaw ratio| that counts as facing the camera
STEPS = ("front", "left", "right", "pan", "code")


class ModelsMissing(RuntimeError):
    pass


@lru_cache(maxsize=1)
def _models():
    d, r = os.path.join(MODEL_DIR, DETECTOR), os.path.join(MODEL_DIR, RECOGNISER)
    if not (os.path.exists(d) and os.path.exists(r)):
        raise ModelsMissing(f"Face models not found in {MODEL_DIR}")
    det = cv2.FaceDetectorYN.create(d, "", (320, 320), 0.75, 0.3, 5000)
    rec = cv2.FaceRecognizerSF.create(r, "")
    return det, rec


def available() -> bool:
    try:
        _models()
        return True
    except ModelsMissing:
        return False


@dataclass
class Face:
    box: tuple[float, float, float, float]
    landmarks: np.ndarray       # 5x2: right eye, left eye, nose, right mouth corner, left mouth corner (image coords)
    score: float
    raw: np.ndarray

    @property
    def yaw(self) -> float:
        """Nose offset from the eye midpoint, in units of eye distance: ~0 facing the camera, +/-0.3 or more when turned."""
        re, le, nose = self.landmarks[0], self.landmarks[1], self.landmarks[2]
        dist = float(np.linalg.norm(le - re)) or 1.0
        return float((nose[0] - (re[0] + le[0]) / 2) / dist)

    @property
    def area(self) -> float:
        return float(self.box[2] * self.box[3])


def decode(data: bytes) -> np.ndarray | None:
    arr = np.frombuffer(data, np.uint8)
    return cv2.imdecode(arr, cv2.IMREAD_COLOR)


def detect(img: np.ndarray) -> list[Face]:
    det, _ = _models()
    h, w = img.shape[:2]
    scale = 1.0
    if max(h, w) > 1280:  # keep detection fast on large photos
        scale = 1280 / max(h, w)
        img = cv2.resize(img, (int(w * scale), int(h * scale)))
        h, w = img.shape[:2]
    det.setInputSize((w, h))
    _, found = det.detect(img)
    out = []
    for f in ([] if found is None else found):
        f = f.copy()
        f[:14] /= scale
        out.append(Face(tuple(float(x) for x in f[:4]), f[4:14].reshape(5, 2), float(f[14]), f))
    return sorted(out, key=lambda x: -x.area)


def embedding(img: np.ndarray, face: Face) -> np.ndarray:
    _, rec = _models()
    return rec.feature(rec.alignCrop(img, face.raw))


def similarity(a: np.ndarray, b: np.ndarray) -> float:
    _, rec = _models()
    return float(rec.match(a, b, cv2.FaceRecognizerSF_FR_COSINE))


def frame_quality(img: np.ndarray, face: Face | None) -> dict:
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    out = {"brightness": round(float(gray.mean()), 1)}
    if face:
        x, y, w, h = (int(max(0, v)) for v in face.box)
        crop = gray[y:y + h, x:x + w]
        if crop.size:
            out["sharpness"] = round(float(cv2.Laplacian(crop, cv2.CV_64F).var()), 1)
        out["faceShare"] = round(face.area / (img.shape[0] * img.shape[1]), 3)
    return out


def liveness(frames: list[tuple[str, np.ndarray, list[Face]]]) -> dict:
    """
    Checks the challenge frames the officer captured: a face in every frame, only one face, facing the camera on
    'front', a clear head turn one way on 'left' and the other way on 'right', and natural movement between frames
    (a printed photo or a frozen screen hardly changes).
    """
    reasons, checks = [], []
    with_face = [(s, img, fs) for s, img, fs in frames if fs]
    present = len(with_face) / len(frames) if frames else 0.0
    multi = sum(1 for _, _, fs in frames if len(fs) > 1)
    yaws = {s: [round(fs[0].yaw, 3) for st, _, fs in frames if st == s and fs] for s in ("front", "left", "right")}

    def add(name, ok, detail):
        checks.append({"check": name, "pass": bool(ok), "detail": detail})
        if not ok:
            reasons.append(f"{name}: {detail}")

    add("Face visible", present >= 0.9, f"face found in {round(present * 100)}% of {len(frames)} frames")
    add("Only one person", multi == 0, "another face appeared" if multi else "one face throughout")
    front_ok = any(abs(y) <= FRONT for y in yaws["front"])
    add("Faces the camera", front_ok, "frontal frame captured" if front_ok else "no frame looking straight at the camera")
    lmax = max(yaws["left"], key=abs, default=0.0)
    rmax = max(yaws["right"], key=abs, default=0.0)
    turned = abs(lmax) >= TURN and abs(rmax) >= TURN and np.sign(lmax) != np.sign(rmax)
    add("Head turn left and right", turned,
        f"turn measured {lmax:+.2f} then {rmax:+.2f}" if (yaws["left"] and yaws["right"]) else "turn frames missing")
    # movement: compare aligned face crops across frames
    crops = []
    for _, img, fs in with_face:
        x, y, w, h = (int(max(0, v)) for v in fs[0].box)
        c = img[y:y + h, x:x + w]
        if c.size:
            crops.append(cv2.resize(cv2.cvtColor(c, cv2.COLOR_BGR2GRAY), (64, 64)).astype(np.float32))
    motion = float(np.mean([np.abs(a - b).mean() for a, b in zip(crops, crops[1:])])) if len(crops) > 1 else 0.0
    add("Natural movement", motion >= 3.0, f"frame-to-frame change {motion:.1f}" + ("" if motion >= 3.0 else " (looks like a still photo or frozen video)"))
    return {"passed": not reasons, "checks": checks, "reasons": reasons, "facePresent": round(present, 3),
            "yaw": yaws, "motion": round(motion, 2)}


def face_match(frames: list[tuple[str, np.ndarray, list[Face]]], id_images: list[tuple[str, np.ndarray]]) -> dict:
    """Best similarity between the frontal live frames and the photo on each ID document."""
    live = [(img, fs[0]) for s, img, fs in frames if fs and s in ("front", "code")] or [(img, fs[0]) for _, img, fs in frames if fs]
    if not live:
        return {"matched": False, "score": 0.0, "threshold": MATCH_THRESHOLD, "against": None, "results": [],
                "reason": "No face in the live video frames"}
    live = sorted(live, key=lambda t: abs(t[1].yaw))[:3]
    live_emb = [embedding(img, f) for img, f in live]
    results = []
    for name, img in id_images:
        fs = detect(img)
        if not fs:
            results.append({"document": name, "faceFound": False, "score": None})
            continue
        e = embedding(img, fs[0])
        s = max(similarity(e, x) for x in live_emb)
        results.append({"document": name, "faceFound": True, "score": round(s, 3)})
    scored = [r for r in results if r["score"] is not None]
    if not scored:
        return {"matched": False, "score": 0.0, "threshold": MATCH_THRESHOLD, "against": None, "results": results,
                "reason": "No face found on the ID document photo"}
    best = max(scored, key=lambda r: r["score"])
    return {"matched": best["score"] >= MATCH_THRESHOLD, "strong": best["score"] >= STRONG_MATCH, "score": best["score"],
            "threshold": MATCH_THRESHOLD, "against": best["document"], "results": results,
            "reason": None if best["score"] >= MATCH_THRESHOLD else "Live face does not match the ID photo"}


def analyse(frames_in: list[tuple[str, bytes]], id_in: list[tuple[str, bytes]], read_pan=None, expected_pan: str | None = None) -> dict:
    """
    frames_in: (step, jpeg) captured during the call; step is front, left, right, pan or code.
    id_in: (document type, image) of the ID documents on file (PAN, masked Aadhaar...).
    read_pan: optional callable(bytes) -> PAN number read from a frame (the existing document reader).
    """
    frames, unreadable = [], 0
    for step, data in frames_in:
        img = decode(data)
        if img is None:
            unreadable += 1
            continue
        frames.append((step, img, detect(img) if step != "pan" else []))
    face_frames = [f for f in frames if f[0] != "pan"]
    live = liveness(face_frames)
    ids = [(n, img) for n, d in id_in if (img := decode(d)) is not None]
    match = face_match(face_frames, ids)

    pan_check = None
    pan_frames = [d for s, d in frames_in if s == "pan"]
    if pan_frames and read_pan:
        found = None
        for d in pan_frames:
            found = read_pan(d)
            if found:
                break
        pan_check = {"found": found, "expected": expected_pan,
                     "matches": bool(found and expected_pan and found.upper() == expected_pan.upper())}

    quality = [dict(step=s, faces=len(fs), **frame_quality(img, fs[0] if fs else None)) for s, img, fs in face_frames]
    reasons = list(live["reasons"])
    if not match["matched"]:
        reasons.append(match.get("reason") or "Face match failed")
    if pan_check is not None and not pan_check["matches"]:
        reasons.append("PAN shown on camera could not be read or does not match the application"
                       if not pan_check["found"] else f"PAN shown on camera ({pan_check['found']}) does not match the application")
    if not pan_frames:
        reasons.append("The PAN card was not shown on camera")
    dark = [q for q in quality if q["brightness"] < 50]
    if dark:
        reasons.append("Video too dark in some frames")
    return {
        "status": "PASS" if not reasons else "REVIEW",
        "liveness": live,
        "faceMatch": match,
        "panCheck": pan_check,
        "frames": {"received": len(frames_in), "unreadable": unreadable, "quality": quality},
        "reasons": reasons,
        "models": {"detector": DETECTOR, "recogniser": RECOGNISER},
    }
