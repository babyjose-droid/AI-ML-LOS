# Rhythm LOS – AI-ML loan origination for small NBFCs

Rhythm is a loan origination system (LOS) with a built-in AI decision engine. It is for NBFCs that do not yet use credit risk models or rely only on the bureau. It takes an application from lead to disbursement: KYC, bank cash-flow analysis, bureau, documents, an explainable decision, credit memo, sanction by delegation, Key Fact Statement and disbursement. Every step has an audit trail.

**Status: Phase 1 (core LOS).** In this phase, all external services (KYC, Account Aggregator, bureau, GST, penny drop, payout) are deterministic mocks. They plug in behind interfaces that the real integrations will implement later.

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
| Anil | Rejected | Tampered salary slip led to a fraud block |
| Meera | Data fetch failed | Bank data call in the dead-letter queue; retry it from Integration logs |
| Sunil | Draft | Not yet submitted |

API documentation: http://localhost:8081/api/docs

If port 8081 or 3000 is already used on your machine, choose other ports:

```bash
BACKEND_PORT=8091 WEB_PORT=3001 docker compose up --build
```

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

Upload a document whose file name contains `blur` to see it rejected as unreadable. A file name containing `tamper` raises a tamper flag.

## Architecture

```
web (React + TypeScript, nginx)  ──/api──▶  backend (Spring Boot 3, Java 21)  ──▶  PostgreSQL 16
                                              ├── state machine (9 domains), audit trail
                                              ├── integration gateway (logs, retry, dead-letter queue)
                                              ├── decision engine (rules + logistic PD model, reason codes)
                                              └── vendor ports (mock adapters today)
```

| Folder | Contents |
|---|---|
| `backend/` | Spring Boot API, Flyway migrations, unit tests, Testcontainers journey tests |
| `web/` | React app |
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

## Roadmap

| Phase | Scope |
|---|---|
| 1 (this) | Core LOS, decision engine, sanction, KFS, disbursement, admin, mock vendors |
| 2 | **AI document and KYC OCR (in-house):** document classification; PAN, Aadhaar, voter ID, driving licence and passport extraction; Aadhaar masking; quality and tamper checks; cross-document matching; human review below a confidence threshold. Hybrid engine: self-hosted open-source OCR, with Claude vision for low-confidence cases |
| 3 | **AI bank statement analysis (in-house):** text and scanned PDFs, multi-bank table parsing, transaction categorisation, balance continuity and fraud checks, cash-flow features for the decision engine |
| 4 | **Video KYC with AI:** liveness, face match against the ID photo, geo-tag and timestamp, recording, and an agent console. RBI V-CIP requires a trained official to conduct the call; AI assists |
| 5 | Policy studio with simulation and maker-checker, trained PD and fraud models, workflow per product, borrower and field apps |
| 6 | **Third-party integrations:** online PAN, Aadhaar and CKYC validation, Account Aggregator, bureaus, eSign and eStamp, eNACH and UPI Autopay, penny drop, payouts, WhatsApp and SMS. Replaces the mocks through the existing vendor ports |
| 7 | Production hardening: multi-tenant, security audit and VAPT, monitoring, Kubernetes, DR |

All demo data is synthetic. The model coefficients are illustrative until they are trained on the pilot NBFC's data. Regulatory controls must be confirmed by the lender's compliance team.
