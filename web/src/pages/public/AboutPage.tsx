import { ExternalLink, LogIn, Send } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { usePublicMeta } from '@/api/meta';
import type { LessonView, LessonViewKind } from '@/api/types';
import { paths } from '@/app/routes';
import { QrPlate } from '@/components/public/QrPlate';
import { TELEGRAM_BOT_HANDLE, TELEGRAM_BOT_URL } from '@/components/public/telegram';
import { LessonCell } from '@/components/schedule';
import { KbdCombo, PageHeader, ParityBadge, ParityMark, buttonClass } from '@/components/ui';
import { LESSON_VIEW_KIND_LABEL } from '@/lib/labels';

/** Приклад заняття для легенди: лише ілюстрація станів, не дані розкладу. */
function example(kind: LessonViewKind, extra: Partial<LessonView> = {}): LessonView {
  return {
    id: `example-${kind}`,
    pair: 2,
    startsAt: '09:40',
    endsAt: '10:40',
    discipline: { id: 'example-stp', shortName: 'СТП', name: 'Сучасні технології проєктування', color: null },
    teacher: { id: 'example-teacher', shortName: 'Кренцін', displayName: 'Кренцін' },
    room: { id: 'example-room', name: '301', type: 'CLASSROOM' },
    group: { id: 'example-group', code: '4КІ1', course: 4 },
    subgroup: 0,
    kind,
    online: false,
    original: null,
    reasonText: null,
    changeId: null,
    ...extra,
  };
}

const LEGEND: ReadonlyArray<{ lesson: LessonView; text: string }> = [
  { lesson: example('REGULAR'), text: 'Колір крапки — лінія дисципліни, однаковий на всіх сторінках.' },
  {
    lesson: example('SUBSTITUTED', {
      discipline: { id: 'example-ndeksm', shortName: 'НДЕКСМ', name: 'Надійність, діагностика та експлуатація компʼютерних систем і мереж', color: null },
      teacher: { id: 'example-teacher-2', shortName: 'Непийвода', displayName: 'Непийвода' },
      original: { discipline: { id: 'example-stp', shortName: 'СТП', name: 'Сучасні технології проєктування', color: null }, teacher: { id: 'example-teacher', shortName: 'Кренцін', displayName: 'Кренцін' } },
      reasonText: 'Зв.зам.',
    }),
    text: 'Що було — закреслено, причина — як у розпорядженні («Відп.», «Зв.зам.»).',
  },
  { lesson: example('CANCELLED', { reasonText: 'Відп.' }), text: 'Група приходить пізніше чи йде раніше.' },
  { lesson: example('ADDITIONAL', { pair: 5 }), text: 'Відпрацювання чи перенесене заняття.' },
  {
    lesson: example('ROOM_CHANGED', { room: { id: 'example-room-2', name: '309', type: 'CLASSROOM' }, original: { room: { id: 'example-room', name: '301', type: 'CLASSROOM' } } }),
    text: 'Та сама пара в іншій аудиторії: стара → нова.',
  },
];

const FEATURES: ReadonlyArray<[string, string]> = [
  ['Розклад групи, викладача й аудиторії', 'День або тиждень, чисельник і знаменник, підгрупи, «зараз» з відліком до кінця пари.'],
  ['Заміни', 'На сьогодні, завтра чи будь-яку дату — з номером розпорядження й файлом для друку.'],
  ['Дзвінки', 'Час пар і перерв профілю, що діє; поточна пара підсвічена.'],
  ['Обране', 'Зірочка на сторінці розкладу — і головна одразу показує вашу групу.'],
  ['Календар', 'Підписка на пари в Google Календарі чи календарі телефону; файл .ics.'],
  ['Друк і QR', 'Тиждень на аркуш A4 і табличка з QR-кодом на двері аудиторії.'],
];

const SHORTCUTS: ReadonlyArray<[string, string]> = [
  ['mod+k', 'пошук групи, викладача чи аудиторії'],
  ['arrowleft', 'попередній день чи тиждень'],
  ['arrowright', 'наступний день чи тиждень'],
  ['t', 'повернутися до сьогодні'],
  ['d', 'подання «День»'],
  ['w', 'подання «Тиждень»'],
  ['?', 'усі скорочення сторінки'],
];

function Block({ title, children, id }: { title: string; children: ReactNode; id: string }) {
  return (
    <section aria-labelledby={id} className="flex flex-col gap-3">
      <h2 id={id} className="text-title font-semibold text-primary">
        {title}
      </h2>
      {children}
    </section>
  );
}

