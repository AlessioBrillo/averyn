# TDD-0015: The consumer experience: design system and the three moments

- **Status:** draft
- **Version:** 0.1
- **Author:** @AlessioBrillo
- **Date:** 2026-10-31
- **Related:** [ADR-0028](../adr/0028-consumer-launch-target.md), [launch checklist](../product/mvp-launch.md), [device matrix](../testing/device-matrix.md) rows S43-S48, issues #122 (this design), #123 (Android), #124 (iOS), #125 (progress), #127 (share card), #128 (voice cues), #129 (onboarding and accessibility), [TDD-0011](TDD-0011-mobile-read-path.md)

## 1. Summary

The main screen of both apps is a developer screen today: a server field, a column of buttons, raw numbers. This design fixes the look and the structure of the three moments the owner chose in ADR-0028 (recording, the summary right after, history and progress), plus the first run, so the two apps can be built against one reference. **It is a draft until the owner approves the mockups**; no screen is built before ([launch checklist](../product/mvp-launch.md), gate 4).

The mockups are four phone screens and a token sheet, shared with the owner as a private canvas (recording, summary, history and progress, first run, tokens). This document records the decisions behind them, so they survive the canvas.

## 2. Goals and non-goals

Goals:

- A recording screen readable at arm's length, in the sun, while moving; a summary that answers "how did it go" in one look; a history that shows weeks, months and records without a server.
- Privacy that is visible: where the data lives, what a private zone hid, what was lost. Recording and viewing work with no account.
- Both phones look the same in structure and say the same things (the numbers come from `shared/`).
- Accessible by construction: contrast, large text, screen readers, targets (S48).

