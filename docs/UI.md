# UI / UX spec — web SPA and Telegram bot

All UI text in **Ukrainian**. The owner's explicit requirements: a real-looking college website (not a generic
admin template), very high usability, intuitive, hovers and tooltips everywhere, nice motion, light + dark theme,
**no "AI slop" look**, and small «wow» conveniences.

## 1. Information architecture (public first)

The main audience is students, parents and teachers who just want *the schedule now*. They never log in.

```
/                         Public home = schedule finder (not a marketing page)
/g/:code                  Group schedule (e.g. /g/4КІ1) — day / week view, changes highlighted
/t/:id                    Teacher schedule (public short names only)
/r/:id                    Room schedule + occupancy
/changes                  Changes (заміни) today / tomorrow / another date, grouped by group, filterable. Guests:
                          dates from publicHistoryFrom on (an older one shows «Цей день уже в архіві»); staff: any
                          date. /g, /t, /r follow the same window by whole weeks (CONTRACTS §1 «Public history window»)
/bells                    Bell schedule (profile in effect, today's highlighted)
/about                    About the system (methodology requires an «about» page) + Telegram bot link
/invite/:token            Teacher registration by invite
/login                    → sign-in service /auth/login (code + PKCE). After login: ADMIN → /admin, TEACHER → /me
/login/callback           OIDC callback (the code is exchanged here, then the role decides where to go)

/me                       Teacher cabinet: my day/week, my substitutions (confirm/decline), report absence,
/me/absences              preferences (unwanted slots grid), connect Telegram (deep link + QR).
/me/preferences           /me?change={id} (link in e-mails) scrolls to that request and highlights it
/me/notifications         «Сповіщення»: which e-mails about my substitutions reach the corporate mailbox
                          (one switch per kind + a master switch, applied at once), the address read-only with
                          what to do when it is missing or unverified, a test letter

/admin                    Dispatcher dashboard for today/tomorrow
/admin/changes            Absences → affected lessons → ranked options → confirmations (main daily screen)
/admin/orders             Order drafts by date, preview in college layout, publish, amend, archive + search
/admin/timetable          Base timetable editor (grid, drag & drop, conflicts, versions, diff, publish)
/admin/generation         Generator: params, live progress, before/after metrics, apply as draft
/admin/load               Load (навантаження) per group/semester with weekly pattern visualisation
/admin/accounting         Plan/fact/forecast, «вичитано» alerts with one-click actions, teacher stats
/admin/import             Upload Excel/DOCX/PDF/photos, review drafts, resolve mappings, apply
/admin/catalog/*          Groups, teachers, rooms, disciplines (+aliases): list with search/filter/sort, forms
/admin/calendar           Semesters, parity overrides, bell profiles, periods (holidays, practice, study modes)
/admin/telegram           Bot status, chat bindings, binding codes/deep links, delivery log
/admin/notifications      «Сповіщення»: mail server status line (SMTP / Mailpit / off), my dispatcher e-mails
                          (+ substitution e-mails if the account has a teacher), «Журнал листів» (masked addresses)
/admin/settings           Approval mode, signatory, numbering, AI model/budget, public visibility, hours per pair
```

Public header: logo/wordmark «Розклад ВТФК», global search, parity badge, theme toggle, «Увійти».
Admin layout: compact left sidebar (icons + labels, collapsible), top bar with date switcher
(«Сьогодні / Завтра / дата»), command palette, user menu. Mobile: public bottom tab bar
(Розклад · Заміни · Дзвінки · Пошук); admin works on tablet+, phone gets read-only friendly views.

## 2. Visual direction — anti «AI slop» rules

The visual language must come from this product (a Ukrainian college timetable), not from a template.
Before coding UI, run the design skills (`impeccable`, `frontend-design`; then `better-*` for exact values)
and keep the decisions in `web/DESIGN.md` (palette, type scale, spacing, radii, motion tokens, components).

