# Rhythm LOS mobile app

One app for every role. Sales officers capture applications and documents at the customer's place, field officers do
visits, credit officers and managers decide and sanction, operations run the checks and disburse. Each person sees the
tabs and actions of their role, with the same rules as the web console. Masters, products, policies and reports stay on
the web console.

Built with React Native and Expo (SDK 58), TypeScript.

## What it does

| Role | On the phone |
|---|---|
| Sales officer | My work, search, new application in six short steps (with the shop's GPS location), KYC and other documents by camera or file, submit, product steps (asset, invoices, plan and pledge documents), borrower accepts the KFS with OTP |
| Field officer | Assigned visits with call and map, visit report: GPS check-in (distance from the business), product checklist, camera photos stamped with time and place, references, remarks; collateral and site photos; tranche inspections |
| Credit officer / manager / CRO | Decision, reasons and credit memo (sign), sanction with amount and conditions, send back, decline, escalate |
| Operations | Run automated checks, review documents, verify down payments, confirm and fund invoices, mark liens, cure margin calls, release tranches, disburse |
| Valuer | Collateral photos and the valuation report |

Also on the phone, for every product: co-applicants and guarantors (added in the wizard or later, consent and signature by
OTP on their own mobile, invite links, their KYC documents, income); the household (members, earners, expenses);
group loans (CGT by the field officer, GRT by credit); collateral (declare, documents, gold appraisal with weights and
purity at the day's rate, the gold rate itself, Collateral AI); video KYC (the live call runs inside the app; the
customer joins by link); and the set-up forms: asset, quotation and invoice details, buyer sub-limits for invoice
finance, plan details and construction or fee stages, deposit details, and exceptions on referred checks.

**Works without signal.** New applications, documents, photos and visit reports are saved on the phone in an outbox
and sent in order when the connection returns (the Outbox tab shows what is waiting; anything the server refuses is
kept with the reason so it can be fixed or discarded). Lists and cases opened earlier can be read offline. Decisions
(sanction, disbursement and so on) need a connection.

**Security.** The session token is kept in the phone's Keychain / Keystore. Everything kept on the phone about
customers (cases and lists read earlier, the outbox, drafts) is encrypted (XChaCha20-Poly1305) with a key made on the
phone and kept in the Keychain / Keystore; copies older than 3 days are not shown. Photos and files wait in the app's
own folder and are deleted once uploaded. Items in the outbox belong to the officer who captured them and are only sent
from their session. Aadhaar is masked to the last four digits on the server. Use HTTPS for a real deployment.

## Run it on your phone (Expo Go)

1. Start Rhythm on your computer as usual (`docker compose up`); the web console is on port 3000.
2. Find your computer's address on the Wi-Fi: on a Mac `ipconfig getifaddr en0` (for example `192.168.1.20`).
3. Install **Expo Go** on the phone (App Store / Play Store). The phone and the computer must be on the same Wi-Fi.
4. On the computer:
   ```bash
   cd mobile
   npm install
   npx expo start
   ```
5. Scan the QR code: with the Camera app on iPhone, or from Expo Go on Android.
6. On the sign-in screen enter the server address `http://192.168.1.20:3000` (your address, port 3000), then a demo
   user such as `sales1` or `field1` with the password `Rhythm@123`.

## Alerts and push notifications

Every new task in an officer's My work becomes an alert (the bell on My work), worked out by the server every minute.
In an installed build of the app the server also pushes it to the phone through the Expo push service, and tapping the
notification opens the application. In Expo Go, remote push is not available on Android, so new alerts are shown as
notifications while the app is open or in the background.

To turn on push for installed builds: run `eas init` in `mobile` (it adds the EAS project id to `app.json`), add the
Firebase (Android) and APNs (iOS) credentials with `eas credentials`, and make sure the server can reach
`exp.host` over HTTPS. Server settings: `RHYTHM_PUSH_ENABLED` (default true), `RHYTHM_PUSH_ACCESS_TOKEN` (if push
security is on in your Expo account), `RHYTHM_NOTIFY_SWEEP_MS` (default 60000).

## In a phone browser

`docker compose up` also starts the same app as a web page on port 3002: open `http://<computer address>:3002` on the
phone (or http://localhost:3002 on the computer with the browser's phone view). The automated tests record the journey
this way.

## Install builds (APK / iOS)

Store and test builds are made with Expo Application Services (needs an Expo account):

```bash
npm install -g eas-cli
eas build -p android --profile preview   # an APK to install directly
eas build -p ios                           # needs an Apple developer account
```

A release build should talk to the server over HTTPS (Android blocks plain HTTP in release builds).

## Develop

```bash
npm install
npx tsc --noEmit                                    # type check
EXPO_OFFLINE=1 npx expo export --platform web       # web build into dist/
```

Code: `App.tsx` (role tabs and screen stack), `src/lib` (API client, outbox, files, camera and GPS), `src/screens`.
Files ending in `.native.ts` are used on phones, the plain `.ts` files in the browser build.
