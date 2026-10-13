# Rhythm LOS – AI-ML loan origination for small NBFCs

Rhythm is a loan origination system (LOS) with a built-in AI decision engine. It is for NBFCs that do not yet use credit risk models or rely only on the bureau. It takes an application from lead to disbursement: KYC, bank cash-flow analysis, bureau, documents, an explainable decision, credit memo, sanction by delegation, Key Fact Statement and disbursement. Every step has an audit trail.

**Status: Phase 5 (policy studio, risk models, borrower portal, reports, workflow designer).**

- **Credit policy studio** (Admin → Credit policy studio):
  - A credit manager drafts changes to any policy parameter, with ranges and consistency checks.
  - The draft is simulated on 2,000 past loans with known outcomes and on recent applications. The simulation shows approval, referral and decline rates, the amount approved, expected loss, the actual bad rate, swap-ins and swap-outs, results by segment, and which applications would change.
  - The CRO, never the drafter, approves the draft before it goes live.
- **Risk models** (Admin → Risk models):
  - The AI service trains a probability-of-default model (regularised logistic regression on 17 engine features) on the loan book.
  - It is compared with the model in use on hold-out loans: AUC, Gini, KS, Brier score, deciles, population stability (PSI), and effects in an unexpected direction.
  - The CRO approves it as champion, and can go back to the built-in model at any time. Decisions stay explainable feature by feature.
- **Workflow per product** (Admin → Products):
  - Video KYC: only when KYC needs review, always, or above an amount.
  - Bank data: statement or AA, statement only, or AA only.
  - Auto-sanction limit for clean engine approvals. Microfinance loans up to ₹50,000 are auto-sanctioned in the demo.
- **Borrower portal** at `/apply`:
  - The borrower signs in with mobile OTP (demo OTP 123456).
  - They can apply, upload documents (a password-protected statement works), follow plain-language progress, and accept the Key Fact Statement.
  - A borrower token sees only that borrower's own applications and reaches nothing in the staff API.
- **Reports**: funnel, product and branch mix, decision mix and risk bands, turnaround times, document AI and video KYC results, vendor reliability, control indicators, and a CSV export.

The 2,000-loan book is **synthetic** and labelled as such. Policy simulation and model training should be repeated on the NBFC's own loan history before real use.

Phase 4 added **video KYC with AI**. An official of the lender runs a live video call (V-CIP) with the customer. The AI assists with these checks; the official decides:

- **One-time customer link:** valid for 24 hours, and only its hash is stored. The customer gives consent and their location, and joins from a phone or computer browser. No app or login is needed.
- **Live call (WebRTC):** signalled through the LOS API and recorded (customer video plus both voices). The recording's SHA-256 is kept.
- **Challenges** the official triggers: face the camera, turn the head left and right, read a random 4-digit code aloud, and show the original PAN card. Frames are captured for each one.
- **AI checks:**
  - face match with the photo on the PAN or Aadhaar on file (SFace)
  - liveness: face visible, only one person, a real head turn each way, natural movement (catches a held-up photo or frozen video)
  - PAN number read from the card on camera
  - customer location inside India
- **Official's decision with V-CIP guards:**
  - Approval is refused if the customer was outside India, if the code was not confirmed, or if the recording is missing.
  - If the AI checks did not pass, approval needs a reason of at least 30 characters and is flagged as an override in the audit trail.
  - A rejection fails KYC.
- **Models:** YuNet face detection (MIT) and SFace face recognition (Apache 2.0) from OpenCV Zoo. They are downloaded and SHA-256-checked when the image is built.

Camera access in browsers needs `https://` (or `localhost`). Calls between devices on different networks need a TURN server (`VKYC_ICE_SERVERS`); RBI expects it to be hosted in India.

Phase 3 added **AI bank statement analysis**. Bank statements are analysed in-house, with no statement-analysis vendor:

- **Any bank, text or scanned:** the transaction table is found by its column headers, so new bank layouts need no template. Text PDFs are read from the text layer; scanned PDFs and photos are deskewed and read with OCR. Claude can be enabled as a fallback for pages OCR cannot read (`STATEMENT_LLM_FALLBACK=true`).
- **Password-protected PDFs:** the password is used once at upload to unlock the file. Only the unlocked copy is stored, and the password is neither saved nor logged.
- **Balance reconciliation:** every row is checked as previous balance + credit − debit = printed balance. In a text PDF, a row that does not add up is treated as a sign of editing. Editing-tool metadata, changed dates, mixed fonts and a wrong closing balance are also flagged.
- **Categorisation and cash flow:** salary, EMIs (with lender), bounces and bounce charges, betting/gambling, cash deposits, rent, utilities, own-account transfers, UPI and more. This gives monthly inflow, outflow, average and minimum balance, and flags for large cash deposits, round-figure cash income and duplicates.
- **Into the decision:** a verified statement replaces the Account Aggregator as the bank data for the engine. Betting transactions trigger rule **R14** (refer, RC-020), an edited statement is caught by the existing document-tamper fraud signal, and the account holder name is matched in KYC.
- **Bank statement tab:** shows KPIs, month-by-month bars, where the money goes, flagged transactions and a filterable transaction list.

