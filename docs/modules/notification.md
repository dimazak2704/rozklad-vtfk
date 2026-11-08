# notification — Telegram bot, delivery and order documents

Service `services/notification` (port 8084, package `ua.vtc.rozklad.notification`). It owns the
Telegram bot @scheduler_vtfk_bot, chat bindings, the outbound message queue with rate limits and the
order documents (DOCX → PDF → PNG). It never writes core data: answers and absences go to core's
internal REST, schedules are read from core's public REST.

## 1. Packages

| Package | What is there |
|---|---|
| `telegram` | `TelegramApi` port (the only Bot API surface the code uses), `OkHttpTelegramApi` adapter over telegrambots-client 10.3 (Jackson 2 lives only here), `UpdateMapper` (library `Update` → `BotUpdate`), `TelegramBotRunner` (start/stop, long polling or webhook, command menus), `PerChatExecutor`, `TelegramWebhookController`, `BotStatus`, `web/BotController` |
| `bot` | conversation: `UpdateDispatcher` → `PrivateChats` / `GroupChats` / `CallbackRouter` / `InlineQueries`; flows `ScheduleViews`, `MyGroupFlow`, `SearchFlow`, `AbsenceFlow`, `SubstitutionReplyFlow`, `BindingFlow`; `BotReplies` (immediate answers), `DialogStore` (Redis), `ScheduleCards` (day card HTML), `ChatAccess` (who the bot talks to), `ChatAdmins` (is the author a chat admin, `getChatMember` cached 1 min in Redis) |
| `binding` | `ChatBinding`, `BindingCode` (one-time codes, SHA-256 only), `ChatBindings`, `BindingCodes`, `BindingAttempts` (limit on wrong codes, Redis), `BindingCodeRequests`, `web/BindingController` |
| `delivery` | persistent queue `OutboundMessage` (= delivery log), `Outbox` (enqueue, idempotent), `DeliveryWorker` (claim → limit → send → record), `RetryPolicy`, `RedisRateLimiter` (Lua token buckets), `MessageSender`, `TestMessages`, `web/DeliveryController` |
| `events` | `EventsListener` (`notification.q.events`), `EventHandlers` (exactly-once via `processed_event`), `AdminNotices` |
| `orders` | `OrderDistribution` (order.published), `OrderRows` (rows per group/teacher), `OrderTexts` (compact HTML) |
| `substitution` | `SubstitutionRequests` (request, reply, edits, deadline reminders), `SubstitutionTexts` |
| `documents` | `OrderDocxRenderer` (POI, college layout), `LibreOfficeConverter`, `PdfPngRenderer` (PDFBox), `OrderDocuments` (cache, media for photos), `PublishedOrders`, `DocumentPrerenderer`, `web/DocumentsController` |
| `core` | `CoreClient`/`RestCoreClient`, `ImporterClient`/`RestImporterClient`, `CoreQueries` (Redis read-through cache), error mapping |
| `digest`, `maintenance` | `MorningDigest` (07:30 Mon–Fri), `ScheduledJobs` (one every-minute method: deadlines, then teachers without Telegram; nightly cleanup) |
| `text` | `Html` (escape/split), `UaDates`, `Plurals`, `WebLinks` |

Tables (Flyway `V2`–`V7`): `chat_binding`, `binding_code`, `outbound_message`, `published_order`,
`substitution_request` (`V6`: `no_telegram_at`, `admin_warned_at`; `V7`: `mode`, `teacher_answer`, `change_kind`,
`original_discipline_name`, `reason_code`, purpose `SUBSTITUTION_NOTICE`), `substitution_message` (+ `processed_event`
from `V1`).

## 2. REST (CONTRACTS §6)