/** «Про систему» (вимога методички): що вміє, як читати розклад, Telegram-бот, для викладачів. */
export default function AboutPage() {
  const meta = usePublicMeta();

  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        title="Про систему"
        description="«Розклад ВТФК» збирає розклад занять, заміни й дзвінки Вінницького технічного фахового коледжу в одному місці. Без входу: відкрийте свою групу — і побачите пари на сьогодні з урахуванням замін."
      />

      <div className="grid items-start gap-10 lg:grid-cols-[minmax(0,1fr)_21rem]">
        <div className="flex min-w-0 flex-col gap-10">
          <Block title="Що тут є" id="about-features">
            <dl className="grid gap-x-8 gap-y-4 sm:grid-cols-2">
              {FEATURES.map(([term, description]) => (
                <div key={term} className="flex flex-col gap-1 border-t border-separator pt-3">
                  <dt className="text-ui font-semibold text-primary">{term}</dt>
                  <dd className="text-ui text-secondary">{description}</dd>
                </div>
              ))}
            </dl>
          </Block>

          <Block title="Як читати розклад" id="about-legend">
            <p className="max-w-prose text-body text-secondary">
              Зміни їдуть поверх розкладу, як оголошення про обʼїзд: оригінал лишається видимим, нове — виділене своїм
              кольором і підписане. Нижче — приклади станів: наведіть курсор на пару або натисніть на неї, щоб побачити всі деталі.
            </p>
            <ul className="flex flex-col divide-y divide-[var(--border-color-separator)] rounded-panel border border-default bg-surface">
              {LEGEND.map(({ lesson, text }) => (
                <li key={lesson.id} className="grid items-center gap-x-5 gap-y-2 p-3 sm:grid-cols-[17rem_minmax(0,1fr)]">
                  <LessonCell lesson={lesson} perspective="group" />
                  <p className="text-ui text-secondary">
                    <span className="font-semibold text-primary">{LESSON_VIEW_KIND_LABEL[lesson.kind]}.</span> {text}
                  </p>
                </li>
              ))}
            </ul>
            <div className="flex flex-col gap-2 rounded-panel border border-default bg-surface p-4">
              <h3 className="flex items-center gap-2 text-heading font-semibold text-primary">
                <ParityMark parity="NUMERATOR" className="text-accent" />
                Чисельник і знаменник
              </h3>
              <p className="max-w-prose text-ui text-secondary">
                Навчальні тижні чергуються. У тиждень-чисельник діють пари з верхнього рядка друкованого розкладу, у
                знаменник — з нижнього; пари на обидва рядки йдуть щотижня. Сайт уже показує пари саме вашого тижня, а
                поточний тиждень видно в шапці.
              </p>
              {meta.data ? (
                <p className="flex flex-wrap items-center gap-2 text-ui text-secondary">
                  Цього тижня: <ParityBadge parity={meta.data.parity} weekNumber={meta.data.weekNumber} size="sm" />
                </p>
              ) : null}
            </div>
          </Block>

          <Block title="Швидкі клавіші" id="about-keys">
            <dl className="grid max-w-xl grid-cols-[auto_minmax(0,1fr)] items-center gap-x-4 gap-y-2.5">
              {SHORTCUTS.map(([keys, description]) => (
                <div key={keys} className="contents">
                  <dt>
                    <KbdCombo keys={keys} />
                  </dt>
                  <dd className="text-ui text-secondary">{description}</dd>
                </div>
              ))}
            </dl>
          </Block>

          <Block title="На телефоні як застосунок" id="about-install">
            <p className="max-w-prose text-body text-secondary">
              У меню браузера оберіть «Додати на головний екран» (у Safari — «Поділитися» → «На початковий екран»). Розклад
              відкриватиметься окремим вікном, а останні переглянуті тижні — навіть без інтернету, з позначкою, що дані
              збережені раніше.
            </p>
          </Block>

          <Block title="Про проєкт" id="about-project">
            <p className="max-w-prose text-body text-secondary">
              Систему створено як курсовий проєкт. Офіційні новини й документи коледжу —{' '}
              <a href="https://vtc.vn.ua" target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 font-semibold text-accent underline-offset-4 hover:underline">
                vtc.vn.ua
                <ExternalLink aria-hidden="true" className="size-3.5" />
              </a>
              .
            </p>
          </Block>
        </div>

        <aside className="flex min-w-0 flex-col gap-6" aria-label="Telegram і вхід">
          <section aria-labelledby="about-bot" className="flex flex-col gap-3 rounded-panel border border-default bg-surface p-4">
            <h2 id="about-bot" className="text-heading font-semibold text-primary">
              Telegram-бот {TELEGRAM_BOT_HANDLE}
            </h2>
            <p className="text-ui text-secondary">
              Розклад групи на сьогодні й завтра у два дотики, а групові чати отримують свої рядки розпорядження одразу
              після публікації. Викладачам бот надсилає запити на заміну з кнопками «Підтверджую» / «Не можу».
            </p>
            <QrPlate value={TELEGRAM_BOT_URL} size={152} label={`QR-код: бот ${TELEGRAM_BOT_HANDLE}`} className="self-start" />
            <a href={TELEGRAM_BOT_URL} target="_blank" rel="noreferrer" className={buttonClass({ variant: 'primary', className: 'self-start' })}>
              <Send aria-hidden="true" className="size-4" />
              Відкрити бота
            </a>
          </section>

          <section aria-labelledby="about-teachers" className="flex flex-col gap-3 rounded-panel border border-default bg-surface p-4">
            <h2 id="about-teachers" className="text-heading font-semibold text-primary">
              Викладачам
            </h2>
            <p className="text-ui text-secondary">
              У кабінеті — ваш розклад, запити на заміну з підтвердженням в один дотик, відсутності й побажання до розкладу.
              Обліковий запис створюється за запрошенням диспетчера.
            </p>
            <Link to={paths.login('/me')} className={buttonClass({ variant: 'secondary', className: 'self-start' })}>
              <LogIn aria-hidden="true" className="size-4" />
              Увійти в кабінет
            </Link>
          </section>
        </aside>
      </div>
    </div>
  );
}
