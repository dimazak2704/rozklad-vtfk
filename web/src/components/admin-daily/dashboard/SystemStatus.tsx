import { ChevronRight, Cpu, Send, Wallet } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { useAiUsage } from '@/api/import';
import { useBotStatus } from '@/api/telegram';
import type { DashboardDto, GenerationJobStatus } from '@/api/types';
import { Skeleton, StatusDot, type StatusTone } from '@/components/ui';
import { usd } from '../format';

const GENERATION: Record<GenerationJobStatus, { text: string; tone: StatusTone }> = {
  QUEUED: { text: 'Задача в черзі', tone: 'change' },
  RUNNING: { text: 'Розвʼязує зараз', tone: 'live' },
  SOLVED: { text: 'Готово: результат — чернетка', tone: 'add' },
  FAILED: { text: 'Остання спроба не вдалася', tone: 'cancel' },
  CANCELLED: { text: 'Останню задачу скасовано', tone: 'neutral' },
};

function Row({ icon, title, children, href, label }: { icon: ReactNode; title: string; children: ReactNode; href: string; label: string }) {
  return (
    <li className="border-b border-separator last:border-b-0">
      <Link
        to={href}
        aria-label={label}
        className="grid grid-cols-[1.25rem_minmax(0,1fr)_auto] items-center gap-3 px-4 py-3 transition-[background-color] duration-100 ease-out hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus"
      >
        <span className="text-muted [&_svg]:size-4">{icon}</span>
        <span className="flex min-w-0 flex-col">
          <span className="text-caption text-muted">{title}</span>
          <span className="text-ui text-primary">{children}</span>
        </span>
        <ChevronRight aria-hidden="true" className="size-4 text-muted rtl:rotate-180" />
      </Link>
    </li>
  );
}

/**
 * Витрати ШІ сьогодні проти денного бюджету. Рахує їх importer (`GET /api/import/ai/usage`, як віджет
 * у налаштуваннях): `DashboardDto.aiSpendToday` core завжди null, і плитка завжди казала «Немає даних».
 */
function AiToday() {
  const usage = useAiUsage();
  if (usage.isPending) return <Skeleton className="h-4 w-24" />;
  if (usage.isError) return <StatusDot tone="cancel" label="Невідомо — сервіс імпорту не відповідає" showLabel />;
  const { enabled, today, dailyBudgetUsd, remainingUsd } = usage.data;
  if (!enabled) return <span className="text-secondary">Вимкнено: не налаштовано ключ API</span>;
  return (
    <span className="figures">
      {usd(today.costUsd)} з {usd(dailyBudgetUsd)}
      {remainingUsd <= 0 ? <span className="font-semibold text-danger"> · бюджет вичерпано</span> : null}
    </span>
  );
}

/** Довкола змін: генератор розкладу, витрати ШІ сьогодні, Telegram-бот — коротко, з переходом у розділ. */
export function SystemStatus({ dashboard }: { dashboard: DashboardDto | undefined }) {
  const bot = useBotStatus();
  const generation = dashboard?.generation ? GENERATION[dashboard.generation.status] : null;
  return (
    <ul className="flex flex-col">
      <Row icon={<Cpu aria-hidden="true" />} title="Генератор розкладу" href="/admin/generation" label="Генератор розкладу: відкрити">
        {dashboard ? (
          generation ? (
            <StatusDot tone={generation.tone} label={generation.text} showLabel pulse={dashboard.generation?.status === 'RUNNING'} />
          ) : (
            <span className="text-secondary">Активних задач немає</span>
          )
        ) : (
          <Skeleton className="h-4 w-32" />
        )}
      </Row>
      <Row icon={<Wallet aria-hidden="true" />} title="ШІ сьогодні" href="/admin/settings#ai" label="Витрати ШІ: налаштування">
        <AiToday />
      </Row>
      <Row icon={<Send aria-hidden="true" />} title="Telegram-бот" href="/admin/telegram" label="Telegram-бот: відкрити">
        {bot.isPending ? (
          <Skeleton className="h-4 w-28" />
        ) : bot.isError ? (
          <StatusDot tone="cancel" label="Стан невідомий — сервіс не відповідає" showLabel />
        ) : bot.data.connected ? (
          <StatusDot tone="live" label={`@${bot.data.username} на звʼязку`} showLabel pulse />
        ) : (
          <StatusDot tone="cancel" label={`@${bot.data.username} не підключений`} showLabel />
        )}
      </Row>
    </ul>
  );
}
