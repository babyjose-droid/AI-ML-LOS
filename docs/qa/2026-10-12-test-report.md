# Test report — Phase 1 and Phase 2 (12 Oct 2026)

**Result: all 54 automated tests pass on commit `b71a499`.** QA found 9 defects. All are fixed and covered by tests.

## What was tested

| Suite | Tests | Covers |
|---|---|---|
| Backend unit (JUnit) | 8 | Decision engine matches the prototype exactly; explanations add up; policy monotonicity; KFS/APR; state machine |
| Backend journeys (Testcontainers + PostgreSQL) | 10 | Straight-through to disbursement; L1/L2/L3 authority; AA outage → DLQ → manual retry; automatic retry; tampered document → fraud reject; referred case → field visit → reasoned L3 override; KYC review; roles; AI document review and correction; Aadhaar masking; KYC from documents; someone else's documents fail KYC |
| AI document service (pytest) | 18 | Validators (PAN, Aadhaar Verhoeff, MRZ); reading of 7 document types; Aadhaar masked everywhere; bad checksum; wrong type; blur; Photoshop edit; specimen flag; PDF input; API; rotation; uneven light; I/l confusion; weak field never auto-accepted |
| Browser journeys (Playwright on the Docker Compose stack, real OCR) | 6 | Demo data; new application with real document reading; L2 sanction → KFS OTP → disbursement; DLQ retry from the UI; role restriction in the UI; reviewer rejects an edited salary slip |
| QA API pack (Playwright) | 10 | Authentication incl. forged token and deactivated user; role matrix (8 roles × 18 actions = 144 checks); 13 validation cases; SQL-injection and XSS text; upload limits and types; cross-application document access; out-of-order actions change nothing; full OCR journey with an edited PAN replaced; full Aadhaar number never in any API response; concurrent double sanction; network fraud (shared mobile); error hygiene; security headers |
| Screen tour | 2 | Every page and all 9 Application 360 tabs at 1366 px and 390 px: no console errors, no server errors, no sideways scrolling; 44 screenshots |

## AI document reading accuracy

These figures come from 728 generated SPECIMEN documents: 8 fictional people × 7 document types × 13 photo conditions. Run `python -m tools.evaluate 8` to reproduce them.

| Condition | Fields read correctly | Accepted automatically | Sent to a person | Rejected as unreadable | Wrong value accepted |
|---|---|---|---|---|---|
| Clean scan | 99% | 98% | 2% | 0% | 0 |
| Phone photo on a table | 92% | 86% | 14% | 0% | 0 |
| Tilted 3° | 86% | 77% | 23% | 0% | 0 |
| Perspective skew | 95% | 89% | 11% | 0% | 0 |
| Sideways (90°) | 81% | 86% | 11% | 4% | 0 |
| Upside down | 95% | 95% | 5% | 0% | 0 |
| Mild blur | 96% | 93% | 7% | 0% | 0 |
| Heavy blur | 2% | 0% | 7% | 93% | 0 |
| Sensor noise | 99% | 95% | 5% | 0% | 0 |
| Low resolution (480 px) | 99% | 98% | 2% | 0% | 0 |
| Heavy JPEG compression | 96% | 91% | 9% | 0% | 0 |
| Uneven lighting | 99% | 98% | 2% | 0% | 0 |
| Dark photo | 99% | 98% | 2% | 0% | 0 |
| **All** | **88%** | **85%** | **8%** | **7%** | **0** |

By document type:

| Type | Fields correct | Auto-accepted |
|---|---|---|
| PAN | 91% | 88% |
| Aadhaar | 93% | 90% |
| Voter ID | 92% | 91% |
| Driving licence | 92% | 91% |
| Udyam | 91% | 90% |
| Salary slip | 92% | 92% |
| Passport | 69% | 51% |

The passport machine-readable zone (MRZ) is the weakest part on degraded photos, so those passports are routed to review rather than accepted. The average reading time is 1.9 s per document on 2 CPU cores.

**Limits of these numbers.** They come from generated cards with clean fonts, not from real documents. Real cards have holograms, background patterns, laminate glare and handwriting. Accuracy must be re-measured on masked real samples before the pilot.

## Defects found and fixed

| # | Defect | Severity | Fix |
|---|---|---|---|
| 1 | Sideways or upside-down photos were not read (0% fields) | High | Orientation detection (Tesseract OSD) with fallback |
| 2 | Uneven or dim lighting broke OCR (5% fields) | High | Background normalisation before OCR |
| 3 | A PAN card with a different PAN number only went to KYC review; clearing the review after video KYC would have let the case proceed | High | Number mismatch now fails KYC and cannot be cleared |
| 4 | Two OCR mistakes were auto-accepted (passport given name, salary net pay "13" for 131,000); a stray letter before a name was also accepted | High | Weakest-word confidence for names, MRZ/printed-name cross-check, joining split amounts, salary sanity checks |
| 5 | An 11 MB upload through the web server hung until the client gave up | Medium | nginx refuses uploads over 10 MB with a JSON 413; client-side size check; the backend also returns 413 |
| 6 | Callers in the wrong role got validation errors instead of 403, which revealed request structure | Low | Role rules enforced at the HTTP layer before the body is read |
| 7 | Bad JSON, wrong parameter types and unknown routes returned generic 500s, and some messages exposed Java types | Low | Specific 400, 404 and 405 errors with plain messages |
| 8 | Capital I read as lower-case l in names ("lyer") lowered name matching | Low | Correction at extraction, plus confusable-aware name matching |
| 9 | UI: "Waiting for" showed on finished cases; rule results said "Success"; long state labels were cut off | Cosmetic | Fixed |

**Confirmed working as designed:** the fraud check referred applications where one mobile number was shared by several PANs. This was first seen in QA test data and is now a dedicated test.

## Not yet tested

- Real (masked) KYC documents and phone photos. Samples are needed from the pilot NBFC.
- Load and performance under concurrent users.
- An external security test (VAPT) by a CERT-In empanelled auditor before production.
- Real vendors. These remain mocks until the integrations phase.
