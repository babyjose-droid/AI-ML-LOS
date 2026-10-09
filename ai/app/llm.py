"""
Claude vision fallback for low-confidence reads.

Used only when ANTHROPIC_API_KEY is set. Aadhaar images are sent only after the first 8 digits are masked.
The model returns structured JSON; every value it returns still goes through the same validators.
"""
import base64
import json
import logging
import os
import re

import httpx

log = logging.getLogger("rhythm.ai.llm")

API_URL = os.getenv("ANTHROPIC_API_URL", "https://api.anthropic.com/v1/messages")
MODEL = os.getenv("CLAUDE_MODEL", "claude-sonnet-4-5")

FIELD_HINTS = {
    "PAN": "pan, name, fatherName, dob (YYYY-MM-DD)",
    "AADHAAR": "aadhaarLast4 (last 4 digits only, never more), name, dob (YYYY-MM-DD or YYYY), gender, address, pincode",
    "VOTER_ID": "epicNumber, name, relationName, dob (YYYY-MM-DD)",
    "DRIVING_LICENCE": "dlNumber, name, dob (YYYY-MM-DD), validTill (YYYY-MM-DD), address",
    "PASSPORT": "passportNumber, surname, givenNames, dob (YYYY-MM-DD), expiry (YYYY-MM-DD), sex, nationality",
    "UDYAM": "udyamNumber, enterpriseName, ownerName, enterpriseType",
    "SALARY_SLIP": "employeeName, employer, netPay (number), grossPay (number), month",
}


def enabled() -> bool:
    return bool(os.getenv("ANTHROPIC_API_KEY"))


def read_document(image_png: bytes, doc_type_hint: str | None) -> dict | None:
    """Returns {"documentType": str, "fields": {name: value}} or None on any failure."""
    if not enabled():
        return None
    types = ", ".join(FIELD_HINTS)
    hint = FIELD_HINTS.get(doc_type_hint or "", "the key identity fields")
    prompt = (
        "You are reading an Indian KYC or income document for a lender. "
        f"Identify the document type (one of: {types}, UNKNOWN) and extract these fields: {hint}. "
        "Copy values exactly as printed; do not guess. Leave a field out if it is not clearly readable. "
        "If the image shows a full 12-digit Aadhaar number, return only its last 4 digits. "
        'Answer with JSON only: {"documentType": "...", "fields": {"field": "value"}}'
    )
    body = {
        "model": MODEL,
        "max_tokens": 600,
        "messages": [{"role": "user", "content": [
            {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": base64.b64encode(image_png).decode()}},
            {"type": "text", "text": prompt},
        ]}],
    }
    headers = {"x-api-key": os.environ["ANTHROPIC_API_KEY"], "anthropic-version": "2023-06-01", "content-type": "application/json"}
    try:
        r = httpx.post(API_URL, json=body, headers=headers, timeout=float(os.getenv("CLAUDE_TIMEOUT", "30")))
        r.raise_for_status()
        text = "".join(b.get("text", "") for b in r.json().get("content", []) if b.get("type") == "text")
        m = re.search(r"\{.*\}", text, re.S)
        data = json.loads(m.group(0)) if m else None
        if not isinstance(data, dict) or not isinstance(data.get("fields"), dict):
            return None
        # defence in depth: never keep more than 4 Aadhaar digits from the model
        for k, v in list(data["fields"].items()):
            if isinstance(v, str) and re.search(r"\d{4}\s?\d{4}\s?\d{4}", v):
                data["fields"][k] = re.sub(r"\d{4}\s?\d{4}\s?(\d{4})", r"XXXX XXXX \1", v)
        return data
    except Exception as e:  # noqa: BLE001 - the fallback must never break the main read
        log.warning("Claude fallback failed: %s", e)
        return None
