---
version: 1
slug: "src-layouts-publiclayout-tsx"
primary_target: "src/layouts/PublicLayout.tsx"
related_targets: ["src/layouts/AdminLayout.tsx","src/layouts/TeacherLayout.tsx","src/components/schedule/WeekGrid.tsx","src/components/schedule/DayList.tsx","src/pages/dev/KitchenSinkPage.tsx"]
---

# App shell and schedule system — «Розклад ВТФК»

Scope: the shared SPA shell (public, teacher, admin layouts), the design system and the schedule visuals
(lesson cell, day list, week grid, now indicator). Visitor mode: **Operate** (public visitors operate a
finder; staff operate working screens). Code-led build.

Audience and job: students and parents find today's/tomorrow's pairs and changes in seconds on a phone;
teachers confirm substitutions; the dispatcher builds the day's changes and order at a desktop before 08:30.
Constraints: Ukrainian UI, WCAG 2.2 AA, 360–1920 px, light + dark, UI.md §2 bans.

Run notes: no question channel (subagent). PRODUCT.md written from the brief with inferred facts labelled;
direction taken unattended from the roll (assigned grounded candidate 7).

## Direction contract

THESIS: The college timetable read the way Vinnytsia reads its trams. A stop timetable's column rhythm
(big pair numerals, small times) sets the grid; the day is drawn as a route line with the live marker on
the current pair; changes ride over the base schedule like a detour notice. Refuses the category default:
admin-template cards with an indigo accent, or calendar confetti blocks.

OWN-WORLD: Cool enamel-white ground, white panels, ink text, 1px blue-grey rules; a deep enamel route-blue
shell band (public header, admin rail) with white type; route blue is the one live colour (now, selection,
primary). Reserved overlay inks: amber detour (change/moved), red strike (cancelled), green (additional),
azure (room change), slate hatch (block). Every discipline owns one line colour shown as a dot and a faint
cell tint. Fira Sans for UI, Fira Sans Condensed for numerals, plates and grid cells, tabular figures (Sofia Sans was dropped: its Cyrillic defaults to Bulgarian letterforms, wrong for Ukrainian readers).
Small radii, no card shadows, flat fields only.

STORY: The visitor recognises a timetable at once, finds their group, sees where «зараз» is on the line,
spots every change by its detour ink with the original still visible, and leaves. Staff screens speak the
same language at higher density.

FIRST VIEWPORT: Route-blue band (56 px): wordmark «Розклад» + «ВТФК» in a white route plate, search field,
parity badge, theme toggle, «Увійти». Under it the day's route strip: pairs as stops on a line, times under
them, live marker on the current pair with «до кінця пари 23 хв» / «перерва до 11:00», then the timetable
grid with large pair numerals in the left column. Phone: vertical route line list, bottom tab bar.

FORM: grounded candidate 7 of 7 (Vinnytsia tram stop timetable and route line); seed:97a6d6c7b5b3524d.
Raise (datamatics): 1px rules on whole pixels and tabular figures wherever numbers stack; structure from rules, never card shadows.
Raise (orienteering map): changes ride as an overlay in reserved inks; the base lesson stays visible underneath, never silently repainted.
Raise (metro tiles): a real type ramp; the live pair and countdown at display size; one live colour owns every live state.
Raise (guide map): flat unmodulated colour fields only; no gradients, glass or grain.
Raise (character catalog): each discipline keeps one fixed line colour and short-name label everywhere it appears.
Verdicts: boarding pass + gate board competitive (clarity holds: a changed row stays lit until seen; audience loses: civil airports closed since 2022); metro tiles, guide map, orienteering, character catalog, datamatics declined.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## Unresolved

- No official college logo on hand; wordmark only.
- Page compositions beyond the shell belong to the page agents and inherit this world.
