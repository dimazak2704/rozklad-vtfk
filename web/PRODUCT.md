# Product

<!-- impeccable:product-schema 1 -->

> Source note: written by the web-foundation agent without a live interview (no question channel in this run).
> Every fact below comes from `docs/ARCHITECTURE.md`, `docs/DOMAIN.md`, `docs/UI.md`, the real files in `seed/`
> and the owner's spoken brief. Facts marked *(inferred)* are reasonable readings of those sources, not confirmed answers.

## Platform

web

## Users

- **Students and parents** (primary, never log in). They open the site on a phone in a corridor, at a bus stop
  or the evening before, to answer one question: *what do I have now / tomorrow, where, and did anything change?*
  They know their group code (`4КІ1`) by heart and type it in Latin as often as in Cyrillic *(inferred from the
  normalisation rules in DOMAIN §10)*.
- **Teachers** (log in via Keycloak). They check their own day/week, confirm or decline substitution requests,
  report absences and mark unwanted slots. Mostly on phones, often from Telegram links *(inferred)*.
- **Dispatcher / deputy director** (role ADMIN). Every school morning they turn absences into substitutions,
  collect confirmations and publish the daily «розпорядження». They work at a desktop, under time pressure,
  before the first pair at 08:30 *(inferred from the bell schedule and order workflow)*. Several times a year they
  edit the base timetable, run the generator, import Excel/DOCX/scans, and maintain the catalog and calendar.

## Product Purpose

«Розклад ВТФК» is the timetable and substitutions system of Vinnytsia Technical Professional College (ВТФК).
It replaces the printed Excel schedule, the Word orders photographed into group chats, and manual phone calls.
Success: a student finds today's schedule with changes in under five seconds without logging in; the dispatcher
publishes a correct order before the first pair; teachers confirm substitutions in one tap.

## Positioning

It speaks the college's own format instead of a generic calendar: numerator/denominator weeks, pairs from the
bell profile, subgroups, the official «розпорядження» layout with its reason codes («Відп.», «Зв.зам.», «Вичит.»),
ranked substitution candidates with reasons, confirmations through the college Telegram bot.

## Operating Context

- The printed schedule «Розклад друк» (course blocks, three columns per group: discipline / teacher / room,
  two rows per pair for numerator/denominator) — `seed/schedule-2025-26.xlsx`.
- Daily orders in the college Word layout, numbered per semester — `seed/orders/*.jpg`.
- Bell profile «2 зміни, 60 хв»: Mon–Thu 8 pairs from 08:30, Friday 6 pairs with different breaks.
- Telegram group chats per study group, bot `@scheduler_vtfk_bot`; QR codes on classroom doors *(planned, UI §4)*.
- The system is a 4th-year coursework project (курсова робота) that must look and work like a real college
  website, not a template.

## Capabilities and Constraints

- Public: schedule by group / teacher / room, changes by date, bells, about page, fuzzy search, ICS, invite registration.
- Teacher cabinet: my schedule, my substitutions, absences, preferences, Telegram binding.
- Admin: dashboard, changes, orders, timetable editor, generator, load, accounting, import, catalog, calendar,
  Telegram, settings.
- All UI text Ukrainian. WCAG 2.2 AA. Responsive 360–1920 px. Light and dark themes. Lighthouse mobile ≥ 85.
- Stack fixed in ARCHITECTURE §2 (React 19.3, Vite 8, Tailwind 4.3, Radix, …).

## Brand Commitments

- Name: «Розклад ВТФК». College: «Вінницький технічний фаховий коледж». Bot: `@scheduler_vtfk_bot`.
- No official logo or brand palette is on hand — do not invent a crest or claim an official identity.
- Explicit owner bans (UI §2): purple/pink gradients, glassmorphism, neon glow, gradient text, emoji icons,
  generic hero sections, lorem ipsum, fake stats, uniform rounded cards, Inter/Roboto-only typography,
  low-contrast grey text, «Welcome back!»-style placeholders.

## Evidence on Hand

- Real group codes, teacher short names, rooms and disciplines in `seed/schedule-2025-26.xlsx`.
- Real orders №35 (19.10.2026), №96, №105 in `seed/orders/`.
- Bell profiles and parity rules in `docs/DOMAIN.md` §2.
- No testimonials, usage numbers, photos of the college or logo files — never fabricate them.

## Product Principles

1. Schedule first: the public site answers «що в мене зараз» before anything else; no marketing page in front of it.
2. Changes must be impossible to miss and impossible to misread (original vs replacement, reason, room).
3. Speak the college's language and formats exactly (pairs, чисельник/знаменник, розпорядження).
4. The dispatcher's morning is time-critical: dense, keyboard-friendly, no decorative friction.
5. Honest data: show only what the system knows; empty and error states say what to do next.

## Accessibility & Inclusion

WCAG 2.2 AA contrast in both themes, full keyboard navigation with visible focus, `aria-live` for realtime
updates, `prefers-reduced-motion` respected, 360 px phones supported (UI §3).