| Method & path | Access | Notes |
|---|---|---|
| `GET /api/notify/bot` | ADMIN | `{username, link, mode, connected}` **+ `problem`** (Ukrainian reason when not connected). `getMe` is cached for 1 min. |
| `GET /api/notify/bindings` | ADMIN | `[{id, type, chatId (masked «-•••7890»), title, groupId, teacherId, createdAt}]` **+ `groupCode`, `digestEnabled`** |
| `DELETE /api/notify/bindings/{id}` | ADMIN | 204 / 404 |
| `PATCH /api/notify/bindings/{id}` `{digestEnabled}` | ADMIN | **round 7.** 200 `BindingDto`; morning schedule of a GROUP_CHAT or SUBSCRIBER; 422 `digest-not-supported` (TEACHER, ADMIN_CHAT), 404, 400 `errors[digestEnabled]`. The web Telegram page shows it as the switch «Ранковий розклад о 7:30». |
| `POST /api/notify/bindings/codes` `{type, groupId?, teacherId?}` | ADMIN; TEACHER only `TEACHER` for himself | 200 `{code, deepLink, expiresAt}` **+ `type`, `label`, `groupLink`, `command`**. Code `K7QX-M2PA` (8 chars, no 0/O/1/I), deep link `https://t.me/scheduler_vtfk_bot?start=K7QXM2PA`; for GROUP_CHAT/ADMIN_CHAT also `?startgroup=` and `/bind K7QX-M2PA`. Errors: 400 `errors[groupId|teacherId|type]`, 404 unknown group/teacher, 403 teacher for someone else, 422 `teacher-not-linked`, 503 core down. A teacher is resolved from his own token (`GET /api/me` on core), never from the body. |
| `GET /api/notify/bindings/me` | TEACHER, ADMIN with a linked teacher | `{teacherId, teacherName, bound, binding?, botUsername, botLink}`: the caller's own TEACHER binding; teacher from `GET /api/me` on core; 422 `teacher-not-linked` |
| `GET /api/notify/deliveries?orderId&status&page&size` | ADMIN | page `{items,page,size,total}` of `{id, orderId, chatId (masked), chatTitle, bindingType, purpose, kind, status QUEUED/RETRYING/SENT/FAILED, attempts, error, createdAt, sentAt, nextAttemptAt}`; newest first; size ≤ 200 |
| `POST /api/notify/test` | ADMIN | sends «Тест: бот Розклад ВТФК підключено ✅» to `TELEGRAM_OWNER_CHAT_ID` right away and returns the delivery (200); 503 bot disabled, 422 no owner chat, 502 Telegram refused |
| `GET /api/documents/orders/{orderId}.{docx\|pdf\|png}` | public | only orders received in `order.published` (PUBLISHED); 404 otherwise; `ETag`, `Cache-Control: public, max-age=300`; DOCX as attachment, PDF/PNG inline; ASCII file names `rozporiadzhennia-35-2026-10-19.pdf`, `dopovnennia-36-…` |
| `POST /api/documents/orders/render?format=docx\|pdf\|png` body `OrderPayload` | ADMIN | draft preview, `no-store`; 400 `errors[format]` |
| `POST /api/notify/telegram/webhook` | open | WEBHOOK mode only (404 otherwise); `X-Telegram-Bot-Api-Secret-Token` must match `TELEGRAM_WEBHOOK_SECRET` (403) |

PDF/PNG without LibreOffice: 503 ProblemDetail, title «Конвертер PDF недоступний» (DOCX still works).

## 3. Bot (UI.md §5)

Texts are Ukrainian HTML (`parse_mode=HTML`), every dynamic value escaped, long texts split at
blank lines/lines (≤ 4000 chars), keyboards on the last part.

* **Private chat.** `/start` → greeting + main keyboard «📅 Сьогодні · 📆 Завтра / 👥 Моя група · 🔎 Знайти /
  🔔 Дзвінки · 🔢 Чисельник?», teachers also «🧑‍🏫 Мої пари завтра · 🤒 Відсутність». Buttons and typed words
  («сьогодні», «чисельник») both work; commands `/today /tomorrow /group /find /bells /parity /mytomorrow
  /absence /help /cancel` are registered as the command menu.
* **Сьогодні/Завтра** show the subscribed group (or the teacher's own lessons), with ◀️ ▶️ to the previous/next
  study day (edits the same message) and «⭐ Зробити моєю групою» for groups found by search.
  Changed lessons: «🔁 заміна» + «замість <s>СТП · Кренцін</s>», «❌ пари не буде» (struck through),
  «➕ додаткова», «↪️ перенесено з N пари», «🚪 інша аудиторія», reason in italics **in words**
  («Зв.зам.» → «Взаємна заміна», «Відп.» → «Викладач відсутній», as `REASON_CODE_HINT` of the public web;
  the codes stay only in the order document), day note «ℹ️ …». A day without lessons that is not a study day
  says «🌿 Навчання немає» + «Наступний навчальний день: **пн, 9 листопада**» and ▶️ jumps to that day: the
  owner's next `studyDay` from one schedule read of the next 30 days (holidays and the group's practice skipped).
* **Моя група**: course (inline 1–4) → group → SUBSCRIBER binding; digest on/off button.
* **Знайти**: core's fuzzy search; one hit → card, several → buttons, none → hint. Free text outside a dialog is
  a search too.
