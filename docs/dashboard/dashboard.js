/* SAM BOND Reports: the browser dashboard.
 *
 * Read-only. It signs in with the same Employee ID and password as the app, asks the server for reports and shows them.
 * - No library, no tracking, nothing loaded from another site (the page's Content-Security-Policy allows only this site and the SAM BOND server).
 * - The sign-in token lives in this browser tab only (sessionStorage) and is removed on Sign out. Report data is never stored.
 * - Everything from the server is put on the page as plain text (never as HTML), and nothing here writes inline style attributes.
 * - The server decides who may see what (own report, a manager's team, an admin's everybody); this page only asks.
 */
(function () {
  'use strict';

  var API = 'https://sambond.samuda-sales.workers.dev';       // the SAM BOND front door (it adds the secret key; nothing secret is on this page)
  var IDLE_MS = 15 * 60 * 1000;                               // signs out after 15 minutes without use
  var WEB_MS = 6 * 60 * 60 * 1000;                            // the server never keeps a web sign-in longer than this
  var STORE = 'sb-web-session';
  var ROWS_FIRST = 100;                                       // people shown before "Show everybody"
  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  var WEEK = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  var XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
  var SVGNS = 'http://www.w3.org/2000/svg';
  var reduce = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

  var S = { token: null, name: '', flags: { isManager: false, isAdmin: false, locks: [] }, scope: 'me', period: 'current', data: null, daily: null,
            sort: { key: 'name', dir: 1 }, query: '', showAll: false, expireAt: 0, last: Date.now(), req: 0, sel: -1, chartW: 0 };

  /* ---------- Small helpers ---------- */
  function h(tag, attrs, kids) {
    var el = document.createElement(tag);
    if (attrs) Object.keys(attrs).forEach(function (k) {
      var v = attrs[k];
      if (v === null || v === undefined || v === false) return;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k.slice(0, 2) === 'on') el.addEventListener(k.slice(2), v);
      else el.setAttribute(k, v === true ? '' : String(v));
    });
    (kids || []).forEach(function (c) {
      if (c === null || c === undefined || c === false) return;
      el.appendChild(typeof c === 'object' ? c : document.createTextNode(String(c)));
    });
    return el;
  }
  function s(tag, attrs, kids) {                               // the same for SVG
    var el = document.createElementNS(SVGNS, tag);
    if (attrs) Object.keys(attrs).forEach(function (k) {
      var v = attrs[k];
      if (v === null || v === undefined || v === false) return;
      if (k === 'text') el.textContent = v; else el.setAttribute(k, String(v));
    });
    (kids || []).forEach(function (c) { if (c) el.appendChild(c); });
    return el;
  }
  var ICON = {
    me: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM5 20a7 7 0 0 1 14 0',
    team: 'M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM2.5 20a6.5 6.5 0 0 1 13 0M16 4.3a3.5 3.5 0 0 1 0 6.4M18 14a6 6 0 0 1 3.5 6',
    all: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z',
    down: 'M12 4v11M7 11l5 5 5-5M5 20h14',
    moon: 'M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5Z'
  };
  function icon(d) { return s('svg', { viewBox: '0 0 24 24', 'aria-hidden': 'true', focusable: 'false' }, [s('path', { d: d })]); }
  function iconSun() {
    return s('svg', { viewBox: '0 0 24 24', 'aria-hidden': 'true', focusable: 'false' }, [s('circle', { cx: 12, cy: 12, r: 4.2 }),
      s('path', { d: 'M12 2.5v2.3M12 19.2v2.3M2.5 12h2.3M19.2 12h2.3M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M18.7 5.3l-1.6 1.6M6.9 17.1l-1.6 1.6' })]);
  }
  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); }
  function show(el, on) { el.hidden = !on; }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function parts(iso) { return { y: Number(iso.slice(0, 4)), m: Number(iso.slice(5, 7)), d: Number(iso.slice(8, 10)) }; }
  function dow(iso) { var p = parts(iso); return new Date(Date.UTC(p.y, p.m - 1, p.d, 12)).getUTCDay(); }
  function short(iso) { var p = parts(iso); return p.d + ' ' + MONTHS[p.m - 1]; }
  function long(iso) { return short(iso) + ' ' + parts(iso).y; }
  function withDay(iso) { return WEEK[dow(iso)] + ' ' + short(iso); }
  function plural(n, one, many) { return n + ' ' + (n === 1 ? one : many); }
  function num(n) { return Number(n) || 0; }
  function initials(name) {
    var w = String(name || '').trim().split(/\s+/).filter(Boolean);
    if (!w.length) return '?';
    return (w.length > 1 ? w[0].charAt(0) + w[1].charAt(0) : w[0].slice(0, 2)).toUpperCase();
  }

  /* ---------- Motion (all of it is skipped when the phone asks for less motion) ---------- */
  var EASE = { out3: function (t) { return 1 - Math.pow(1 - t, 3); }, expo: function (t) { return t >= 1 ? 1 : 1 - Math.pow(2, -10 * t); } };
  function tween(o) {
    var f = EASE[o.ease || 'out3'], dur = (o.dur || .6) * 1000, delay = (o.delay || 0) * 1000;
    if (reduce) { if (o.on) o.on(1, 1); return; }
    var t0 = performance.now() + delay;
    (function step(now) {
      if (now < t0) { requestAnimationFrame(step); return; }
      var k = Math.min(1, (now - t0) / dur);
      if (o.on) o.on(f(k), k);
      if (k < 1) requestAnimationFrame(step);
    })(performance.now());
  }
  function countUp(node, delay) {
    var fin = Number(node.getAttribute('data-n')), sfx = node.getAttribute('data-sfx') || '';
    if (!isFinite(fin) || reduce) return;
    node.textContent = '0' + sfx;
    tween({ dur: 1.1, delay: delay, ease: 'expo', on: function (p, k) { node.textContent = (k >= 1 ? String(fin) : String(Math.round(fin * p))) + sfx; } });
  }
  function fitVals() {                                         // a long number must not run under the ring beside it: shrink it until it fits
    $$('.tile .val', stage).forEach(function (v) {
      v.style.fontSize = '';
      var size = parseFloat(getComputedStyle(v).fontSize), min = Math.max(16, size * .55);
      while (v.scrollWidth > v.clientWidth + 1 && size > min) { size -= 1; v.style.fontSize = size + 'px'; }
    });
  }

  /* ---------- Talking to the server ---------- */
  function api(body, timeoutMs) {
    var ctl = typeof AbortController === 'function' ? new AbortController() : null;
    var timer = ctl ? setTimeout(function () { ctl.abort(); }, timeoutMs || 60000) : null;
    var payload = {};
    Object.keys(body).forEach(function (k) { payload[k] = body[k]; });
    if (S.token && body.action !== 'login') payload.token = S.token;
    return fetch(API, {
      method: 'POST', headers: { 'Content-Type': 'text/plain;charset=utf-8' }, body: JSON.stringify(payload),
      credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer', signal: ctl ? ctl.signal : undefined
    }).then(function (res) {
      if (timer) clearTimeout(timer);
      if (res.status === 429) return { ok: false, error: 'Too many requests in a short time. Please wait a minute and try again.' };
      return res.text().then(function (t) {
        try { var j = JSON.parse(t); if (j && typeof j === 'object') return j; } catch (e) { /* not JSON: handled below */ }
        return { ok: false, error: 'The server sent an answer this page could not read. Please try again in a minute.' };
      });
    }).catch(function (e) {
      if (timer) clearTimeout(timer);
      if (e && e.name === 'AbortError') return { ok: false, error: 'The server took too long. Please try again.' };
      // The browser gives the same failure for "no internet" and for "the server answered but does not let this website read the answer".
      // A second, harmless request that the page does not need to read tells them apart.
      return probe().then(function (reachable) {
        return { ok: false, error: reachable
          ? 'The server answered, but it does not let this website read the answer yet. Your admin must put the newest worker.js into the Cloudflare Worker (see the Website Dashboard page in the wiki). The app is not affected.'
          : 'Could not reach the server. Check your internet connection and try again.' };
      });
    });
  }

  // True when the server can be reached at all (the answer itself is hidden from the page by the browser, which is fine here).
  function probe() {
    return fetch(API, { method: 'POST', mode: 'no-cors', headers: { 'Content-Type': 'text/plain;charset=utf-8' }, body: '{"action":"webLogout"}', credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer' })
      .then(function () { return true; }, function () { return false; });
  }

  /* ---------- Session (this tab only) ---------- */
  function tokenExpiry(tok) {
    try {
      var p = String(tok).split('.')[0].replace(/-/g, '+').replace(/_/g, '/');
      while (p.length % 4) p += '=';
      return Number(JSON.parse(atob(p)).x) || 0;
    } catch (e) { return 0; }
  }
  function saveSession() {
    try { sessionStorage.setItem(STORE, JSON.stringify({ t: S.token, n: S.name, m: S.flags.isManager, a: S.flags.isAdmin, l: S.flags.locks })); } catch (e) { /* blocked: the sign-in then lasts until reload */ }
  }
  function loadSession() {
    try { var o = JSON.parse(sessionStorage.getItem(STORE) || 'null'); return o && o.t ? o : null; } catch (e) { return null; }
  }
  function dropSession() { try { sessionStorage.removeItem(STORE); } catch (e) { /* nothing to do */ } }

  /* ---------- Page parts ---------- */
  var el = {};
  ['login', 'lgc', 'msc', 'mMouth', 'loginForm', 'loginMsg', 'loginGo', 'user', 'pass', 'showPass', 'app', 'side', 'scrim', 'mnu', 'navx', 'menu', 'pill', 'who', 'whoRole', 'av', 'out', 'out2', 'mode', 'mode2',
   'title', 'sub', 'until', 'paceT', 'paceBar', 'controls', 'scopes', 'period', 'dates', 'from', 'to', 'show', 'refresh', 'msg', 'toast',
   'report', 'range', 'todayCard', 'todayDate', 'todayRow', 'notIn', 'notInSum', 'notInList', 'kpis', 'kpis2', 'chartCard', 'legend', 'chart', 'readout', 'dayTable',
   'peopleCard', 'search', 'people', 'peopleNote', 'more', 'meCard', 'meTitle', 'meBody', 'dlCard', 'dl', 'dlg', 'dlgAv', 'dlgTitle', 'dlgSub', 'dlgBody', 'dlgClose'
  ].forEach(function (id) { el[id] = document.getElementById(id); });
  var stage = $('.stage');

  function showLogin(message, isError) {
    S.token = null; S.data = null; S.daily = null;
    document.body.classList.add('locked');
    show(el.app, false); show(el.login, true);
    document.title = 'Sign in · SAM BOND Reports';
    if (message) { el.loginMsg.textContent = message; el.loginMsg.className = 'lgm' + (isError ? '' : ' info'); show(el.loginMsg, true); } else show(el.loginMsg, false);
    el.pass.value = ''; el.pass.type = 'password'; syncEye(); mRest();
    setTimeout(function () { try { (el.user.value ? el.pass : el.user).focus(); } catch (e) { /* not focusable yet */ } }, 0);
  }

  function signOut(message, serverKnows) {
    var tok = S.token;
    if (tok && !serverKnows) {
      try {
        fetch(API, { method: 'POST', headers: { 'Content-Type': 'text/plain;charset=utf-8' }, body: JSON.stringify({ action: 'webLogout', token: tok }), credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer', keepalive: true }).catch(function () { /* the sign-in ends by itself after 6 hours anyway */ });
      } catch (e) { /* same */ }
    }
    dropSession();
    S.req++;                                                  // an answer that is still on its way is thrown away
    try { if (el.dlg.open) el.dlg.close(); } catch (e) { /* not open */ }
    closeNav(true);
    [el.kpis, el.kpis2, el.chart, el.legend, el.dayTable, el.people, el.meBody, el.dl, el.todayRow, el.notInList, el.dlgBody, el.scopes].forEach(function (n) { if (n) clear(n); });
    el.user.value = '';
    S.name = ''; S.flags = { isManager: false, isAdmin: false, locks: [] };
    showLogin(message || 'You are signed out.', false);
  }

  function showMsg(text, isError) {
    if (!text) { show(el.msg, false); return; }
    el.msg.textContent = text; el.msg.className = 'banner ' + (isError ? 'warn' : 'note'); show(el.msg, true);
  }
  var toastTimer = 0;
  function toast(text) {
    el.toast.textContent = text; el.toast.classList.add('on');
    clearTimeout(toastTimer); toastTimer = setTimeout(function () { el.toast.classList.remove('on'); }, 4500);
  }

  /* ---------- Sign in (with the little drop that looks away while you type a password) ---------- */
  var MOUTH = { smile: 'M88 152Q100 163 112 152', flat: 'M91 154Q100 157 109 154', sad: 'M88 158Q100 148 112 158' };
  function mLook(x, y) { el.msc.style.setProperty('--fx', x.toFixed(1)); el.msc.style.setProperty('--fy', y.toFixed(1)); }
  function mMouth(k) { el.mMouth.setAttribute('d', MOUTH[k]); }
  var mctx = null;
  function caretFrac(inp) {
    try {
      if (!mctx) mctx = document.createElement('canvas').getContext('2d');
      var cs = getComputedStyle(inp); mctx.font = cs.fontWeight + ' ' + cs.fontSize + ' ' + cs.fontFamily;
      var pos = inp.selectionStart == null ? inp.value.length : inp.selectionStart;
      var w = mctx.measureText(inp.value.slice(0, pos)).width, room = inp.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
      return Math.max(0, Math.min(1, w / Math.max(1, room)));
    } catch (e) { return .5; }
  }
  function mFollow() { if (document.activeElement === el.user) { mLook((caretFrac(el.user) - .5) * 18, 8); mMouth(el.user.value ? 'smile' : 'flat'); } }
  function mPass() { var peek = el.pass.type === 'text'; el.msc.classList.add('cover'); el.msc.classList.toggle('peek', peek); mLook(0, peek ? 7 : 2); mMouth('flat'); }
  function mRest() { el.msc.classList.remove('cover', 'peek'); mLook(0, 0); mMouth('smile'); }
  ['focus', 'input', 'keyup', 'click'].forEach(function (ev) { el.user.addEventListener(ev, function () { el.msc.classList.remove('cover', 'peek'); mFollow(); }); });
  el.user.addEventListener('blur', function () { if (document.activeElement !== el.pass) mRest(); });
  el.pass.addEventListener('focus', mPass);
  el.pass.addEventListener('blur', function () { setTimeout(function () { if (document.activeElement !== el.pass && document.activeElement !== el.showPass) mRest(); }, 0); });
  document.addEventListener('pointermove', function (e) {     // nothing focused: the eyes follow the pointer
    if (el.login.hidden || el.msc.classList.contains('cover') || document.activeElement === el.user) return;
    var r = el.msc.getBoundingClientRect(), dx = e.clientX - (r.left + r.width / 2), dy = e.clientY - (r.top + r.height / 2), d = Math.sqrt(dx * dx + dy * dy) || 1, k = Math.min(1, d / 220);
    mLook(dx / d * 8 * k, dy / d * 7 * k);
  }, { passive: true });

  function syncEye() {
    var on = el.pass.type === 'text';
    el.showPass.setAttribute('aria-pressed', on ? 'true' : 'false');
    el.showPass.setAttribute('aria-label', on ? 'Hide password' : 'Show password');
  }
  el.showPass.addEventListener('click', function () {
    el.pass.type = el.pass.type === 'password' ? 'text' : 'password';
    syncEye(); el.pass.focus(); mPass();
  });

  function loginBusy(on) {
    el.loginGo.disabled = on; clear(el.loginGo);
    if (on) { el.loginGo.appendChild(h('span', { class: 'sp', 'aria-hidden': 'true' })); el.loginGo.appendChild(h('span', { text: 'Signing in…' })); } else el.loginGo.appendChild(h('span', { text: 'Sign in' }));
  }
  function loginFail(text) {
    el.loginMsg.textContent = text; el.loginMsg.className = 'lgm'; show(el.loginMsg, true);
    mMouth('sad');
    el.lgc.classList.remove('shake'); void el.lgc.offsetWidth; el.lgc.classList.add('shake');
  }

  el.loginForm.addEventListener('submit', function (ev) {
    ev.preventDefault();
    var user = el.user.value.trim(), pass = el.pass.value;
    if (!user || !pass) { loginFail('Enter your Employee ID and your password.'); (user ? el.pass : el.user).focus(); return; }
    loginBusy(true); show(el.loginMsg, false);
    api({ action: 'login', username: user, password: pass, client: 'web', remember: false }, 30000).then(function (r) {
      loginBusy(false);
      el.pass.value = '';
      if (!r || !r.ok || !r.token) { loginFail((r && r.error) || 'Could not sign in. Please try again.'); el.pass.focus(); return; }
      S.token = r.token; S.name = String(r.name || user);
      S.flags = { isManager: !!r.isManager, isAdmin: !!r.isAdmin, locks: Array.isArray(r.locks) ? r.locks : [] };
      saveSession(); enterApp();
    });
  });

  /* ---------- Sign out, idle time, session end ---------- */
  function doOut() { signOut('You are signed out. See you again.', false); }
  el.out.addEventListener('click', doOut); el.out2.addEventListener('click', doOut);
  function touch() { S.last = Date.now(); }
  ['pointerdown', 'keydown', 'touchstart', 'input', 'wheel'].forEach(function (n) { document.addEventListener(n, touch, { passive: true, capture: true }); });
  window.addEventListener('scroll', touch, { passive: true, capture: true });
  function hm(ms) { var m = Math.max(0, Math.floor(ms / 60000)); return Math.floor(m / 60) + ' h ' + pad(m % 60) + ' m'; }
  function paintPace() {
    if (!S.token) return;
    if (!S.expireAt) { el.paceT.textContent = '–'; return; }
    var left = Math.max(0, S.expireAt - Date.now());
    el.paceT.textContent = hm(left) + ' left';
    el.paceBar.style.transform = 'scaleX(' + Math.max(0, Math.min(1, left / WEB_MS)).toFixed(3) + ')';
  }
  function watch() {
    if (!S.token) return;
    var now = Date.now();
    if (S.expireAt && now >= S.expireAt) signOut('Your 6 hours are over. Please sign in again.', true);
    else if (now - S.last >= IDLE_MS) signOut('You were signed out after 15 minutes without use.', false);
    else paintPace();
  }
  setInterval(watch, 15000);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) watch(); });

  function sessionEnded() { signOut('Your sign-in has ended. Please sign in again.', true); }

  /* ---------- The side menu (a capsule on a computer, a drawer on a phone) ---------- */
  function openNav() {
    el.side.classList.add('open'); el.scrim.classList.add('on'); el.mnu.setAttribute('aria-expanded', 'true');
    var first = $('.mi', el.side) || el.navx; try { first.focus(); } catch (e) { /* hidden */ }
  }
  function closeNav(quiet) {
    if (!el.side.classList.contains('open')) return;
    el.side.classList.remove('open'); el.scrim.classList.remove('on'); el.mnu.setAttribute('aria-expanded', 'false');
    if (!quiet) { try { el.mnu.focus(); } catch (e) { /* hidden */ } }
  }
  el.mnu.addEventListener('click', openNav); el.navx.addEventListener('click', function () { closeNav(); }); el.scrim.addEventListener('click', function () { closeNav(); });
  document.addEventListener('keydown', function (ev) { if (ev.key === 'Escape') closeNav(); });

  var st = { t: 0, r: 0, b: 0, l: 0 }, tok = {};              // the highlighted pill: leading edge first, trailing edge catches up
  function paintPill() { el.pill.style.clipPath = 'inset(' + st.t + 'px ' + st.r + 'px ' + st.b + 'px ' + st.l + 'px round 15px)'; }
  function edges(btn) {
    var W = el.menu.scrollWidth, H = el.menu.scrollHeight;
    el.pill.style.right = 'auto'; el.pill.style.bottom = 'auto'; el.pill.style.width = W + 'px'; el.pill.style.height = H + 'px';
    return { l: btn.offsetLeft, t: btn.offsetTop, r: W - (btn.offsetLeft + btn.offsetWidth), b: H - (btn.offsetTop + btn.offsetHeight) };
  }
  function glide(k, to, dur) {
    var a = st[k], my = tok[k] = (tok[k] || 0) + 1;
    tween({ dur: dur, ease: 'out3', on: function (p) { if (tok[k] !== my) return; st[k] = a + (to - a) * p; paintPill(); } });
  }
  function slidePill(btn, animate) {
    var to = edges(btn), k;
    if (!animate || reduce) { for (k in to) { st[k] = to[k]; tok[k] = (tok[k] || 0) + 1; } paintPill(); return; }
    [['t', 'b'], ['l', 'r']].forEach(function (ax) {
      var a = ax[0], b = ax[1];
      if (to[a] === st[a] && to[b] === st[b]) return;
      var fwd = a === 't' ? to.t > st.t : to.l > st.l, lead = fwd ? b : a, trail = fwd ? a : b;
      glide(lead, to[lead], .36); glide(trail, to[trail], .55);
    });
  }
  function activeScope() { return $('.mi[aria-pressed="true"]', el.scopes); }
  function resetPill() { var b = activeScope(); if (b) slidePill(b, false); }
  window.addEventListener('resize', function () { resetPill(); if (window.innerWidth > 900) closeNav(true); });
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(resetPill);

  /* ---------- The dashboard ---------- */
  function scopeLabel(sc) { return sc === 'all' ? 'Everyone' : (sc === 'team' ? 'My team' : 'My report'); }

  function enterApp() {
    S.expireAt = tokenExpiry(S.token); S.last = Date.now();
    document.body.classList.remove('locked'); show(el.login, false); show(el.app, true);
    el.who.textContent = S.name; el.av.textContent = initials(S.name);
    el.whoRole.textContent = S.flags.isAdmin ? 'Admin' : (S.flags.isManager ? 'Manager' : 'Employee');
    var locked = S.flags.locks.indexOf('reports') >= 0;
    S.scope = S.flags.isAdmin ? 'all' : (S.flags.isManager ? 'team' : 'me');
    S.period = 'current'; S.query = ''; S.showAll = false; S.sel = -1;
    el.period.value = 'current'; show(el.dates, false); el.search.value = '';
    var end = S.expireAt ? new Date(S.expireAt) : null;
    el.until.textContent = end ? 'Signed in until ' + pad(end.getHours()) + ':' + pad(end.getMinutes()) + ' at the latest. You are signed out after 15 minutes without use.' : 'You are signed out after 15 minutes without use.';
    paintPace();
    buildScopes();
    if (locked) {
      show(el.controls, false); show(el.report, false);
      el.title.textContent = 'Reports'; el.sub.textContent = '';
      showMsg('Reports are switched off for your account. Please ask your admin.', true);
      return;
    }
    show(el.controls, true); showMsg('');
    load();
  }

  function buildScopes() {
    var list = [['me', 'My report', ICON.me]];
    if (S.flags.isManager) list.push(['team', 'My team', ICON.team]);
    if (S.flags.isAdmin) list.push(['all', 'Everyone', ICON.all]);
    clear(el.scopes);
    list.forEach(function (it) {
      var b = h('button', { type: 'button', class: 'mi', 'data-scope': it[0], 'aria-pressed': it[0] === S.scope ? 'true' : 'false' }, [icon(it[2]), h('span', { text: it[1] })]);
      b.addEventListener('click', function () {
        closeNav(true);
        if (S.scope === it[0]) return;
        S.scope = it[0]; S.query = ''; S.showAll = false; S.sel = -1; el.search.value = '';
        $$('.mi', el.scopes).forEach(function (x) { x.setAttribute('aria-pressed', x === b ? 'true' : 'false'); });
        slidePill(b, true); load();
      });
      el.scopes.appendChild(b);
    });
    requestAnimationFrame(resetPill);
  }

  el.period.addEventListener('change', function () {
    S.period = el.period.value;
    show(el.dates, S.period === 'custom');
    if (S.period !== 'custom') load();
    else { if (S.data) { el.from.max = S.data.today; el.to.max = S.data.today; if (!el.from.value) el.from.value = S.data.from; if (!el.to.value) el.to.value = S.data.to; } el.from.focus(); }
  });
  el.controls.addEventListener('submit', function (ev) { ev.preventDefault(); load(); });
  el.refresh.addEventListener('click', function () { load(); });

  function load() {
    var id = ++S.req;
    var body = { action: 'webReport', who: S.scope, period: S.period };
    if (S.period === 'custom') {
      if (!el.from.value || !el.to.value) { showMsg('Choose a start date and an end date.', true); return; }
      body = { action: 'webReport', who: S.scope, from: el.from.value, to: el.to.value };
    }
    el.show.disabled = true; el.refresh.disabled = true; show(el.report, false); showMsg('Loading the report… a big team can take up to a minute.', false);
    api(body, 90000).then(function (r) {
      if (id !== S.req) return;                                // an older answer: ignore
      el.show.disabled = false; el.refresh.disabled = false;
      if (r && r.auth) { sessionEnded(); return; }
      if (!r || !r.ok) { showMsg((r && r.error) || 'The report could not be loaded.', true); return; }
      S.data = r; showMsg('');
      render();
    });
  }

  /* ---------- Day codes ---------- */
  function kindOf(c) {
    if (c === 'P') return 'in'; if (c === 'L') return 'late'; if (c === 'A') return 'abs'; if (c === 'O') return 'off'; if (c === 'N') return 'pend'; if (c === '-') return 'none';
    return 'leave';                                            // 1 to 4 (the leave type) or V
  }
  function leaveName(c, d) { var i = Number(c); return i >= 1 && d.leaveTypes && d.leaveTypes[i - 1] ? d.leaveTypes[i - 1] : 'Leave'; }
  function leaveShort(c, d) {
    var n = leaveName(c, d); if (n === 'Leave') return 'Leave';
    var w = n.split(/\s+/), sh = w.length > 1 ? w[0].charAt(0) + w[1].charAt(0) : n.slice(0, 2);
    return sh.toUpperCase();
  }
  function holName(d, date) { var hol = d.holidays && d.holidays[date]; return hol ? (typeof hol === 'string' ? hol : (hol.name || 'holiday')) : ''; }

  function dailyCounts(d) {
    var out = d.dates.map(function () { return { in: 0, late: 0, leave: 0, abs: 0, off: 0, pend: 0, none: 0 }; });
    d.people.forEach(function (p) { for (var i = 0; i < out.length; i++) out[i][kindOf(p.days.charAt(i))]++; });
    return out;
  }

  /* ---------- Render ---------- */
  function render() {
    var d = S.data, many = d.people.length > 1;
    el.title.textContent = scopeLabel(S.scope);
    el.sub.textContent = d.company || '';
    document.title = scopeLabel(S.scope) + ' · SAM BOND Reports';
    el.range.textContent = long(d.from) + ' to ' + long(d.to) + ' · ' + plural(d.days, 'day', 'days') + (many ? ' · ' + plural(d.people.length, 'person', 'people') + '. Totals count person-days: one person on one day is one day.' : '');
    el.from.max = d.today; el.to.max = d.today;
    var daily = S.daily = dailyCounts(d);
    S.sel = -1;
    renderToday(d, daily, many);
    renderKpis(d, daily, many);
    show(el.chartCard, many); if (many) renderChart(d, daily, true);
    show(el.peopleCard, many); if (many) renderPeople();
    show(el.meCard, !many);
    if (!many) {
      el.meTitle.textContent = d.people.length ? (d.people[0].name || 'Your days') : 'Your days';
      clear(el.meBody); if (d.people.length) el.meBody.appendChild(personView(d.people[0], d, false)); else el.meBody.appendChild(h('p', { class: 'muted', text: 'There is nothing to show for this period.' }));
    }
    renderDownloads();
    show(el.report, true);
    enter();
  }

  function renderToday(d, daily, many) {
    var i = d.dates.indexOf(d.today);
    var ok = many && i >= 0 && d.to === d.today;
    show(el.todayCard, ok);
    if (!ok) return;
    var c = daily[i];
    el.todayDate.textContent = withDay(d.today);
    clear(el.todayRow);
    [['In', c.in + c.late], ['Late', c.late], ['On leave', c.leave], ['Not in yet', c.pend], ['Absent', c.abs], ['Day off', c.off]].forEach(function (x) {
      if ((x[0] === 'Not in yet' || x[0] === 'Day off') && !x[1]) return;
      el.todayRow.appendChild(h('div', null, [h('b', { text: String(x[1]) }), x[0]]));
    });
    var names = d.people.filter(function (p) { var k = p.days.charAt(i); return k === 'A' || k === 'N'; });
    show(el.notIn, names.length > 0);
    if (names.length) {
      el.notInSum.textContent = 'Not in: ' + plural(names.length, 'person', 'people') + ' (' + withDay(d.today) + ')';
      clear(el.notInList);
      names.slice(0, 60).forEach(function (p) { el.notInList.appendChild(h('li', { text: p.name + (p.days.charAt(i) === 'N' ? ' (not in yet)' : '') })); });
      if (names.length > 60) el.notInList.appendChild(h('li', { text: '… and ' + (names.length - 60) + ' more. Open the People table below.' }));
    }
  }

  /* ---------- Number tiles with a ring (outer: the share, inner: the on-time part of Present) ---------- */
  function ring(share, inner, label) {
    var pct = Math.round(share * 100), g = [];
    g.push(s('circle', { class: 'tr', cx: 40, cy: 40, r: 34, 'stroke-width': 6 }));
    g.push(s('circle', { class: 'ra', cx: 40, cy: 40, r: 34, 'stroke-width': 6, pathLength: 100, 'stroke-dasharray': (share * 100).toFixed(2) + ' 100', 'data-to': (share * 100).toFixed(2) }));
    if (inner !== null && inner !== undefined) {
      g.push(s('circle', { class: 'tr', cx: 40, cy: 40, r: 26, 'stroke-width': 3 }));
      g.push(s('circle', { class: 'ra in2', cx: 40, cy: 40, r: 26, 'stroke-width': 3, pathLength: 100, 'stroke-dasharray': (inner * 100).toFixed(2) + ' 100', 'data-to': (inner * 100).toFixed(2) }));
    }
    return h('div', { class: 'ringw' }, [
      s('svg', { viewBox: '0 0 80 80', 'aria-hidden': 'true', focusable: 'false' }, [s('g', { transform: 'rotate(-90 40 40)' }, g)]),
      h('div', { class: 'rc' }, [h('span', { class: 'n', 'data-n': pct, 'data-sfx': '%', text: pct + '%' }), h('span', { class: 'sr', text: ' ' + label })])
    ]);
  }

  function tile(o) {
    var main = h('div', { class: 'tmain' }, [
      h('div', { class: 'val' }, [h('span', { class: 'n', 'data-n': o.value, text: String(o.value) }), o.unit ? h('small', { text: o.unit }) : null]),
      o.sub ? h('div', { class: 'tsub', text: o.sub }) : null,
      o.chip ? h('span', { class: 'chip ' + o.chip[0], text: o.chip[1] }) : null
    ]);
    var row = h('div', { class: 'trow' }, [main, o.share === null || o.share === undefined ? null : ring(o.share, o.inner, o.shareLabel)]);
    return h('article', { class: 'glass card lift tile kpi k-' + o.kind + (o.hero ? ' hero' : ''), 'data-rise': true }, [
      h('h3', null, [h('i', { 'aria-hidden': 'true' }), h('span', { text: o.label })]), row, o.spark || null]);
  }
  function ratio(a, b) { return b > 0 ? Math.max(0, Math.min(1, a / b)) : null; }

  function renderKpis(d, daily, many) {
    var t = d.totals, unit = many ? 'person-days' : 'days';
    var present = num(t.present), late = num(t.late), leave = num(t.leave), absent = num(t.absent), off = num(t.off), pending = num(t.pending);
    var base = present + leave + absent + pending;
    clear(el.kpis); clear(el.kpis2);
    el.kpis.appendChild(tile({ kind: 'in', label: 'Present', value: present, unit: 'days', sub: many ? 'person-days' : '', share: ratio(present, base), inner: present > 0 ? Math.max(0, present - late) / present : 0,
      shareLabel: 'of counted days (outer ring), on time (inner ring)', hero: true, chip: present > 0 ? ['up', Math.max(0, present - late) + ' on time'] : null, spark: many ? spark(d, daily) : null }));
    el.kpis.appendChild(tile({ kind: 'late', label: 'Late', value: late, sub: 'inside Present', share: ratio(late, present), shareLabel: 'of present days' }));
    el.kpis.appendChild(tile({ kind: 'leave', label: 'Leave', value: leave, sub: unit, share: ratio(leave, base), shareLabel: 'of counted days' }));
    el.kpis.appendChild(tile({ kind: 'abs', label: 'Absent', value: absent, sub: unit, share: ratio(absent, base), shareLabel: 'of counted days' }));
    el.kpis.appendChild(tile({ kind: 'off', label: 'Day off', value: off, sub: 'Fridays and holidays', share: ratio(off, base + off), shareLabel: 'of all days' }));
    el.kpis2.appendChild(tile({ kind: 'gen', label: 'Night holds', value: num(t.nights) }));
    if (num(t.km) > 0) el.kpis2.appendChild(tile({ kind: 'gen', label: 'Driver km', value: Math.round(t.km), unit: 'km' }));
    if (pending > 0) el.kpis2.appendChild(tile({ kind: 'gen', label: 'Pending', value: pending, sub: 'today is not over' }));
  }

  /* a small line of present people for each counted day: point or tap to read a day */
  function spark(d, daily) {
    var pts = [];
    daily.forEach(function (c, i) { if (c.in + c.late + c.leave + c.abs + c.pend > 0) pts.push([i, c.in + c.late]); });
    if (pts.length < 2) return null;
    var W = 200, H = 46, n = d.dates.length, mx = 0, mn = Infinity;
    pts.forEach(function (p) { mx = Math.max(mx, p[1]); mn = Math.min(mn, p[1]); });
    mn = mn * .9;
    var X = function (i) { return 3 + (W - 6) * i / Math.max(1, n - 1); }, Y = function (v) { return 4 + (H - 10) * (1 - (v - mn) / ((mx - mn) || 1)); };
    var line = pts.map(function (p, k) { return (k ? 'L' : 'M') + X(p[0]).toFixed(1) + ' ' + Y(p[1]).toFixed(1); }).join(' ');
    var area = line + ' L' + X(pts[pts.length - 1][0]).toFixed(1) + ' ' + H + ' L' + X(pts[0][0]).toFixed(1) + ' ' + H + ' Z';
    var dot = h('i', { class: 'spd' }), tip = h('div', { class: 'spt' });
    var wrap = h('div', { class: 'spw', 'aria-hidden': 'true' }, [dot, tip,
      s('svg', { class: 'spark', viewBox: '0 0 200 46', preserveAspectRatio: 'none', focusable: 'false' }, [
        s('defs', null, [s('linearGradient', { id: 'sg', x1: 0, y1: 0, x2: 0, y2: 1 }, [s('stop', { class: 'sg1', offset: 0 }), s('stop', { class: 'sg2', offset: 1 })])]),
        s('path', { class: 'a', d: area }), s('path', { class: 'l', 'data-line': '', d: line })])]);
    function at(e) {
      var r = wrap.getBoundingClientRect(), fx = (e.clientX - r.left) / r.width * W, best = pts[0];
      pts.forEach(function (p) { if (Math.abs(X(p[0]) - fx) < Math.abs(X(best[0]) - fx)) best = p; });
      dot.style.left = (X(best[0]) / W * 100) + '%'; dot.style.top = (Y(best[1]) / H * 100) + '%'; dot.classList.add('on');
      clear(tip); tip.appendChild(h('b', { text: withDay(d.dates[best[0]]) })); tip.appendChild(document.createTextNode(best[1] + ' present'));
      tip.classList.add('on');
      var tw = tip.offsetWidth, x = X(best[0]) / W * r.width - tw / 2;
      tip.style.left = Math.max(0, Math.min(r.width - tw, x)) + 'px';
    }
    function off() { dot.classList.remove('on'); tip.classList.remove('on'); }
    wrap.addEventListener('pointermove', at); wrap.addEventListener('pointerdown', at);
    wrap.addEventListener('pointerleave', function (e) { if (e.pointerType === 'mouse') off(); });
    document.addEventListener('pointerdown', function (e) { if (!wrap.contains(e.target)) off(); }, { passive: true });
    return wrap;
  }

  /* ---------- Day-by-day chart (stacked bars: on time, late, leave, absent) ---------- */
  function niceTop(max) {
    if (!(max > 0)) return { top: 1, step: 1 };
    var raw = max / 4, p = Math.pow(10, Math.floor(Math.log10(raw))), step = p;
    [1, 2, 2.5, 5, 10].some(function (m) { step = m * p; return step >= raw; });
    if (step < 1) step = 1;
    return { top: Math.ceil(max / step) * step, step: step };
  }

  function renderChart(d, daily, animate) {
    var items = [['in', 'On time'], ['late', 'Late'], ['leave', 'Leave'], ['abs', 'Absent']];
    clear(el.legend);
    items.forEach(function (it) { el.legend.appendChild(h('li', null, [h('i', { class: 'dot dot--' + it[0], 'aria-hidden': 'true' }), it[1]])); });
    el.legend.appendChild(h('li', null, [h('i', { class: 'dot dot--off', 'aria-hidden': 'true' }), 'Day off']));
    drawChart(d, daily, animate);

    clear(el.dayTable);
    el.dayTable.appendChild(h('thead', null, [h('tr', null, ['Day', 'On time', 'Late', 'Leave', 'Absent', 'Day off'].map(function (t) { return h('th', { scope: 'col', text: t }); }))]));
    var tb = h('tbody');
    daily.forEach(function (c, i) {
      tb.appendChild(h('tr', null, [h('td', { text: withDay(d.dates[i]) }), h('td', { text: String(c.in) }), h('td', { text: String(c.late) }), h('td', { text: String(c.leave) }), h('td', { text: String(c.abs) }), h('td', { text: String(c.off) })]));
    });
    el.dayTable.appendChild(tb);
  }

  function isOffDay(c) { return (c.in + c.late + c.leave + c.abs) === 0 && c.off > 0; }

  function drawChart(d, daily, animate) {
    var host = el.chart, n = d.dates.length;
    clear(host);
    var W = Math.max(280, host.clientWidth), H = Math.round(Math.max(210, Math.min(280, W * .5)));
    S.chartW = host.clientWidth;
    var m = { l: 34, r: 6, t: 12, b: 26 }, iw = W - m.l - m.r, ih = H - m.t - m.b, band = iw / n, bw = Math.max(2, Math.min(26, band * .68));
    var max = 1; daily.forEach(function (c) { max = Math.max(max, c.in + c.late + c.leave + c.abs); });
    var nt = niceTop(max), y = function (v) { return m.t + ih - (v / nt.top) * ih; }, x = function (i) { return m.l + band * i + band / 2; };
    var svg = s('svg', { viewBox: '0 0 ' + W + ' ' + H, width: W, height: H, 'aria-hidden': 'true', focusable: 'false' });
    for (var g = 0; g * nt.step <= nt.top + 1e-9; g++) {
      var gv = g * nt.step, gy = y(gv);
      svg.appendChild(s('line', { class: 'gl', x1: m.l, x2: W - m.r, y1: gy, y2: gy }));
      svg.appendChild(s('text', { x: m.l - 6, y: gy + 3.5, 'text-anchor': 'end', text: String(gv) }));
    }
    var step = Math.max(1, Math.ceil(n / Math.max(2, Math.floor(iw / 58))));       // as many day names as fit without touching
    for (var i = 0; i < n; i++) {
      if (i % step === 0 && (step === 1 || i <= n - 1 - step)) svg.appendChild(s('text', { x: x(i), y: H - 8, 'text-anchor': 'middle', text: short(d.dates[i]) }));
    }
    var tip = h('div', { class: 'tip' }), hls = [];
    daily.forEach(function (c, i) {
      var base = m.t + ih;
      if (isOffDay(c)) svg.appendChild(s('rect', { class: 'seg s-off', 'data-bar': '', 'data-i': i, x: x(i) - bw / 2, y: base - 4, width: bw, height: 4, rx: 2 }));
      [['in', c.in], ['late', c.late], ['leave', c.leave], ['abs', c.abs]].forEach(function (sg) {
        if (!sg[1]) return;
        var hgt = sg[1] / nt.top * ih, gap = hgt > 5 ? 2 : 0;       // a 2px gap between the stacked pieces
        svg.appendChild(s('rect', { class: 'seg s-' + sg[0], 'data-bar': '', 'data-i': i, x: x(i) - bw / 2, y: base - hgt, width: bw, height: Math.max(1, hgt - gap), rx: Math.min(3, bw / 2) }));
        base -= hgt;
      });
    });
    daily.forEach(function (c, i) {
      var hit = s('rect', { class: 'hit', 'data-i': i, x: m.l + band * i, y: m.t, width: band, height: ih });
      var hl = s('rect', { class: 'hl', x: m.l + band * i, y: m.t, width: band, height: ih, rx: 6 });
      hls.push(hl);
      hit.addEventListener('pointerenter', function () { pick(i); });
      hit.addEventListener('click', function () { pick(i); });
      svg.appendChild(hit); svg.appendChild(hl);
    });
    svg.addEventListener('pointerleave', function () { tip.classList.remove('show'); });
    host.appendChild(svg); host.appendChild(tip);

    function pick(i) {
      var c = daily[i], date = d.dates[i], cx = x(i);
      S.sel = i;
      hls.forEach(function (r, k) { r.setAttribute('class', k === i ? 'hl on' : 'hl'); });
      el.readout.textContent = readoutText(d, c, i);
      clear(tip); tip.appendChild(h('b', { text: withDay(date) }));
      if (isOffDay(c)) tip.appendChild(h('div', { text: 'Day off' + (holName(d, date) ? ' (' + holName(d, date) + ')' : '') }));
      else {
        [['in', 'On time', c.in], ['late', 'Late', c.late], ['leave', 'Leave', c.leave], ['abs', 'Absent', c.abs], ['pend', 'Not in yet', c.pend], ['off', 'Day off', c.off]].forEach(function (r) {
          if ((r[0] === 'pend' || r[0] === 'off') && !r[2]) return;
          tip.appendChild(h('div', null, [h('i', { class: 'dot dot--' + r[0], 'aria-hidden': 'true' }), r[1] + ' ' + r[2]]));
        });
      }
      tip.classList.add('show');
      var left = cx > W * .6 ? cx - tip.offsetWidth - 10 : cx + 10;
      tip.style.left = Math.max(0, left) + 'px'; tip.style.top = Math.max(0, m.t + 6) + 'px';
    }
    S.pick = pick;
    if (S.sel >= 0 && S.sel < n) { hls[S.sel].setAttribute('class', 'hl on'); }
    else el.readout.textContent = 'Each bar is one day. Tap or point at a bar to see that day. The tallest bar is ' + plural(max, 'person', 'people') + '.';
  }

  function startDay() { var n = S.data.dates.length, i = Math.max(0, S.data.dates.indexOf(S.data.today)); return i >= n ? n - 1 : i; }
  el.chart.addEventListener('focus', function () {
    if (!S.data || !S.daily || !S.pick || !el.chart.matches(':focus-visible')) return;
    S.pick(S.sel >= 0 ? S.sel : startDay());
  });
  el.chart.addEventListener('keydown', function (ev) {
    if (!S.data || !S.pick) return;
    var n = S.data.dates.length, cur = S.sel >= 0 ? S.sel : startDay();
    var to = ev.key === 'ArrowRight' ? cur + 1 : (ev.key === 'ArrowLeft' ? cur - 1 : (ev.key === 'Home' ? 0 : (ev.key === 'End' ? n - 1 : -1)));
    if (to < 0 || to >= n) return;
    ev.preventDefault(); S.pick(to);
  });
  var resizeTimer = 0;
  window.addEventListener('resize', function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      if (S.data && S.daily && !el.chartCard.hidden && el.chart.clientWidth && el.chart.clientWidth !== S.chartW) drawChart(S.data, S.daily, false);
    }, 150);
  });

  function readoutText(d, c, i) {
    var date = d.dates[i], day = withDay(date), hol = holName(d, date);
    if (isOffDay(c)) return day + ': day off' + (hol ? ' (' + hol + ')' : '');
    return day + ': on time ' + c.in + ', late ' + c.late + ', leave ' + c.leave + ', absent ' + c.abs + (c.pend ? ', not in yet ' + c.pend : '') + (c.off ? ', day off ' + c.off : '');
  }

  /* ---------- People table ---------- */
  function columns(d) {
    var cols = [['name', 'Person'], ['present', 'Present'], ['late', 'Late'], ['leave', 'Leave'], ['absent', 'Absent'], ['off', 'Off']];
    if (num(d.totals.pending) > 0) cols.push(['pending', 'Pending']);
    cols.push(['nights', 'Nights']);
    if (num(d.totals.km) > 0) cols.push(['km', 'Km']);
    return cols;
  }

  function renderPeople() {
    var d = S.data, cols = columns(d), q = S.query.trim().toLowerCase();
    var rows = d.people.filter(function (p) { return !q || (p.name + ' ' + p.username + ' ' + (p.designation || '') + ' ' + (p.manager || '')).toLowerCase().indexOf(q) >= 0; });
    var key = S.sort.key, dir = S.sort.dir;
    rows = rows.slice().sort(function (a, b) {
      var x = a[key], y = b[key];
      if (key === 'name') return String(x).localeCompare(String(y), undefined, { sensitivity: 'base' }) * dir;
      x = num(x); y = num(y);
      return (x === y ? String(a.name).localeCompare(String(b.name)) : (x - y) * dir);
    });
    var shown = (S.showAll || rows.length <= ROWS_FIRST) ? rows : rows.slice(0, ROWS_FIRST);
    var me = (d.me && d.me.username || '').toLowerCase();
    var focusKey = document.activeElement && document.activeElement.getAttribute && document.activeElement.getAttribute('data-sort');

    clear(el.people);
    el.people.appendChild(h('thead', null, [h('tr', null, cols.map(function (c) {
      var on = c[0] === key;
      return h('th', { scope: 'col', 'aria-sort': on ? (dir > 0 ? 'ascending' : 'descending') : 'none' }, [
        h('button', { type: 'button', 'data-sort': c[0], onclick: function () { if (S.sort.key === c[0]) S.sort.dir = -S.sort.dir; else S.sort = { key: c[0], dir: c[0] === 'name' ? 1 : -1 }; renderPeople(); } },
          [c[1], on ? (dir > 0 ? ' ▲' : ' ▼') : ''])]);
    }))]));
    var tb = h('tbody');
    shown.forEach(function (p) {
      var cells = cols.map(function (c, ci) {
        if (ci === 0) {
          return h('td', null, [h('button', { type: 'button', class: 'person', onclick: function () { openPerson(p); } },
            [p.name + (p.username.toLowerCase() === me ? ' (you)' : ''), h('small', { text: [p.username, p.designation].filter(Boolean).join(' · ') })])]);
        }
        var v = p[c[0]]; if (c[0] === 'km') v = v === null || v === undefined ? '' : Math.round(v);
        return h('td', { class: (v === 0 || v === '') ? 'zero' : '', text: String(v === null || v === undefined ? '' : v) });
      });
      tb.appendChild(h('tr', null, cells));
    });
    el.people.appendChild(tb);
    if (rows.length) {
      var sums = { present: 0, late: 0, leave: 0, absent: 0, off: 0, pending: 0, nights: 0, km: 0 };
      rows.forEach(function (p) { Object.keys(sums).forEach(function (k) { sums[k] += num(p[k]); }); });
      el.people.appendChild(h('tfoot', null, [h('tr', { class: 'tot' }, cols.map(function (c, ci) { return h('td', { text: ci === 0 ? (q ? 'Total of these ' + rows.length : 'Total') : String(c[0] === 'km' ? Math.round(sums.km) : sums[c[0]]) }); }))]));
    }
    el.peopleNote.textContent = rows.length ? (shown.length < rows.length ? 'Showing ' + shown.length + ' of ' + rows.length + ' people.' : plural(rows.length, 'person', 'people') + '.') + ' Press a name to see their days.' : 'Nobody matches your search.';
    show(el.more, shown.length < rows.length);
    if (focusKey) { var b = el.people.querySelector('[data-sort="' + focusKey + '"]'); if (b) b.focus(); }
  }
  el.search.addEventListener('input', function () { S.query = el.search.value; S.showAll = false; if (S.data) renderPeople(); });
  el.more.addEventListener('click', function () { S.showAll = true; renderPeople(); });

  /* ---------- One person: numbers and calendar ---------- */
  function personView(p, d, noMeta) {
    var box = h('div');
    var meta = [p.username, p.designation, p.manager ? 'Reports to ' + p.manager : ''].filter(Boolean).join(' · ');
    if (meta && !noMeta) box.appendChild(h('p', { class: 'meta', text: meta }));
    var chips = [['in', 'Present ' + p.present], ['late', 'Late ' + p.late], ['leave', 'Leave ' + p.leave], ['abs', 'Absent ' + p.absent], ['off', 'Day off ' + p.off], ['off', 'Night holds ' + p.nights]];
    if (p.pending) chips.push(['pend', 'Pending ' + p.pending]);
    if (p.km !== null && p.km !== undefined) chips.push(['off', 'Driver km ' + Math.round(p.km)]);
    box.appendChild(h('div', { class: 'chips' }, chips.map(function (c) { return h('span', { class: 'pchip' }, [h('i', { class: 'dot dot--' + c[0], 'aria-hidden': 'true' }), c[1]]); })));
    var byType = (d.leaveTypes || []).map(function (n, i) { return p.leaveByType && p.leaveByType[i] ? n + ' ' + p.leaveByType[i] : ''; }).filter(Boolean);
    if (byType.length) box.appendChild(h('p', { class: 'meta', text: 'Leave taken: ' + byType.join(', ') }));
    box.appendChild(calendar(p, d));
    var key = h('ul', { class: 'key' });
    [['In', 'present'], ['Late', 'present after the late time'], ['Abs', 'absent'], ['Off', 'Friday or holiday'], ['Pend', 'today is not over']].forEach(function (k) { key.appendChild(h('li', { text: k[0] + ' = ' + k[1] })); });
    (d.leaveTypes || []).forEach(function (n, i) { key.appendChild(h('li', { text: leaveShort(String(i + 1), d) + ' = ' + n })); });
    box.appendChild(key);
    var holi = Object.keys(d.holidays || {}).sort().map(function (k) { return short(k) + ' ' + holName(d, k); });
    if (holi.length) box.appendChild(h('p', { class: 'holi', text: 'Holidays in this period: ' + holi.join(', ') + '.' }));
    if (p.note) box.appendChild(h('p', { class: 'holi', text: p.note }));
    if (!p.times) box.appendChild(h('p', { class: 'holi muted', text: 'Check-in times are shown when 80 people or fewer are on the page.' }));
    return box;
  }

  function calendar(p, d) {
    var grid = h('div', { class: 'cal', role: 'list', 'aria-label': 'One box for each day' });
    ['Sat', 'Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri'].forEach(function (w) { grid.appendChild(h('div', { class: 'cal__h', text: w, 'aria-hidden': 'true' })); });
    var lead = (dow(d.dates[0]) + 1) % 7;                      // the week starts on Saturday
    for (var g = 0; g < lead; g++) grid.appendChild(h('div', { class: 'day day--gap', 'aria-hidden': 'true' }));
    d.dates.forEach(function (date, i) {
      var c = p.days.charAt(i), k = kindOf(c), label, full;
      if (k === 'in') { label = 'In'; full = 'Present'; } else if (k === 'late') { label = 'Late'; full = 'Present, late'; } else if (k === 'abs') { label = 'Abs'; full = 'Absent'; }
      else if (k === 'off') { label = 'Off'; full = 'Day off'; } else if (k === 'pend') { label = 'Pend'; full = 'Not in yet'; } else if (k === 'none') { label = '–'; full = 'Not counted'; }
      else { label = leaveShort(c, d); full = leaveName(c, d); }
      var hol = holName(d, date);
      if (hol) full += ' (' + hol + ')';
      var time = p.times && p.times[i] ? String(p.times[i]) : '';
      var dayNo = parts(date).d;
      grid.appendChild(h('div', { class: 'day day--' + k, role: 'listitem', title: withDay(date) + ': ' + full + (time ? ' at ' + time : ''), 'aria-label': withDay(date) + ': ' + full + (time ? ' at ' + time : '') }, [
        h('span', { class: 'day__d', text: (dayNo === 1 || i === 0) ? short(date) : String(dayNo) }),
        h('span', { class: 'day__c', text: label }),
        h('span', { class: 'day__t', text: time })
      ]));
    });
    return grid;
  }

  function openPerson(p) {
    el.dlgTitle.textContent = p.name;
    el.dlgAv.textContent = initials(p.name);
    el.dlgSub.textContent = [p.username, p.designation, p.manager ? 'Reports to ' + p.manager : ''].filter(Boolean).join(' · ');
    clear(el.dlgBody); el.dlgBody.appendChild(personView(p, S.data, true));
    if (typeof el.dlg.showModal === 'function') el.dlg.showModal(); else el.dlg.setAttribute('open', '');
  }
  el.dlgClose.addEventListener('click', function () { el.dlg.close(); });
  el.dlg.addEventListener('click', function (ev) { if (ev.target === el.dlg) el.dlg.close(); });

  /* ---------- Excel files ---------- */
  function renderDownloads() {
    clear(el.dl);
    [['Attendance sheet', 'attendance'], ['Salary sheet', 'salary'], ['Night holds', 'night']].forEach(function (f) {
      var lbl = h('span', { text: f[0] + ' (Excel)' });
      var btn = h('button', { type: 'button', class: 'btn alt' }, [icon(ICON.down), lbl]);
      btn.addEventListener('click', function () { download(f[1], btn, lbl, f[0]); });
      el.dl.appendChild(btn);
    });
    show(el.dlCard, true);
  }

  function download(kind, btn, lbl, label) {
    var d = S.data; if (!d) return;
    var body = { action: S.scope === 'all' ? 'adminReport' : 'myReport', kind: kind, from: d.from, to: d.to };
    if (S.scope !== 'all') body.who = S.scope;
    var before = lbl.textContent; btn.disabled = true; lbl.textContent = 'Preparing…'; showMsg('');
    api(body, 120000).then(function (r) {
      btn.disabled = false; lbl.textContent = before;
      if (r && r.auth) { sessionEnded(); return; }
      if (!r || !r.ok || !r.data) { showMsg((r && r.error) || 'The file could not be made.', true); return; }
      try {
        var bin = atob(r.data), bytes = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        var url = URL.createObjectURL(new Blob([bytes], { type: XLSX }));
        var a = h('a', { href: url, download: String(r.name || 'report.xlsx').replace(/[^\w.\-]+/g, '_') });
        document.body.appendChild(a); a.click(); document.body.removeChild(a);
        setTimeout(function () { URL.revokeObjectURL(url); }, 30000);
        toast(label + ' is ready: ' + (r.name || 'report.xlsx') + '. Look in your Downloads.');
      } catch (e) { showMsg('The file could not be saved on this device.', true); }
    });
  }

  /* ---------- Entrance: the panels rise in, numbers count up, rings and bars draw ---------- */
  function enter() {
    var rises = $$('[data-rise]', stage).filter(function (n) { return !n.hidden; });
    fitVals();
    $$('.n[data-n]', stage).forEach(function (n) { var host = n.closest('[data-rise]'), k = rises.indexOf(host); countUp(n, (k < 0 ? 0 : k) * .055 + .25); });
    if (reduce || !stage.animate) return;
    var ease = 'cubic-bezier(.16,1,.3,1)';
    rises.forEach(function (n, i) {
      n.animate([{ opacity: 0, transform: 'translateY(22px) scale(.97)', filter: 'blur(10px)' }, { opacity: 1, transform: 'none', filter: 'blur(0px)' }], { duration: 700, delay: 40 + i * 55, easing: ease, fill: 'backwards' });
    });
    $$('circle.ra', stage).forEach(function (c) {
      var host = c.closest('[data-rise]'), k = rises.indexOf(host);
      c.animate([{ strokeDasharray: '0 100' }, { strokeDasharray: c.getAttribute('data-to') + ' 100' }], { duration: 1100, delay: 250 + Math.max(0, k) * 55, easing: 'cubic-bezier(.22,1,.36,1)', fill: 'backwards' });
    });
    $$('[data-bar]', stage).forEach(function (b, i) {
      b.animate([{ transform: 'scaleY(0)' }, { transform: 'none' }], { duration: 760, delay: 350 + (Number(b.getAttribute('data-i')) || i) * 45, easing: ease, fill: 'backwards' });
    });
    $$('[data-line]', stage).forEach(function (p) {
      var L = p.getTotalLength ? p.getTotalLength() : 0; if (!L) return;
      p.style.strokeDasharray = L + ' ' + L;
      var an = p.animate([{ strokeDashoffset: L }, { strokeDashoffset: 0 }], { duration: 1100, delay: 700, easing: 'cubic-bezier(.4,0,.2,1)', fill: 'backwards' });
      an.onfinish = function () { p.style.strokeDasharray = ''; };
    });
  }

  /* ---------- A soft light that follows the pointer over each glass panel ---------- */
  var sheenQueued = false, sheenEv = null;
  document.addEventListener('pointermove', function (e) {
    if (e.pointerType === 'touch') return;
    sheenEv = e;
    if (sheenQueued) return;
    sheenQueued = true;
    requestAnimationFrame(function () {
      sheenQueued = false;
      var t = sheenEv && sheenEv.target, g = t && t.closest ? t.closest('.glass') : null;
      if (!g) return;
      var r = g.getBoundingClientRect();
      g.style.setProperty('--mx', (sheenEv.clientX - r.left) + 'px'); g.style.setProperty('--my', (sheenEv.clientY - r.top) + 'px');
    });
  }, { passive: true });

  /* ---------- Light / Dark (same choice as the main page) ---------- */
  var root = document.documentElement;
  function isDark() {
    var t = root.getAttribute('data-theme');
    if (t) return t === 'dark';
    return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  }
  function paintMode() {
    var dk = isDark();
    [el.mode, el.mode2].forEach(function (b) {
      var old = $('svg', b), nu = dk ? iconSun() : icon(ICON.moon);
      if (old) b.replaceChild(nu, old); else b.insertBefore(nu, b.firstChild);
    });
    el.mode.setAttribute('aria-label', dk ? 'Switch to the light theme' : 'Switch to the dark theme');
    $('span', el.mode2).textContent = dk ? 'Light' : 'Dark';
  }
  function flip() {
    var next = isDark() ? 'light' : 'dark';
    root.setAttribute('data-theme', next);
    try { localStorage.setItem('sb-theme', next); } catch (e) { /* not saved: fine */ }
    paintMode();
  }
  el.mode.addEventListener('click', flip); el.mode2.addEventListener('click', flip);
  paintMode();

  /* ---------- Start ---------- */
  loginBusy(false);
  var saved = loadSession();
  if (saved && tokenExpiry(saved.t) > Date.now() + 30000) {
    S.token = saved.t; S.name = String(saved.n || '');
    S.flags = { isManager: !!saved.m, isAdmin: !!saved.a, locks: Array.isArray(saved.l) ? saved.l : [] };
    enterApp();
  } else {
    dropSession(); showLogin('', false);
  }
})();
