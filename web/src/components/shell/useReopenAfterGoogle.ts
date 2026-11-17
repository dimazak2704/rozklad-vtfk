import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { accountKeys, PASSWORD_RETURN_PARAM, PASSWORD_RETURN_VALUE } from '@/api/account';
import { renewSession } from '@/auth/userManager';

/**
 * Після повернення з Google (`?password=set`) прибирає позначку з адреси й відкриває вікно ще раз. Меню буває
 * змонтоване двічі (шапка і бічна панель), тож позначку забирає лише перший екземпляр для цього переходу.
 *
 * Перед відкриттям вікна токени тихо оновлюються (`renew`): повторний вхід дав сесії входу новий id, і зі старим
 * ID token-ом «Вийти» не завершило б сесію на сервері. Потім стан пароля читається заново — уже з новим токеном
 * (людина могла обрати в Google інший обліковий запис). Без сервісу входу (dev, мок-API) оновлювати нічого.
 */
let claimedReturn: string | null = null;

export function useReopenAfterGoogle(enabled: boolean, onReturn: () => void, renew = true): void {
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  useEffect(() => {
    if (!enabled) return;
    const params = new URLSearchParams(location.search);
    if (params.get(PASSWORD_RETURN_PARAM) !== PASSWORD_RETURN_VALUE) {
      // позначку вже прибрано: наступне повернення (у тестах — з тим самим ключем `default`) знову чинне
      claimedReturn = null;
      return;
    }
    if (claimedReturn === location.key) return;
    claimedReturn = location.key;
    params.delete(PASSWORD_RETURN_PARAM);
    const search = params.toString();
    void navigate({ pathname: location.pathname, search: search ? `?${search}` : '', hash: location.hash }, { replace: true });
    if (!renew) {
      onReturn();
      return;
    }
    // без скасування при прибиранні ефекту: navigate вище сам перезапускає ефект, а вікно має відкритися
    void renewSession()
      .then(() => queryClient.invalidateQueries({ queryKey: accountKeys.password() }))
      .finally(onReturn);
  }, [enabled, location, navigate, onReturn, queryClient, renew]);
}
