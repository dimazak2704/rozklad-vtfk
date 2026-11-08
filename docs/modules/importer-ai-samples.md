# importer — live AI samples (07–08.11.2026)

One live run per sample through the real importer (jar on port 18083, own database, multipart upload with an
admin token, core unreachable on purpose) with the Anthropic key from `.env`. Models: vision
`claude-sonnet-5-5` (effort `medium`), text `claude-haiku-5-5` (effort `low`); structured outputs with the
schemas of `AiSchemas`. Every call went through `AiExtractionService` (budget check, usage row).

| # | Sample | Purpose | Input / output tokens | Cost (price table) | Time |
|---|---|---|---|---|---|
| 1 | `seed/orders/order-035-2026-10-19.jpg` | ORDER_DOCUMENT | 3 179 / 2 576 | $0.0321 | 31 s |
| 2 | `seed/orders/order-096-2026-05-22.jpg` | ORDER_DOCUMENT | 3 425 / 3 465 | $0.0415 | 18 s |
| 3 | `seed/orders/order-105-2026-06-04.jpg` | ORDER_DOCUMENT | 3 329 / 5 174 | $0.0584 | 27 s |
| 4 | `seed/curriculum-ki-2023.pdf` (5 pages), semester 7 | CURRICULUM | 9 657 / 3 021 | $0.0495 | 24 s |
| 5 | «не зможу провести пари, приболів трохи» | ABSENCE_TEXT | small | < $0.001 | < 2 s |
| | **Total** (`GET /api/import/ai/usage`) | 5 calls | 20 707 / 14 349 | **$0.1817** | |

Output tokens include adaptive thinking. Two more parse-absence messages were answered by the rules without AI.

## 1. Order №35 (19.10.2026, ПОНЕДІЛОК – ЗНАМЕННИК)

Header, notes and signature: all correct (number 35, date 2026-10-19, weekday, parity, 5 notes verbatim,
«Заступник директора з НР» / «Микола ПОЛІЩУК»).

| Row (my reading of the photo) | AI | |
|---|---|---|
| 1ТК1 · 1-4 · ЗУ Ємельяненко · 204 | BLOCK, same | ✔ |
| 1ОП2 · 4 · — · БЖ Погосян · 403 | ADDITIONAL, same | ✔ |
| 1ТК1 · 1 · Ін. мова Крисак Л. (п.) · Відп. · Пари не буде | CANCELLATION, same | ✔ |
| 1КІ2 · 2 · Ін. мова Крисак Л. · Відп. · Ін.мова Прокопчук · 110 | teacher «Крисак Л. (п.)» | ✘ extra «(п.)» |
| 1М · 3 · Ін. мова Крисак Л. · Відп. · Ін.мова Прокопчук · 110 | teacher «Крисак Л. (п.)» | ✘ extra «(п.)» |
| 1ЕА2 · 3 · ЕТ Масловата · Зв.зам. · Культ. Шароварська · 405 | same | ✔ |
| 2ОД · 4 · — · ІКТ Вернигора · 309 | ADDITIONAL, same | ✔ |
| 2ОД · 6 · ІМД Масловата · Зв.зам. · ОД Яцуба · 308 | same | ✔ |
| 2ОБ · 7 · ЕіФБ Охота О. · Зв.зам. · ІКТ Вернигора · 304 | same | ✔ |
| 2ОБ · 8 · ІМД Масловата · Зв.зам. · ТБО Фабіянська · 409 | same | ✔ |
| 2М · 8 · ЕіФБ Охота О. · Зв.зам. · Укр.літ. Кучинська · 303 | discipline «Укр.діт.» | ✘ one letter |
| 2М · 5 · ІМД Масловата · Зв.зам. · Вс.іст. Бадунь · 409 | teacher «Балунь» | ✘ one letter |
| 2Ф · 7 · ІМД Масловата · Зв.зам. · ЕіФБ Кукурудзяк · 408 | same | ✔ |
| 3Ф · 4 · Ін.мова Крисак Л. (п.) · Відп. · Пари не буде | CANCELLATION, same | ✔ |
| 4КІ1 · 2 · СТП Кренцін · Зв.зам. · НДКС Непийвода · 6 | same | ✔ |
| 4КІ2 · 4 · СТП Кренцін · Зв.зам. · ОПЦЗ Ганза · 6 | same | ✔ |
| 1ОП2 · 4 · — · БЖ Погосян · 403 (printed twice in the original) | same; the model warned about the duplicate | ✔ |

