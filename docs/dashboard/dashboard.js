/* SAM BOND Reports: the browser dashboard.
 *
 * Read-only. It signs in with the same Employee ID and password as the app, asks the server for reports and shows them.
 * - No library, no tracking, nothing loaded from another site (the page's Content-Security-Policy allows only this site and the SAM BOND server).
 * - The sign-in token lives in this browser tab only (sessionStorage) and is removed on Sign out. Report data is never stored.
 * - Everything from the server is put on the page as plain text (never as HTML).
 * - The server decides who may see what (own report, a manager's team, an admin's everybody); this page only asks.
 */
(function () {
  'use strict';

  var API = 'https://sambond.samuda-sales.workers.dev';       // the SAM BOND front door (it adds the secret key; nothing secret is on this page)
  var IDLE_MS = 15 * 60 * 1000;                               // signs out after 15 minutes without use
  var STORE = 'sb-web-session';
  var ROWS_FIRST = 100;                                       // people shown before "Show everybody"
  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  var WEEK = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  var XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

  var $ = function (s, r) { return (r || document).querySelector(s); };
  var S = { token: null, name: '', flags: { isManager: false, isAdmin: false, locks: [] }, scope: 'me', period: 'current', data: null,
            sort: { key: 'name', dir: 1 }, query: '', showAll: false, expireAt: 0, last: Date.now(), req: 0 };

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
      return { ok: false, error: (e && e.name === 'AbortError') ? 'The server took too long. Please try again.' : 'Could not reach the server. Check your internet connection and try again.' };
    });
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

  /* ---------- Screens ---------- */
  var el = {};
  ['login', 'loginForm', 'loginMsg', 'loginGo', 'user', 'pass', 'showPass', 'app', 'who', 'out', 'mode', 'title', 'sub', 'until', 'controls', 'scopes', 'period', 'dates', 'from', 'to', 'show', 'msg',
   'report', 'range', 'todayCard', 'todayRow', 'notIn', 'notInSum', 'notInList', 'kpis', 'chartCard', 'legend', 'chart', 'readout', 'dayTable', 'peopleCard', 'search', 'people', 'peopleNote', 'more',
   'meCard', 'meTitle', 'meBody', 'dlCard', 'dl', 'dlg', 'dlgTitle', 'dlgBody', 'dlgClose'].forEach(function (id) { el[id] = document.getElementById(id); });

  function showLogin(message, isError) {
    S.token = null; S.data = null;
    show(el.app, false); show(el.login, true); show(el.out, false); show(el.who, false);
    document.title = 'Sign in · SAM BOND Reports';
    if (message) { el.loginMsg.textContent = message; el.loginMsg.className = 'msg' + (isError ? ' msg--err' : ''); show(el.loginMsg, true); } else show(el.loginMsg, false);
    el.pass.value = '';
    setTimeout(function () { (el.user.value ? el.pass : el.user).focus(); }, 0);
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
    [el.kpis, el.chart, el.legend, el.dayTable, el.people, el.meBody, el.dl, el.todayRow, el.notInList, el.dlgBody].forEach(function (n) { if (n) clear(n); });
    el.user.value = '';
    S.name = ''; S.flags = { isManager: false, isAdmin: false, locks: [] };
    showLogin(message || 'You are signed out.', false);
  }

  function showMsg(text, isError) {
    if (!text) { show(el.msg, false); return; }
    el.msg.textContent = text; el.msg.className = 'msg' + (isError ? ' msg--err' : ''); show(el.msg, true);
  }

  /* ---------- Sign in ---------- */
  el.showPass.addEventListener('click', function () {
    var on = el.pass.type === 'password';
    el.pass.type = on ? 'text' : 'password';
    el.showPass.textContent = on ? 'Hide' : 'Show';
    el.showPass.setAttribute('aria-pressed', on ? 'true' : 'false');
  });

  el.loginForm.addEventListener('submit', function (ev) {
    ev.preventDefault();
    var user = el.user.value.trim(), pass = el.pass.value;
    if (!user || !pass) { el.loginMsg.textContent = 'Enter your Employee ID and your password.'; el.loginMsg.className = 'msg msg--err'; show(el.loginMsg, true); return; }
    el.loginGo.disabled = true; el.loginGo.textContent = 'Signing in…'; show(el.loginMsg, false);
    api({ action: 'login', username: user, password: pass, client: 'web', remember: false }, 30000).then(function (r) {
      el.loginGo.disabled = false; el.loginGo.textContent = 'Sign in';
      el.pass.value = '';
      if (!r || !r.ok || !r.token) {
        el.loginMsg.textContent = (r && r.error) || 'Could not sign in. Please try again.'; el.loginMsg.className = 'msg msg--err'; show(el.loginMsg, true);
        el.pass.focus(); return;
      }
      S.token = r.token; S.name = String(r.name || user);
      S.flags = { isManager: !!r.isManager, isAdmin: !!r.isAdmin, locks: Array.isArray(r.locks) ? r.locks : [] };
      saveSession(); enterApp();
    });
  });

  /* ---------- Sign out, idle time, session end ---------- */
  el.out.addEventListener('click', function () { signOut('You are signed out. See you again.', false); });
  function touch() { S.last = Date.now(); }
  ['pointerdown', 'keydown', 'touchstart', 'input', 'wheel'].forEach(function (n) { document.addEventListener(n, touch, { passive: true, capture: true }); });
  window.addEventListener('scroll', touch, { passive: true, capture: true });
  function watch() {
    if (!S.token) return;
    var now = Date.now();
    if (S.expireAt && now >= S.expireAt) signOut('Your 6 hours are over. Please sign in again.', true);
    else if (now - S.last >= IDLE_MS) signOut('You were signed out after 15 minutes without use.', false);
  }
  setInterval(watch, 15000);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) watch(); });

  function sessionEnded() { signOut('Your sign-in has ended. Please sign in again.', true); }

  /* ---------- The dashboard ---------- */
  function scopeLabel(s) { return s === 'all' ? 'Everyone' : (s === 'team' ? 'My team' : 'My report'); }

  function enterApp() {
    S.expireAt = tokenExpiry(S.token); S.last = Date.now();
    show(el.login, false); show(el.app, true); show(el.out, true);
    el.who.textContent = S.name; show(el.who, true);
    var locked = S.flags.locks.indexOf('reports') >= 0;
    S.scope = S.flags.isAdmin ? 'all' : (S.flags.isManager ? 'team' : 'me');
    S.period = 'current'; S.query = ''; S.showAll = false;
    el.period.value = 'current'; show(el.dates, false); el.search.value = '';
    var end = S.expireAt ? new Date(S.expireAt) : null;
    el.until.textContent = end ? 'Signed in until ' + pad(end.getHours()) + ':' + pad(end.getMinutes()) + ' at the latest. You are signed out after 15 minutes without use.' : 'You are signed out after 15 minutes without use.';
    if (locked) {
      show(el.controls, false); show(el.report, false);
      el.title.textContent = 'Reports'; el.sub.textContent = '';
      showMsg('Reports are switched off for your account. Please ask your admin.', true);
      return;
    }
    show(el.controls, true); showMsg('');
    buildScopes();
    load();
  }

  function buildScopes() {
    var list = [['me', 'My report']];
    if (S.flags.isManager) list.push(['team', 'My team']);
    if (S.flags.isAdmin) list.push(['all', 'Everyone']);
    clear(el.scopes);
    show(el.scopes, list.length > 1);
    list.forEach(function (it) {
      el.scopes.appendChild(h('button', { type: 'button', 'data-scope': it[0], 'aria-pressed': it[0] === S.scope ? 'true' : 'false', text: it[1],
        onclick: function () { if (S.scope === it[0]) return; S.scope = it[0]; S.query = ''; S.showAll = false; el.search.value = ''; markScopes(); load(); } }));
    });
  }
  function markScopes() {
    Array.prototype.forEach.call(el.scopes.children, function (b) { b.setAttribute('aria-pressed', b.getAttribute('data-scope') === S.scope ? 'true' : 'false'); });
  }

  el.period.addEventListener('change', function () {
    S.period = el.period.value;
    show(el.dates, S.period === 'custom');
    if (S.period !== 'custom') load();
    else { if (S.data) { el.from.max = S.data.today; el.to.max = S.data.today; if (!el.from.value) el.from.value = S.data.from; if (!el.to.value) el.to.value = S.data.to; } el.from.focus(); }
  });
  el.controls.addEventListener('submit', function (ev) { ev.preventDefault(); load(); });

  function load() {
    var id = ++S.req;
    var body = { action: 'webReport', who: S.scope, period: S.period };
    if (S.period === 'custom') {
      if (!el.from.value || !el.to.value) { showMsg('Choose a start date and an end date.', true); return; }
      body = { action: 'webReport', who: S.scope, from: el.from.value, to: el.to.value };
    }
    el.show.disabled = true; show(el.report, false); showMsg('Loading the report… a big team can take up to a minute.', false);
    api(body, 90000).then(function (r) {
      if (id !== S.req) return;                                // an older answer: ignore
      el.show.disabled = false;
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
    var w = n.split(/\s+/), s = w.length > 1 ? w[0].charAt(0) + w[1].charAt(0) : n.slice(0, 2);
    return s.toUpperCase();
  }

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
    var daily = dailyCounts(d);
    renderToday(d, daily, many);
    renderKpis(d);
    show(el.chartCard, many); if (many) renderChart(d, daily);
    show(el.peopleCard, many); if (many) renderPeople();
    show(el.meCard, !many);
    if (!many) {
      el.meTitle.textContent = d.people.length ? (d.people[0].name || 'Your days') : 'Your days';
      clear(el.meBody); if (d.people.length) el.meBody.appendChild(personView(d.people[0], d)); else el.meBody.appendChild(h('p', { class: 'muted', text: 'There is nothing to show for this period.' }));
    }
    renderDownloads();
    show(el.report, true);
  }

  function renderToday(d, daily, many) {
    var i = d.dates.indexOf(d.today);
    var ok = many && i >= 0 && d.to === d.today;
    show(el.todayCard, ok);
    if (!ok) return;
    var c = daily[i];
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

  function renderKpis(d) {
    var t = d.totals, many = d.people.length > 1;
    var tiles = [['Present', t.present, 'in', many ? 'person-days' : 'days'], ['Late', t.late, 'late', 'inside Present'], ['Leave', t.leave, 'leave', ''], ['Absent', t.absent, 'abs', ''],
                 ['Day off', t.off, 'off', 'Fridays and holidays'], ['Night holds', t.nights, 'off', '']];
    if (num(t.km) > 0) tiles.push(['Driver km', Math.round(t.km), 'off', '']);
    if (num(t.pending) > 0) tiles.push(['Pending', t.pending, 'off', 'today is not over']);
    clear(el.kpis);
    tiles.forEach(function (x) {
      el.kpis.appendChild(h('div', { class: 'kpi' }, [h('b', { text: String(x[1]) }), h('span', null, [h('i', { class: 'dot dot--' + x[2], 'aria-hidden': 'true' }), x[0]]), x[3] ? h('small', { text: x[3] }) : null]));
    });
  }

  /* ---------- Day-by-day chart ---------- */
  function renderChart(d, daily) {
    var items = [['in', 'On time'], ['leave', 'Leave'], ['late', 'Late'], ['abs', 'Absent']];
    clear(el.legend);
    items.forEach(function (it) { el.legend.appendChild(h('li', null, [h('i', { class: 'dot dot--' + it[0], 'aria-hidden': 'true' }), it[1]])); });
    el.legend.appendChild(h('li', null, [h('i', { class: 'dot', 'aria-hidden': 'true' }), 'Day off']));
    var max = 1; daily.forEach(function (c) { max = Math.max(max, c.in + c.late + c.leave + c.abs); });
    clear(el.chart); el.chart.style.setProperty('--n', String(d.dates.length));
    var step = Math.max(1, Math.ceil(d.dates.length / 8));
    var cols = [];
    daily.forEach(function (c, i) {
      var date = d.dates[i], isOff = (c.in + c.late + c.leave + c.abs) === 0 && c.off > 0;
      var btn = h('button', { type: 'button', class: 'col', tabindex: i === 0 ? '0' : '-1', 'aria-pressed': 'false', 'aria-label': readoutText(d, c, i) });
      if (isOff) btn.appendChild(h('span', { class: 'seg2 seg2--off' }));
      [['in', c.in], ['leave', c.leave], ['late', c.late], ['abs', c.abs]].forEach(function (s) {
        if (!s[1]) return;
        var seg = h('span', { class: 'seg2 seg2--' + s[0] });
        seg.style.height = 'calc(' + (s[1] / max * 100).toFixed(2) + '% - 2px)';
        btn.appendChild(seg);
      });
      var pick = function () { cols.forEach(function (b) { b.setAttribute('aria-pressed', 'false'); }); btn.setAttribute('aria-pressed', 'true'); el.readout.textContent = readoutText(d, c, i); };
      btn.addEventListener('click', pick); btn.addEventListener('mouseenter', pick); btn.addEventListener('focus', pick);
      btn.addEventListener('keydown', function (ev) {
        var to = ev.key === 'ArrowRight' ? i + 1 : (ev.key === 'ArrowLeft' ? i - 1 : (ev.key === 'Home' ? 0 : (ev.key === 'End' ? cols.length - 1 : -1)));
        if (to < 0 || to >= cols.length) return;
        ev.preventDefault(); cols[i].setAttribute('tabindex', '-1'); cols[to].setAttribute('tabindex', '0'); cols[to].focus();
      });
      cols.push(btn);
      el.chart.appendChild(h('div', { class: 'cw' }, [btn, (i % step === 0 && (step === 1 || i <= d.dates.length - 1 - step)) ? h('span', { class: 'lab', text: short(date) }) : null]));
    });
    el.readout.textContent = 'Each bar is one day. Tap or point at a bar to see that day. The tallest bar is ' + plural(max, 'person', 'people') + '.';

    clear(el.dayTable);
    el.dayTable.appendChild(h('thead', null, [h('tr', null, ['Day', 'On time', 'Late', 'Leave', 'Absent', 'Day off'].map(function (t) { return h('th', { scope: 'col', text: t }); }))]));
    var tb = h('tbody');
    daily.forEach(function (c, i) {
      tb.appendChild(h('tr', null, [h('td', { text: withDay(d.dates[i]) }), h('td', { text: String(c.in) }), h('td', { text: String(c.late) }), h('td', { text: String(c.leave) }), h('td', { text: String(c.abs) }), h('td', { text: String(c.off) })]));
    });
    el.dayTable.appendChild(tb);
  }

  function readoutText(d, c, i) {
    var date = d.dates[i], day = withDay(date);
    var hol = d.holidays && d.holidays[date];
    if ((c.in + c.late + c.leave + c.abs) === 0 && c.off > 0) return day + ': day off' + (hol ? ' (' + (typeof hol === 'string' ? hol : (hol.name || 'holiday')) + ')' : '');
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
  function personView(p, d) {
    var box = h('div');
    var meta = [p.username, p.designation, p.manager ? 'Reports to ' + p.manager : ''].filter(Boolean).join(' · ');
    if (meta) box.appendChild(h('p', { class: 'meta', text: meta }));
    var chips = [['in', 'Present ' + p.present], ['late', 'Late ' + p.late], ['leave', 'Leave ' + p.leave], ['abs', 'Absent ' + p.absent], ['off', 'Day off ' + p.off], ['off', 'Night holds ' + p.nights]];
    if (p.pending) chips.push(['off', 'Pending ' + p.pending]);
    if (p.km !== null && p.km !== undefined) chips.push(['off', 'Driver km ' + Math.round(p.km)]);
    box.appendChild(h('div', { class: 'chips' }, chips.map(function (c) { return h('span', { class: 'chip' }, [h('i', { class: 'dot dot--' + c[0], 'aria-hidden': 'true' }), c[1]]); })));
    var byType = (d.leaveTypes || []).map(function (n, i) { return p.leaveByType && p.leaveByType[i] ? n + ' ' + p.leaveByType[i] : ''; }).filter(Boolean);
    if (byType.length) box.appendChild(h('p', { class: 'meta', text: 'Leave taken: ' + byType.join(', ') }));
    box.appendChild(calendar(p, d));
    var key = h('ul', { class: 'key' });
    [['In', 'present'], ['Late', 'present after the late time'], ['Abs', 'absent'], ['Off', 'Friday or holiday'], ['Pend', 'today is not over']].forEach(function (k) { key.appendChild(h('li', { text: k[0] + ' = ' + k[1] })); });
    (d.leaveTypes || []).forEach(function (n, i) { key.appendChild(h('li', { text: leaveShort(String(i + 1), d) + ' = ' + n })); });
    box.appendChild(key);
    var holi = Object.keys(d.holidays || {}).sort().map(function (k) { var v = d.holidays[k]; return short(k) + ' ' + (typeof v === 'string' ? v : (v && v.name) || 'holiday'); });
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
      var hol = d.holidays && d.holidays[date];
      if (hol) full += ' (' + (typeof hol === 'string' ? hol : (hol.name || 'holiday')) + ')';
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
    clear(el.dlgBody); el.dlgBody.appendChild(personView(p, S.data));
    if (typeof el.dlg.showModal === 'function') el.dlg.showModal(); else el.dlg.setAttribute('open', '');
  }
  el.dlgClose.addEventListener('click', function () { el.dlg.close(); });
  el.dlg.addEventListener('click', function (ev) { if (ev.target === el.dlg) el.dlg.close(); });

  /* ---------- Excel files ---------- */
  function renderDownloads() {
    clear(el.dl);
    [['Attendance sheet', 'attendance'], ['Salary sheet', 'salary'], ['Night holds', 'night']].forEach(function (f) {
      var btn = h('button', { type: 'button', class: 'btn btn--ghost' }, ['⬇ ' + f[0] + ' (Excel)']);
      btn.addEventListener('click', function () { download(f[1], btn, f[0]); });
      el.dl.appendChild(btn);
    });
    show(el.dlCard, true);
  }

  function download(kind, btn, label) {
    var d = S.data; if (!d) return;
    var body = { action: S.scope === 'all' ? 'adminReport' : 'myReport', kind: kind, from: d.from, to: d.to };
    if (S.scope !== 'all') body.who = S.scope;
    var before = btn.textContent; btn.disabled = true; btn.textContent = 'Preparing…'; showMsg('');
    api(body, 120000).then(function (r) {
      btn.disabled = false; btn.textContent = before;
      if (r && r.auth) { sessionEnded(); return; }
      if (!r || !r.ok || !r.data) { showMsg((r && r.error) || 'The file could not be made.', true); return; }
      try {
        var bin = atob(r.data), bytes = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        var url = URL.createObjectURL(new Blob([bytes], { type: XLSX }));
        var a = h('a', { href: url, download: String(r.name || 'report.xlsx').replace(/[^\w.\-]+/g, '_') });
        document.body.appendChild(a); a.click(); document.body.removeChild(a);
        setTimeout(function () { URL.revokeObjectURL(url); }, 30000);
        showMsg(label + ' is ready: ' + (r.name || 'report.xlsx') + '. Look in your Downloads.', false);
      } catch (e) { showMsg('The file could not be saved on this device.', true); }
    });
  }

  /* ---------- Light / Dark (same choice as the main page) ---------- */
  var root = document.documentElement;
  function isDark() {
    var t = root.getAttribute('data-theme');
    if (t) return t === 'dark';
    return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  }
  function paintMode() {
    var dk = isDark();
    el.mode.textContent = dk ? '☀️' : '🌙';
    el.mode.setAttribute('aria-label', dk ? 'Switch to the light theme' : 'Switch to the dark theme');
  }
  el.mode.addEventListener('click', function () {
    var next = isDark() ? 'light' : 'dark';
    root.setAttribute('data-theme', next);
    try { localStorage.setItem('sb-theme', next); } catch (e) { /* not saved: fine */ }
    paintMode();
  });
  paintMode();

  /* ---------- Start ---------- */
  var saved = loadSession();
  if (saved && tokenExpiry(saved.t) > Date.now() + 30000) {
    S.token = saved.t; S.name = String(saved.n || '');
    S.flags = { isManager: !!saved.m, isAdmin: !!saved.a, locks: Array.isArray(saved.l) ? saved.l : [] };
    enterApp();
  } else {
    dropSession(); showLogin('', false);
  }
})();
