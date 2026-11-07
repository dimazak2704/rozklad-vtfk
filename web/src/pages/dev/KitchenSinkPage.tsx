import { zodResolver } from '@hookform/resolvers/zod';
import { CalendarDays, Copy, Download, FileText, Pencil, Plus, Search, Trash, Users } from 'lucide-react';
import { useMemo, useState, type ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '@/api/errors';
import type { IsoDate, WeekParity } from '@/api/types';
import { DayColumn, DayList, LessonCell, NowIndicator, WeekGrid } from '@/components/schedule';
import {
  Avatar,
  Badge,
  Breadcrumbs,
  Button,
  Checkbox,
  Chip,
  Combobox,
  DataTable,
  DateNav,
  Dialog,
  DialogContent,
  DialogTrigger,
  Drawer,
  DrawerContent,
  DrawerTrigger,
  EmptyState,
  ErrorState,
  Field,
  HoverCard,
  HoverCardContent,
  HoverCardTrigger,
  IconButton,
  Input,
  Kbd,
  KbdCombo,
  notify,
  NumberInput,
  PageHeader,
  Pagination,
  Plate,
  Panel,
  ParityBadge,
  Popover,
  PopoverContent,
  PopoverTrigger,
  RadioGroup,
  RowActions,
  ScoreBar,
  Section,
  SegmentedControl,
  Select,
  Skeleton,
  SkeletonText,
  StatusDot,
  Switch,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  Textarea,
  Tooltip,
  type DataTableColumn,
} from '@/components/ui';
import { minutesToTime } from '@/lib/date';
import { LINE_COUNT } from '@/lib/discipline-color';
import { cn } from '@/lib/cn';
import { confirm } from '@/stores/confirm';
import { useThemeStore, type ThemePreference } from '@/stores/theme';
import { BELLS_MON_THU, DEMO_BLOCK, DEMO_OPTIONS, DEMO_WEEK, DISCIPLINES } from './demo-data';

const DEMO_TODAY: IsoDate = '2026-11-09';
const NAV = [
  ['tokens', 'Токени'],
  ['buttons', 'Кнопки'],
  ['forms', 'Форми'],
  ['states', 'Стани'],
  ['overlays', 'Шари'],
  ['navigation', 'Навігація'],
  ['table', 'Таблиця'],
  ['schedule', 'Розклад'],
  ['themes', 'Обидві теми'],
] as const;

function Demo({ title, children, className }: { title: string; children: ReactNode; className?: string }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-3 rounded-panel border border-default bg-surface p-4', className)}>
      <h3 className="text-caption font-semibold text-muted">{title}</h3>
      {children}
    </div>
  );
}

function Swatch({ name, className }: { name: string; className: string }) {
  return (
    <div className="flex items-center gap-2.5">
      <span className={cn('size-9 shrink-0 rounded-control border border-default', className)} />
      <code className="font-mono text-meta text-secondary">{name}</code>
    </div>
  );
}

const GROUP_OPTIONS = ['1КІ1', '1КІ2', '2КІ3', '3КІ1', '4КІ1', '4КІ2', '4КІ3', '4ЕА1', '4РТ2', '4ОП1', '1ТК1', '2ОБ1'].map((code) => ({
  value: code,
  label: code,
  description: `${code.charAt(0)} курс`,
}));

const formSchema = z.object({
  group: z.string().min(1, 'Оберіть групу'),
  hours: z.number({ error: 'Вкажіть кількість годин' }).min(1, 'Щонайменше 1 година').max(8, 'Не більше 8 годин на тиждень'),
  comment: z.string().max(200, 'Не більше 200 символів').optional(),
});
type FormValues = z.infer<typeof formSchema>;

interface TeacherRow {
  id: string;
  name: string;
  commission: string;
  substitutions: number;
  hours: number;
  linked: boolean;
}

