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
| `9601`–`9699` | Only 4 months of bank history: the decision is INCOMPLETE and names what is missing |
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
- The role on each stage decides who acts: the Run automated checks, KYC, document review, field visit and disbursement buttons, the API permission and the My work task all follow it (an admin can always act; video KYC stays with operations, as RBI V-CIP needs a trained official).
- Stages a person records (JLG group formation and GRT, vehicle valuation, gold appraisal, legal) hold the decision, sanction and disbursement until done. A failed GRT rejects the application.
- Every application is fixed to the workflow version published when it was created (shown in the pipeline and the application header). Publishing a new version affects only applications created afterwards; older ones finish on their own version. The database refuses any change of version once an application is submitted. The designer shows how many applications are on each version and how many are still in progress.
- **Parallel stages:** tick "Runs alongside the stage above" and both start together (for example documents with KYC, or legal with the vehicle valuation); the stage after the group waits for all of them. Lead, Underwriting, Sanction and Disbursement always run alone, and a stage cannot run alongside one it needs finished first (Credit memo after Underwriting, GRT after CGT).
- Demo: Lakshmi Devi (JLG) waits at group formation. Sign in as `field1` to record CGT, then `co1` to record GRT.
- SLA check interval: `WORKFLOW_SLA_CHECK_MS` (default 60000).

## Video KYC (V-CIP), set per product

Video KYC is built in: the official sends a one-time link, the customer consents and shares location, and the call runs
in the browser (WebRTC, signalled through Rhythm; add a TURN server in `rhythm.vkyc.ice-servers` for calls across
strict networks). The official asks for the face, head turns, a random code and the PAN card; frames go to the AI
(liveness, face match with the ID photo, PAN read on camera) and the call is recorded. The official decides.

Products → **Video KYC** sets, for each product:

| Setting | Business | Personal | Used CV | Gold | LAP | JLG |
|---|---|---|---|---|---|---|
| Applicant | when KYC needs review / always / above an amount (existing rule) | | | | | |
| Co-applicants and guarantors | when their KYC needs review | same | same | never | **every one, before the decision** | never |
| Minimum face match | 0.363 | 0.363 | 0.363 | 0.363 | **0.45** | 0.363 |
| PAN on camera | yes | yes | yes | yes | yes | no |
| Link valid | 24 h | 24 h | 48 h | 24 h | 48 h | 24 h |
| Who runs the call | operations | operations | operations | operations | operations or credit officer | operations |

Also per product: liveness required, call hours (India time), calls allowed per person (3), languages, and up to 10
questions the official must ask and confirm before approving. Always on (RBI V-CIP): a trained official, consent,
location in India, the random code and the recording. Each session keeps the settings it was created with.

- **IP country as well as GPS:** the country of the customer's IP address is recorded when they join; a call from a
  foreign IP (a VPN or a spoofed location) cannot be approved even when GPS shows India. Load your geo-IP provider's
  ranges (one `CIDR,country` per line) with `rhythm.vkyc.ip-country-file`; private addresses count as unknown network.
- **Queue and booked slots:** Work → **Video KYC queue** shows customers waiting now (longest first, late bookings
  marked), calls in progress and booked calls. A customer who cannot take the call now books a 15-minute slot on the
  link page (staff can book or move it for them); each slot takes `rhythm.vkyc.slot-capacity` calls (default 2).

## Co-applicants and guarantors

Every product has its own rules (Products → **Co-applicants & guarantors**, approved by the CRO like any product change):

| Product | Co-applicant | Guarantor | Income counted | Party bureau minimum |
|---|---|---|---|---|
| Business loan | optional, up to 2 | optional (1); **required before sanction above ₹5 L** | spouse or business partner, 2 earners | 700 |
| Personal loan | optional (1) | not allowed | spouse, 2 earners | — |
| Used CV loan | optional (1) | optional (1); required before sanction above ₹10 L | any co-applicant, 2 earners | 680 |
| Gold loan | not allowed | not allowed | — | — |
| Loan against property | optional, up to 3; **every co-owner of the property must join** | optional, up to 2 | any co-applicant, 3 earners | 700 |
| JLG | not allowed (the group is the guarantee) | not allowed | — | — |

