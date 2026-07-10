# Paka Device Test Result — `<candidate>`

Copy this file for each release candidate. Do not replace `NOT RUN` with `PASS`
without exercising the item on the recorded hardware. Use only synthetic
passes, TOTP secrets, and documents; never record live payloads or credentials.

Overall status: **NOT RUN**

## Candidate

- Release title: `<Paka version objective preview N, or stable title>`
- Tag: `<vX.Y.Z...>`
- Commit: `<full SHA>`
- Source branch: `<branch>`
- `versionCode` / `versionName`: `<code>` / `<name>`
- APK filename: `<Paka-v....apk>`
- APK SHA-256: `<digest>`
- Certificate SHA-256: `<digest>`
- Tagged workflow URL and result: `<URL>` — `NOT RUN`
- Local release checks: `NOT RUN`
- `tools/verify_release_apk.sh`: `NOT RUN`

## Hardware

- Tester: `<name>`
- Date and timezone: `<YYYY-MM-DD TZ>`
- Device: `Light Phone III`
- LightOS / Android build: `<build>`
- Previous installed Paka version: `<version>`
- Install path: `<upgrade or clean install>`
- Relevant Developer settings: `<defaults or differences>`

Use `PASS`, `FAIL`, `BLOCKED`, or `NOT RUN` in every result cell.

## Core and lifecycle

| Check | Result | Notes |
| --- | --- | --- |
| Upgrade preserves passes, 2FA accounts, PDFs, and photos | NOT RUN | |
| Pass home and 2FA home open correctly | NOT RUN | |
| Return-home default and disabled behavior | NOT RUN | |
| Reopen from pass, scanner, capture, PDF, and photo screens | NOT RUN | |
| App/task background stays black | NOT RUN | |
| TOTP tick stops in background and resumes correctly | NOT RUN | |
| Capture focus retry stops in background | NOT RUN | |
| PDF/photo sessions release and reopen correctly | NOT RUN | |
| Torch label and actual torch state agree after backgrounding | NOT RUN | |

## Camera and scanner matrix

| Sample | Normal light | Low light | Glare/damage | Exact payload confirmed | Notes |
| --- | --- | --- | --- | --- | --- |
| Dense Aztec | NOT RUN | NOT RUN | NOT RUN | NOT RUN | |
| QR | NOT RUN | NOT RUN | NOT RUN | NOT RUN | |
| PDF417 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | |
| GS1 DataBar Expanded | NOT RUN | NOT RUN | NOT RUN | NOT RUN | |
| EAN/UPC | NOT RUN | NOT RUN | NOT RUN | NOT RUN | |

## Capture, import, and viewing

| Check | Result | Notes |
| --- | --- | --- |
| Capture and crop one-sided document | NOT RUN | |
| Capture and crop two-sided document | NOT RUN | |
| Import and crop one photo | NOT RUN | |
| Import and crop two photos | NOT RUN | |
| EXIF-rotated and mirrored picker images enter crop with the expected orientation | NOT RUN | |
| Move all crop corners and the whole selection | NOT RUN | |
| Repeated explicit Rotate actions remain stable and hard-cut | NOT RUN | |
| Maximum permitted photo can be cropped and rotated without memory failure | NOT RUN | |
| Torch is actually off during review and remains off through retake | NOT RUN | |
| `FLAG_SECURE` remains active through capture → review → retake | NOT RUN | |
| No gallery, media-store, or temporary capture artifact | NOT RUN | |
| Barcode, PDF, and one-/two-sided photo viewing | NOT RUN | |
| Mixed stack paging and immediate side changes | NOT RUN | |
| PDF double-tap and photo pinch-only behavior | NOT RUN | |

## Localization and layout

| Check | Result | Notes |
| --- | --- | --- |
| All eight supported languages select, persist, and return to the language page | NOT RUN | |
| Hebrew, Arabic, and other unsupported languages are absent; layout stays LTR | NOT RUN | |
| Official and fallback fonts render every supported-language diacritic | NOT RUN | |
| Menus, forms, confirmations, viewers, scanner, and errors contain no English residue | NOT RUN | |
| Long translations remain readable without overlapping controls or indicators | NOT RUN | |
| Lists retain five fixed slots and one-page hard-cut scrolling in every language | NOT RUN | |
| Edge and non-scrollable gestures remain silent; real page changes vibrate once | NOT RUN | |

## Accessibility

| Check | Result | Notes |
| --- | --- | --- |
| TalkBack next/previous actions change one hard-cut page | NOT RUN | |
| Items after the first five remain reachable with TalkBack | NOT RUN | |
| Current page and unavailable edge actions are announced correctly | NOT RUN | |
| Back, bottom-bar, and reorder glyph labels are meaningful | NOT RUN | |
| Disabled reorder actions are exposed as unavailable | NOT RUN | |
| Manual-entry and pass-Details long-press actions are discoverable | NOT RUN | |
| Camera/capture light controls announce their actual state | NOT RUN | |

## Battery and memory observations

- Idle pass-list duration / battery change: `<duration>` / `<change or NOT RUN>`
- Idle 2FA-list duration / battery change: `<duration>` / `<change or NOT RUN>`
- PSS before repeated mixed-stack pass: `<value or NOT RUN>`
- PSS after repeated mixed-stack pass: `<value or NOT RUN>`
- Number of stack cycles: `<count or NOT RUN>`
- Peak PSS while cropping/rotating a maximum permitted photo: `<value or NOT RUN>`
- Observed stalls, heat, or memory pressure: `<notes or NOT RUN>`

Record how measurements were taken, for example the relevant `adb shell
dumpsys meminfo` commands, so a later candidate can be compared consistently.

## Privacy, backup, and release artifact

| Check | Result | Notes |
| --- | --- | --- |
| Forced brightness restores; disabled setting stays off | NOT RUN | |
| Clipboard clears only a Paka-owned TOTP value | NOT RUN | |
| Sensitive flows block screenshots; pass codes remain capturable | NOT RUN | |
| Barcode-only backup export/restore | NOT RUN | |
| PDF backup export/restore | NOT RUN | |
| One-/two-sided photo backup export/restore | NOT RUN | |
| External references remain links and outside backups | NOT RUN | |
| Legacy backup restores; wrong passphrase changes nothing | NOT RUN | |
| Back/cancel remains disabled while restore or encrypted save is committing | NOT RUN | |
| Downloaded release APK matches recorded digest and certificate | NOT RUN | |
| APK requests only the expected permission set | NOT RUN | |

## Findings and sign-off

- Blocking findings: `<none recorded; testing not yet complete>`
- Non-blocking findings: `<none recorded; testing not yet complete>`
- Follow-up issue/commit links: `<links>`
- Stable promotion approved: **NO — NOT RUN**
- Sign-off: `<tester and date after all required rows are resolved>`
