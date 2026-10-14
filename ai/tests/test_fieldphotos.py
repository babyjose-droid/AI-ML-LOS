import io

import numpy as np
from fastapi.testclient import TestClient
from PIL import Image

from app import fieldphotos
from app.main import app


def jpeg(arr: np.ndarray, gps=None, when=None) -> bytes:
    im = Image.fromarray(arr)
    ex = Image.Exif()
    if when:
        ex[0x0132] = when
        ex.get_ifd(0x8769)[0x9003] = when
    if gps:
        ex.get_ifd(0x8825).update(gps)
    b = io.BytesIO()
    im.save(b, "JPEG", exif=ex)
    return b.getvalue()


def test_exif_time_and_location():
    gps = {1: "N", 2: (19.0, 59.0, 51.0), 3: "E", 4: (73.0, 47.0, 23.0)}
    data = jpeg(np.full((120, 160, 3), 120, np.uint8), gps, "2026:10:13 10:15:00")
    e = fieldphotos.exif(data)
    assert e["takenAt"] == "2026-10-13T10:15:00"
    assert abs(e["lat"] - 19.9975) < 1e-3 and abs(e["lng"] - 73.7897) < 1e-3


def test_no_exif_is_not_an_error():
    e = fieldphotos.exif(jpeg(np.zeros((50, 50, 3), np.uint8)))
    assert e["takenAt"] is None and e["lat"] is None


def test_shelf_fill_tells_busy_from_empty():
    rng = np.random.default_rng(1)
    busy = (rng.integers(0, 2, (400, 400)) * 255).astype(np.uint8)
    busy = np.stack([busy] * 3, -1)
    empty = np.full((400, 400, 3), 200, np.uint8)
    assert fieldphotos.shelf_fill(busy)["level"] == "HIGH"
    assert fieldphotos.shelf_fill(empty)["level"] == "LOW"


def test_scene_scores_every_group():
    s = fieldphotos.scene(np.full((300, 300, 3), 90, np.uint8))
    if fieldphotos.classifier_available():
        assert set(s["scores"]) == {"SHOP", "LIVESTOCK", "VEHICLE", "HOME"}
        assert s["scene"] in ("SHOP", "LIVESTOCK", "VEHICLE", "HOME", "UNCLEAR")
    else:
        assert s["scene"] is None


def test_field_photo_api():
    c = TestClient(app)
    files = [("photos", ("front-1.jpg", jpeg(np.full((200, 200, 3), 128, np.uint8), when="2026:10:13 10:15:00"), "image/jpeg")),
             ("photos", ("stock-1.jpg", jpeg(np.full((200, 200, 3), 30, np.uint8)), "image/jpeg")),
             ("photos", ("owner-1.jpg", jpeg(np.full((200, 200, 3), 60, np.uint8)), "image/jpeg"))]
    r = c.post("/v1/field/photos/analyze", files=files)
    assert r.status_code == 200, r.text
    out = r.json()["photos"]
    assert [p["kind"] for p in out] == ["front", "stock", "owner"]
    assert out[0]["exif"]["takenAt"] == "2026-10-13T10:15:00"
    assert "shelf" in out[1] and "shelf" not in out[0]
    assert "face" in out[2]
