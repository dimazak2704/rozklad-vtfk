import { useSearchParams } from 'react-router';
import { useEmailStatus } from '@/api/email';
import { EmailLog } from '@/components/notifications/EmailLog';
import { EmailPreferences } from '@/components/notifications/EmailPreferences';
import { EmailStatusLine } from '@/components/notifications/EmailStatusLine';
import { PageHeader, Panel, Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui';

type Section = 'mine' | 'log';

/**
 * Сповіщення на пошту для диспетчера: стан поштового сервера рядком під заголовком, мої листи
 * (диспетчерські й, якщо обліковий запис повʼязаний з викладачем, про мої заміни) і журнал усіх листів.
 */
export default function NotificationsPage() {
  const status = useEmailStatus();
  const [params, setParams] = useSearchParams();
  const section: Section = params.get('tab') === 'log' ? 'log' : 'mine';

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Сповіщення" meta={<EmailStatusLine status={status.data} loading={status.isPending} failed={status.isError} />} />

      <Tabs
        value={section}
        onValueChange={(value) =>
          setParams(
            (prev) => {
              const next = new URLSearchParams(prev);
              if (value === 'log') next.set('tab', 'log');
              else next.delete('tab');
              return next;
            },
            { replace: true },
          )
        }
      >
        <TabsList aria-label="Розділи сповіщень">
          <TabsTrigger value="mine">Мої листи</TabsTrigger>
          <TabsTrigger value="log">Журнал листів</TabsTrigger>
        </TabsList>
        <TabsContent value="mine">
          <Panel className="max-w-3xl">
            <EmailPreferences audiences={['ADMIN', 'TEACHER']} />
          </Panel>
        </TabsContent>
        <TabsContent value="log">
          <Panel>
            <EmailLog />
          </Panel>
        </TabsContent>
      </Tabs>
    </div>
  );
}
