import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import type { ChatBindingDto } from '@/api/types';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { resetMockStores } from '@/mocks/store';
import { renderWithProviders } from '@/test/render';
import { BindingsTable } from './BindingsTable';

const GROUP_CHAT: ChatBindingDto = {
  id: 'b-group',
  type: 'GROUP_CHAT',
  chatId: '-•••7890',
  title: 'Чат 4КІ1',
  groupId: 'g-4ki1',
  groupCode: '4КІ1',
  teacherId: null,
  digestEnabled: false,
  createdAt: '2026-11-08T08:00:00Z',
};

const TEACHER: ChatBindingDto = {
  id: 'b-teacher',
  type: 'TEACHER',
  chatId: '•••1234',
  title: 'Непийвода М.В.',
  groupId: null,
  groupCode: null,
  teacherId: 't-1',
  digestEnabled: false,
  createdAt: '2026-11-08T08:00:00Z',
};

beforeEach(() => resetMockStores());

function renderTable() {
  renderWithProviders(<BindingsTable bindings={[GROUP_CHAT, TEACHER]} loading={false} error={null} onRetry={() => {}} />);
}

describe('BindingsTable: ранковий розклад', () => {
  it('перемикач лише в чаті групи, з тією самою назвою, що й у боті', () => {
    renderTable();
    expect(screen.getByRole('columnheader', { name: 'Ранковий розклад о 7:30' })).toBeInTheDocument();
    const toggle = screen.getByRole('switch', { name: 'Ранковий розклад о 7:30: Чат 4КІ1' });
    expect(toggle).not.toBeChecked();
    // викладачу ранковий розклад не надсилається — перемикача немає
    expect(screen.getAllByRole('switch')).toHaveLength(1);
  });

  it('вмикає ранковий розклад запитом PATCH', async () => {
    const sent: unknown[] = [];
    server.use(
      http.patch(apiPath('/notify/bindings/:id'), async ({ params, request }) => {
        const body = (await request.json()) as { digestEnabled: boolean };
        sent.push({ id: params.id, ...body });
        return HttpResponse.json({ ...GROUP_CHAT, digestEnabled: body.digestEnabled });
      }),
    );
    renderTable();
    await userEvent.click(screen.getByRole('switch', { name: 'Ранковий розклад о 7:30: Чат 4КІ1' }));
    await waitFor(() => expect(sent).toEqual([{ id: 'b-group', digestEnabled: true }]));
  });

  it('на вузькому екрані той самий перемикач є в меню рядка', async () => {
    const sent: unknown[] = [];
    server.use(
      http.patch(apiPath('/notify/bindings/:id'), async ({ params, request }) => {
        const body = (await request.json()) as { digestEnabled: boolean };
        sent.push({ id: params.id, ...body });
        return HttpResponse.json({ ...GROUP_CHAT, digestEnabled: body.digestEnabled });
      }),
    );
    renderTable();
    await userEvent.click(screen.getByRole('button', { name: 'Дії: Чат 4КІ1' }));
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Увімкнути ранковий розклад о 7:30' }));
    await waitFor(() => expect(sent).toEqual([{ id: 'b-group', digestEnabled: true }]));
  });
});