Hard bans (the "slop detector" checklist — `web/scripts/slop-check.mjs` must grep the source and fail CI):
- purple/indigo→pink gradients, glassmorphism everywhere, neon glows, gradient text, random blobs;
- emoji as icons in UI chrome; stock "sparkles ✨ AI" decorations; generic 3-feature hero sections;
- centered-everything landing with a big CTA and no real content; lorem ipsum; fake testimonials/stats;
- uniform rounded-2xl cards with drop shadows for everything; identical icon-in-circle tiles;
- Inter/Roboto as the only face with default weights and no typographic hierarchy;
- `light-dark()` inside token variables (it resolves at `:root`, so light islands turn dark);
- grey-on-grey low-contrast text; meaningless placeholders («Welcome back!», «Get started»).

Positive direction — «Повітря» з живим часом (round 8; the full system is `web/DESIGN.md`):
- **A calm personal calendar where only time moves.** One sky-blue signal marks "now" (the countdown, the
  now-line, the current pair); everything else is ink on a cool canvas with hairlines, quiet until the user
  hovers, taps or asks. The earlier "tram stop timetable" world (navy band, condensed digits, route plates,
  departure-board rows) is retired.
- **One loud thing per screen.** Home leads with the countdown («4 пара · 38 хв до дзвінка»), a group page
  with the group code and the week grid, admin «Заміни» with the decision. Say it in a sentence, not in a
  number: «Далі перерва 20 хв, 5 пара о 13:30», «Вирішено 3 з 7 пар» — no KPI tile rows, no zero counts.
- **Restraint budget.** Per area: one primary button, at most two secondary, the rest in `⋯`; rows show no
  buttons at rest; parity and week number once per screen; no description under page titles.
- **Every page has a way back.** `PageHeader` shows «‹ Розклад» / «‹ Мій розклад» from the route registry;
  the mark always leads home; no dead ends — empty states say what happens next and offer one action.
- **States are shape and word first, colour second:** change = amber wash + the old value struck through,
  cancelled = red strike and «Пари не буде», added = green wash, room change = `ауд. ~~301~~ 208`,
  moved = dashed outline; numerator/denominator = a half-filled disc, never a colour.
- **Type:** Manrope (UI and text) and Geologica (headings and tabular figures), both OFL via
  `@fontsource-variable`, full Ukrainian Cyrillic. No uppercase labels, no eyebrows.
- **Discipline colour** is only an 8 px dot before the title (five hues from `lines.css`), never a fill.
- **Themes follow the system** by default; the override lives in the footer and the user menu. Dark is
  layered by lightness (higher layer = lighter), washes stay low-chroma, the accent flips lighter with dark
  text on it. Light «paper» islands (`data-theme="light"`) stay light in dark mode.
- **Motion only answers time and state:** the now-line glides, the countdown digits roll, a finished pair
  settles, a live change draws its strike once; nothing loops except the spinner, and
  `prefers-reduced-motion` turns it all off.

## 3. UX rules

- Every icon-only button has a tooltip (Radix Tooltip, 300 ms delay) and `aria-label`.
- Every lesson cell has a hover card: full discipline name, teacher full name, room, time, change details
  (who replaced whom, reason), parity explanation. The kind line («Вид») is shown for a lecture, practice, lab
  or seminar only; `MIXED` (unknown or combined, e.g. load derived from the timetable) hides it.
- Teacher names, one formatter (`web/src/lib/teacher-name.ts`): grid cells, change lists and chips write
  «Прізвище І. Б.» (initials from the college short form, completed by id from the name directory — public
  teachers with `showTeacherFullNames`, the admin catalog on the board — when the short form has fewer; two-letter
  initials of namesakes stay); the hover card,
  lists and the teacher page title write «Прізвище Імʼя По батькові» when the name is known and, on public pages,
  `showTeacherFullNames` is on, otherwise «Прізвище І. Б.». Orders and print keep the college short form.
- Dated audit lines: an event of another day shows the date, «відмовився 14.11 о 01:15»; today only the time.
- Loading = skeletons shaped like the content; errors = inline with retry; empty states = explain + next action.
- Forms: react-hook-form + zod, inline Ukrainian messages, keep input on server errors, disable submit while pending.
- Optimistic updates for small edits with undo toast; destructive actions confirm with the object name.
- Keyboard: full keyboard nav, visible focus, `Ctrl/Cmd+K` command palette, `?` shortcut sheet,
  `←/→` previous/next day, `T` today, `G` go-to group.
