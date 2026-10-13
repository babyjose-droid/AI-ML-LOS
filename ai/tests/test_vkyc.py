"""
Video KYC checks. Liveness logic is tested with constructed face landmarks; face matching uses public-domain
US-government portraits (from the face_recognition project's examples), downloaded at test time and not stored
in this repository. Those tests are skipped when there is no network.
"""
import os
import pathlib
import urllib.request

import cv2
import numpy as np
import pytest

from app import vkyc

pytestmark = pytest.mark.skipif(not vkyc.available(), reason="face models not downloaded (run tools/fetch_models.sh models)")

CACHE = pathlib.Path(os.environ.get("VKYC_TEST_CACHE", pathlib.Path.home() / ".cache" / "rhythm-vkyc"))
URL = "https://raw.githubusercontent.com/ageitgey/face_recognition/master/examples/"


def _photo(name):
    CACHE.mkdir(parents=True, exist_ok=True)
    p = CACHE / name
    if not p.exists():
        try:
            urllib.request.urlretrieve(URL + name, p)
        except Exception:  # noqa: BLE001
            pytest.skip("no network for test photos")
    return p.read_bytes()


def face(yaw, x=100.0):
    """A Face whose landmarks give the wanted yaw ratio (eye distance 40 px)."""
    re, le = np.array([x, 100.0]), np.array([x + 40, 100.0])
    nose = np.array([x + 20 + yaw * 40, 120.0])
    lm = np.stack([re, le, nose, [x + 5, 140], [x + 35, 140]]).astype(np.float32)
    return vkyc.Face((x - 20, 70, 80, 100), lm, 0.95, np.zeros(15, np.float32))


def frames_for(yaws_by_step, moving=True):
    rng = np.random.default_rng(1)
    out = []
    for step, yaws in yaws_by_step.items():
        for y in yaws:
            img = rng.integers(0, 255, (240, 320, 3), dtype=np.uint8) if moving else np.full((240, 320, 3), 120, np.uint8)
            out.append((step, img, [face(y)]))
    return out


def test_yaw_from_landmarks():
    assert abs(face(0).yaw) < 1e-6
    assert face(0.4).yaw == pytest.approx(0.4)
    assert face(-0.35).yaw == pytest.approx(-0.35)


def test_live_person_passes_the_challenges():
    r = vkyc.liveness(frames_for({"front": [0.02, -0.05], "left": [0.1, 0.38], "right": [-0.1, -0.41]}))
    assert r["passed"], r["reasons"]


def test_no_head_turn_fails():
    r = vkyc.liveness(frames_for({"front": [0.0], "left": [0.05], "right": [-0.04]}))
    assert not r["passed"]
    assert any("Head turn" in x for x in r["reasons"])


def test_turning_the_same_way_twice_fails():
    r = vkyc.liveness(frames_for({"front": [0.0], "left": [0.4], "right": [0.35]}))
    assert any("Head turn" in x for x in r["reasons"])


def test_still_photo_fails_movement():
    r = vkyc.liveness(frames_for({"front": [0.0], "left": [0.4], "right": [-0.4]}, moving=False))
    assert any("Natural movement" in x for x in r["reasons"])


def test_second_person_and_missing_face_are_caught():
    f = frames_for({"front": [0.0], "left": [0.4], "right": [-0.4]})
    f[0] = (f[0][0], f[0][1], [face(0), face(0, x=200)])
    f.append(("front", f[0][1], []))
    r = vkyc.liveness(f)
    assert any("Only one person" in x for x in r["reasons"])
    assert any("Face visible" in x for x in r["reasons"])


def _jpg(img, quality=85):
    return cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, quality])[1].tobytes()


def test_same_person_matches_and_different_person_does_not():
    a, a2, b = (vkyc.decode(_photo(n)) for n in ("obama.jpg", "obama2.jpg", "biden.jpg"))
    live = [("front", a2, vkyc.detect(a2))]
    same = vkyc.face_match(live, [("PAN", a)])
    other = vkyc.face_match(live, [("PAN", b)])
    assert same["matched"] and same["score"] > 0.5, same
    assert not other["matched"] and other["score"] < vkyc.MATCH_THRESHOLD, other


def test_id_card_photo_is_found_when_small():
    # the ID photo is a small part of a card: put the portrait on a large plain card
    a, a2 = vkyc.decode(_photo("obama.jpg")), vkyc.decode(_photo("obama2.jpg"))
    card = np.full((630, 1000, 3), 240, np.uint8)
    card[120:120 + 260, 60:60 + 208] = cv2.resize(a, (208, 260))
    r = vkyc.face_match([("front", a2, vkyc.detect(a2))], [("PAN", card)])
    assert r["results"][0]["faceFound"] and r["matched"], r


def test_full_analysis_and_api():
    from fastapi.testclient import TestClient
    from app.main import app
    a, a2 = _photo("obama.jpg"), _photo("obama2.jpg")
    c = TestClient(app)
    files = [("frames", ("front-1.jpg", a2, "image/jpeg")), ("frames", ("left-1.jpg", a2, "image/jpeg")),
             ("frames", ("right-1.jpg", a2, "image/jpeg")), ("id_images", ("PAN.jpg", a, "image/jpeg"))]
    r = c.post("/v1/vkyc/analyze", files=files, data={"expected_pan": "ABCDE1234F"})
    assert r.status_code == 200
    j = r.json()
    assert j["faceMatch"]["matched"]
    # the same still image three times: no head turn, no movement, PAN never shown -> review
    assert j["status"] == "REVIEW"
    assert not j["liveness"]["passed"]
    assert any("PAN card was not shown" in x for x in j["reasons"])


def test_no_face_at_all():
    blank = _jpg(np.full((480, 640, 3), 90, np.uint8))
    r = vkyc.analyse([("front", blank), ("left", blank), ("right", blank)], [])
    assert r["status"] == "REVIEW"
    assert r["faceMatch"]["reason"] == "No face in the live video frames"