Non-goals: a map style, a logo or a public name (owner's decision, ADR-0008), social features, a watch app, a tablet layout, a dark theme beyond the token mapping below.

## 3. Functional requirements

| Id | Requirement |
|---|---|
| U1 | **Navigation** is four tabs: Record, Activities, Progress, Settings. The recording screen is full screen and has no tab bar while an activity runs. |
| U2 | **Recording** shows, in this order of size: the moving time, the distance and the pace, the heart rate with its zone, the last kilometre and the voice-cue setting. The controls are Pause (the largest target) and a press-and-hold Finish, so a pocket cannot end an activity. GPS and strap state are two small chips, never hidden. |
| U3 | **Units** follow the profile (km or miles, min/km or min/mi) everywhere, including the voice cues. Today the screens show metres and seconds (audit 2026-10-30). |
| U4 | **Summary**, shown at once when an activity ends: date and name, the distance as the hero, time, pace and average heart rate, the route with the private zones drawn as hidden, the kilometre splits, the GPS quality and the count of samples lost, where the activity lives (on this phone only, or synced). Actions: Done, Share card. The diagnostics dump is not this screen: it stays in the history, under export. |
| U5 | **Progress**: week or month totals as a number and a bar chart of the last eight periods, a month calendar with the active days, the newest personal record. Computed by `progressTotals` and `activityDays` in `shared/client` (#130), so it works without an account. |
| U6 | **First run**: four short steps. Step 2 explains the location permission before the system asks (why, "Always" and "Precise", what leaves the phone) and offers "Not now". The account is never a step: sign-in lives in Settings. |
| U7 | **Advanced** (under Settings) holds the server address and the diagnostics export. Nothing about a server appears on the main screens. |

## 4. Non-functional requirements

- **Contrast:** text at least 4.5:1, large text and graphics at least 3:1, measured on the tokens below (the ratios are on the token sheet).
- **Text size:** every number is a scalable text style; at the largest accessibility size the layout stacks and scrolls, nothing is cut or overlapped. The recording screen is checked at the largest size first.
- **Colour is never the only signal:** the heart-rate zone is a number and a segment count as well as a colour; the current bar in a chart differs in lightness and is named.
- **Targets:** 56 pt or more for anything touched while moving.
- **Battery:** no animation on the recording screen except the clock; the screen follows the system's dark setting (OLED saving) with the same tokens.
- **Privacy:** no analytics, no remote fonts in the apps (the mockups load a web font; the apps bundle theirs or use the system font), no coordinates in a log.

## 5. Design

### 5.1 Tokens

One source of truth, `docs/design/tokens.md` once approved, then the platform files (`ui/theme/` on Android, an asset catalogue on iOS) built from it. A web equivalent (CSS variables) for the web app.

| Token | Light | Use | Contrast |
|---|---|---|---|
| ground | `#F5F3EE` | screen background | |
| surface | `#FFFFFF` | cards | |
| ink | `#14181C` | text | 16:1 on ground |
| secondary | `#4A5560` | labels | 6.9:1 on ground |
| accent | `#0B6B58` | actions, the current period | 6.4:1 on white |
| heart | `#B42318` | heart rate, the stop action | 6:1 on ground |
| series | `#77908B` | past bars | 3.1:1 on ground |
| rule | `#D9D4C8` | dividers (decoration) | |

Dark: ground `#0E1113`, surface `#1A1F22`, ink `#F2F0EA`; accent, heart and series are re-picked to keep the same ratios on the dark ground (to be validated with the contrast check before the tokens file is written; they are not guessed here).

Type: one family, tabular numerals. Hero number 104 (recording) / 84 (summary), metric 52, title 28, control 17 bold, label 13 semibold with tracking. The family is Figtree in the mockups: an open licence (OFL) it is bundled with the app, not loaded from the network; the owner can choose another, nothing else depends on it.

Shape: cards radius 20, buttons are pills, spacing 4, 8, 12, 16, 24, 32.

### 5.2 Structure of the code

- Android: `MainActivity.kt` (about 1,000 lines) splits into `ui/theme/`, `ui/record/`, `ui/summary/`, `ui/progress/`, `ui/settings/`, `ui/onboarding/`; navigation is a single `NavHost`. The state stays where it is (`AverynApplication`, `TrackingService`).
- iOS: `ContentView.swift` splits the same way into views and a view model per screen; a test target is added to `project.yml` with the first view model that has logic (Progress).
- Shared: units and formatting stay in `shared/client` (`UnitFormat.kt`); progress in `Progress.kt`; the cues in `SplitCueTracker` (#131). A new shared object, if one is needed, goes there and not in a phone.

### 5.3 Accessibility

Every control has a label; the charts have a text equivalent (a table the screen reader can read: period and total); the route map has a description; the calendar days are buttons named by their date and count; the hold-to-finish control has an alternative (a confirm dialog) when a screen reader is on. Headings are real headings. The first-run step indicator is announced as "step 2 of 4".

### 5.4 What is deliberately not here

A map on the recording screen (it costs battery and attention while moving; the summary has it), social, a feed, coaching, a paywall (ADR-0028).

## 6. Test strategy

- Logic is tested where it lives (`commonTest` for progress and cues).
- Screens are checked on the matrix: S47 (readability outdoors), S48 (large text, screen reader, contrast), S43-S46 for the features. Android gets Compose screenshot or semantics tests for the recording and summary screens; iOS gets the same through its test target once it exists.
- The contrast ratios of the token table are recomputed by a script in CI when the tokens file exists, so a token change cannot lower them unseen.

## 7. Rollout and migration

Order: approve this design (owner), tokens file, Android screens (#123), iOS screens (#124, needs the Mac), progress (#125), share card (#127), voice cues (#128), first run and accessibility (#129). The old screen stays reachable under Advanced until the new ones are verified on the matrix.

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| The look is approved on a mockup and fails outdoors | S47 is a gate; the recording screen is the first built and the first tested |
| The two apps drift | Structure and numbers come from one document and `shared/`; the screens are compared side by side on the matrix |
| A web font or an icon set adds a network call or a licence problem | Bundled font under an open licence; icons drawn as vectors in the repository |
| The summary invites a cloud nobody asked for | The summary says where the data is; sync is a Settings choice |

## 9. Open questions

| Question | Owner | Becomes |
|---|---|---|
| Approve the mockups, or what changes (the owner) | owner | status `approved` |
| The font (Figtree or another) | owner | tokens file |
| Dark theme colours | developer, with the contrast script | tokens file |
| The wording and the voice of the cues (distance, time, pace; per kilometre or mile) | owner | TDD of #128 |
| Public name and logo | owner | ADR-0008 follow-up |