* **Дзвінки**: today's slots as plain lines «1 · 08:30–09:30», the current one bold with «◀ зараз»;
  «Зараз 2 пара — до кінця 23 хв» / «Перерва — 3 пара о 11:00»; weekend → Monday; footer = bell profile name
  with the shift count agreed («2 зміни, 60 хв» even if the profile was typed «2 змін»). No `<pre>`: the pair
  number and `HH:mm–HH:mm` have the same width in Telegram's fonts (tabular digits), so the column is straight
  without a monospace block with its «copy» header. **Чисельник?**: «Цього тижня — ЗНАМЕННИК (8-й тиждень)»;
  on a weekend/holiday also «Завтра (пн, 9 листопада) — **чисельник**, 11-й тиждень» (or «Наступний навчальний
  день (…)») from core's `meta.nextStudyDay` — notification computes no parity itself.
* **Absence wizard** (teacher): quick dates (today/next study day) or an inline calendar of study days
  (Mon–Fri, past days and > 90 days ahead disabled, month navigation) → one day or a range → whole day or
  picked pairs (toggle buttons) → reason (hidden from everyone but the dispatcher) → confirm → core
  `POST /api/internal/absences` (source BOT). One message is edited step by step; ⬅️ Назад, ✏️ Змінити,
  ✖️ Скасувати. Free text of a teacher that reads like an absence («не буду», «лікарняний», «відпустка»…) or
  any text inside the wizard goes to importer `POST /api/import/ai/parse-absence`; the result is echoed
  («Я зрозумів так: …» [✅ Так, надіслати] [✏️ Змінити]); the teacher's words are passed as the private comment.
  Unclear/low confidence (< 0.6)/past/far dates/importer down → explanation + date buttons.
