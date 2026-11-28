import { fireEvent, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { Link } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import type { PublicGroupDto } from '@/api/types';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { useFavoritesStore } from '@/stores/favorites';
import { renderWithProviders } from '@/test/render';
import { UserMenu } from '../shell/UserMenu';
import { Wordmark } from '../shell/Wordmark';
import { GroupHint } from './GroupHint';
import { ScheduleFinder } from './home/ScheduleFinder';

vi.mock('@/auth/useCurrentUser', () => ({
  useCurrentUser: () => ({
    status: 'authenticated',
    user: { name: 'Кренцін Максим Дмитрович', username: 'krentsin' },
    isAdmin: false,
    isTeacher: true,
    isDevBypass: false,
    signOut: () => undefined,
  }),
}));

const group = (code: string, specialty: PublicGroupDto['specialty']): PublicGroupDto => ({
  id: `g-${code}`,
  code,
  course: Number(code[0]),
  specialty,
  department: null,
  shift: 1,
});

describe('підказки до посилань на розклад', () => {
  it('«Нещодавно»: короткий код чи прізвище каже, чий розклад відкриється', async () => {
    useFavoritesStore.setState({
      favorites: [],
      recent: [
        { type: 'GROUP', id: 'g', label: '1ВЕ', url: '/g/1ВЕ' },
        { type: 'TEACHER', id: 't', label: 'Кренцін', url: '/t/t' },
        { type: 'ROOM', id: 'r', label: 'ауд. 16', url: '/r/r' },
      ],
    });
    renderWithProviders(<ScheduleFinder />);
    fireEvent.focus(screen.getByRole('link', { name: 'Кренцін' }));
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Розклад викладача');
    fireEvent.blur(screen.getByRole('link', { name: 'Кренцін' }));
    fireEvent.focus(screen.getByRole('link', { name: 'ауд. 16' }));
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Розклад аудиторії');
  });

  it('код групи: дія завжди, назва спеціальності — другим рядком, коли відома', async () => {
    server.use(
      http.get(apiPath('/public/groups'), () =>
        HttpResponse.json([group('4КІ1', { letters: 'КІ', name: 'Компʼютерна інженерія' }), group('2ЕА2', { letters: 'ЕА', name: 'ЕА' })]),
      ),
    );
    renderWithProviders(
      <>
        <GroupHint code="4КІ1" when="на цей день">
          <Link to="/g/4КІ1">4КІ1</Link>
        </GroupHint>
        <GroupHint code="2ЕА2">
          <Link to="/g/2ЕА2">2ЕА2</Link>
        </GroupHint>
      </>,
    );
    // довідник ще вантажиться — дія вже є
    fireEvent.focus(screen.getByRole('link', { name: '2ЕА2' }));
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/^Розклад групи$/);
    fireEvent.blur(screen.getByRole('link', { name: '2ЕА2' }));

    fireEvent.focus(screen.getByRole('link', { name: '4КІ1' }));
    const tip = await screen.findByRole('tooltip');
    expect(tip).toHaveTextContent('Розклад групи на цей день');
    await vi.waitFor(() => expect(screen.getByRole('tooltip')).toHaveTextContent('Компʼютерна інженерія · 4 курс'));
  });
});

describe('підказки до кнопок лише з іконкою', () => {
  it('аватар у шапці: підказка й назва кажуть, що це обліковий запис', async () => {
    renderWithProviders(<UserMenu />);
    const trigger = screen.getByRole('button', { name: 'Обліковий запис: Кренцін Максим Дмитрович' });
    fireEvent.focus(trigger);
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Обліковий запис: Кренцін Максим Дмитрович');
  });

  it('у бічній панелі імʼя видно, тож підказки до меню немає', () => {
    renderWithProviders(<UserMenu variant="block" />);
    fireEvent.focus(screen.getByRole('button', { name: /Обліковий запис$/ }));
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('знак у згорнутій панелі — лише логотип: підказка «На головну»', async () => {
    renderWithProviders(<Wordmark to="/admin" compact tooltipSide="right" />);
    const link = screen.getByRole('link', { name: 'Розклад ВТФК — на головну' });
    fireEvent.focus(link);
    expect(await screen.findByRole('tooltip')).toHaveTextContent('На головну');
  });
});
