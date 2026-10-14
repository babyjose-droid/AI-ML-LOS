from fastapi.testclient import TestClient

from app import llm
from app.main import app

client = TestClient(app)


def test_mask_text_removes_identifiers():
    s = llm.mask_text("PAN ABCPK1234L, Aadhaar 1234 5678 9012, mobile 9876543210, amount 500000")
    assert "ABCPK1234L" not in s and "5678" not in s and "9876543210" not in s
    assert "[PAN]" in s and "[AADHAAR]" in s and "[MOBILE]" in s and "500000" in s


def test_answer_is_null_without_a_key(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    r = client.post("/v1/assistant/answer", json={"question": "Who approves 8 lakh?", "passages": [{"id": "DEL-matrix", "title": "Delegation", "text": "L2 up to 1000000"}]})
    assert r.status_code == 200
    assert r.json() == {"answer": None, "engine": "none"}


def test_prompt_is_masked_and_grounded(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test")
    seen = {}

    class R:
        def raise_for_status(self):
            pass

        def json(self):
            return {"content": [{"type": "text", "text": "A credit manager approves it [DEL-matrix]."}]}

    def fake_post(url, json, headers, timeout):
        seen["body"] = json
        return R()

    monkeypatch.setattr(llm.httpx, "post", fake_post)
    out = llm.answer_question("Can ABCPK1234L with mobile 9876543210 get 8 lakh?", [{"id": "DEL-matrix", "title": "Delegation", "text": "L2 up to 1000000"}])
    assert out.startswith("A credit manager")
    prompt = seen["body"]["messages"][0]["content"]
    assert "ABCPK1234L" not in prompt and "9876543210" not in prompt
    assert "[DEL-matrix]" in prompt and "ONLY the passages" in prompt


def test_rejects_empty_question():
    assert client.post("/v1/assistant/answer", json={"question": "", "passages": []}).status_code == 422
