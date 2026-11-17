import { MonitorSmartphone } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import type { EntityType, IsoDate, LessonView, WeekParity } from '@/api/types';
import { paths } from '@/app/routes';
import { useTeacherFullName } from '@/hooks/useTeacherNames';
import { cn } from '@/lib/cn';
import { formatWeekdayDate } from '@/lib/date';
import { lineAttr } from '@/lib/discipline-color';
import { capitalize, ENTITY_SCHEDULE, LESSON_VIEW_KIND_LABEL, openScheduleHint, reasonInWords, ROOM_TYPE_LABEL, subgroupLabel } from '@/lib/labels';
import { pairOrdinalGenitive, pairOrdinalLabel } from '@/lib/ordinal';
import { LESSON_TONE, lessonTime, roomLabel } from '@/lib/schedule';
import { ParityMark } from '../ui/ParityBadge';
import { Tooltip } from '../ui/Tooltip';
import { useLessonLinks } from './lesson-links';

const STATE_WORD = {
  change: 'text-change',
  cancel: 'text-cancel',
  add: 'text-add',
  room: 'text-room',
  regular: 'text-secondary',
  block: 'text-secondary',
} as const;

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[4.75rem_minmax(0,1fr)] items-baseline gap-x-3">
      <dt className="text-caption text-muted">{label}</dt>
      <dd className="text-ui text-primary">{children}</dd>
    </div>
  );
}

interface LessonDetailsProps {
  lesson: LessonView;
  date?: IsoDate;
  /**
   * `grid` — картку відкрито з сітки тижня чи списку дня: день уже видно, тож «Коли» — лише пара й час.
   * `standalone` (за замовчуванням) — деінде (списки змін, пошук): з повною датою.
   */
  context?: 'grid' | 'standalone';
  /** Перехід за посиланням у картці: закрити шторку, у якій вона стоїть. */
  onNavigate?: () => void;
}

/**
 * Значення-посилання: виглядає як значення (основне чорнило), тонка риска акцентного відтінку каже, що його
 * можна натиснути; під мишею текст стає акцентним. На дотик область натискання вища за рядок.
 */
const VALUE_LINK =
  'rounded-tag text-primary underline decoration-[var(--text-decoration-color-accent)] decoration-[1.5px] underline-offset-[0.2em] ' +
  'transition-[color,text-decoration-color] duration-150 hover:text-accent hover:decoration-current pointer-coarse:py-1.5 ' +
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus';

const PARITY_ONLY: Record<WeekParity, string> = {
  NUMERATOR: 'лише по чисельниках',
  DENOMINATOR: 'лише по знаменниках',
};

/**
 * Значення, що веде на свій розклад (з тим самим поданням і днем), або простий текст, якщо це сутність сторінки.
 * Риска каже, що його можна натиснути, а підказка при наведенні чи фокусі — куди: «Розклад викладача».
 * Підказка праворуч, у тому ж рядку: сусідні рядки картки лишаються видимими. Біля краю екрана Radix перекидає
 * її ліворуч, на підпис рядка, тож крізь неї проходить миша (`passThrough`): курсор, що йде до сусіднього
 * посилання, не впирається в підказку, і картка під нею не закривається. На дотик підказки немає — тап одразу
 * переходить.
 */
function EntityValue({
  to,
  own,
  type,
  onNavigate,
  children,
}: {
  to: string;
  own: boolean;
  type: EntityType;
  onNavigate?: () => void;
  children: ReactNode;
}) {
  const { search } = useLessonLinks();
  if (own) return <>{children}</>;
  return (
    <Tooltip content={openScheduleHint(type)} side="right" passThrough>
      <Link to={{ pathname: to, search }} className={VALUE_LINK} onClick={onNavigate}>
        {children}
        {/* у режимі перегляду читач екрана підказки не відкриває: куди веде посилання — в самій назві */}
        <span className="sr-only">{`, ${ENTITY_SCHEDULE[type]}`}</span>
      </Link>
    </Tooltip>
  );
}

/**
 * Повні відомості про заняття для картки при наведенні чи шторки на телефоні: повна назва з крапкою
 * дисципліни, «Веде / Де / Коли / Група», для зміни — «Було» й причина словами. Викладач, аудиторія й група —
 * посилання на їхні розклади (крім сутності самої сторінки), тож окремих переходів унизу картки немає.
 */
