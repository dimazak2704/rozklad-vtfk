// Тема сторінки входу до першого кадру — так само, як вбудований скрипт web/index.html:
// вибір користувача з SPA (localStorage 'rozklad.theme', те саме походження), інакше системна тема.
// Без JavaScript лишається світла тема (:root у theme.css).
(function () {
  var root = document.documentElement;
  var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  function preference() {
    try {
      var raw = localStorage.getItem('rozklad.theme');
      return raw ? JSON.parse(raw).state.preference : 'system';
    } catch (e) {
      return 'system';
    }
  }

  function apply() {
    var pref = preference();
    var dark = pref === 'dark' || (pref !== 'light' && media !== null && media.matches);
    var theme = dark ? 'dark' : 'light';
    root.setAttribute('data-theme', theme);
    root.style.colorScheme = theme;
  }

  apply();
  if (media !== null && media.addEventListener) {
    media.addEventListener('change', apply);
  }
})();
