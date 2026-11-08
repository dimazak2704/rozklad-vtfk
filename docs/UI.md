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
/changes                  Changes (заміни) today / tomorrow / any date, grouped by group, filterable
/bells                    Bell schedule (profile in effect, today's highlighted)
/about                    About the system (methodology requires an «about» page) + Telegram bot link
/invite/:token            Teacher registration by invite
/login                    → Keycloak (PKCE). After login: ADMIN → /admin, TEACHER → /me

/me                       Teacher cabinet: my day/week, my substitutions (confirm/decline), report absence,
/me/absences              preferences (unwanted slots grid), connect Telegram (deep link + QR)
/me/preferences

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
/admin/settings           Approval mode, signatory, numbering, AI model/budget, public visibility, hours per pair
```

Public header: logo/wordmark «Розклад ВТФК», global search, parity badge, theme toggle, «Увійти».
Admin layout: compact left sidebar (icons + labels, collapsible), top bar with date switcher
(«Сьогодні / Завтра / дата»), command palette, user menu. Mobile: public bottom tab bar
(Розклад · Заміни · Дзвінки · Пошук); admin works on tablet+, phone gets read-only friendly views.

## 2. Visual direction — anti «AI slop» rules

The visual language must come from this product (a Ukrainian college timetable), not from a template.
Before coding UI, run the design skills (`impeccable`, `frontend-design`; then `better-*` for exact values)
and write the decisions to `web/DESIGN.md` (palette, type scale, spacing, radii, motion tokens, components).

Hard bans (the "slop detector" checklist — `web/scripts/slop-check.mjs` must grep the source and fail CI):
- purple/indigo→pink gradients, glassmorphism everywhere, neon glows, gradient text, random blobs;
- emoji as icons in UI chrome; stock "sparkles ✨ AI" decorations; generic 3-feature hero sections;
- centered-everything landing with a big CTA and no real content; lorem ipsum; fake testimonials/stats;
- uniform rounded-2xl cards with drop shadows for everything; identical icon-in-circle tiles;
- Inter/Roboto as the only face with default weights and no typographic hierarchy;
- grey-on-grey low-contrast text; meaningless placeholders («Welcome back!», «Get started»).

Positive direction (to be refined by the design skills):
- Feels like an official yet modern Ukrainian institution timetable board: dense, tabular, calm, precise.
  Think railway/airport departure boards and printed college schedules: strong grid, tabular numerals,
  clear time column, restrained colour used **semantically** (change = amber, cancelled = red strike,
  added = green, room change = blue, numerator/denominator = two distinct subtle patterns/tints).
- A distinctive but readable type pairing with full Cyrillic support (e.g. a grotesk with character for UI +
  tabular figures; optionally a condensed face for the timetable grid). Self-host via `@fontsource`.
- Discipline colours are deterministic per discipline (hash → palette with AA contrast in both themes).
- Light and dark themes from the same tokens; dark is not just inverted — tuned surfaces and accents.
- Motion is purposeful: layout transitions when lessons move, list reorder, toast/undo, skeleton shimmer,
  live pulse on realtime updates; respects `prefers-reduced-motion`.

## 3. UX rules

- Every icon-only button has a tooltip (Radix Tooltip, 300 ms delay) and `aria-label`.
- Every lesson cell has a hover card: full discipline name, teacher full name, room, time, change details
  (who replaced whom, reason), parity explanation.
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
13. **PWA** for public pages (installable, last viewed group cached offline).
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
