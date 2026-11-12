import { KeyRound, Link2Off, Pencil, Plus, RotateCcw, Search, ShieldOff } from 'lucide-react';
import { useMemo, useRef, useState } from 'react';
import {
  useAccessList,
  useRestoreAccess,
  useRevokeAccess,
  useUnbindGoogle,
  type AccessDto,
  type AccessRole,
  type AccessStatus,
} from '@/api/access';
import { useMe } from '@/api/me';
import { EditAccessDialog } from '@/components/access/EditAccessDialog';
import { GrantAccessDialog } from '@/components/access/GrantAccessDialog';
import { PasswordLinkDialog } from '@/components/access/PasswordLinkDialog';
import { loginName, rolesText, signInText, STATUS_KIND, STATUS_LABEL } from '@/components/access/labels';
import {
  Button,
  DataTable,
  EmptyState,
  Input,
  PageHeader,
  Panel,
  RowActions,
  Select,
  StatusText,
  notify,
  type DataTableColumn,
  type RowAction,
} from '@/components/ui';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { formatInstant } from '@/lib/date';
import { confirm } from '@/stores/confirm';
import { handleSearchEscape, useHotkeys } from '@/hooks/useHotkeys';

const ALL = 'all';
type RoleFilter = AccessRole | typeof ALL;
type StatusFilter = AccessStatus | typeof ALL;

const ROLE_FILTERS: ReadonlyArray<{ value: RoleFilter; label: string }> = [
  { value: ALL, label: 'Усі ролі' },
  { value: 'ADMIN', label: 'Адміністратори' },
  { value: 'TEACHER', label: 'Викладачі' },
];

const STATUS_FILTERS: ReadonlyArray<{ value: StatusFilter; label: string }> = [
  { value: ALL, label: 'Усі' },
  { value: 'ACTIVE', label: 'Активні' },
  { value: 'REVOKED', label: 'Відкликані' },
];

type Dialog =
  | { kind: 'grant' }
  | { kind: 'edit'; access: AccessDto }
  | { kind: 'password'; access: AccessDto }
  | null;

function Muted({ children }: { children: string }) {
  return <span className="text-muted">{children}</span>;
}

/**
 * «Доступи»: хто може увійти. Доступ за e-mail — це те, що пропускає вхід через Google; пароль людина задає
 * сама за одноразовим посиланням. Відкликаний доступ повертає лише «Повернути доступ».
 */
