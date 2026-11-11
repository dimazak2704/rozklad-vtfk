/**
 * Повідомлення батьківській вкладці з прихованого iframe тихого оновлення: та сама форма, яку надсилає
 * `UserManager.signinSilentCallback()` бібліотеки oidc-client-ts (`{source: 'oidc-client', url, keepOpen}`,
 * лише на своє походження). Без самої бібліотеки: сторінка /login/silent.html вантажиться на кожне
 * оновлення, а спільний з основним бандлом шматок коду вибив би бібліотеку з заздалегідь кешованої оболонки PWA.
 * Тест silentNotify.test.ts порівнює повідомлення з тим, що надсилає бібліотека.
 */
export function notifyParent(win: Pick<Window, 'parent' | 'location'> = window): void {
  if (win.parent === (win as unknown)) return;
  win.parent.postMessage({ source: 'oidc-client', url: win.location.href, keepOpen: false }, win.location.origin);
}