- **Mandatory** parties block submission; a **required-when** rule (workflow fields: amount, income, score, band…) must be met before sanction, so credit can bring a party in after appraisal.
- **Adding people:** on the new-application form, on the application's **Parties** tab, or when credit asks (**Ask for a co-applicant or guarantor**; a credit manager or the CRO can waive with a reason).
- **Consent:** each person consents on their own phone: an OTP read out in the branch, or an **invite link** (`/party/<token>`, valid 7 days) where they verify by OTP, read what they are agreeing to, consent and upload their PAN and Aadhaar (kept masked). Demo OTP 123456 until SMS is integrated.
- **Checks:** KYC (from their own documents when document AI is on, else the KYC provider) and a bureau pull per person; the decision waits until every party is verified.
- **Decision:** counted co-applicants' income and their EMIs join the applicant's for affordability; rule **R23** refers the case when a party misses the policy (bureau score, no credit record for a guarantor, age at maturity, more than two other open guarantees) with reason code RC-026. The credit memo has a joint section.
- **Gates:** sanction waits for co-owners, credit's requests and a re-run decision if someone joined after it; disbursement waits until every party has signed by OTP (co-applicants the KFS and agreement, guarantors the guarantee deed).
- **Company and firm guarantors** (business, used CV and LAP products): a company, LLP, partnership, trust or HUF can stand guarantor, never co-applicant. The PAN must be of its type (fourth letter C, F, T or H), an authorised signatory is named, and KYC needs the board resolution, partnership deed, LLP agreement or trust deed.
- **Income checked against their own bank data:** where the product counts co-applicant income, it counts only after their bank statement (read by AI) or Account Aggregator data has been checked, with their consent; the lower of declared and found income counts.
- **After sanction:** the sanction letter, KFS and borrower portal name every co-applicant and guarantor; they join the live loan; collections may contact a co-applicant any time and a guarantor only at 30+ days past due or after a broken promise; the credit bureau file reports each person on the account in their own role (JOINT, GUARANTOR).
- Applications created before this release keep the old behaviour.

## Collateral after disbursement

- On disbursement each collateral that passed becomes a lien on the loan (Portfolio → loan → **Collateral**).
- **Release:** after closure the original documents are due back within 30 days (₹5,000 a day late is shown and paid, RBI 2023); before closure one collateral can be released after a part-payment only if what remains covers the outstanding within each type's maximum LTV.
- **Substitution:** a new collateral offered in place of one held goes through the usual checks; it can be swapped in only when it has passed and still covers the loan.
- Operations or credit asks, a credit manager or the CRO approves (never the person who asked), operations records the handover and a release letter is issued.
- **Automated valuation:** registered sales within 3 km in the last 24 months, brought to today and weighted by distance, age and size, give a value and range beside the empanelled valuer's report (a cross-check; it never sets the value). Import sales (registry data, your past valuations) under Collateral AI → Policy.

## Loan documents: templates, products and the document pack

**Admin → Document templates** holds the lender's documents and clause library. It starts with an application form,
sanction letter, KFS, loan agreement, deed of guarantee, DPN, hypothecation deed, memorandum of deposit of title deeds
(mortgage), welcome letter and no dues certificate, plus ten clauses. Review them with your legal team before use.

- **Write in the browser or in Word.** The editor has formatting, tables, a field picker, repeated sections, conditions
  and clause inserts, and an HTML view. Or type the fields into your own Word agreement and upload the .docx; headings,
  bold, lists and tables come across and it becomes a draft.
