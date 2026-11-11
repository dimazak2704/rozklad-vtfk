// Сторінка входу: кнопка «Показати пароль» і фокус на першому полі. Без JavaScript форма працює так само.
(function () {
  var reveal = document.querySelector('.auth-reveal');
  var password = document.getElementById('password');
  if (reveal && password) {
    reveal.hidden = false;
    reveal.addEventListener('click', function () {
      var show = password.type === 'password';
      password.type = show ? 'text' : 'password';
      reveal.setAttribute('aria-pressed', show ? 'true' : 'false');
      reveal.setAttribute('aria-label', show ? 'Сховати пароль' : 'Показати пароль');
      password.focus();
    });
    // перед відправкою пароль знову прихований: браузер не запам'ятає його як звичайний текст
    if (password.form) {
      password.form.addEventListener('submit', function () {
        password.type = 'password';
      });
    }
  }

  // фокус на логіні, якщо форма видима (згорнута під «Увійти з паролем» — не чіпаємо)
  var username = document.getElementById('username');
  if (username && username.offsetParent !== null) {
    username.focus();
  }
})();