Phase 2 added **AI document reading**. Identity and income documents are read in-house by the AI document service (`ai/`), with no third-party KYC vendor:

- **Classification and extraction:** the document type is identified, and the key fields are extracted with a confidence score for each.
- **Validators:** PAN structure, the Aadhaar Verhoeff checksum, and the passport MRZ check digits.
- **Image checks:** blur, resolution and exposure are checked, and edit signals (editing software, PDF changes) are flagged.
- **Aadhaar masking:** the first 8 digits are hidden on the stored image and in all data.
- **KYC from documents:** the name, date of birth, PAN and PIN code are compared between the documents and the application.
- **Human review:** anything the AI is unsure about goes to a review queue.

OCR runs locally with Tesseract. If `ANTHROPIC_API_KEY` is set, Claude vision reads low-confidence documents, and only masked Aadhaar images are ever sent. Online validation with issuers, the Account Aggregator, bureaus, eSign, eNACH and UPI mandates remain mocks until the integrations phase.

## Run it locally

You need Docker Desktop.

```bash
docker compose up --build
```

Open http://localhost:3000. Every demo user has the password `Rhythm@123`.

| User | Role |
|---|---|
| `sales1` | Sales / DSA: captures applications and documents, gets the KFS accepted |
| `ops1` | Operations: runs checks, resolves KYC reviews, records field visits, disburses, retries failed vendor calls |
| `co1` | Credit officer: sanctions L1 cases (up to ₹2 lakh, bands A–C, no deviations) |
| `cm1` | Credit manager: sanctions L2 cases (up to ₹10 lakh, band D or one deviation) |
| `cro1` | CRO: sanctions L3 cases (above ₹10 lakh, band E, two or more deviations, fraud flags) |
| `field1` | Field officer: field visits, JLG group formation (CGT), vehicle valuation |
| `fraud1` | Fraud analyst: clears or confirms fraud referrals |
| `comp1` | Compliance: audit trail and integration logs (read-only) |
| `admin` | Tenant admin: branches, users, products |

On first start, seven demo applications are created at different stages:

| Applicant | Stage | What it shows |
|---|---|---|
| Ramesh | Waiting for L2 sanction | No bureau record (thin file) |
| Priya | Disbursed | Full journey to disbursement |
| Lakshmi | Field visit pending | JLG loan |
| Suresh | Referred to L3 | Referral to the top sanction level |
| Anil | Document review | Salary slip saved from photo-editing software, now waiting for a reviewer |
| Meera | Data fetch failed | Bank data call in the dead-letter queue; retry it from Integration logs |
| Sunil | Draft | Not yet submitted |

API documentation: http://localhost:8081/api/docs

If port 8081 or 3000 is already used on your machine, choose other ports:

```bash
BACKEND_PORT=8091 WEB_PORT=3001 docker compose up --build
```

### Run from Docker Hub (no build)

After every green CI run on `main`, the tested images are published to Docker Hub as `rhythmconsultancy/rhythm-los-ai`, `rhythmconsultancy/rhythm-los-backend` and `rhythmconsultancy/rhythm-los-web` (tags `latest` and the short commit hash).

```bash
docker compose pull
BACKEND_PORT=8091 docker compose up
```

`RHYTHM_TAG=<commit>` runs a specific build. Publishing needs one repository secret, `DOCKERHUB_TOKEN`: a Docker Hub access token (Read & Write) for the `rhythmconsultancy` account.

## Mock vendor scenarios

The four digits in the PAN choose how the mock vendors respond. This lets you replay any path in the journey.

| PAN digits | Behaviour |
|---|---|
| `9001`–`9099` | Bureau no-hit (thin file) |
| `9101`–`9199` | Account Aggregator times out twice, then succeeds (automatic retry) |
| `9201`–`9299` | Account Aggregator fails until retried manually from Integration logs (dead-letter queue) |
| `9301`–`9399` | KYC address and contact mismatch, which goes to KYC review |
| `9401`–`9499` | Declared income far above bank inflows (fraud signal) |
| `9501`–`9599` | Penny drop name mismatch, so disbursement fails and can be retried |

Demo case **Kiran Joshi** has a password-protected bank statement with online rummy payments, which shows R14. `e2e/fixtures/sunil-statement-locked.pdf` (password `SUNI1402`) can be uploaded on the draft for Sunil Wagh. To make more specimen statements, use `ai/tools/statements_gen.py`. Accuracy is measured by `cd ai && python -m tools.evaluate_statements 3`.

SPECIMEN identity cards for fictional people are in `e2e/fixtures/`. To make more, run `cd ai && python -m tools.specimens out/`. Upload them on an application to watch the AI read them.

In production, set `ALLOW_SPECIMEN=false` so that any document marked SPECIMEN is sent for review.

### Optional: Claude vision fallback

```bash
ANTHROPIC_API_KEY=sk-ant-... docker compose up --build
```