**13 of 17 rows exact**; all row types, groups and pairs right; 4 single-cell errors. «Балунь» and «Укр.діт.»
are exactly the cases catalog matching turns into suggestions («Бадунь», «Укр.літ.») that the admin confirms.
The model also reported that the «ЗАМІНА АУДИТОРІЙ» section is empty.

## 2. Order №96 (22.05.2026, П'ЯТНИЦЯ – ЗНАМЕННИК)

Header and 5 notes correct; position «Заступник директора з НМРтаІТ» correct, name read as «Микола ПОЛЩУК» (✘,
should be «ПОЛІЩУК»).

All 26 rows have the right group, row type (BLOCK, 6 × ADDITIONAL with «ДОДАТКОВО», 2 × CANCELLATION, 5 × FINISHED
«Вичит.», REPLACEMENT) and texts, **except one pair number**: «1М · 2 · ОП Масловата · Зв.зам. · Укр.літ.
Кучинська · 303» came back as pair 1 (✘). **25 of 26 rows exact.** The model's warnings pointed at the missing
reason in «2ТК 5» and the missing room in «2КІ1 5» — both are really empty in the original.

## 3. Order №105 (04.06.2026, ЧЕТВЕР – ЗНАМЕННИК) — the hardest photo

Header, signature and all 8 «ЗАМІНА АУДИТОРІЙ» rows correct (1РТ1 1/2 → 401, 1РТ2 3/4 → 403, 1ОБ 1, 1ВЕ 2, 1ЕА1 3 →
301, 1ЕА2 3 → 410). Notes correct except «ЗОД» (Cyrillic З instead of the digit 3 in «3ОД»).

Correct and notable: AS_SCHEDULED «1ОБ · 4 · За розкладом»; subgroup cells «Харченко О. / Михальчук» → «Овчар Ін. /
Овчар Ів.» with rooms «304 / 309»; «Крисак Л. / Харченко О.»; the block rows «3КІ1 1-3», «3КІ2 1-4», «3КІ3 1-4»
(«Вичит.») as BLOCK.

Errors (✘):
* five rows of the 3РТ1/3РТ2 block read as **1РТ1/1РТ2** (the model itself warned that this part of the table
  was read imprecisely);
* «1ЕА2 · 2» lost its reason «Зв.зам.» (warned);
* «ФіА» read as «ФіЗ.»; «Голєва» read as «Голева» (twice);
* «1КІ1 · 1 · ФК Бурбела · Зв.зам.» with an empty right side was typed FINISHED (the original is itself
  ambiguous; the model explained its choice in a warning).

**31 of 40 rows exact (78 %).** The group-code errors are the dangerous kind: «1РТ1» exists, so matching cannot
catch them. This is why AI-recognized orders always get the INFO row «звірте рядки з оригіналом» and are only
imported after review.

## 4. Curriculum КІ 2023 (ред. 2025), semester 7 — compared with DOMAIN §4

Result: `weeks = 22`, `totalHoursPerWeek = 31`, 12 rows.