- **Template language:**
  - Fields: `{{applicant.name}}`, with formats such as `{{loan.amount | rupees}}`, `| words` (Rupees Six Lakh Only), `| date`, `| percent`.
  - Repeated sections: `{{#each coApplicants}}…{{/each}}`.
  - Conditions: `{{#if guarantors}}…{{else}}…{{/if}}` or `{{#if loan.amount > 1000000}}`.
  - Clauses: `{{> CL_INTEREST}}`.
  - Built-in blocks: `{{> KFS_STANDARD}}` (RBI's KFS table, which a KFS must include and cannot change), `{{> SCHEDULE_TABLE}}`, `{{> PARTIES_TABLE}}`, `{{> COLLATERAL_TABLE}}`, `{{> SIGNATURES}}`.
  - Unknown fields and broken blocks are flagged before a template can go for approval.
- **Versions and approval:**
  - Each template has versions per language. Compliance or the CRO approves; the drafter cannot.
  - Approving retires the earlier version.
  - An application keeps the version (and language) it was first made with, unless staff choose the newer one and give a reason.
- **Products → Signing pack:**
  - Choose which documents a product uses.
  - Choose when each is made: with the application, at sanction, before disbursement, after disbursement or at closure.
  - Choose who signs (borrower, co-applicants, guarantors) and when it applies (always, with a guarantor, with collateral, unsecured).
  - Optionally, disbursement waits until the documents are made, up to date and signed.
- **Application → Signing pack:**
  - Make each stage's documents as PDF, in the borrower's language when a translation is approved. They are stored with a fingerprint.
  - View, download, and record each signature by OTP, or a wet-ink signature checked by operations.
  - A document goes out of date when the details it shows change (for example a guarantor is added); it must then be made again and signed again.
- PDFs come from the AI service (WeasyPrint with Noto fonts, so Hindi, Marathi, Tamil and the other scripts print correctly). Without it a plain-text PDF is made.

## Models, templates and cost per file

- **Your own loan history:** Admin → Risk models → **Our own loan history** imports past loans (CSV; columns listed on the screen). Bad = 90+ days past due in the first 12 months or written off. Tick "Only our own loans" to train without the synthetic book.
- **Fraud model:** a separate model trained on fraud outcomes (imported history and every case a fraud analyst confirmed or cleared here); same governance as the PD models, and once the CRO approves it, its weights replace the built-in fraud score.
- **Templates:** Admin → **Templates** edits the sanction letter and KFS wording per language and the customer messages (OTP, invites, video KYC link, sanction, disbursement, reminders), with placeholder checks, preview, versions and audit.
- **Cost per file:** Benchmarking shows vendor and staff cost per disbursed file (direct and all-in, by product and item) at rates the admin or CRO sets, and compares it with peers.

## Phase 4: Scale

- **Collateral AI** (Work → Collateral AI; a Collateral tab on every application). These screens cover SCR-140 to SCR-149:
  - Overview of every case.
  - A case workspace with eight tabs: 360 summary with evidence IDs, Documents, EC analysis, Title chain, Property vision, Valuation & LTV, Risk & decision, and Audit.
  - A review queue for the new **Legal reviewer** and **Valuer** roles and for field officers.
- **What Collateral AI reads and checks:**
  - **Documents:** the sale deed, EC, khata, tax receipt, plan, OC, land record, valuation report, insurance and RC are read by the AI service (`/v1/collateral/documents/analyze`). Fields read below 85% confidence wait for a person to confirm them. Typed fields count as confirmed.
  - **EC:** charges are matched to their releases.
  - **Title chain:** gaps and double transfers are flagged.
  - **Photos:** checked for GPS distance, coverage, quality, and reuse across files.
  - **Valuation:** value used is the lower of the empanelled valuations. The AI cross-check (locality rate × area, or a depreciated new price) is shown only.
  - **Risk:** a 10-dimension risk vector.
- **Collateral rules:** CR-01 to CR-11 give PASS, REFER or FAIL, and open reviews in the right queue. Every review is cleared or upheld with a reason.
- **Unified decision:** credit and collateral are combined. Sanction waits for collateral PASS. An amount above the maximum exposure (LTV cap) needs L3 and a reason. The collateral conditions go on the sanction.
- **Collateral policy per type:** EC years, haircut and maximum LTV, valuation age, variance limit, second valuation above ₹50 lakh, photos and GPS radius. These are editable by the admin or CRO.
- **Monitoring:** revaluation due, insurance expiring and LTV breach.
- **LAP product** with a "Collateral AI review" workflow stage. Demo cases:
  - residential → PASS
  - shop → REFER, then PASS with LTV cap after reviews
  - plot → FAIL
- **Gold and vehicle valuation:**
  - **Gold:** appraisal on net weight and purity at the 22-carat rate (the lower of the 30-day average and the latest rate). RBI LTV tiers apply: 85% up to ₹2.5 lakh, 80% up to ₹5 lakh, 75% above.
  - **Vehicle:** the RC owner and any hypothecation are checked.
- **Co-lending** (Portfolio → Co-lending):
  - Each partner bank has its own rules: products, ticket size, bands, new-to-credit, vintage, PSL, share and rate.
  - The screen shows the eligible pool with the blended rate and our yield on our share, and reasons for loans that are not eligible.
  - Selected loans go to the partner as a loan file (CSV), and the partner's approval or rejection is tracked.
  - Each lender keeps at least 10% of every loan (RBI co-lending directions, 2025).
- **Capital and liquidity** (Portfolio): a 12-month projection of book, collections, cash and CRAR. It alerts when cash goes negative or CRAR falls below 15%. Starting figures come from the loan book, and scenarios can be saved.
- **Multi-tenancy:**
  - Each lender has its own PostgreSQL schema (`t_<code>`), users, products, policy, files and caches. Sign-in takes an organisation code, and the session token carries the tenant. A request for another tenant is refused.
  - Background jobs run per tenant. New releases migrate every tenant's schema at start-up.
  - The platform operator is an admin of the default tenant. They create, suspend and reactivate tenants under Admin → Tenants (platform).
- **Tenant onboarding** (Admin → Onboarding and go-live, SCR-094):
  - The ten steps show their evidence from the configuration.
  - The sandbox pack runs 12 checks, including tenant isolation and idempotency.
  - The tenant admin requests go-live with a rollback plan, and the CRO approves.
- **Benchmarking:** opt-in and anonymised. It compares approval rate, days to disburse, straight-through rate, 30+ DPD at 6 months, fraud stops, yield and book current. Quartiles appear only when five or more lenders take part. Demo mode adds five clearly labelled synthetic peers.

## Phase 3: Portfolio and growth

- **Loan book** (Portfolio → Loan book): loans disbursed in Rhythm join the book at once. The LMS sends outstanding, overdue, days past due, bounces and payments by API (`POST /api/book/feed`) or CSV. Until the LMS is connected, **Run demo LMS feed** seeds a book from the synthetic history and simulates one month per click.
- **SMA / NPA and provisions:** SMA-0/1/2 and NPA by days past due. An NPA is upgraded only when all arrears are paid (RBI, Nov 2021). RBI minimum provisions (0.4% standard, 10% sub-standard, doubtful by age and security) sit next to the **Ind AS 109 ECL** (stage 1/2/3, 12-month or lifetime PD × LGD × exposure). A shortfall of ECL below the minimum shows as an impairment reserve.
- **Early warning:** first instalment missed, days past due rising, bounces, behaviour PD, no payment for 45 days, restructured, fraud review. A score of 40 or more puts the loan on the watchlist with a suggested action.
- **Restructuring** (loan detail → Propose restructuring): tenure extension, moratorium (interest capitalised) or rate cut, with the new schedule. Maker-checker: a credit manager or the CRO approves, never the proposer. On approval the account is NPA (restructured) until a year of on-time payment under the new terms (RBI prudential framework, 2019).
- **Collections AI** (Portfolio → Collections): a ranked queue with likelihood to pay, next channel (WhatsApp, SMS, call, field visit, notice), best hour (learned from when the borrower answered before) and a script. Calls and visits can only be logged between 8 am and 7 pm. Promises to pay are marked kept or broken from the LMS feed.
- **Top-up offers** (Portfolio → Top-ups): good payers only (6 months on book, never 30+ days late, no recent bounce, not restructured, behaviour PD under 10%). Exposure is capped at 1.5× the original loan. Taking up an offer creates a pre-filled draft. The borrower gives fresh consent, and the draft is underwritten again.
- **Rate simulator** (Portfolio → Rate simulator): a rate change applied to the book's mix. It shows the effect on volume (elasticity), ticket size (EMI affordability), adverse selection, yield, NIM, expected loss and risk-adjusted income, with a −300 to +300 bps curve.
- **Compliance pack** (Portfolio → Compliance and returns): 15 checks computed from the records. They cover:
  - KFS before disbursement and APR shown
  - consent before data
  - rate cap, own-account disbursal, no disbursal under fraud review
  - delegated authority, restructuring maker-checker, contact hours
  - classification, provisions
  - Aadhaar never stored in full, append-only audit, independent model approval, bureau-reporting identifiers

  Each check shows its exceptions and evidence, and the pack downloads as CSV.
- **Regulatory returns:**
  - asset classification with NPA age classes
  - NPA movement for any period
  - exposure by segment, product and region, with top-20 concentration
  - the fortnightly credit bureau (CIC) file, with a readiness check. The full file contains PAN and is for compliance only.
- **Branch and officer scores:** out of 100, with the parts shown.
  - Branches: book quality, disbursals, speed, promises kept.
  - Officers: early delinquency, conversion, volume, complete data.
- **Policy assistant** (`/assistant`): answers from the live policy, products, delegation matrix, rules and reason codes. Give an application number to have its decision explained. Names, PAN, Aadhaar and mobile numbers are masked before anything leaves Rhythm.
  - With `ANTHROPIC_API_KEY` on the AI service, Claude writes the answer from the retrieved passages, with citations.
  - Without a key, the passages are quoted.

## Phase 2: AI copilot

- **Money rotation and adjusted turnover:** the statement AI finds round trips with the same party within 7 days and cash deposited then withdrawn within 2 days. It also separates self transfers, loan credits and refunds. The engine's income is the adjusted turnover, and R19 refers the case when rotation is above 30% of credits.
- **Household cash flow** (Customer tab): members, earners, expenses and repayments. For microfinance the household is the unit: R20 rejects household income above ₹3 lakh a year, and R21 caps all repayments at 50% of household income (RBI 2022).
- **Network risk** (Customer tab): links other PANs through a shared mobile, bank account, device, email or address, two hops deep. Shared accounts and devices and links to fraud cases raise the 0–100 score. The score feeds the fraud score, and R22 refers the case at 60. Switch it off with `rhythm.network.enabled=false`.
- **Industry scorecards** (kirana, dairy, transport, salaried, other MSME; Masters → Industry scorecards): a points table per industry, shown next to the PD model on every decision. They use field-visit readings such as stock against the loan, animals and vehicle age.
- **Model governance:** every candidate model scores each decision in shadow, and the CRO sees band and decision agreement. Drift is measured per feature and on the PD (PSI) against the training data, or against the loan book for the built-in models.
- **Fairness monitoring** (Operations → Fairness): approval rate, price and PD by gender, age band, PIN region and segment, with the four-fifths adverse-impact check.
- **Sanction letter and KFS in the borrower's language** (Sanction & KFS tab → link): English plus Hindi, Marathi, Tamil, Malayalam, Kannada, Telugu, Bengali or Gujarati, ready to print. The language the KFS was accepted in is stored. The lender's compliance team must check the translations.
- **Field officer app** (`/field`, installable on the phone):
  - Assigned visits are cached on the phone. Visits captured offline are kept encrypted on the phone and synced when back online.
  - Check-in GPS is checked to within 150 m of the business location saved on the application.
  - Each visit has a checklist per industry and required photos taken with the camera.
  - On sync, each photo is checked for time and place, reuse across all applications (reuse sends the case to fraud review), EXIF data, scene type, shelf fill and the owner's face against the KYC photo.
  - A browser cannot fully block gallery photos, so photos whose time or place don't match the visit are flagged.

## Phase 1 close (prototype v3.1 review)

Corrections:
- Pricing stays inside the product's rate band. Product eligibility covers age at maturity, vintage, minimum income and geography (R18 once branches have PIN codes).
- Missing minimum data gives an **INCOMPLETE** decision with the missing items; the decision never runs on partial data.
- A new-to-credit applicant needs a field visit; the decision is provisional and sanction waits for the visit.
- The sanction amount is re-checked against the approver's authority.
- Separate trained models for bureau-hit (THICK) and new-to-credit (NTC) applicants.
- Product changes are versioned and need CRO approval; each application keeps the product version it started on.
- PAN and account numbers are masked everywhere except the draft form. Staff see their own branch and the units under it.

Credit and process:
- Sanction: approve, approve with conditions, send back to credit (decision re-opened, the underwriter gets a My work task), escalate, or decline.
- **Review queue**: case cards with band, PD, reason codes, deviations and SLA.
- **Consent ledger**: one record per source with purpose, scope, validity, channel and language. Withdrawal stops further pulls and records its effect. Disbursement re-checks consents and new links.
- **Credit memo**: edit, justify each deviation, sign and lock. Versions are kept, and numbers not found in the file are flagged.
- **Data capture by stage** (FSD 11–12) on the Workflow tab, and the preferred language on the form.
- **Decision API**: `POST /api/v1/credit/decision` runs the same engine statelessly.
- **Credit policy**:
  - per-segment and per-product overrides
  - effective dates
  - CRO rollback and cancel
  - Simulation B on the live book (classification, behaviour PD, top-up and renewal effect, drill-down)

Admin and security:
- **Branches**: edit, deactivate, PIN codes and products per branch. Portal applications route by PIN code.
- **Masters**: holiday calendar (SLA clocks skip holidays), and a roles and permissions matrix with sanction limits as data.
- **Sign-in**: lockout after five failed sign-ins, MFA with an authenticator app (My account), admin unlock and MFA reset, and idle sign-out after 15 minutes (`rhythm.security.idle-timeout-minutes`).
- **Integration hub**:
  - per-connector on/off, timeout, attempts and circuit breaker
  - correlation IDs
  - masked log bodies
  - bulk retry
- **Audit trail**: append-only in the database, with CSV export.
- **Screens**: Overview, stage board, Customer 360 tab.

## Roadmap

| Phase | Scope |
|---|---|
| 1 (done) | Core LOS, decision engine, sanction, KFS, disbursement, admin, mock vendors |
| 2 (done) | **AI document and KYC OCR (in-house):** document classification; PAN, Aadhaar, voter ID, driving licence and passport extraction; Aadhaar masking; quality and tamper checks; cross-document matching; human review below a confidence threshold. Hybrid engine: self-hosted open-source OCR, with Claude vision for low-confidence cases |
| 3 (done) | **AI bank statement analysis (in-house):** text and scanned PDFs, multi-bank table parsing, transaction categorisation, balance continuity and fraud checks, cash-flow features for the decision engine |
| 4 (done) | **Video KYC with AI:** liveness, face match against the ID photo, geo-tag and timestamp, recording, and an agent console. RBI V-CIP requires a trained official to conduct the call; AI assists |
| 5 (done) | Policy studio with simulation and maker-checker, trained PD model with champion/challenger, workflow per product, borrower portal, reports, **workflow designer** (per-product stages with role, SLA, mode, failure action, skip rules; CRO publishes versions; in-flight applications keep theirs; SLA breaches escalate). Still to come: import of the NBFC's loan history, a trained fraud model, field officer app |
| 6 | **Third-party integrations:** online PAN, Aadhaar and CKYC validation, Account Aggregator, bureaus, eSign and eStamp, eNACH and UPI Autopay, penny drop, payouts, WhatsApp and SMS. Replaces the mocks through the existing vendor ports |
| 7 | Production hardening: multi-tenant, security audit and VAPT, monitoring, Kubernetes, DR |

All demo data is synthetic. The model coefficients are illustrative until they are trained on the pilot NBFC's data. Regulatory controls must be confirmed by the lender's compliance team.
