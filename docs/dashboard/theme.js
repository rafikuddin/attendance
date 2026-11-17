/* Applies the saved Light / Dark choice before the page paints (the same choice as the main page). */
(function () {
  try {
    var t = localStorage.getItem('sb-theme');
    if (t === 'light' || t === 'dark') document.documentElement.setAttribute('data-theme', t);
  } catch (e) { /* storage can be blocked: the page then follows the phone's setting */ }
})();