Without a key, everything runs locally, and low-confidence reads go to a human reviewer.

## Architecture

```
web (React + TypeScript, nginx)  ──/api──▶  backend (Spring Boot 3, Java 21)  ──▶  PostgreSQL 16
                                              │
                                              └──▶ ai (Python FastAPI: Tesseract OCR, OpenCV, validators, masking, optional Claude vision)
                                              ├── state machine (9 domains), audit trail
                                              ├── integration gateway (logs, retry, dead-letter queue)
                                              ├── decision engine (rules + logistic PD model, reason codes)
                                              └── vendor ports (mock adapters today)
```

| Folder | Contents |
|---|---|
| `backend/` | Spring Boot API, Flyway migrations, unit tests, Testcontainers journey tests |
| `web/` | React app |
| `ai/` | AI document service with pytest tests over generated SPECIMEN cards |
| `e2e/` | Playwright browser journeys against the running Docker Compose stack |
| `.github/workflows/ci.yml` | Builds and tests everything on each push, then publishes a summary to the `ci-status` branch |

### Key rules

- **State changes go through `ApplicationService.transition`.** Moves not listed in `StateMachine` are refused with `INVALID_TRANSITION`.
- **Vendor calls go through `IntegrationGateway`.** Every attempt is logged, retries use exponential backoff, and a final failure goes to the dead-letter queue.
- **Decisions are reproducible.** Each one stores the input hash, model version and policy version, the rule results, the feature contributions and the credit memo.
- **Overrides need a reason.** Approving a referred case, or sanctioning above the recommended amount, requires a reason and is flagged in the audit trail.

## Develop without Docker

```bash
# database
docker run -d -p 5432:5432 -e POSTGRES_DB=rhythm -e POSTGRES_USER=rhythm -e POSTGRES_PASSWORD=rhythm postgres:16-alpine
# backend on :8081
cd backend && mvn spring-boot:run
# web on :3000 (proxies /api to :8081)
cd web && npm install && npm run dev
```

Tests: `cd backend && mvn verify` (needs Docker for Testcontainers). Then, with the stack running, `cd e2e && npm ci && npx playwright install chromium && npx playwright test`.

## Workflow designer

Admin → **Workflow designer** (admin or credit manager edits, the CRO approves; the maker cannot approve).

- Each product has a stage sequence. Per stage: who does it, SLA in hours, mode (automatic, automatic with a person confirming, manual), what happens on failure or SLA breach (send to queue, escalate, reject, pause and notify borrower) and a skip rule such as `segment = SALARIED AND amount <= 5,00,000 AND incomeVerifiedViaAA = true`.
- Checks before approval: starts with Lead, ends with Disbursement, KYC and Sanction mandatory, KYC before Underwriting before Sanction, valid SLAs and rules.
- Stages a person records (JLG group formation and GRT, vehicle valuation, gold appraisal, legal) hold the decision, sanction and disbursement until done. A failed GRT rejects the application.
- Every application shows its stages (strip above the tabs, **Workflow** tab). New applications follow the published version; earlier ones keep theirs.
- Demo: Lakshmi Devi (JLG) waits at group formation. Sign in as `field1` to record CGT, then `co1` to record GRT.
- SLA check interval: `WORKFLOW_SLA_CHECK_MS` (default 60000).

## Roadmap

| Phase | Scope |
|---|---|
| 1 (done) | Core LOS, decision engine, sanction, KFS, disbursement, admin, mock vendors |
| 2 (done) | **AI document and KYC OCR (in-house):** document classification; PAN, Aadhaar, voter ID, driving licence and passport extraction; Aadhaar masking; quality and tamper checks; cross-document matching; human review below a confidence threshold. Hybrid engine: self-hosted open-source OCR, with Claude vision for low-confidence cases |
| 3 (done) | **AI bank statement analysis (in-house):** text and scanned PDFs, multi-bank table parsing, transaction categorisation, balance continuity and fraud checks, cash-flow features for the decision engine |
| 4 (done) | **Video KYC with AI:** liveness, face match against the ID photo, geo-tag and timestamp, recording, and an agent console. RBI V-CIP requires a trained official to conduct the call; AI assists |
| 5 (this) | Policy studio with simulation and maker-checker, trained PD model with champion/challenger, workflow per product, borrower portal, reports, **workflow designer** (per-product stages with role, SLA, mode, failure action, skip rules; CRO publishes versions; in-flight applications keep theirs; SLA breaches escalate). Still to come: import of the NBFC's loan history, a trained fraud model, field officer app |
| 6 | **Third-party integrations:** online PAN, Aadhaar and CKYC validation, Account Aggregator, bureaus, eSign and eStamp, eNACH and UPI Autopay, penny drop, payouts, WhatsApp and SMS. Replaces the mocks through the existing vendor ports |
| 7 | Production hardening: multi-tenant, security audit and VAPT, monitoring, Kubernetes, DR |

All demo data is synthetic. The model coefficients are illustrative until they are trained on the pilot NBFC's data. Regulatory controls must be confirmed by the lender's compliance team.