const TEACHER_ROWS: TeacherRow[] = [
  { id: '1', name: 'Кренцін М.Д.', commission: 'Компʼютерної інженерії', substitutions: 2, hours: 4, linked: true },
  { id: '2', name: 'Непийвода М.В.', commission: 'Компʼютерної інженерії', substitutions: 5, hours: 10, linked: true },
  { id: '3', name: 'Колесник А.І.', commission: 'Компʼютерної інженерії', substitutions: 1, hours: 2, linked: false },
  { id: '4', name: 'Алексєєнко О.А.', commission: 'Економічних дисциплін', substitutions: 0, hours: 0, linked: false },
  { id: '5', name: 'Прокопчук', commission: 'Іноземних мов', substitutions: 3, hours: 6, linked: false },
];

/** Сторінка всіх компонентів (лише `npm run dev`): перевірка в обох темах на 360–1440 px. */
export default function KitchenSinkPage() {
  const preference = useThemeStore((state) => state.preference);
  const setPreference = useThemeStore((state) => state.setPreference);
  const [minutes, setMinutes] = useState(10 * 60 + 5);
  const [date, setDate] = useState<IsoDate>(DEMO_TODAY);
  const [group, setGroup] = useState<string | null>('4КІ1');
  const [view, setView] = useState<'day' | 'week'>('week');
  const [hours, setHours] = useState<number | null>(4);
  const [select, setSelect] = useState<string>('CONFIRM');
  const [radio, setRadio] = useState<string>('SICK_LEAVE');
  const [checked, setChecked] = useState<boolean | 'indeterminate'>('indeterminate');
  const [switchOn, setSwitchOn] = useState(true);
  const [tableState, setTableState] = useState<'data' | 'loading' | 'empty' | 'error'>('data');
  const [page, setPage] = useState(0);
  const [onlyMine, setOnlyMine] = useState(true);

  const form = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: { group: '', comment: '' },
  });

  const columns = useMemo<Array<DataTableColumn<TeacherRow>>>(
    () => [
      { id: 'name', header: 'Викладач', cell: (row) => row.name, sortValue: (row) => row.name, rowHeader: true },
      { id: 'commission', header: 'Циклова комісія', cell: (row) => row.commission, sortValue: (row) => row.commission, hideBelow: 'md' },
      { id: 'subs', header: 'Замін', cell: (row) => row.substitutions, sortValue: (row) => row.substitutions, numeric: true },
      { id: 'hours', header: 'Годин', cell: (row) => row.hours, sortValue: (row) => row.hours, numeric: true, hideBelow: 'sm' },
      {
        id: 'linked',
        header: 'Обліковий запис',
        cell: (row) => (row.linked ? <Badge tone="success">Підключено</Badge> : <Badge>Не запрошено</Badge>),
        hideBelow: 'lg',
      },
    ],
    [],
  );

  const monday = DEMO_WEEK.days[0];
  const tuesday = DEMO_WEEK.days[1];
  const lessonStates = [
    DEMO_WEEK.days[0]?.lessons[0],
    DEMO_WEEK.days[0]?.lessons[1],
    DEMO_WEEK.days[1]?.lessons[3],
    DEMO_WEEK.days[2]?.lessons[3],
    DEMO_WEEK.days[3]?.lessons[1],
    DEMO_WEEK.days[4]?.lessons[2],
    DEMO_BLOCK,
    DEMO_WEEK.days[3]?.lessons[2],
  ].filter((lesson) => lesson !== undefined);

  const parity: WeekParity = 'NUMERATOR';

  return (
    <div className="flex flex-col gap-10">
      <PageHeader
        title="Компоненти"
        description="Дизайн-система «Розклад ВТФК»: світ трамвайного розкладу — лінія маршруту, табличний ритм, обʼїзди для змін. Дані демо з розкладу 4КІ1 (seed), зміни синтетичні."
        breadcrumbs={[{ label: 'Розробка' }, { label: 'Компоненти' }]}
        actions={
          <SegmentedControl<ThemePreference>
            aria-label="Тема сторінки"
            value={preference}
            onValueChange={setPreference}
            options={[
              { value: 'light', label: 'Світла' },
              { value: 'dark', label: 'Темна' },
              { value: 'system', label: 'Система' },
            ]}
          />
        }
      />

      <nav aria-label="Розділи сторінки" className="scrollbar-none -mt-4 flex gap-1 overflow-x-auto">
        {NAV.map(([id, label]) => (
          <a
            key={id}
            href={`#${id}`}
            className="shrink-0 rounded-full border border-default bg-surface px-3 py-1.5 text-caption font-semibold text-secondary hover:border-strong hover:text-primary focus-visible:outline-2 focus-visible:outline-focus"
          >
            {label}
          </a>
        ))}
      </nav>

      <Section id="tokens" title="Токени" description="Кольори за ролями, шкала тексту, лінії дисциплін.">
        <div className="grid gap-4 lg:grid-cols-3">
          <Demo title="Поверхні й текст">
            <div className="grid gap-2 sm:grid-cols-2">
              <Swatch name="bg-canvas" className="bg-canvas" />
              <Swatch name="bg-surface" className="bg-surface" />
              <Swatch name="bg-sunken" className="bg-sunken" />
              <Swatch name="bg-shell" className="bg-shell" />
              <Swatch name="bg-accent" className="bg-accent" />
              <Swatch name="bg-selected" className="bg-selected" />
            </div>
            <p className="text-ui text-primary">text-primary — основний текст</p>
            <p className="text-ui text-secondary">text-secondary — пояснення</p>
            <p className="text-ui text-muted">text-muted — підписи, час</p>
            <p className="text-ui text-accent">text-accent — посилання, «зараз»</p>
          </Demo>
          <Demo title="Стани занять («обʼїзди»)">
            <div className="grid gap-2 sm:grid-cols-2">
              <Swatch name="change" className="border-change bg-change-subtle" />
              <Swatch name="cancel" className="border-cancel bg-cancel-subtle" />
              <Swatch name="add" className="border-add bg-add-subtle" />
              <Swatch name="room" className="border-room bg-room-subtle" />
              <Swatch name="block" className="hatch bg-block-subtle" />
              <Swatch name="danger" className="bg-danger" />
            </div>
            <div className="flex flex-wrap gap-1.5">
              <Badge tone="change">Заміна</Badge>
              <Badge tone="cancel">Пари не буде</Badge>
              <Badge tone="add">Додаткова</Badge>
              <Badge tone="room">Аудиторія</Badge>
            </div>
          </Demo>
          <Demo title="Лінії дисциплін (12, AA в обох темах)">
            <div className="grid grid-cols-4 gap-2">
              {Array.from({ length: LINE_COUNT }, (_, index) => (
                <span
                  key={index}
                  data-line={index}
                  className="flex items-center gap-1.5 rounded-plate bg-[var(--line-tint)] px-2 py-1 text-caption font-semibold text-[var(--line-text)]"
                >
                  <span className="size-2 rounded-full bg-[var(--line-dot)]" />
                  {index + 1}
                </span>
              ))}
            </div>
          </Demo>
        </div>
        <Demo title="Шкала тексту: Fira Sans + Fira Sans Condensed (українська кирилиця, табличні цифри)">
          <div className="flex flex-col gap-2">
            <p className="font-condensed text-numeral leading-none font-bold text-accent figures">23</p>
            <p className="font-condensed text-display font-bold">4КІ1 · ЗНАМЕННИК</p>
            <p className="text-headline font-semibold">Розклад групи на тиждень</p>
            <p className="text-title font-semibold">Заміни на пʼятницю, 13 листопада</p>
            <p className="text-heading font-semibold">Ґанок, їжак, Єнакієве, пʼятниця — кирилиця повністю</p>
            <p className="text-body">
              Основний текст 16 px: пари за чисельником проходять у непарні тижні семестру, якщо в календарі немає винятку.
            </p>
            <p className="text-ui">Інтерфейс 14 px: кнопки, поля, таблиці, меню.</p>
            <p className="text-caption text-secondary">Підпис 13 px: час, ауд. 301, 1 підгрупа.</p>
            <p className="text-meta text-muted">Мета 12 px: 08:30–09:30 · 12:20–13:20</p>
            <p className="font-condensed text-heading font-bold figures">0123456789 · 11:11 · 08:08</p>
          </div>
        </Demo>
      </Section>

      <Section id="buttons" title="Кнопки" description="Одна заповнена дія на екран; решта — другорядні.">
        <div className="grid gap-4 lg:grid-cols-2">
          <Demo title="Варіанти">
            <div className="flex flex-wrap items-center gap-2">
              <Button variant="primary">Опублікувати</Button>
              <Button>Зберегти чернетку</Button>
              <Button variant="ghost">Скасувати</Button>
              <Button variant="danger">Видалити</Button>
              <Button variant="danger-ghost">Скасувати заміну</Button>
              <Button variant="link">Детальніше</Button>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button variant="primary" size="sm" iconStart={<Plus aria-hidden="true" className="size-4" />}>
                Додати
              </Button>
              <Button size="md" iconStart={<Download aria-hidden="true" className="size-4" />}>
                DOCX
              </Button>
              <Button size="lg" variant="primary" loading>
                Публікуємо
              </Button>
              <Button disabled>Недоступно</Button>
            </div>
          </Demo>
          <Demo title="Іконки з підписом і клавіші">
            <div className="flex flex-wrap items-center gap-1">
              <IconButton label="Редагувати" icon={<Pencil aria-hidden="true" className="size-4" />} />
              <IconButton label="Копіювати посилання" icon={<Copy aria-hidden="true" className="size-4" />} />
              <IconButton label="Видалити" icon={<Trash aria-hidden="true" className="size-4" />} variant="danger-ghost" />
              <IconButton label="Пошук" shortcut="mod+k" icon={<Search aria-hidden="true" className="size-4" />} variant="secondary" />
              <IconButton label="Закріпити" pressed icon={<FileText aria-hidden="true" className="size-4" />} />
            </div>
            <p className="flex flex-wrap items-center gap-2 text-ui text-secondary">
              <KbdCombo keys="mod+k" /> палітра · <Kbd>?</Kbd> довідка · <KbdCombo keys="arrowleft" /> <KbdCombo keys="arrowright" /> день ·{' '}
              <Kbd>T</Kbd> сьогодні
            </p>
          </Demo>
        </div>
      </Section>

      <Section id="forms" title="Форми" description="react-hook-form + zod, помилки українською під полем.">
        <div className="grid gap-4 lg:grid-cols-2">
          <Demo title="Поля">
            <Field label="Пошук групи" hint="Можна латиницею: 4ki1">
              <Input placeholder="Код групи" iconStart={<Search className="size-4" />} />
            </Field>
            <Field label="Група" required>
              <Combobox value={group} onValueChange={setGroup} options={GROUP_OPTIONS} placeholder="Оберіть групу" clearable />
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Годин на тиждень">
                <NumberInput value={hours} onValueChange={setHours} min={0} max={10} step={1} unit="год" />
              </Field>
              <Field label="Режим погодження">
                <Select
                  value={select}
                  onValueChange={setSelect}
                  options={[
                    { value: 'CONFIRM', label: 'З підтвердженням', description: 'Викладач підтверджує в Telegram' },
                    { value: 'AUTO', label: 'Автоматично', description: 'Заміна одразу підтверджена' },
                  ]}
                />
              </Field>
            </div>
            <Field label="Коментар" hint="Бачить лише диспетчер" error="Не більше 200 символів">
              <Textarea defaultValue="Лікарняний до пʼятниці, далі — за станом" />
            </Field>
          </Demo>
          <Demo title="Вибір і перемикачі">
            <Checkbox label="Лише мої зміни" description="Показувати заміни, де я новий викладач" checked={checked} onCheckedChange={setChecked} />
            <Switch label="Ранкова розсилка о 07:30" description="Бот надішле розклад групи на день" checked={switchOn} onCheckedChange={setSwitchOn} />
            <RadioGroup
              legend="Причина відсутності"
              value={radio}
              onValueChange={setRadio}
              options={[
                { value: 'SICK_LEAVE', label: 'Лікарняний' },
                { value: 'BUSINESS_TRIP', label: 'Відрядження' },
                { value: 'PERSONAL', label: 'Особисті обставини', description: 'У розпорядженні друкується як «Відп.»' },
              ]}
            />
            <SegmentedControl<'day' | 'week'>
              aria-label="Подання"
              value={view}
              onValueChange={setView}
              options={[
                { value: 'day', label: 'День' },
                { value: 'week', label: 'Тиждень' },
              ]}
            />
            <DateNav value={date} onChange={setDate} today={DEMO_TODAY} />
          </Demo>
        </div>
        <Demo title="Форма з перевіркою (zod)">
          <form
            noValidate
            className="grid gap-4 sm:grid-cols-[1fr_10rem_1fr_auto] sm:items-start"
            onSubmit={(event) =>
              void form.handleSubmit((values) => {
                notify.success(`Навантаження ${values.group}: ${values.hours} год збережено`);
              })(event)
            }
          >
            <Field label="Група" required error={form.formState.errors.group?.message}>
              <Input {...form.register('group')} placeholder="4КІ1" />
            </Field>
            <Field label="Годин" required error={form.formState.errors.hours?.message}>
              <Input {...form.register('hours', { valueAsNumber: true })} inputMode="numeric" placeholder="4" />
            </Field>
            <Field label="Коментар" error={form.formState.errors.comment?.message}>
              <Input {...form.register('comment')} placeholder="Необовʼязково" />
            </Field>
            <Button type="submit" variant="primary" className="sm:mt-6.5" loading={form.formState.isSubmitting}>
              Зберегти
            </Button>
          </form>
        </Demo>
      </Section>

      <Section id="states" title="Стани" description="Бейджі, оцінки, парність, завантаження, порожнеча, помилка.">
        <div className="grid gap-4 lg:grid-cols-3">
          <Demo title="Бейджі й таблетки">
            <div className="flex flex-wrap gap-1.5">
              <Badge>Чернетка</Badge>
              <Badge tone="accent">Чекає підтвердження</Badge>
              <Badge tone="success">Підтверджено</Badge>
              <Badge tone="danger">Відхилено</Badge>
              <Badge tone="change">Відп.</Badge>
              <Badge tone="change">Зв.зам.</Badge>
              <Badge tone="neutral">Вичит.</Badge>
            </div>
            <div className="flex flex-wrap gap-2">
              <Chip selected={onlyMine} onToggle={() => setOnlyMine((value) => !value)}>
                Лише мої зміни
              </Chip>
              <Chip icon={<Users aria-hidden="true" className="size-3.5" />} onRemove={() => notify.info('Фільтр прибрано')} count={3}>
                4 курс
              </Chip>
            </div>
            <div className="flex flex-wrap items-center gap-4">
              <StatusDot tone="live" pulse label="Наживо" showLabel />
              <StatusDot tone="change" label="Очікує" showLabel />
              <StatusDot tone="cancel" label="Офлайн" showLabel />
            </div>
          </Demo>
          <Demo title="Парність, таблички, люди">
            <div className="flex flex-wrap items-center gap-2">
              <Plate size="xl">4КІ1</Plate>
              <Plate size="lg" tone="accent">
                2ОБ1
              </Plate>
              <Plate>ауд. 309</Plate>
              <Plate size="sm">1ТК1</Plate>
            </div>
            <div className="flex flex-wrap gap-2">
              <ParityBadge parity="NUMERATOR" weekNumber={11} prefix="Зараз" />
              <ParityBadge parity="DENOMINATOR" weekNumber={8} size="sm" />
            </div>
            <div className="flex items-center gap-3">
              <Avatar name="Кренцін М.Д." />
              <Avatar name="Непийвода М.В." size="lg" />
              <Avatar name="Микола Поліщук" size="sm" />
            </div>
          </Demo>
          <Demo title="Варіанти заміни: оцінка і «Чому?»">
            <ul className="flex flex-col gap-2.5">
              {DEMO_OPTIONS.map((option, index) => (
                <li key={option.title} className="grid grid-cols-[1.25rem_minmax(0,1fr)_auto] items-center gap-x-2.5 gap-y-1">
                  <Kbd>{index + 1}</Kbd>
                  <span className="truncate text-ui font-semibold">{option.title}</span>
                  <Popover>
                    <PopoverTrigger asChild>
                      <Button variant="link" size="sm" className="text-caption">
                        Чому?
                      </Button>
                    </PopoverTrigger>
                    <PopoverContent width="sm" title={option.title}>
                      <ul className="flex list-disc flex-col gap-1 ps-4 text-caption text-secondary">
                        {option.reasons.map((reason) => (
                          <li key={reason}>{reason}</li>
                        ))}
                        {option.warnings.map((warning) => (
                          <li key={warning} className="text-change">
                            {warning}
                          </li>
                        ))}
                      </ul>
                    </PopoverContent>
                  </Popover>
                  <ScoreBar value={option.score} label={`Оцінка: ${option.title}`} className="col-span-3 col-start-2" />
                </li>
              ))}
            </ul>
          </Demo>
        </div>
        <div className="grid gap-4 lg:grid-cols-3">
          <Demo title="Завантаження">
            <div className="flex flex-col gap-3">
              <Skeleton className="h-6 w-40" />
              <SkeletonText lines={3} />
              <Skeleton className="h-16 w-full" />
            </div>
          </Demo>
          <Demo title="Порожньо">
            <EmptyState
              size="sm"
              icon={<CalendarDays aria-hidden="true" />}
              title="Замін на завтра немає"
              description="Коли диспетчер внесе зміни, вони зʼявляться тут і в Telegram-каналі групи."
              action={<Button size="sm">Дивитися заміни на інший день</Button>}
            />
          </Demo>
          <Demo title="Помилка з повтором">
            <ErrorState
              size="sm"
              error={new ApiError({ status: 0, kind: 'network', requestId: 'req-4f2a' })}
              onRetry={() => notify.info('Повторюємо запит')}
            />
          </Demo>
        </div>
      </Section>

      <Section id="overlays" title="Накладені шари" description="Діалоги, шторка, спливні панелі, підказки, сповіщення.">
        <div className="flex flex-wrap gap-2">
          <Dialog>
            <DialogTrigger asChild>
              <Button>Діалог</Button>
            </DialogTrigger>
            <DialogContent
              title="Нова відсутність"
              description="Кренцін М.Д. — пари, які зачепить відсутність, зʼявляться в списку замін."
              footer={
                <>
                  <Button variant="ghost">Скасувати</Button>
                  <Button variant="primary">Зберегти</Button>
                </>
              }
            >
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="З дати">
                  <Input type="date" defaultValue="2026-11-09" />
                </Field>
                <Field label="По дату">
                  <Input type="date" defaultValue="2026-11-13" />
                </Field>
              </div>
            </DialogContent>
          </Dialog>
          <Button
            variant="danger-ghost"
            onClick={() =>
              void confirm({
                title: 'Скасувати заміну?',
                description: 'Пара повернеться в стан «потребує рішення», викладач отримає сповіщення.',
                objectName: '4КІ1 · 2 пара · НДЕКСМ (Непийвода) замість СТП',
                confirmLabel: 'Скасувати заміну',
                cancelLabel: 'Залишити',
                tone: 'danger',
                onConfirm: () => new Promise((resolve) => setTimeout(resolve, 700)),
              }).then((ok) => ok && notify.undo('Заміну скасовано', { onUndo: () => notify.success('Заміну відновлено') }))
            }
          >
            Підтвердження
          </Button>
          <Drawer direction="right">
            <DrawerTrigger asChild>
              <Button>Шторка</Button>
            </DrawerTrigger>
            <DrawerContent side="right" title="4КІ1 · понеділок" description="Деталі дня групи">
              {monday ? <DayList day={monday} bells={BELLS_MON_THU} today={DEMO_TODAY} at={minutes} showHeader={false} /> : null}
            </DrawerContent>
          </Drawer>
          <HoverCard openDelay={300}>
            <HoverCardTrigger asChild>
              <a href="#hover" className="inline-flex h-9 items-center px-2 text-ui font-semibold text-accent underline-offset-4 hover:underline">
                Наведіть на мене
              </a>
            </HoverCardTrigger>
            <HoverCardContent>
              <p className="text-ui">Картка при наведенні для миші. Для клавіатури використовуйте HoverDetails.</p>
            </HoverCardContent>
          </HoverCard>
          <Tooltip content="Підказка зʼявляється через 300 мс" shortcut="?">
            <Button variant="ghost">Підказка</Button>
          </Tooltip>
          <Button onClick={() => notify.success('Розпорядження №35 опубліковано', { description: '16 рядків, 9 груп отримають повідомлення' })}>
            Успіх
          </Button>
          <Button
            onClick={() =>
              notify.error(
                new ApiError({ status: 409, detail: 'Непийвода вже має пару в цей час (4КІ2, ауд. 14).', requestId: 'req-91c' }),
                'Не вдалося призначити заміну',
              )
            }
          >
            Помилка
          </Button>
          <Button onClick={() => notify.undo('Пару переставлено на 2', { onUndo: () => notify.info('Повернули як було') })}>
            Із скасуванням
          </Button>
        </div>
      </Section>

      <Section id="navigation" title="Навігація" description="Вкладки, ланцюжок, сторінки.">
        <div className="grid gap-4 lg:grid-cols-2">
          <Demo title="Вкладки">
            <Tabs defaultValue="pending">
              <TabsList aria-label="Стани замін">
                <TabsTrigger value="pending" count={3}>
                  Чекають
                </TabsTrigger>
                <TabsTrigger value="confirmed" count={12}>
                  Підтверджені
                </TabsTrigger>
                <TabsTrigger value="declined" count={1}>
                  Відхилені
                </TabsTrigger>
              </TabsList>
              <TabsContent value="pending">
                <p className="text-ui text-secondary">Три заміни чекають відповіді викладачів у Telegram.</p>
              </TabsContent>
              <TabsContent value="confirmed">
                <p className="text-ui text-secondary">Підтверджені заміни потраплять у чернетку розпорядження.</p>
              </TabsContent>
              <TabsContent value="declined">
                <p className="text-ui text-secondary">Відхилена заміна повертає пару в «потребує рішення».</p>
              </TabsContent>
            </Tabs>
          </Demo>
          <Demo title="Ланцюжок і сторінки">
            <Breadcrumbs items={[{ label: 'Панель', to: '/admin' }, { label: 'Каталог', to: '/admin/catalog/groups' }, { label: 'Викладачі' }]} />
            <Pagination page={page} size={20} total={134} onPageChange={setPage} noun="викладачів" />
          </Demo>
        </div>
      </Section>

      <Section
        id="table"
        title="Таблиця"
        description="Сортування, стани, дії рядка."
        actions={
          <SegmentedControl
            aria-label="Стан таблиці"
            size="sm"
            value={tableState}
            onValueChange={setTableState}
            options={[
              { value: 'data', label: 'Дані' },
              { value: 'loading', label: 'Завантаження' },
              { value: 'empty', label: 'Порожньо' },
              { value: 'error', label: 'Помилка' },
            ]}
          />
        }
      >
        <Panel flush title="Викладачі" description="Заміни й години за жовтень 2026 (демо)">
          <div className="p-2">
            <DataTable
              caption="Викладачі: заміни за місяць"
              columns={columns}
              rows={tableState === 'data' ? TEACHER_ROWS : tableState === 'empty' ? [] : undefined}
              getRowId={(row) => row.id}
              loading={tableState === 'loading'}
              error={tableState === 'error' ? new ApiError({ status: 503 }) : undefined}
              onRetry={() => setTableState('data')}
              defaultSort={{ columnId: 'subs', direction: 'desc' }}
              rowActions={(row) => (
                <RowActions
                  rowLabel={row.name}
                  actions={[
                    { label: 'Редагувати', icon: <Pencil aria-hidden="true" />, onSelect: () => notify.info(`Редагування: ${row.name}`) },
                    { label: 'Надіслати запрошення', icon: <Plus aria-hidden="true" />, onSelect: () => notify.success('Запрошення створено') },
                    {
                      label: 'Видалити',
                      icon: <Trash aria-hidden="true" />,
                      tone: 'danger',
                      separatorBefore: true,
                      onSelect: () => void confirm({ title: 'Видалити викладача?', objectName: row.name, tone: 'danger', confirmLabel: 'Видалити' }),
                    },
                  ]}
                />
              )}
              empty={
                <EmptyState
                  size="sm"
                  title="Викладачів не знайдено"
                  description="Змініть пошук або додайте викладача вручну чи через імпорт."
                  action={<Button size="sm" variant="primary">Додати викладача</Button>}
                />
              }
            />
          </div>
        </Panel>
      </Section>

      <Section
        id="schedule"
        title="Розклад"
        description="Лінія маршруту з «живим» маркером, клітинки з обʼїздами, тиждень і день. Час можна посунути."
        actions={
          <label className="flex items-center gap-3 text-caption text-secondary">
            Час демо
            <input
              type="range"
              min={8 * 60}
              max={14 * 60}
              step={5}
              value={minutes}
              onChange={(event) => setMinutes(Number(event.target.value))}
              className="w-40 accent-[var(--background-color-accent)]"
            />
            <span className="w-11 font-semibold figures text-primary">{minutesToTime(minutes)}</span>
          </label>
        }
      >
        <Panel>
          <NowIndicator bells={BELLS_MON_THU} lessonPairs={[1, 2, 3, 4]} at={minutes} />
        </Panel>
        <Demo title="NowIndicator inline">
          <NowIndicator bells={BELLS_MON_THU} lessonPairs={[1, 2, 3, 4]} at={minutes} variant="inline" className="max-w-md" />
        </Demo>
        <Demo title="Стани клітинки (наведіть або натисніть Enter — деталі)">
          <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
            {lessonStates.map((lesson) => (
              <div key={lesson.id} className="rounded-panel border border-default p-1">
                <LessonCell lesson={lesson} date={DEMO_TODAY} dayParity={parity} />
              </div>
            ))}
          </div>
        </Demo>
        <div className="hidden md:block">
          <WeekGrid schedule={DEMO_WEEK} today={DEMO_TODAY} at={minutes} stickyTop="var(--header-height)" />
        </div>
        <div className="grid gap-4 lg:grid-cols-[22.5rem_minmax(0,1fr)]">
          <Demo title="DayList — телефон (360 px)">
            <div className="mx-auto w-full max-w-[22.5rem]">
              {monday ? <DayList day={monday} bells={BELLS_MON_THU} today={DEMO_TODAY} at={minutes} /> : null}
            </div>
          </Demo>
          <div className="grid gap-4 sm:grid-cols-2">
            {monday ? <DayColumn day={monday} bells={BELLS_MON_THU} today={DEMO_TODAY} at={minutes} /> : null}
            {tuesday ? <DayColumn day={tuesday} bells={BELLS_MON_THU} today={DEMO_TODAY} perspective="group" /> : null}
          </div>
        </div>
      </Section>

      <Section id="themes" title="Обидві теми поруч" description="Ті самі токени в «острівцях» data-theme: аркуш розпорядження завжди світлий.">
        <div className="grid gap-4 lg:grid-cols-2">
          {(['light', 'dark'] as const).map((theme) => (
            <div key={theme} data-theme={theme} className="flex flex-col gap-3 rounded-panel border border-default bg-canvas p-4 text-primary">
              <div className="flex items-center justify-between gap-2">
                <p className="text-heading font-semibold">{theme === 'light' ? 'Світла' : 'Темна'}</p>
                <ParityBadge parity="NUMERATOR" weekNumber={11} size="sm" plain />
              </div>
              <div className="flex flex-wrap gap-2">
                <Button variant="primary" size="sm">
                  Опублікувати
                </Button>
                <Button size="sm">Чернетка</Button>
                <Badge tone="change">Заміна</Badge>
                <Badge tone="cancel">Скасовано</Badge>
                <Badge tone="add">Додаткова</Badge>
                <Badge tone="room">Аудиторія</Badge>
              </div>
              <div className="grid gap-1.5 rounded-panel border border-default bg-surface p-1.5">
                {lessonStates.slice(0, 5).map((lesson) => (
                  <LessonCell key={lesson.id} lesson={lesson} date={DEMO_TODAY} dayParity={parity} />
                ))}
              </div>
              <NowIndicator bells={BELLS_MON_THU} lessonPairs={[1, 2, 3, 4]} at={minutes} variant="inline" />
            </div>
          ))}
        </div>
        <p className="text-caption text-muted">
          Дисципліни демо: {Object.values(DISCIPLINES).map((discipline) => discipline.shortName).join(', ')}.
        </p>
      </Section>
    </div>
  );
}