* **Substitution notice (NOTIFY, default — DOMAIN §7)**: «📌 Вам поставили заміну», the same details without a
  deadline, «Якщо не можете, натисніть «Не можу» — диспетчер обере інший варіант.», [👍 Добре] [❌ Не можу]
  (same callbacks `sr:c:`/`sr:d:` as a request; core knows the change's mode). Purpose `SUBSTITUTION_NOTICE`;
  the local copy starts CONFIRMED with `mode = NOTIFY` and keeps `teacher_answer`. Not sent for a lesson that is
  already over. «Добре» → «👍 Добре, заміну прийнято. Дякуємо!»; «Не можу» before the order → core declines the
  change → «❌ Ви відмовилися від заміни. Диспетчер обере інший варіант.» and `status-changed DECLINED` tells
  admins «❌ Відмова від заміни / {викладач} не може взяти {N} пару {група} у {день}. Оберіть інший варіант на
  сторінці змін.»; «Не можу» after the order → «❗ Ви повідомили, що не можете. … диспетчера повідомлено: він
  внесе доповнення.» and `substitution.teacher-replied` tells admins «❗ Потрібне доповнення до розпорядження …».
  An unanswered notice of a change that became APPLIED is edited to «📄 Заміну внесено до розпорядження.» and keeps
  its buttons, so the teacher can still object. No deadline, no reminders. The race of the button with core's
  event for the same answer is handled like the request (locked row, the answer is recorded once, the second
  writer sees it and does nothing): `SubstitutionFlowIT.*Racing*` covers «Добре», «Не можу» before and after
  publication.
* **Substitution request (CONFIRM)**: date, pair + time, group (+ subgroup), discipline, room, whom to replace,
  deadline, [✅ Підтверджую] [❌ Не можу] → core `POST /api/internal/changes/{id}/reply` → the message is edited to the
  final state (no buttons). 409 → «Цей запит уже неактуальний» (the message keeps a known final state; a
  repeated tap of the answer core already holds gets the same success reply), 403 → «адресовано іншому
  викладачу», core down → alert, buttons stay. A repeated `confirmation-requested` for a pending change is sent
  as «⏰ Нагадування». Deadline passed without an answer → one reminder to the teacher and one notice to admin
  chats (not for requests that never reached a teacher without Telegram, not for past lessons).
  **Race-safe:** core publishes `substitution.status-changed` for the very answer, so the button handler and
  the event consumer write the same `substitution_request` row at the same time. Both read it with
  `SELECT … FOR NO KEY UPDATE` and only move a PENDING request to a final state; the second one waits for the
  first one's commit, sees the state and does nothing. The handler talks to core first; once core accepted the
  answer the teacher always gets the success reply (a still failing local update is logged and retried
  3× on lock conflicts, the event brings the copy up to date). `SubstitutionFlowIT.*Racing*` reproduces the
  interleaving of 08.11.2026 (event's UPDATE flushed but not committed while the button is pressed); the event
  commits only after `pg_blocking_pids` shows the button's transaction waiting for its lock, so the test always
  covers the dangerous order. The two every-minute jobs (deadlines, teachers without Telegram) run one after
  another in one scheduled method (the scheduler uses virtual threads, separate methods would run side by side),
  lock their rows in one order (`order by change_id`, also across instances) and look teacher names up in core
  before locking, so no HTTP call holds a row the button or an event waits for.
* **REPLACE_DISCIPLINE** (request and notice): the line «🔁 Замість «{дисципліна за розкладом}» ({викладач})», plus
  «(взаємна заміна)» when the reason is MUTUAL, replaces «👤 Замість: …»; «📘» names the discipline the teacher
  will teach (the event's `disciplineName`).
* **Teacher without Telegram**: the request cannot be sent; instead of one admin message per change, the
  every-minute job tells admin chats about requests still PENDING (CONFIRM) or notices still unanswered and in
  force (NOTIFY: CONFIRMED or APPLIED, worded «⚠️ {викладач} не підключив Telegram — повідомте про заміну самі»)
  after `app.bot.no-telegram-notice-delay`
  (2 min) — one message per teacher and lesson date listing the pairs (dedup key per teacher, date and chat), so
  core's reminders and further changes of that date add nothing; past dates are skipped silently. A seeded or
  quickly confirmed/published order (CONFIRMED/APPLIED within the delay) causes no message. The pending changes
  are in the web panel (link in the message); the order summary still lists «Без Telegram: …».
* **Group chat**: `/bind CODE` (or `/start CODE` after `?startgroup=`), `/today`, `/tomorrow`, `/status`,
  `/rozklad_on`, `/rozklad_off`, `/help` (the same commands are in the group command menu: «Що отримує цей чат»,
  «Щоранку надсилати розклад групи», «Не надсилати ранковий розклад»); chatter and `/cmd@other_bot` are ignored.
  Changes after a published order always come (no command to switch them off). The morning schedule is
  **opt-in** (round 7): a new GROUP_CHAT binding starts with `digestEnabled = false`; re-binding the chat to another
  group keeps its choice. `/status` (anyone): «ℹ️ Чат прив’язано до групи 4КІ1. / Цей чат отримує: / • заміни після
  публікації розпорядження ✅ / • ранковий розклад о 7:30 — вимкнено. Увімкнути: /rozklad_on». `/rozklad_on|off`:
  only chat administrators — `getChatMember` status `creator`/`administrator`, or an anonymous admin (message sent
  as the group itself, `sender_chat` = chat); the answer is cached 1 min in Redis (yes and no), a failed check is
  not cached and changes nothing («Не вдалося перевірити, чи ви адміністратор чату. Спробуйте за хвилину.»);
  others get «Змінити це може лише адміністратор чату.». The bind confirmation and `/help` explain this in two
  lines. A chat that is bound but not to a group (ADMIN_CHAT in a group) gets «Цей чат ще не прив’язаний до групи…»
  and Telegram is not asked. Bot removed/blocked (`my_chat_member`) → the chat's bindings are
  deleted; group → supergroup migration moves bindings.
* **Inline mode**: `@scheduler_vtfk_bot 4КІ1` → cards of the nearest study day and the next one (up to two
  owners), `cache_time` 60 s. Needs inline mode switched on in @BotFather (`/setinline`).
* **Morning digest**: 07:30 Mon–Fri (`app.bot.digest-cron`), only when core says `studyDay`, only groups with
  lessons, once per (date, chat); recipients = SUBSCRIBER and GROUP_CHAT bindings with `digestEnabled` (one query).
  Before round 7 group chats never got it; existing GROUP_CHAT bindings keep their stored flag (it was always
  `false`, no data migration), so nothing changes for them until an admin sends `/rozklad_on`.

### Who the bot talks to (owner's rule)

`TELEGRAM_ACCESS=BOUND_ONLY` (default): the bot answers and writes only to the owner chat
(`TELEGRAM_OWNER_CHAT_ID`, auto-registered as ADMIN_CHAT on start) and to chats bound through the system.
An unknown chat gets an answer only when it presents a code that exists (used/expired codes are explained,
invented ones are ignored); inline queries of unknown users get an empty answer. Pushes go only to bindings,
and the worker re-checks the binding right before sending (deleted binding ⇒ FAILED «Чат більше не прив’язаний»).
`TELEGRAM_ACCESS=OPEN` opens «Моя група», search etc. to everyone (public launch).

## 4. Delivery

* Every push (orders, substitution requests/edits, admin notices, digest, test) is a row of
  `outbound_message` written in the same transaction as the event mark ⇒ an event is either fully queued or
  not processed. `dedup_key` makes it idempotent per (eventId, chatId, part) / (digest, date, chat).
* Worker (`DELIVERY_WORKER_ENABLED`, every 500 ms, batch 20): claims with `FOR UPDATE SKIP LOCKED` only the
  **oldest pending message of each chat** (`queue_seq`), so a chat's messages keep their order (rows before
  the picture) while other chats go on; lease 60 s ⇒ at-least-once after a crash.
* Rate limits — Redis token buckets checked and taken atomically by one Lua script: whole bot 25 msg/s, group
  chat 18 msg/min, private chat 1 msg/s, burst 3 (`app.delivery.*`). No token ⇒ the message is deferred without
  spending an attempt. Redis down ⇒ sending continues (logged once a minute) and Telegram's 429 throttles.
  Immediate bot replies take tokens from the same buckets (wait ≤ 3 s).
* Retries (`RetryPolicy`): 429 → exactly `retry_after`, not counted; 5xx/network/local rendering errors →
  2 s · 2^(n-1) up to 10 min, FAILED after 8 attempts; supergroup migration → binding and message move to the
  new chat id; other 4xx (blocked, chat not found) → FAILED at once; edit «message is not modified» = success.
  Messages older than 24 h are FAILED as stale (no flood of old news after an outage).
* Metrics: `notification.deliveries{outcome=sent|retry|failed|deferred|stale|unbound|migrated}`.
* Retention: SENT/FAILED rows 30 days, processed events 30 days, expired codes 7 days, substitution requests 60
  days after the lesson (03:40 Kyiv).

## 5. Events (`notification.q.events`, DLQ `notification.q.events.dlq`)

The payload type comes from the routing key. Unreadable messages are rejected to the DLQ; handler failures are
retried 3× and then dead-lettered.

| Event | Effect |
|---|---|
| `order.published` | stores the order (only PUBLISHED); each bound group chat of `affectedGroups` gets **only its rows** (compact HTML) + the order PNG; each affected teacher with Telegram gets his rows (matched by name: same surname, compatible initials — «Поліщук Л.» ≠ «Поліщук М.»); admin chats get a summary (counts, who has no Telegram) + PNG; schedules cache invalidated; PNG pre-rendered after commit |
| `substitution.confirmation-requested` | request to the teacher's chat (or reminder); no Telegram → the request is marked, admins are told later once per teacher and date if it is still pending (§3); reassigned change → old message «неактуальний» |
| `substitution.status-changed` | final states (CONFIRMED/DECLINED/CANCELLED/APPLIED) edit all request messages (an unanswered NOTIFY notice of an APPLIED change keeps its buttons); DECLINED → admin chats «Оберіть інший варіант на сторінці змін.» |
| `substitution.teacher-replied` | NOTIFY answer that kept the status (from Telegram or the cabinet): the notice messages show it; an objection (DECLINE on APPLIED) → admin chats «Потрібне доповнення до розпорядження» |
| `absence.reported` | admin chats: teacher, dates, pairs, source (reason is never in the event) |
| `generation.completed` | admin chats: SOLVED (hard/soft, top penalties) / FAILED (error) / CANCELLED |
| `schedule.changed`, `timetable.version.published` | cache invalidation (generation counter in Redis) |

## 6. Order documents

`OrderDocxRenderer` reproduces the college form (DOMAIN §8, `seed/orders/*.jpg`): A4 portrait, margins
2/1/1.5/1.5 cm, Liberation Serif (metric twin of Times New Roman); centred bold header «Р О З П О Р Я Д Ж Е Н Н Я №35»
(letter spacing, text stays searchable), title, «На 19 жовтня 2026 р.», «ПОНЕДІЛОК - ЗНАМЕННИК»; table of 8 grid
columns with one header row and merged «За розкладом» / «По заміні»; row types: REPLACEMENT (all cells), CANCELLATION
(merged «Пари не буде»), ADDITIONAL (merged «ДОДАТКОВО»), AS_SCHEDULED (merged «За розкладом»), FINISHED (default
reason «Вичит.»), BLOCK/ROOM_CHANGE as given; two subgroups («Харченко О./Михальчук», «304/309») are printed one under
another; then an empty row, the merged «ЗАМІНА АУДИТОРІЙ» row and room changes (one empty row if none); bold notes;
signature «Заступник директора з НР ⇥ Микола ПОЛІЩУК». Rows are printed in core's order. Amendments print
«ДОПОВНЕННЯ №36» + «до розпорядження №35» (when the main order is known here); drafts «№ ____».

PDF: `soffice --headless --convert-to pdf` with a private profile in the cache dir, one conversion at a time,
timeout 90 s. PNG (the picture for Telegram and `….png`): PDFBox at 2× (144 DPI), **cropped to the printed
content** — one column range for all pages (the table stays aligned), each page cut to its own ink rows, 12 pt
white margin, blank pages dropped — pages stacked (max 5 pages), then scaled down only if Telegram would refuse
the photo (width + height ≤ 10000 px, ratio ≤ 20). A one-table order is a compact picture instead of an A4 sheet
that Telegram shrinks into unreadable lines; PDF and DOCX stay A4.
Published orders are cached on disk (`DOCUMENTS_CACHE_DIR`, key = order id + payload hash + format); the picture
is rendered once per order no matter how many chats receive it.

## 7. Configuration

| Env | Default | Meaning |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | empty | empty ⇒ bot disabled (documents/REST work, queue stays QUEUED) |
| `TELEGRAM_OWNER_CHAT_ID` | empty | test messages; registered as ADMIN_CHAT |
| `TELEGRAM_MODE` | `POLLING` | `WEBHOOK` + `TELEGRAM_WEBHOOK_URL` + `TELEGRAM_WEBHOOK_SECRET`; WEBHOOK without URL = send-only |
| `TELEGRAM_ACCESS` | `BOUND_ONLY` | `OPEN` for a public bot |
| `SOFFICE_PATH` | empty (host) / `/usr/bin/soffice` (image) | LibreOffice |
| `DOCUMENTS_CACHE_DIR` | `${java.io.tmpdir}/rozklad-notification` | rendered files + LibreOffice profile |
| `DELIVERY_WORKER_ENABLED`, `DELIVERY_GLOBAL_PER_SECOND`, `DELIVERY_GROUP_CHAT_PER_MINUTE` | `true`, 25, 18 | queue |
| `BOT_DIGEST_ENABLED` | `true` | morning digest |
| `app.bot.no-telegram-notice-delay` (property) | `2m` | how long a request of a teacher without Telegram may stay pending before admins are told |
| `app.bot.admin-check-ttl` (property) | `1m` | how long a `getChatMember` answer is reused |
| `app.bot.bind-attempts`, `app.bot.bind-attempts-window` (properties) | `5`, `15m` | wrong binding codes per chat and per user, then codes are not looked up until the window ends |
| `EVENTS_LISTENER_ENABLED` | `true` | `false` = do not consume the shared queue (manual runs) |
| `PUBLIC_WEB_URL` | `http://localhost:5180` | links in messages (omitted for local hosts — Telegram rejects them) |

Only ONE instance may long-poll a token (Telegram answers 409 to a second one); use WEBHOOK for replicas.

## 8. Ризики прив’язки чатів

Що захищає прив’язку і що буде, якщо код потрапить не туди. Кожен пункт перевірено в коді й тестах.

* **Код одноразовий, 8 символів** з 32 (без 0/O/1/I), тобто 2⁴⁰ варіантів (`BindingCodes`). Використаний код
  позначається; два чати з тим самим кодом одночасно не прив’яжуться обидва (рядок блокується).
* **Діє 24 години** (`app.bot.code-ttl`); прострочений код відхиляється, через 7 днів рядок видаляється.
* **У базі лише SHA-256 коду.** Сам код бачить тільки той, хто створив його у веб-панелі (`BindingCodesTest`).
* **Коди для чатів груп і службових чатів створює лише ADMIN.** Викладач може створити тільки код свого
  особистого чату; викладача визначає токен, а не тіло запиту (`NotifyApiIT.teacherCreatesOnlyHisOwnCode`).
* **Підбір коду обмежено** (`BindingAttempts`, раунд 7): 5 хибних кодів за 15 хвилин на чат і окремо на
  користувача Telegram (анонімного адміністратора рахуємо лише за чатом). Далі коди не перевіряються до кінця
  вікна. Відомому чату бот пише «Забагато невдалих спроб. Спробуйте ще раз через 15 хв.», незнайомому нічого не
  пише, як і на вигаданий код. Лічильники в Redis; без Redis код не перевіряється взагалі (fail closed), у лозі
  помилка. До раунду 7 обмеження не було: при 2⁴⁰ варіантів підбір був нереальним, тепер він ще й обмежений
  (`BindingAttemptsIT`).
* **Що дає код групи, який потрапив не туди:** ще один чат отримуватиме зміни цієї групи після публікації
  розпорядження і зможе дивитися її розклад (`/today`, `/tomorrow`, ранковий розклад, якщо там його ввімкнуть).
  Це ті самі дані, що й так відкриті на сайті. Код спрацьовує один раз: справжній чат групи побачить «Цей код уже
  використано» і попросить новий, а диспетчер видалить зайву прив’язку. Код викладача, надісланий у груповий чат,
  анулюється одразу. Код викладача в чужих руках дав би прохання про заміни цього викладача, тому такий код
  створює лише сам викладач або диспетчер, а нова прив’язка переносить викладача в новий чат.
* **Диспетчер бачить і видаляє прив’язки** (`GET`/`DELETE /api/notify/bindings`, лише ADMIN; id чатів
  замасковано). Якщо бота вилучили з чату, прив’язки цього чату видаляються самі.
* **Бот ігнорує неприв’язані чати** (`TELEGRAM_ACCESS=BOUND_ONLY`, типово): відповідає лише чату власника і
  прив’язаним чатам; незнайомий чат отримає відповідь, лише якщо надішле справжній код. Розсилки йдуть тільки в
  прив’язані чати, і перед кожним надсиланням прив’язку перевіряють ще раз.
* **Хто перемикає ранковий розклад чату групи:** лише адміністратор цього чату (`getChatMember`, кеш 1 хв, тож
  розжалуваний адміністратор зберігає право щонайбільше хвилину) або диспетчер на сторінці Telegram. Заміни після
  публікації вимкнути не можна; їх зупиняє лише диспетчер, коли видаляє прив’язку.

## 9. Decisions

* Own `TelegramApi` port instead of the library's 50-method `TelegramClient`: tests use `FakeTelegramClient`,
  failures are one typed `TelegramApiFailure` (429 `retry_after`, migration, «not modified»), the bot token is
  scrubbed from error messages.
* Immediate replies (`BotReplies`) are synchronous (users wait for them) but use the same token buckets; pushes
  always go through the persistent queue.
* Updates are handled on virtual threads, serially per chat (`PerChatExecutor`) — a wizard never sees its clicks
  out of order, different chats run in parallel.
* Binding codes: stored as SHA-256; one teacher = one TEACHER chat (re-binding moves it); a TEACHER code posted in a
  group chat is burned (everyone there saw it); a wrong-chat code is not spent otherwise.
* Order PNG for photos is referenced (`order-png:<id>`) and rendered at send time from the disk cache instead of
  being copied into every queued row.
* Public documents are served only from orders received in `order.published` — core has no public endpoint with
  the full order, and drafts must never be public.
* Teacher rows of an order are found by name (the payload has no teacher ids per row); see `OrderRows`.
* `GET /api/notify/bot.connected` is true only if `getMe` works **and** updates are received.
* Links to the SPA are added only for public hosts (Telegram does not accept `localhost` links).

## 10. Contract clarifications (to copy into docs/CHANGELOG-CONTRACTS.md)

| Area | Decision | Why |
|---|---|---|
| `POST /api/notify/bindings/codes` | 200 (not 201): codes are not addressable resources. Adds `type`, `label`, `groupLink` (`?startgroup=`), `command` (`/bind …`). TEACHER role: teacher taken from `GET /api/me` with the caller's token; 422 `teacher-not-linked`. | Admins need the group-chat way to bind; ownership must not trust the body. |
| `GET /api/notify/bindings` | adds `groupCode`, `digestEnabled`; `chatId` masked `-•••7890` | Readable list without exposing chat ids. |
| `GET /api/notify/bot` | adds `problem` (Ukrainian reason when `connected=false`) | Admin sees why the bot is down. |
| `GET /api/notify/deliveries` | paged `{items,page,size,total}`; item `{id, orderId, chatId, chatTitle, bindingType, purpose, kind, status, attempts, error, createdAt, sentAt, nextAttemptAt}`; statuses `QUEUED/RETRYING/SENT/FAILED` | The contract only says «delivery log»; matches the SPA's inferred `DeliveryDto` plus extras. |
| `POST /api/notify/test` | 200 with the delivery; 503/422/502 | Synchronous: the admin wants to see the result. |
| `GET /api/documents/orders/{id}.{fmt}` | only orders received via `order.published`; ETag + 5 min cache; unknown format → 404 | Drafts are never public; core offers no public order payload. |
| `POST …/render` | `format` default `pdf`; invalid → 400 `errors[format]` | — |
| Webhook | `POST /api/notify/telegram/webhook` 404 unless WEBHOOK mode | Reserved path from the foundation. |
| `GET …/{id}.png` | cropped to the content, not an A4 page (08.11.2026) | Readable on a phone; PDF/DOCX stay A4. |
| core `GET /api/public/meta` | reads the additive `nextStudyDay` (08.11.2026); an older core without it only loses the «Завтра — …» line | Parity of the next study day comes from core. |
| `PATCH /api/notify/bindings/{id}` (round 7) | `{digestEnabled}` → 200 `BindingDto`; 422 `digest-not-supported` for TEACHER/ADMIN_CHAT | The dispatcher switches the morning schedule of a chat without Telegram rights. |
| Group chats (round 7) | new GROUP_CHAT bindings start with `digestEnabled = false`; `/rozklad_on`, `/rozklad_off` (chat admins), `/status`; the digest includes GROUP_CHAT | Owner: changes after publication always, the morning schedule only if the group wants it. |

## 11. How to run and test

```bash
source scripts/env.sh
mvn -q -pl services/notification -am verify          # 83 unit tests + 108 Testcontainers ITs (all green, 08.11.2026)
```

* Unit (`*Test`): DOCX of order №35 (header, merged cells, every row type, subgroups, notes, signature —
  writes `target/test-output/order-035-2026-10-19.docx`), PNG renderer, retry policy, HTML splitting, dates,
  row selection/teacher matching, texts, command/menu parsing, AI validation and the calendar, the OkHttp
  adapter against a fake Bot API (429, migration, not modified, network, token never in messages),
  `Update` JSON mapping.
* Integration (`*IT`, one shared context, Postgres/RabbitMQ/Redis in Docker, `FakeTelegramClient`, fake core/importer,
  PDFBox stand-in for LibreOffice, controllable clock): every private/group/inline flow with exact texts and
  keyboards, the absence wizard (buttons, calendar, AI, core errors), substitution requests (confirm, decline,
  cancel, reminder, reassignment, foreign teacher, 409/outage), order distribution (rows per chat, photo,
  admin summary, idempotency, documents API, no LibreOffice), events through the real broker (duplicate,
  cache invalidation, DLQ), the delivery worker (per-chat order, 429, backoff, max attempts, 403, migration,
  stale/unbound, split, log API, test message), Redis token buckets, REST (codes, bindings, status, webhook),
  morning digest.

Manual run on the host (own database, no polling, not consuming the shared queue):

```bash
docker exec rozklad-postgres-1 psql -U postgres -c "CREATE DATABASE notification_dev OWNER notification"
SERVER_PORT=18084 DB_NAME=notification_dev TELEGRAM_MODE=WEBHOOK EVENTS_LISTENER_ENABLED=false \
  BOT_DIGEST_ENABLED=false TELEGRAM_BOT_TOKEN=… TELEGRAM_OWNER_CHAT_ID=… java -jar services/notification/target/app.jar
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:18084/api/notify/test
```

Verified on 08.11.2026 this way against the real infrastructure (Flyway V2–V5 on PostgreSQL 18, Redis,
Keycloak tokens): `getMe` → @scheduler_vtfk_bot, `GET /api/notify/bot` reports the identity, the owner chat
was registered as ADMIN_CHAT, `POST /api/notify/test` delivered exactly one message «Тест: бот Розклад ВТФК
підключено ✅» (status SENT); the bot token does not occur in the log. No polling was started.

Order №35 was rendered by `OrderDocxRendererTest`, converted by LibreOffice 24.2 of the `rozklad/notification`
image and rasterized by `PdfPngRenderer`; the PNG matches the layout of `seed/orders/order-035-2026-10-19.jpg`
(header, merged cells, «ЗАМІНА АУДИТОРІЙ», bold notes, signature) on one A4 page.

## 12. Known gaps

* Not tested end-to-end against a running core/importer (their endpoints were being built in parallel);
  DTOs follow CONTRACTS §1/§3/§4/§5 and ignore unknown fields. `LessonView.original` members are read both
  as refs and as text.
* Teacher rows of an order are matched by name; a row with a surname only and two teachers of that surname
  would go to both.
* Orders longer than 5 pages have no PNG (PDF/DOCX only); such orders are distributed without the picture
  after the worker gives up (logged as FAILED).
* Morning digest and deadline reminders run in every instance; idempotency keys prevent duplicates, but the
  work is repeated.
* Inline mode must be enabled in @BotFather; the bot cannot do it itself.
* docker compose passes `../.env` to the container: an empty `SOFFICE_PATH=` copied from `.env.example`
  overrides the image's `/usr/bin/soffice` and disables PDF/PNG. Leave the key out of `.env` for compose.
* With the real token in `.env`, `docker compose --profile app up` starts long polling — stop any other
  polling instance (host run, another clone) first.
* The bells footer is the bell profile name stored in core. The repository's reference data says «2 зміни,
  60 хв»; the live bot showed «2 змін», so that profile was renamed in the running database. The bot now agrees
  «N зміна/зміни/змін» when it displays the name; the stored name itself is fixed in the web panel (calendar →
  bell profiles), not by notification.
* «One warning per teacher and date» means a second change of the same teacher on the same date, created after
  the warning, is not announced in Telegram again; the dispatcher sees it as pending in the web panel.