export default function AccessPage() {
  const [search, setSearch] = useState('');
  const searchRef = useRef<HTMLInputElement>(null);
  useHotkeys([{ keys: '/', handler: () => searchRef.current?.focus(), description: 'Пошук користувача', group: 'Доступ' }]);
  const [role, setRole] = useState<RoleFilter>(ALL);
  const [status, setStatus] = useState<StatusFilter>(ALL);
  const q = useDebouncedValue(search.trim(), 250);
  const list = useAccessList({ q: q || undefined, role: role === ALL ? undefined : role, status: status === ALL ? undefined : status });
  const revoke = useRevokeAccess();
  const restore = useRestoreAccess();
  const unbind = useUnbindGoogle();
  // свій доступ відкликати не можна (core відповідає 422 SELF_REVOKE) — пункту меню для себе немає
  const me = useMe();
  const [dialog, setDialog] = useState<Dialog>(null);
  const filtered = Boolean(q) || role !== ALL || status !== ALL;

  const revokeAccess = async (access: AccessDto) => {
    await confirm({
      title: 'Відкликати доступ?',
      objectName: access.displayName,
      description:
        'Людина більше не зможе увійти ні через Google, ні з паролем; відкриті запрошення й посилання перестануть діяти. ' +
        'Відкликайте доступ, коли працівник іде з коледжу. Повернути можна в цьому ж списку.',
      confirmLabel: 'Відкликати доступ',
      tone: 'danger',
      onConfirm: async () => {
        try {
          await revoke.mutateAsync(access.id);
          notify.success(`Доступ відкликано: ${access.displayName}`);
        } catch (error) {
          notify.error(error, 'Не вдалося відкликати доступ');
        }
      },
    });
  };

  const restoreAccess = async (access: AccessDto) => {
    try {
      await restore.mutateAsync(access.id);
      notify.success(`Доступ повернуто: ${access.displayName}`);
    } catch (error) {
      notify.error(error, 'Не вдалося повернути доступ');
    }
  };

  const unbindGoogle = async (access: AccessDto) => {
    await confirm({
      title: 'Відв’язати Google?',
      objectName: access.displayName,
      description:
        'Потрібно, коли акаунт Google створили заново з тією самою адресою. Наступний вхід через Google прив’яже новий акаунт.',
      confirmLabel: 'Відв’язати',
      onConfirm: async () => {
        try {
          await unbind.mutateAsync(access.id);
          notify.success(`Google відв’язано: ${access.displayName}`);
        } catch (error) {
          notify.error(error, 'Не вдалося відв’язати Google');
        }
      },
    });
  };

  const columns = useMemo<Array<DataTableColumn<AccessDto>>>(
    () => [
      {
        id: 'name',
        header: 'Ім’я',
        rowHeader: true,
        cell: (a) => (
          <span className="flex flex-col">
            <span className="flex flex-wrap items-baseline gap-x-2">
              <span className="font-[650]">{a.displayName}</span>
              {a.demo ? <span className="text-caption font-normal text-muted">демо</span> : null}
            </span>
            <span className="text-caption font-normal text-muted">{loginName(a)}</span>
          </span>
        ),
      },
      { id: 'roles', header: 'Ролі', hideBelow: 'sm', cell: (a) => rolesText(a.roles) },
      {
        id: 'teacher',
        header: 'Викладач',
        hideBelow: 'md',
        className: 'max-md:hidden!',
        cell: (a) => (a.teacher ? a.teacher.shortName : <Muted>—</Muted>),
      },
      {
        id: 'signIn',
        header: 'Вхід',
        hideBelow: 'lg',
        className: 'max-md:hidden!',
        cell: (a) => {
          const text = signInText(a);
          return text ? text : <Muted>ще не входили</Muted>;
        },
      },
      {
        id: 'status',
        header: 'Стан',
        cell: (a) => <StatusText kind={STATUS_KIND[a.status]}>{STATUS_LABEL[a.status]}</StatusText>,
      },
      {
        id: 'lastLogin',
        header: 'Останній вхід',
        hideBelow: 'lg',
        className: 'max-md:hidden!',
        // дата — довідка, не головне в рядку: вторинним кольором, щоб не перекрикувала ім’я
        cell: (a) => (a.lastLoginAt ? <span className="text-secondary figures">{formatInstant(a.lastLoginAt)}</span> : <Muted>—</Muted>),
      },
    ],
    [],
  );

  const actionsFor = (a: AccessDto): RowAction[] => {
    const self = me.data?.userId === a.id;
    const actions: RowAction[] = [{ label: 'Змінити доступ', icon: <Pencil aria-hidden="true" />, onSelect: () => setDialog({ kind: 'edit', access: a }) }];
    if (a.status !== 'REVOKED') {
      actions.push({ label: 'Посилання для пароля', icon: <KeyRound aria-hidden="true" />, onSelect: () => setDialog({ kind: 'password', access: a }) });
    }
    if (a.google.linked) {
      actions.push({ label: 'Відв’язати Google', icon: <Link2Off aria-hidden="true" />, onSelect: () => void unbindGoogle(a) });
    }
    if (a.status === 'REVOKED') {
      actions.push({ label: 'Повернути доступ', icon: <RotateCcw aria-hidden="true" />, separatorBefore: true, onSelect: () => void restoreAccess(a) });
    } else if (!self) {
      actions.push({
        label: 'Відкликати доступ',
        icon: <ShieldOff aria-hidden="true" />,
        tone: 'danger',
        separatorBefore: true,
        onSelect: () => void revokeAccess(a),
      });
    }
    return actions;
  };

  const reset = () => {
    setSearch('');
    setRole(ALL);
    setStatus(ALL);
  };

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Доступи"
        primaryAction={
          <Button variant="primary" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={() => setDialog({ kind: 'grant' })}>
            Надати доступ
          </Button>
        }
      />
      <div className="flex flex-wrap items-end gap-3">
        <Input
          ref={searchRef}
          onKeyDown={(event) => handleSearchEscape(event, search, () => setSearch(''))}
          aria-label="Пошук за ім’ям, e-mail або логіном"
          placeholder="Ім’я, e-mail або логін"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          // з іконкою, як пошук довідників: тоді й обгортка має ширину, і фільтри стають у той самий рядок
          iconStart={<Search aria-hidden="true" className="size-4" />}
          wrapperClassName="w-full sm:w-72"
        />
        <Select<RoleFilter> aria-label="Роль" value={role} onValueChange={setRole} options={ROLE_FILTERS} className="w-44" />
        <Select<StatusFilter> aria-label="Стан" value={status} onValueChange={setStatus} options={STATUS_FILTERS} className="w-40" />
      </div>
      <Panel flush>
        <DataTable
          caption="Хто може увійти"
          columns={columns}
          rows={list.data}
          getRowId={(a) => a.id}
          loading={list.isPending}
          error={list.error}
          onRetry={() => void list.refetch()}
          onRowClick={(a) => setDialog({ kind: 'edit', access: a })}
          rowClassName={(a) => (a.status === 'ACTIVE' ? undefined : 'text-muted')}
          rowActions={(a) => <RowActions rowLabel={a.displayName} actions={actionsFor(a)} />}
          empty={
            filtered ? (
              <EmptyState
                size="sm"
                title="За цим фільтром нікого немає."
                action={
                  <Button variant="ghost" size="sm" onClick={reset}>
                    Показати всіх
                  </Button>
                }
              />
            ) : (
              <EmptyState size="sm" title="Доступу ще ні в кого немає." />
            )
          }
        />
      </Panel>

      {dialog?.kind === 'grant' ? <GrantAccessDialog open onOpenChange={(open) => (open ? undefined : setDialog(null))} /> : null}
      {dialog?.kind === 'edit' ? (
        <EditAccessDialog key={dialog.access.id} access={dialog.access} open onOpenChange={(open) => (open ? undefined : setDialog(null))} />
      ) : null}
      {dialog?.kind === 'password' ? (
        <PasswordLinkDialog key={dialog.access.id} access={dialog.access} open onOpenChange={(open) => (open ? undefined : setDialog(null))} />
      ) : null}
    </div>
  );
}
