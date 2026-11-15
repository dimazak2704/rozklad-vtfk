# Seed data (real college files)

| File | What |
|---|---|
| `schedule-2025-26.xlsx` | Official schedule 2025/26, I semester (sheets «Розклад друк», «пари», «дзвінки (2 зм)», «дзвінки») |
| `academic-calendar-2026-27.xlsx` | The college's academic calendar («графік освітнього процесу») 2026/2027, sheet «2026-2027»: weeks of every group with codes (НС, НП, І, Д, К, …) and the legend. Seed step `academic` imports it as `ACADEMIC_CALENDAR_XLSX` (56 groups, 11 codes) |
| `orders/order-035-2026-10-19.jpg` | Розпорядження №35, 19.10.2026, ПОНЕДІЛОК - ЗНАМЕННИК |
| `orders/order-096-2026-05-22.jpg` | Розпорядження №96, 22.05.2026, П'ЯТНИЦЯ - ЗНАМЕННИК |
| `orders/order-105-2026-06-04.jpg` | Розпорядження №105, 04.06.2026, ЧЕТВЕР - ЗНАМЕННИК |
| `programs/*.docx, *.doc` | Discipline programs (economics, cybersecurity, НДЕКСМ, a business-communications fragment) |
| `teacher-names.csv` | Full names of 89 teachers, matched to the schedule's short names, with the public source of each row (seed step `names`; checked by `python -m unittest discover -s scripts`) |

Known mismatch in the demo, kept as the college's files have it: order №35 (19.10.2026) carries the note «4ОП2 -
Основи національного спротиву з 05.10.26р. по 16.10.26р.», while the academic calendar (the version of 29 September)
puts НС of 4ОП2 on 19.10–30.10. The seed copies the order's notes verbatim and imports the calendar as it is, so on
19.10 the order says that 4ОП2's НС is over while the site shows 4ОП2 without pairs that day.

Curriculum КІ 2023 (ред. 2025), scanned PDF: https://drive.google.com/file/d/1TiKLkqZeLzyktGurteD6pBrfmNeMth1r/view