export function LessonDetails({ lesson, date, context = 'standalone', onNavigate }: LessonDetailsProps) {
  const teacherName = useTeacherFullName();
  const { self } = useLessonLinks();
  const tone = LESSON_TONE[lesson.kind];
  const original = lesson.original;
  const subgroup = subgroupLabel(lesson.subgroup);
  const replacedDiscipline = original?.discipline && original.discipline.id !== lesson.discipline.id;
  const replacedTeacher = original?.teacher && original.teacher.id !== lesson.teacher?.id;
  const replacedRoom = original?.room && original.room.id !== lesson.room?.id;
  const movedFrom = original?.pair && original.pair !== lesson.pair ? original.pair : null;
  const pairAndTime = `${pairOrdinalLabel(lesson.pair)}${lessonTime(lesson) ? ` · ${lessonTime(lesson)}` : ''}`;
  // у сітці день уже видно: лише пара й час; деінде — з датою
  const when = context === 'standalone' && date ? `${formatWeekdayDate(date)}, ${pairAndTime}` : capitalize(pairAndTime);
  const parityOnly = lesson.parity === 'NUMERATOR' || lesson.parity === 'DENOMINATOR' ? lesson.parity : null;
  // сторінка аудиторії прибирає свою аудиторію із занять (клітинки її не повторюють): у картці вона є, простим текстом
  const ownRoom = self?.kind === 'room' ? self : null;
  const room = lesson.room ?? ownRoom?.room ?? null;
  const ownRoomLabel = !room && ownRoom ? ownRoom.label : null;

  return (
    <div className="flex w-full flex-col gap-3 sm:w-[min(21rem,calc(100vw-3rem))]">
      <div className="flex flex-col gap-1">
        {tone !== 'regular' && tone !== 'block' ? (
          <span className={cn('text-meta font-semibold', STATE_WORD[tone])}>{LESSON_VIEW_KIND_LABEL[lesson.kind]}</span>
        ) : null}
        <p className="flex items-start gap-2 text-heading font-bold text-primary">
          <span {...lineAttr(lesson.discipline)} className="mt-2 size-2 shrink-0 rounded-full bg-[var(--line-dot)]" aria-hidden="true" />
          <span className={cn(tone === 'cancel' && 'line-through decoration-[var(--text-decoration-color-cancel)] decoration-2')}>
            {lesson.discipline.name || lesson.discipline.shortName}
          </span>
        </p>
      </div>

      <dl className="flex flex-col gap-1.5 pointer-coarse:gap-2.5">
        <Row label="Веде">
          {lesson.teacher ? (
            <EntityValue
              to={paths.teacher(lesson.teacher.id)}
              own={self?.kind === 'teacher' && self.id === lesson.teacher.id}
              type="TEACHER"
              onNavigate={onNavigate}
            >
              {teacherName(lesson.teacher)}
            </EntityValue>
          ) : (
            <span className="text-muted">не призначено</span>
          )}
        </Row>
        <Row label="Де">
          {room ? (
            <>
              <EntityValue
                to={paths.room(room.id)}
                own={self?.kind === 'room' && self.id === room.id}
                type="ROOM"
                onNavigate={onNavigate}
              >
                {roomLabel(room.name)}
              </EntityValue>
              {/* «ауд. 16 · аудиторія» — повтор; тип називаємо, лише коли він щось каже (клас, лабораторія, зал) */}
              {room.type !== 'CLASSROOM' && room.type !== 'OTHER' ? (
                <span className="text-secondary"> · {ROOM_TYPE_LABEL[room.type]}</span>
              ) : null}
            </>
          ) : ownRoomLabel ? (
            ownRoomLabel
          ) : (
            <span className="text-muted">не вказано</span>
          )}
          {lesson.online ? (
            <span className="ms-1 inline-flex items-center gap-1 text-secondary">
              <MonitorSmartphone aria-hidden="true" className="size-3.5" />
              дистанційно
            </span>
          ) : null}
        </Row>
        <Row label="Коли">
          <span className="inline-flex flex-wrap items-baseline gap-x-2 gap-y-1">
            <span>{when}</span>
            {parityOnly ? (
              // пара не щотижня: позначка лише в такої пари; загальна парність тижня — у шапці сторінки
              <span className="inline-flex items-center gap-1 self-center rounded-tag bg-sunken px-1.5 py-px text-meta font-semibold whitespace-nowrap text-secondary">
                <ParityMark parity={parityOnly} className="size-3" />
                {PARITY_ONLY[parityOnly]}
              </span>
            ) : null}
          </span>
        </Row>
        <Row label="Група">
          <EntityValue
            to={paths.group(lesson.group.code)}
            own={self?.kind === 'group' && self.id === lesson.group.id}
            type="GROUP"
            onNavigate={onNavigate}
          >
            {lesson.group.code}
          </EntityValue>
          {subgroup ? <span className="text-secondary"> · {subgroup}</span> : null}
        </Row>
      </dl>

      {tone !== 'regular' && tone !== 'block' && (replacedDiscipline || replacedTeacher || replacedRoom || movedFrom || lesson.reasonText) ? (
        <dl className="flex flex-col gap-1.5 border-t border-separator pt-3">
          {replacedDiscipline || replacedTeacher ? (
            <Row label="Було">
              <s className="text-secondary decoration-[var(--text-decoration-color-change)]">
                {[original?.discipline?.shortName ?? lesson.discipline.shortName, original?.teacher ? teacherName(original.teacher) : null]
                  .filter(Boolean)
                  .join(' · ')}
              </s>
            </Row>
          ) : null}
          {replacedRoom ? (
            <Row label="Аудиторія">
              <s className="text-secondary">{roomLabel(original?.room?.name)}</s> → <span className="font-[650] text-room">{roomLabel(lesson.room?.name)}</span>
            </Row>
          ) : null}
          {movedFrom ? <Row label="Перенесено">з {pairOrdinalGenitive(movedFrom)} пари</Row> : null}
          {lesson.reasonText ? (
            <Row label="Причина">
              <span className="font-[650]">{reasonInWords(lesson.reasonText)}</span>
            </Row>
          ) : null}
        </dl>
      ) : null}
    </div>
  );
}