- URLs are shareable (selected date/view in query params). Responsive 360–1920 px.
- Accessibility: WCAG 2.2 AA contrast, semantic landmarks, labels, `aria-live` for realtime changes.
- Performance: route-level code splitting, Lighthouse mobile ≥ 85 Performance/Accessibility, ≥ 90 Best Practices
  on public pages; virtualise big grids.

## 4. «Wow» micro-features (pick and polish, don't overdo)

1. **Live "now"**: current pair highlighted with a thin progress bar and «до кінця пари 23 хв» / «перерва до 11:00».
2. **Parity badge** «Зараз ЧИСЕЛЬНИК · 8 тиждень» with tooltip explaining the rule.
3. **Instant fuzzy search** tolerant to Latin/Cyrillic and typos: `4ki1` → 4КІ1, `кренц` → Кренцін, `6` → ауд. 6.
4. **Diff-style changes**: original struck through, replacement highlighted, reason chip; filter «лише мої зміни».
5. **Ranked candidates** with score bars and «Чому?» popovers listing reasons; keyboard 1–9 to pick.
6. **Realtime** (SSE): a teacher confirms in Telegram → the admin card flips to «Підтверджено» with a pulse + toast.
7. **Generator live chart** of score while solving + «Було / Стало» metrics (вікна 42 → 7, рвані дні 15 → 0).
8. **Timetable editor**: drag & drop with green/red drop targets (conflict preview), `Ctrl+Z`, version diff.
9. **Order preview** pixel-close to the college Word layout; download DOCX/PDF/PNG; «Копіювати PNG» for Instagram.
10. **QR codes**: group schedule QR to print on the classroom door; Telegram deep link QR for binding.
11. **Calendar subscription** (ICS) for a group/teacher — «Додати в Google Календар».
12. **Share** today's schedule (Web Share API) and print-friendly A4 week view.
13. **PWA** for public pages (installable, last viewed group cached offline; only token-less answers are cached).
14. **View Transitions** for theme switch and page changes where supported.

## 5. Telegram bot (@scheduler_vtfk_bot) flows

Language Ukrainian, concise, buttons first, commands as shortcuts. Never show teachers' phones/emails.
- `/start` (private): greeting + main keyboard: «📅 Сьогодні», «Завтра», «Моя група», «Знайти», «Дзвінки»,
  «Чисельник?». (Emoji are acceptable inside Telegram messages/buttons — this is the platform convention.)
- `/start <code>` deep link: binds a teacher (private) or confirms subscription; reply with who was bound.
- Group chat: `/bind <code>` from the web panel binds the chat to a group; after each published order the
  chat receives only its rows (compact text) + PNG of the order (always, no switch); `/today`, `/tomorrow` in the chat
  answer for that group; `/status` says what the chat receives. The morning schedule at 07:30 is opt-in for group
  chats: `/rozklad_on` / `/rozklad_off`, only chat administrators («Змінити це може лише адміністратор чату.»);
  the admin's Telegram page has the same switch «Ранковий розклад о 7:30».
- Public private-chat flow: «Моя група» → choose course (inline 1–4) → choose group → saved subscription;
  then «Сьогодні»/«Завтра» answer immediately; optional morning digest at 07:30 on study days.
- Teacher private flow: «Мої пари завтра», «Повідомити про відсутність» → dates (today/tomorrow/calendar
  inline) → pairs (all / pick) → reason (buttons) → confirm. Free text («4.11 не буду, лікарняний»)
  is parsed by AI and echoed back for confirmation with buttons [Так] [Змінити].
- Substitution to a teacher (default NOTIFY, DOMAIN §7): «Вам поставили заміну» with date, pair+time, group,
  discipline, room, original teacher (for another discipline: «🔁 Замість «…» (…)»), buttons [👍 Добре] [❌ Не можу];
  no deadline. In mode CONFIRM a request with a deadline and [✅ Підтверджую] [❌ Не можу], reminders on timeout.
  After a reply the message is edited to the final state.
- Admin chat: new absences, declined/expired confirmations, import failures, order published summary.
- Inline mode: `@scheduler_vtfk_bot 4КІ1` returns today's schedule card.
- Rate limits respected (queue); long replies split; HTML parse mode with escaping.