| Discipline (as recognized) | h/week | h/sem | lect / pract / lab | control | DOMAIN §4 |
|---|---|---|---|---|---|
| Іноземна мова (за професійним спрям.) | 2 | 44 | 6 / 80 / — | credit | 2 ✔ |
| Українська мова (за проф.спрямув.) | 2 | 44 | 22 / 22 / — | exam | 2 ✔ |
| Фізичне виховання | 2 | 44 | 4 / 134 / — | credit | 2 ✔ |
| Системне програмування | 2 | 44 | 24 / — / 20 | credit | 2 ✔ |
| Комп'ютерні мережі | 4 | 88 | 48 / — / 40 | exam | 4 ✔ |
| Надійність, діагностика та експлуатація комп'ютерних систем та мереж | 2 | 44 | 24 / — / 20 | credit | 2, 24 + 20 = 44 ✔ |
| Економіка та управління ІТ-проектами | 3 | 66 | 24 / 20 / 20 | exam | 3 ✔ |
| Охорона праці та цивільний захист | 2 | 44 | 20 / 24 / — | exam | 2 ✔ |
| Соціологія і чинники успішного працевлаштування | 2 | 44 | 22 / 22 / — | credit | 2 ✔ |
| Вибірковий освітній компонент 1, 2, 3 | 4 each | 88 each | 48 / 16 / 24 | credit | not listed in DOMAIN |

Checked against the scan myself (pages 3–4): every value above, including the exam/credit columns and the three
electives (ВК1–ВК3: 5 credits, 150 h, 88 classroom, 4 h/week in semester 7), is as printed. All nine disciplines of
DOMAIN §4 are found with the right weekly hours, and НДЕКСМ's 24 + 20 = 44 h confirms the program/plan mismatch
(the program says 40).

Validation warnings produced by the importer, all genuine properties of the document:
* weekly hours of the rows sum to **33**, the «Тижневе навантаження» row says **31** — physical education (2 h) is
  not counted in the weekly load norm;
* Іноземна мова: 6 + 80 = 86 ≠ 108 classroom hours; Економіка: 24 + 20 + 20 = 64 ≠ 66 — as printed in the plan.

The model noted the low scan quality and skipped «Мультидисциплінарний курсовий проєкт» (no weekly hours in
semester 7) — correct.

## 5. parse-absence

| Message (today 2026-11-07) | Answer | Source |
|---|---|---|
| «4.11 не буду, лікарняний» | 2026-11-04, whole day, SICK_LEAVE, 1.0 | rules |
| «з 4 по 6 листопада відрядження, 1-2 пари» | 2026-11-04…06, pairs [1, 2], BUSINESS_TRIP, 1.0 | rules |
| «не зможу провести пари, приболів трохи» | 2026-11-07, SICK_LEAVE, 0.7, «Будь ласка, уточніть, на які дати ви хворієте, і чи йдеться про весь день або конкретні пари.» | AI (Haiku) |

After this run «приболів»/«болить» were added to the rule keywords, so the same message now gets SICK_LEAVE from
the rules too (the date is still unknown, so the model is still asked for the clarification).

## Conclusions

* Order photos: 69 of 83 rows exact (83 %), headers and notes almost perfect; errors are single cells (a letter,
  a pair number, a group digit). The draft is a strong starting point, never a final document — review before
  apply is required by design.
* The curriculum scan was read correctly for semester 7; the remaining differences are inconsistencies of the plan
  itself, surfaced as warnings.
* Cost: ~4–6 cents per photo or scan with Sonnet 5.5 at medium effort; a text fallback with Haiku 5.5 costs a
  fraction of a cent. The default daily budget of $2 covers ~40 photos.

## 6. Live stack run with Haiku as the vision model (08.11.2026)

Full stack in Docker, `AI_MODEL_VISION=claude-haiku-5-5`, `AI_EFFORT_VISION=low`, through the gateway with a real
Keycloak token (`scripts/smoke_workflows.py ai`): `seed/curriculum-ki-2023.pdf`, semester 7, groups 4КІ1–4КІ3 →
READY → apply → core created the load items. 9 657 input / 3 618 output tokens, **$0.0028**, one call. The pipeline
works end-to-end, but Haiku returned only 2 rows (Іноземна мова, Українська мова) of the 12 Sonnet reads — the
vision default stays Sonnet 5.5.

