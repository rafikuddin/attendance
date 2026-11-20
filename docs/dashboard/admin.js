/* SAM BOND Reports: the Admin panel on the website.
 *
 * Shown only to an admin whose Admin Panel menu is not locked (the server decides, the page only follows).
 * - Every change asks for the admin's OWN password again. The password goes to the server with that one request and is not kept anywhere on this page.
 *   A correct password also gives a 15-minute marker (kept in memory only, never stored) so a second change is not asked again at once.
 * - The server writes every change made from the website to the WebAdminLog sheet (who, what, when, never a password).
 * - What the server sends is put on the page as plain text only. No HTML is built from it and nothing here writes inline style.
 * - Not here (the app only): app updates, downloads, targets, products and orders, SMS, e-mail, surveys, profile edits, manual-entry dates.
 */
(function () {
  'use strict';

  var WRONG = /own password is wrong/i;
  var RIGHTS = [
    ['surveys', 'Manage surveys', 'Create, close and read the results of surveys.'],
    ['teamview', 'See team profiles', 'A manager may open the profile and calendar of the people below them (read only).'],
    ['accounts', 'Accounts', 'Enters the money received on orders.'],
    ['delivery', 'Delivery', 'Moves orders to Ready to Ship, Shipped and Delivered.'],
    ['driver', 'Driver', 'Photographs the car meter with every attendance.'],
    ['track', 'Share location', 'The phone shares its location every few minutes after attendance.']
  ];
  var VIEWS = [
    { id: 'users', label: 'Users', title: 'Users', sub: 'Add people, switch accounts on or off, menu access',
      icon: 'M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM2.5 20a6.5 6.5 0 0 1 13 0M16 4.3a3.5 3.5 0 0 1 0 6.4M18 14a6 6 0 0 1 3.5 6' },
    { id: 'requests', label: 'Sign-up requests', title: 'Sign-up requests', sub: 'People who asked for an account in the app',
      icon: 'M15 19a5 5 0 0 0-10 0M10 13a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM19 8v6M16 11h6' },
    { id: 'holidays', label: 'Holidays', title: 'Holidays', sub: 'Days off for everybody (Friday is always off)',
      icon: 'M5 6h14a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1ZM4 10h16M8 3v4M16 3v4' },
    { id: 'notices', label: 'Notices', title: 'Notices', sub: 'Messages everybody sees in the app',
      icon: 'M6 16V10a6 6 0 0 1 12 0v6l1.5 2h-15ZM10 21h4' },
    { id: 'settings', label: 'Settings', title: 'Settings', sub: 'Night hold hours, safeguards, leave days',
      icon: 'M4 7h9M17 7h3M4 17h3M11 17h9M15 4v6M9 14v6' }
  ];
  var ROWS_FIRST = 150;
  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  var WEEK = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

  window.SBAdminFactory = function (ctx) {
    var h = ctx.h, clear = ctx.clear, host = ctx.host, el = ctx.el;
    var A = { view: '', req: 0, users: null, items: null, elev: null, q: '', filter: 'all', showAll: false, flash: '', year: '' };
    var pw = null;                                             // the password question that is open: { body, opts, resolve, busy }
    var strip = null;

    /* ---------- small helpers ---------- */
    function extend(a, b) { var o = {}; Object.keys(a).forEach(function (k) { o[k] = a[k]; }); Object.keys(b || {}).forEach(function (k) { o[k] = b[k]; }); return o; }
    function pad(n) { return (n < 10 ? '0' : '') + n; }
    function isoLocal(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
    function dayText(iso) {
      var y = Number(iso.slice(0, 4)), m = Number(iso.slice(5, 7)), d = Number(iso.slice(8, 10));
      return WEEK[new Date(Date.UTC(y, m - 1, d, 12)).getUTCDay()] + ' ' + d + ' ' + MONTHS[m - 1] + ' ' + y;
    }
    function hourText(n) { return (n % 12 === 0 ? 12 : n % 12) + ':00 ' + (n < 12 ? 'AM' : 'PM'); }
    function say(text, isError) { ctx.msg(text, isError); }
    function showDlg(d) { if (d.open) return; if (typeof d.showModal === 'function') d.showModal(); else d.setAttribute('open', ''); }
    function hideDlg(d) { try { if (d.open) d.close(); } catch (e) { d.removeAttribute('open'); } }
    function sleepy(node, on) { if (node) node.disabled = !!on; }
    function field(label, control, hint, extra) {
      return h('label', { class: 'af' + (extra ? ' ' + extra : '') }, [h('span', { text: label }), control, hint ? h('small', { text: hint }) : null]);
    }
    function button(text, cls, fn, svgPath) {
      var b = h('button', { type: 'button', class: 'btn' + (cls ? ' ' + cls : '') }, [svgPath ? ctx.icon(svgPath) : null, h('span', { text: text })]);
      if (fn) b.addEventListener('click', fn);
      return b;
    }
    function errBox() { return h('p', { class: 'lgm aerr', role: 'alert', hidden: true }); }
    function fail(box, text) { box.textContent = text; box.hidden = false; }
    function chip(text, kind) { return h('span', { class: 'ch ch--' + kind, text: text }); }
    function panel(title, small, kids) {
      var head = h('div', { class: 'ph' }, [h('h2', { text: title }), small ? h('small', { text: small }) : null]);
      return h('section', { class: 'glass card panel' }, [head].concat(kids || []));
    }
    function disclose(trigger, box) {                         // a button that reveals a small form or question, with Cancel to go back
      box.hidden = true;
      trigger.addEventListener('click', function () { trigger.hidden = true; box.hidden = false; var f = box.querySelector('input,textarea,select,button'); if (f) { try { f.focus(); } catch (e) { /* hidden */ } } });
      var cancel = box.querySelector('[data-cancel]');
      if (cancel) cancel.addEventListener('click', function () { box.hidden = true; trigger.hidden = false; trigger.focus(); });
    }

    /* ---------- talking to the server ---------- */
    function call(body, ms) {
      return ctx.api(body, ms || 60000).then(function (r) {
        r = r || { ok: false, error: 'No answer from the server.' };
        if (r.auth) { ctx.ended(); return { ok: false, ended: true }; }
        if (r.ok === false && (r.locked === 'admin' || /Admin Panel (on the website )?is (locked|switched off)|switched off\. Please use the app|^Only an admin/i.test(String(r.error || '')))) {
          ctx.lost(r.error || 'The Admin panel is not available for you.'); return { ok: false, lost: true, error: r.error };
        }
        return r;
      });
    }

    /* ---------- the password question ---------- */
    function elevOk() { return !!(A.elev && A.elev.x > Date.now() + 20000); }
    function keepElev(r) {
      if (r && r.ok && typeof r.elev === 'string' && r.elev) { A.elev = { t: r.elev, x: ctx.expiry(r.elev) }; paintStrip(); }
    }
    function dropElev() { A.elev = null; paintStrip(); }
    function ask(body, opts) {
      return new Promise(function (resolve) {
        if (!opts.always && elevOk()) {
          call(extend(body, { elev: A.elev.t })).then(function (r) {
            if (r.ok === false && WRONG.test(String(r.error || ''))) { dropElev(); question(body, opts, resolve); return; }
            keepElev(r); resolve(r);
          });
          return;
        }
        question(body, opts, resolve);
      });
    }
    function question(body, opts, resolve) {
      pw = { body: body, opts: opts, resolve: resolve, busy: false };
      el.pwTitle.textContent = opts.title || 'Confirm with your password';
      el.pwSub.textContent = opts.sub || '';
      el.pwText.textContent = 'Type your own password to ' + (opts.verb || 'confirm this change') + '. It is sent with this one change and is not kept on this page.';
      el.pwInput.value = ''; el.pwMsg.hidden = true; sleepy(el.pwGo, false);
      el.pwGoText.textContent = opts.go || 'Confirm';
      showDlg(el.pwDlg);
      setTimeout(function () { try { el.pwInput.focus(); } catch (e) { /* not ready */ } }, 0);
    }
    function pwFinish(r) { var cur = pw; pw = null; hideDlg(el.pwDlg); if (cur) cur.resolve(r); }
    el.pwForm.addEventListener('submit', function (ev) {
      ev.preventDefault();
      if (!pw || pw.busy) return;
      var v = el.pwInput.value;
      if (!v) { el.pwMsg.textContent = 'Type your password.'; el.pwMsg.hidden = false; return; }
      pw.busy = true; sleepy(el.pwGo, true); el.pwMsg.hidden = true; el.pwInput.value = '';
      var mine = pw;
      call(extend(mine.body, { adminPassword: v })).then(function (r) {
        if (pw !== mine) return;
        mine.busy = false; sleepy(el.pwGo, false);
        if (r.ended || r.lost) { pwFinish(r); return; }
        if (r.ok === false && (WRONG.test(String(r.error || '')) || r.locked === true)) { el.pwMsg.textContent = r.error; el.pwMsg.hidden = false; try { el.pwInput.focus(); } catch (e) { /* hidden */ } return; }
        keepElev(r); pwFinish(r);
      });
    });
    function pwCancel() { if (pw && !pw.busy) pwFinish({ ok: false, cancelled: true }); }
    el.pwCancel.addEventListener('click', pwCancel); el.pwClose.addEventListener('click', pwCancel);
    el.pwDlg.addEventListener('cancel', function (ev) { if (pw && pw.busy) ev.preventDefault(); });
    el.pwDlg.addEventListener('close', function () { el.pwInput.value = ''; if (pw && !pw.busy) { var cur = pw; pw = null; cur.resolve({ ok: false, cancelled: true }); } });

    // One change: ask (or use the marker), then show the answer. errBox = where to show a refusal; done(r) runs on success.
    function run(btn, box, body, opts, done) {
      if (box) box.hidden = true;
      sleepy(btn, true);
      ask(body, opts).then(function (r) {
        sleepy(btn, false);
        if (r.cancelled || r.ended || r.lost) return;
        if (!r.ok) { if (box) fail(box, r.error || 'That did not work. Please try again.'); else say(r.error || 'That did not work. Please try again.', true); return; }
        done(r);
      });
    }

    /* ---------- the strip: "password confirmed" ---------- */
    function paintStrip() {
      if (!strip) return;
      if (!elevOk()) { strip.hidden = true; return; }
      var left = Math.max(1, Math.round((A.elev.x - Date.now()) / 60000));
      strip.firstChild.textContent = 'Your password was confirmed. You are not asked again for ' + left + ' more minute' + (left === 1 ? '' : 's') + '.';
      strip.hidden = false;
    }
    setInterval(paintStrip, 20000);

    /* ---------- the admin window (a person, a form) ---------- */
    function openWin(title, sub, av) {
      el.adTitle.textContent = title; el.adSub.textContent = sub || ''; el.adAv.textContent = av || '';
      clear(el.adBody); showDlg(el.adDlg);
    }
    el.adClose.addEventListener('click', function () { hideDlg(el.adDlg); });
    el.adDlg.addEventListener('click', function (ev) { if (ev.target === el.adDlg) hideDlg(el.adDlg); });
    el.adDlg.addEventListener('close', function () { clear(el.adBody); A.flash = ''; });   // a temporary password never stays on the page

    function flashBox() {
      if (!A.flash) return null;
      var t = A.flash; A.flash = '';
      return h('p', { class: 'aok', role: 'status', text: t });
    }

    /* ---------- data ---------- */
    function loadUsers() {
      var need = [call({ action: 'adminUsers' })];
      if (!A.items) need.push(call({ action: 'adminAccessItems' }));
      return Promise.all(need).then(function (rs) {
        var u = rs[0], it = rs[1];
        if (u.ended || u.lost) return { ok: false, quiet: true };
        if (!u.ok) return { ok: false, error: u.error || 'The list of users could not be loaded.' };
        if (it) { if (it.ended || it.lost) return { ok: false, quiet: true }; if (it.ok && Array.isArray(it.items)) A.items = it.items; }
        if (!A.items) A.items = [];
        A.users = u.users || [];
        return { ok: true };
      });
    }
    function nameOf(username) { var u = (A.users || []).filter(function (x) { return x.username === username; })[0]; return u ? u.name : username; }
    function managerSelect(selected, exclude) {
      var sel = h('select', null, [h('option', { value: '', text: 'Nobody (no line manager)' })]);
      (A.users || []).slice().sort(function (a, b) { return a.name.toLowerCase() < b.name.toLowerCase() ? -1 : 1; }).forEach(function (u) {
        if (u.inactive || (exclude && u.username.toLowerCase() === exclude.toLowerCase())) return;
        sel.appendChild(h('option', { value: u.username, selected: u.username === selected, text: u.name + ' (' + u.username + ')' }));
      });
      return sel;
    }

    /* ======================================================= USERS ======================================================= */
    function usersView(id) {
      host.appendChild(panel('People', null, [h('p', { class: 'note', text: 'Loading the people…' })]));
      loadUsers().then(function (r) {
        if (id !== A.req) return;
        clear(host);
        if (!r.ok) { if (!r.quiet) say(r.error, true); return; }
        say('');
        drawUsers();
      });
    }

    function drawUsers() {
      clear(host); host.appendChild(stripNode());
      var search = h('input', { type: 'search', placeholder: 'Search name or ID', autocomplete: 'off', spellcheck: 'false', 'aria-label': 'Search people', value: A.q });
      var filter = h('select', { 'aria-label': 'Show' }, [['all', 'Everybody'], ['active', 'Active'], ['inactive', 'Inactive'], ['admin', 'Admins']].map(function (o) { return h('option', { value: o[0], selected: A.filter === o[0], text: o[1] }); }));
      var add = button('Add user', '', function () { openAdd(); }, 'M12 5v14M5 12h14');
      var tbl = h('table', { class: 't tl people' });
      var note = h('p', { class: 'note' });
      var more = button('Show everybody', 'alt', function () { A.showAll = true; paint(); });
      function paint() {
        var q = A.q.trim().toLowerCase();
        var rows = A.users.filter(function (u) {
          if (A.filter === 'active' && u.inactive) return false;
          if (A.filter === 'inactive' && !u.inactive) return false;
          if (A.filter === 'admin' && !u.isAdmin) return false;
          return !q || u.name.toLowerCase().indexOf(q) >= 0 || u.username.toLowerCase().indexOf(q) >= 0;
        });
        rows.sort(function (a, b) { return a.name.toLowerCase() < b.name.toLowerCase() ? -1 : (a.name.toLowerCase() > b.name.toLowerCase() ? 1 : 0); });
        var shown = A.showAll ? rows : rows.slice(0, ROWS_FIRST);
        clear(tbl);
        tbl.appendChild(h('thead', null, [h('tr', null, ['Person', 'Reports to', 'Status', 'Menus', 'Last seen'].map(function (c) { return h('th', { scope: 'col', text: c }); }))]));
        var tb = h('tbody');
        shown.forEach(function (u) {
          var open = h('button', { type: 'button', class: 'person' }, [u.name, h('small', { text: u.username })]);
          open.addEventListener('click', function () { openUser(u.username); });
          var st = [];
          if (u.inactive) st.push(chip('Inactive', 'bad')); else st.push(chip('Active', 'good'));
          if (u.isAdmin) st.push(chip('Admin', 'info'));
          if (u.mustChange && !u.inactive) st.push(chip('New password due', 'late'));
          var menus = u.locks && u.locks.length ? plural(u.locks.length, 'menu', 'menus') + ' locked' : 'All open';
          tb.appendChild(h('tr', null, [h('td', null, [open]), h('td', { text: u.manager || '–' }), h('td', null, [h('span', { class: 'chs' }, st)]), h('td', { class: u.locks && u.locks.length ? '' : 'zero', text: menus }), h('td', { class: u.lastSeen ? '' : 'zero', text: u.lastSeen || 'Never' })]));
        });
        tbl.appendChild(tb);
        note.textContent = rows.length ? (shown.length < rows.length ? 'Showing ' + shown.length + ' of ' + rows.length + ' people.' : plural(rows.length, 'person', 'people') + '.') + ' Press a name to open that person.' : 'Nobody matches.';
        more.hidden = shown.length >= rows.length;
      }
      search.addEventListener('input', function () { A.q = search.value; A.showAll = false; paint(); });
      filter.addEventListener('change', function () { A.filter = filter.value; A.showAll = false; paint(); });
      var tools = h('div', { class: 'atools' }, [h('label', { class: 'psearch' }, [h('span', { class: 'sr', text: 'Search people' }), ctx.icon('M11 4.5a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13ZM20 20l-4.2-4.2'), search]), h('label', { class: 'af slim' }, [h('span', { class: 'sr', text: 'Show' }), filter]), add]);
      var card = panel('People', null, [tools, h('div', { class: 'tw' }, [tbl]), note, h('p', { class: 'note swipe', text: 'Swipe the table sideways to see more columns.' }), more]);
      host.appendChild(card); paint(); ctx.rise([card]);
    }
    function plural(n, one, many) { return n + ' ' + (n === 1 ? one : many); }

    function stripNode() {
      strip = h('div', { class: 'astrip glass', role: 'status', hidden: true }, [h('span'), h('button', { type: 'button', class: 'lnk', text: 'Ask for my password again' })]);
      strip.lastChild.addEventListener('click', function () { dropElev(); ctx.toast('You will be asked for your password again.'); });
      paintStrip();
      return strip;
    }

    // After a change: read the list again, then show the same person again (or the list).
    function reload(username, thenTo) {
      return loadUsers().then(function (r) {
        if (!r.ok) { if (!r.quiet) say(r.error, true); return; }
        if (A.view === 'users') drawUsers();
        if (username) { var u = A.users.filter(function (x) { return x.username === username; })[0]; if (u && el.adDlg.open) paintUser(u); }
        if (thenTo) thenTo();
      });
    }

    function openUser(username) {
      var u = A.users.filter(function (x) { return x.username === username; })[0];
      if (!u) return;
      openWin(u.name, u.username + (u.manager ? ' · reports to ' + nameOf(u.manager) : ''), ctx.initials(u.name));
      paintUser(u);
    }

    function paintUser(u) {
      var me = String(ctx.me() || '').toLowerCase() === u.username.toLowerCase();
      var body = el.adBody; clear(body);
      var fl = flashBox(); if (fl) body.appendChild(fl);
      var facts = h('dl', { class: 'facts' });
      [['Employee ID', u.username], ['Reports to', u.manager ? nameOf(u.manager) + ' (' + u.manager + ')' : 'Nobody'],
        ['Role', u.isAdmin ? 'Admin' : (u.driverRole ? 'Driver' : 'Employee')], ['Status', u.inactive ? 'Inactive' : 'Active'],
        ['App on the phone', u.appVersion ? 'Version ' + u.appVersion : 'Not seen yet'], ['Last seen', u.lastSeen || 'Never'],
        ['Face data', u.face ? 'Saved (' + plural(u.face, 'photo', 'photos') + ')' : 'None yet']].forEach(function (f) {
        facts.appendChild(h('div', null, [h('dt', { text: f[0] }), h('dd', { text: f[1] })]));
      });
      body.appendChild(facts);
      if (me) body.appendChild(h('p', { class: 'note', text: 'This is your own account. You cannot make it inactive or reset its password here. Use the app to change your password.' }));

      /* account on / off */
      var acc = h('div', { class: 'asec' }, [h('h3', { text: 'Account' })]);
      if (u.inactive) {
        acc.appendChild(h('p', { class: 'note', text: 'This account is inactive' + (u.lastWorkingDay ? '. Last working day: ' + u.lastWorkingDay : '') + (u.inactiveRemarks ? '. Remarks: ' + u.inactiveRemarks : '') + '.' }));
        var errOn = errBox();
        var on = button('Make active again', '', function () {
          run(on, errOn, { action: 'adminSetActive', username: u.username, active: true }, { title: 'Make ' + u.name + ' active', sub: u.username, verb: 'make this account active again', go: 'Make active' }, function () {
            A.flash = u.name + ' is active again. They can sign in with their old password.'; ctx.toast('Made active.'); reload(u.username);
          });
        });
        acc.appendChild(on); acc.appendChild(errOn);
      } else if (!me) {
        var date = h('input', { type: 'date', value: isoLocal(new Date()) });
        var why = h('textarea', { rows: 3, maxlength: 300, placeholder: 'Why is the account being made inactive?' });
        var errOff = errBox();
        var go = button('Make inactive', 'danger', function () {
          if (!/^\d{4}-\d{2}-\d{2}$/.test(date.value)) { fail(errOff, 'Choose the last working day.'); return; }
          if (why.value.trim().length < 3) { fail(errOff, 'Write a short remark (why the account is being made inactive).'); return; }
          run(go, errOff, { action: 'adminSetActive', username: u.username, active: false, lastWorkingDay: date.value, remarks: why.value.trim() }, { title: 'Make ' + u.name + ' inactive', sub: u.username, verb: 'make this account inactive', go: 'Make inactive' }, function (r) {
            var mails = typeof r.emailed === 'number' ? ' ' + plural(r.emailed, 'e-mail was', 'e-mails were') + ' sent to their line managers and the admins.' : '';
            A.flash = u.name + ' is now inactive and signed out everywhere.' + mails; ctx.toast('Made inactive.'); reload(u.username);
          });
        });
        var form = h('div', { class: 'aform' }, [h('p', { class: 'note', text: 'They are signed out on every phone and cannot sign in again. Their records are kept. Their line managers and the admins are told by e-mail.' }),
          field('Last working day', date), field('Remarks', why), errOff, h('div', { class: 'arow' }, [h('button', { type: 'button', class: 'btn alt', 'data-cancel': '', text: 'Cancel' }), go])]);
        var open = button('Make inactive…', 'alt', null);
        disclose(open, form); acc.appendChild(open); acc.appendChild(form);
      }
      body.appendChild(acc);

      /* password */
      if (!me) {
        var pwSec = h('div', { class: 'asec' }, [h('h3', { text: 'Password' })]);
        var errPw = errBox(), result = h('div');
        var gen = button('Make the new password', '', function () {
          run(gen, errPw, { action: 'adminResetPassword', targetUsername: u.username }, { title: 'New password for ' + u.name, sub: u.username, verb: 'make a new password for this person', go: 'Make password', always: true }, function (r) {
            sureBox.hidden = true; clear(result); result.appendChild(tempBox(r.tempPassword, u.name)); ctx.toast('New password made.');
          });
        });
        var sureBox = h('div', { class: 'aform' }, [h('p', { class: 'note', text: 'A temporary password is made and shown here once. The person is signed out everywhere and must choose their own password at the next sign-in. A “too many wrong passwords” pause is lifted.' }), errPw,
          h('div', { class: 'arow' }, [h('button', { type: 'button', class: 'btn alt', 'data-cancel': '', text: 'Cancel' }), gen])]);
        var rs = button('Reset password…', 'alt', null);
        disclose(rs, sureBox); pwSec.appendChild(rs); pwSec.appendChild(sureBox); pwSec.appendChild(result); body.appendChild(pwSec);
      }

      /* face */
      if (u.face) {
        var fSec = h('div', { class: 'asec' }, [h('h3', { text: 'Face data' })]);
        var errF = errBox();
        var fgo = button('Forget the face data', 'danger', function () {
          run(fgo, errF, { action: 'adminFaceReset', targetUsername: u.username }, { title: 'Reset face data of ' + u.name, sub: u.username, verb: 'reset this person’s face data', go: 'Reset' }, function () {
            A.flash = 'Face data was reset. The next selfies make a new reference.'; ctx.toast('Face data reset.'); reload(u.username);
          });
        });
        var fbox = h('div', { class: 'aform' }, [h('p', { class: 'note', text: 'The saved reference is removed. The next attendance selfies make a new one. Use this when a person cannot pass the face check.' }), errF,
          h('div', { class: 'arow' }, [h('button', { type: 'button', class: 'btn alt', 'data-cancel': '', text: 'Cancel' }), fgo])]);
        var fopen = button('Reset face data…', 'alt', null);
        disclose(fopen, fbox); fSec.appendChild(fopen); fSec.appendChild(fbox); body.appendChild(fSec);
      }

      /* menu access */
      body.appendChild(accessSection(u, me));
    }

    function tempBox(text, name) {
      var code = h('code', { class: 'tp', text: text });
      var copy = button('Copy', 'alt', function () {
        var done = function () { copy.lastChild.textContent = 'Copied'; setTimeout(function () { copy.lastChild.textContent = 'Copy'; }, 1800); };
        try { navigator.clipboard.writeText(text).then(done, function () { selectText(code); }); } catch (e) { selectText(code); }
      });
      return h('div', { class: 'temp' }, [h('p', { class: 'aok', role: 'status', text: 'Temporary password for ' + name + ' (shown once):' }), h('div', { class: 'tprow' }, [code, copy]),
        h('p', { class: 'note', text: 'Give it to them in person or by phone. They must choose their own at the first sign-in. When you close this window it is gone from this page.' })]);
    }
    function selectText(node) { try { var r = document.createRange(); r.selectNodeContents(node); var s = window.getSelection(); s.removeAllRanges(); s.addRange(r); } catch (e) { /* the person can select it by hand */ } }

    function accessSection(u, me) {
      var sec = h('div', { class: 'asec' }, [h('h3', { text: 'Menu access' }), h('p', { class: 'note', text: 'Tick what this person can open in the app. Untick a menu to lock it.' })]);
      var lockedNow = u.locks || [], grantsNow = u.grants || [];
      var grid = h('div', { class: 'tick' });
      var boxes = {};
      (A.items || []).forEach(function (it) {
        if (it.key === 'admin' && !u.isAdmin) return;
        var cb = h('input', { type: 'checkbox', checked: lockedNow.indexOf(it.key) < 0, disabled: it.key === 'admin' && me });
        boxes['m:' + it.key] = cb;
        var hint = it.key === 'admin' && me ? 'You cannot lock your own' : (it.key === 'attendance' ? 'Off: no attendance in the app and no My report on the website' : (it.key === 'reports' ? 'Off: no reports on the website' : ''));
        grid.appendChild(h('label', { class: 'tk' }, [cb, h('span', { text: it.label }), hint ? h('small', { text: hint }) : null]));
      });
      sec.appendChild(grid);
      sec.appendChild(h('p', { class: 'note', text: 'Extra rights' }));
      var rg = h('div', { class: 'tick' });
      RIGHTS.forEach(function (r) {
        var cb = h('input', { type: 'checkbox', checked: grantsNow.indexOf(r[0]) >= 0 });
        boxes['g:' + r[0]] = cb;
        rg.appendChild(h('label', { class: 'tk' }, [cb, h('span', { text: r[1] }), h('small', { text: r[2] })]));
      });
      sec.appendChild(rg);
      var err = errBox();
      var save = button('Save menu access', '', function () {
        var d = diffAccess(boxes, lockedNow, grantsNow);
        if (!d.any) return;
        run(save, err, { action: 'adminSetAccess', mode: 'merge', usernames: [u.username], lock: d.lock, unlock: d.unlock, give: d.give, take: d.take }, { title: 'Menu access for ' + u.name, sub: u.username, verb: 'save these menu changes', go: 'Save' }, function () {
          A.flash = 'Menu access saved.'; ctx.toast('Saved.'); reload(u.username);
        });
      });
      save.disabled = true;
      function sync() { save.disabled = !diffAccess(boxes, lockedNow, grantsNow).any; }
      Object.keys(boxes).forEach(function (k) { boxes[k].addEventListener('change', sync); });
      sec.appendChild(err); sec.appendChild(h('div', { class: 'arow' }, [save]));
      return sec;
    }
    function diffAccess(boxes, lockedNow, grantsNow) {
      var d = { lock: [], unlock: [], give: [], take: [], any: false };
      Object.keys(boxes).forEach(function (k) {
        var key = k.slice(2), on = boxes[k].checked;
        if (k.charAt(0) === 'm') {
          var wasLocked = lockedNow.indexOf(key) >= 0;
          if (!on && !wasLocked) d.lock.push(key); else if (on && wasLocked) d.unlock.push(key);
        } else {
          var had = grantsNow.indexOf(key) >= 0;
          if (on && !had) d.give.push(key); else if (!on && had) d.take.push(key);
        }
      });
      d.any = !!(d.lock.length || d.unlock.length || d.give.length || d.take.length);
      return d;
    }

    /* add a user */
    function openAdd() {
      openWin('Add a user', 'They choose their own password at the first sign-in', '+');
      var body = el.adBody;
      var id = h('input', { type: 'text', maxlength: 30, autocomplete: 'off', autocapitalize: 'none', spellcheck: 'false' });
      var nm = h('input', { type: 'text', maxlength: 100, autocomplete: 'off' });
      var mg = managerSelect('', '');
      var ad = h('input', { type: 'checkbox' });
      var pass = h('input', { type: 'password', maxlength: 100, autocomplete: 'new-password' });
      var err = errBox();
      var go = button('Add user', '', function () {
        var uid = id.value.trim();
        if (!/^[A-Za-z0-9][A-Za-z0-9._-]{2,29}$/.test(uid)) { fail(err, 'Employee ID must be 3 to 30 letters, numbers, dot, dash or underscore.'); return; }
        if (!nm.value.trim()) { fail(err, 'Please enter the full name.'); return; }
        var b = { action: 'adminAddUser', newUsername: uid, name: nm.value.trim(), manager: mg.value, makeAdmin: !!ad.checked };
        if (pass.value) b.password = pass.value;
        run(go, err, b, { title: 'Add ' + b.name, sub: uid, verb: 'add this person', go: 'Add user', always: true }, function (r) {
          clear(body);
          body.appendChild(h('p', { class: 'aok', role: 'status', text: b.name + ' (' + uid + ') was added.' }));
          if (r.tempPassword) body.appendChild(tempBox(r.tempPassword, b.name));
          else body.appendChild(h('p', { class: 'note', text: 'They sign in with the password you typed and must choose their own at the first sign-in.' }));
          body.appendChild(h('div', { class: 'arow' }, [button('Add another', 'alt', function () { openAdd(); }), button('Done', '', function () { hideDlg(el.adDlg); })]));
          ctx.toast('User added.'); reload('');
        });
      });
      body.appendChild(h('div', { class: 'aform open' }, [field('Employee ID', id, '3 to 30 letters, numbers, dot, dash or underscore. The person types this to sign in.'), field('Full name', nm), field('Reports to', mg, 'The line manager. Can be changed later in the app.'),
        h('label', { class: 'tk single' }, [ad, h('span', { text: 'Make this person an admin' }), h('small', { text: 'An admin can use the whole Admin Panel, here and in the app.' })]),
        field('Password (optional)', pass, 'Leave empty to make a temporary password that is shown once.'), err, h('div', { class: 'arow' }, [h('button', { type: 'button', class: 'btn alt', text: 'Cancel', onclick: function () { hideDlg(el.adDlg); } }), go])]));
      setTimeout(function () { try { id.focus(); } catch (e) { /* hidden */ } }, 0);
    }

    /* ======================================================= SIGN-UP REQUESTS ======================================================= */
    function requestsView(id) {
      host.appendChild(panel('Waiting for a decision', null, [h('p', { class: 'note', text: 'Loading…' })]));
      var need = [call({ action: 'adminSignups' })];
      if (!A.users) need.push(loadUsers());
      Promise.all(need).then(function (rs) {
        if (id !== A.req) return;
        var r = rs[0]; clear(host);
        if (r.ended || r.lost) return;
        if (!r.ok) { say(r.error || 'The requests could not be loaded.', true); return; }
        say(''); host.appendChild(stripNode());
        drawRequests(r);
      });
    }
    function drawRequests(r) {
      var open = r.open !== false;
      var err = errBox();
      var tog = button(open ? 'Close sign-up' : 'Open sign-up', 'alt', function () {
        run(tog, err, { action: 'adminSignupSetting', open: !open }, { title: open ? 'Close sign-up' : 'Open sign-up', verb: open ? 'close sign-up' : 'open sign-up', go: open ? 'Close sign-up' : 'Open sign-up' }, function () {
          ctx.toast(open ? 'Sign-up is closed.' : 'Sign-up is open.'); requestsReload();
        });
      });
      var sw = panel('Sign-up in the app', null, [h('div', { class: 'srow' }, [h('p', { class: 'note', text: open ? 'People can ask for an account in the app. You decide below. Close it to stop new requests.' : 'Sign-up is closed. Nobody can ask for a new account in the app.' }), chip(open ? 'Open' : 'Closed', open ? 'good' : 'bad'), tog]), err]);
      var list = (r.requests || []);
      var items = list.map(function (q) {
        var err2 = errBox();
        var okb = button('Approve…', '', function () { openApprove(q); });
        var rej = button('Reject', 'alt', null);
        var rgo = button('Yes, reject', 'danger', function () {
          run(rgo, err2, { action: 'adminSignupDecide', username: q.username, decision: 'reject' }, { title: 'Reject ' + q.name, sub: q.username, verb: 'reject this request', go: 'Reject' }, function () { ctx.toast('Request rejected.'); requestsReload(); });
        });
        var sure = h('span', { class: 'inl' }, [h('span', { class: 'note', text: 'Reject this request?' }), rgo, h('button', { type: 'button', class: 'btn alt', 'data-cancel': '', text: 'Keep' })]);
        disclose(rej, sure);
        return h('li', { class: 'req' }, [h('div', { class: 'rq' }, [h('b', { text: q.name }), h('small', { text: q.username + ' · ' + q.email + (q.on ? ' · asked ' + q.on : '') })]), h('div', { class: 'arow tight' }, [okb, rej, sure]), err2]);
      });
      var card = panel('Waiting for a decision', plural(list.length, 'request', 'requests'), [list.length ? h('ul', { class: 'reqs' }, items) : h('p', { class: 'note', text: 'No requests are waiting.' })]);
      host.appendChild(sw); host.appendChild(card); ctx.rise([sw, card]);
    }
    function requestsReload() { if (A.view !== 'requests') return; var id = ++A.req; clear(host); requestsView(id); }
    function openApprove(q) {
      openWin('Approve ' + q.name, q.username + ' · ' + q.email, ctx.initials(q.name));
      var mg = managerSelect('', ''), err = errBox();
      var go = button('Approve', '', function () {
        run(go, err, { action: 'adminSignupDecide', username: q.username, decision: 'approve', manager: mg.value }, { title: 'Approve ' + q.name, sub: q.username, verb: 'approve this account', go: 'Approve', always: true }, function () {
          hideDlg(el.adDlg); ctx.toast(q.name + ' was approved. They are told by e-mail.'); A.users = null; requestsReload();
        });
      });
      el.adBody.appendChild(h('div', { class: 'aform open' }, [h('p', { class: 'note', text: 'The account is made with the password the person chose when they signed up. They get an e-mail when you approve.' }), field('Reports to (optional)', mg, 'You can leave this empty and set it later.'), err,
        h('div', { class: 'arow' }, [h('button', { type: 'button', class: 'btn alt', text: 'Cancel', onclick: function () { hideDlg(el.adDlg); } }), go])]));
    }

    /* ======================================================= HOLIDAYS ======================================================= */
    function holidaysView(id) {
      host.appendChild(panel('Holidays', null, [h('p', { class: 'note', text: 'Loading…' })]));
      call({ action: 'holidays' }).then(function (r) {
        if (id !== A.req) return;
        clear(host);
        if (r.ended || r.lost) return;
        if (!r.ok) { say(r.error || 'The holidays could not be loaded.', true); return; }
        say(''); host.appendChild(stripNode()); drawHolidays(r.holidays || []);
      });
    }
    function holidaysReload() { if (A.view !== 'holidays') return; var id = ++A.req; clear(host); holidaysView(id); }
    function drawHolidays(list) {
      var date = h('input', { type: 'date' }), nm = h('input', { type: 'text', maxlength: 60, placeholder: 'For example Eid Holiday', autocomplete: 'off' }), err = errBox();
      var add = button('Add holiday', '', function () {
        if (!/^\d{4}-\d{2}-\d{2}$/.test(date.value)) { fail(err, 'Choose the date.'); return; }
        if (nm.value.trim().length < 2) { fail(err, 'Please enter the holiday name.'); return; }
        run(add, err, { action: 'adminAddHoliday', date: date.value, name: nm.value.trim() }, { title: 'Add a holiday', sub: nm.value.trim() + ' · ' + date.value, verb: 'add this holiday', go: 'Add holiday' }, function () { ctx.toast('Holiday added.'); holidaysReload(); });
      });
      var form = panel('Add a holiday', 'Nobody is marked absent on a holiday', [h('div', { class: 'hform' }, [field('Date', date), field('Name', nm, null, 'grow'), add]), err]);
      var years = {}; list.forEach(function (x) { years[x.date.slice(0, 4)] = 1; });
      var thisYear = String(new Date().getFullYear()); years[thisYear] = 1;
      var ys = Object.keys(years).sort();
      if (!A.year || !years[A.year]) A.year = thisYear;
      var sel = h('select', { 'aria-label': 'Year' }, ys.map(function (y) { return h('option', { value: y, selected: y === A.year, text: y }); }));
      var tb = h('tbody'), tbl = h('table', { class: 't tl' }, [h('thead', null, [h('tr', null, ['Date', 'Holiday', ''].map(function (c) { return h('th', { scope: 'col', text: c }); }))]), tb]);
      var empty = h('p', { class: 'note' });
      function paint() {
        clear(tb);
        var rows = list.filter(function (x) { return x.date.slice(0, 4) === A.year; });
        rows.forEach(function (x) {
          var rm = button('Remove', 'alt small', null), eb = errBox();
          var yes = button('Yes, remove', 'danger small', function () {
            run(yes, eb, { action: 'adminDeleteHoliday', date: x.date }, { title: 'Remove a holiday', sub: x.name + ' · ' + x.date, verb: 'remove this holiday', go: 'Remove' }, function () { ctx.toast('Holiday removed.'); holidaysReload(); });
          });
          var sure = h('span', { class: 'inl' }, [yes, h('button', { type: 'button', class: 'btn alt small', 'data-cancel': '', text: 'Keep' })]);
          disclose(rm, sure);
          tb.appendChild(h('tr', null, [h('td', { text: dayText(x.date) }), h('td', { text: x.name }), h('td', { class: 'act' }, [rm, sure, eb])]));
        });
        empty.textContent = rows.length ? plural(rows.length, 'holiday', 'holidays') + ' in ' + A.year + '.' : 'No holidays added for ' + A.year + '.';
        tbl.hidden = !rows.length;
      }
      sel.addEventListener('change', function () { A.year = sel.value; paint(); });
      var card = panel('Holidays', null, [h('label', { class: 'af slim' }, [h('span', { class: 'sr', text: 'Year' }), sel]), h('div', { class: 'tw' }, [tbl]), empty]);
      host.appendChild(form); host.appendChild(card); paint(); ctx.rise([form, card]);
    }

    /* ======================================================= NOTICES ======================================================= */
    function noticesView(id) {
      host.appendChild(panel('Notices', null, [h('p', { class: 'note', text: 'Loading…' })]));
      call({ action: 'notices' }).then(function (r) {
        if (id !== A.req) return;
        clear(host);
        if (r.ended || r.lost) return;
        if (!r.ok) { say(r.error || 'The notices could not be loaded.', true); return; }
        say(''); host.appendChild(stripNode()); drawNotices((r.notices || []).filter(function (n) { return !n.team; }));
      });
    }
    function noticesReload() { if (A.view !== 'notices') return; var id = ++A.req; clear(host); noticesView(id); }
    function drawNotices(list) {
      var title = h('input', { type: 'text', maxlength: 80, autocomplete: 'off' }), msg = h('textarea', { rows: 4, maxlength: 600 }), days = h('input', { type: 'number', min: 1, max: 90, value: 7, inputmode: 'numeric' }), err = errBox();
      var post = button('Post notice', '', function () {
        if (title.value.trim().length < 2) { fail(err, 'Please enter a title.'); return; }
        if (msg.value.trim().length < 2) { fail(err, 'Please enter the message.'); return; }
        var n = parseInt(days.value, 10); if (!(n >= 1 && n <= 90)) { fail(err, 'Show it for 1 to 90 days.'); return; }
        run(post, err, { action: 'adminPostNotice', title: title.value.trim(), message: msg.value.trim(), days: n }, { title: 'Post a notice', sub: title.value.trim(), verb: 'post this notice', go: 'Post notice' }, function () { ctx.toast('Notice posted.'); noticesReload(); });
      });
      var form = panel('Post a notice', 'Everybody sees it in the app', [h('div', { class: 'aform open' }, [field('Title', title), field('Message', msg), field('Show it for (days)', days, 'It disappears by itself after these days.', 'short'),
        h('p', { class: 'note', text: 'A text message (SMS) can only be sent from the app.' }), err, h('div', { class: 'arow' }, [post])])]);
      var items = list.map(function (n) {
        var rm = button('Remove', 'alt small', null), eb = errBox();
        var yes = button('Yes, remove', 'danger small', function () {
          run(yes, eb, { action: 'adminDeleteNotice', id: n.id }, { title: 'Remove a notice', sub: n.title, verb: 'remove this notice', go: 'Remove' }, function () { ctx.toast('Notice removed.'); noticesReload(); });
        });
        var sure = h('span', { class: 'inl' }, [yes, h('button', { type: 'button', class: 'btn alt small', 'data-cancel': '', text: 'Keep' })]);
        disclose(rm, sure);
        return h('li', { class: 'req' }, [h('div', { class: 'rq' }, [h('b', { text: n.title }), h('p', { class: 'msgt', text: n.message }), h('small', { text: 'Posted ' + (n.date || '') + (n.time ? ' ' + n.time : '') + (n.postedBy ? ' by ' + n.postedBy : '') + (n.expires ? ' · until ' + n.expires : '') })]), h('div', { class: 'arow tight' }, [rm, sure]), eb]);
      });
      var card = panel('Notices people see now', plural(list.length, 'notice', 'notices'), [list.length ? h('ul', { class: 'reqs' }, items) : h('p', { class: 'note', text: 'No admin notice is showing right now.' })]);
      host.appendChild(form); host.appendChild(card); ctx.rise([form, card]);
    }

    /* ======================================================= SETTINGS ======================================================= */
    function settingsView(id) {
      host.appendChild(panel('Settings', null, [h('p', { class: 'note', text: 'Loading…' })]));
      Promise.all([call({ action: 'adminEntrySettings' }), call({ action: 'adminLeaveQuota' })]).then(function (rs) {
        if (id !== A.req) return;
        clear(host);
        var a = rs[0], b = rs[1];
        if (a.ended || a.lost || b.ended || b.lost) return;
        if (!a.ok || !b.ok) { say((!a.ok ? a.error : b.error) || 'The settings could not be loaded.', true); return; }
        say(''); host.appendChild(stripNode()); drawSettings(a, b);
      });
    }
    function settingsReload() { if (A.view !== 'settings') return; var id = ++A.req; clear(host); settingsView(id); }
    function hourSelect(v) { var s = h('select'); for (var i = 0; i < 24; i++) s.appendChild(h('option', { value: String(i), selected: i === v, text: hourText(i) })); return s; }
    function drawSettings(a, q) {
      /* night hours */
      var open = hourSelect(a.nightOpen), close = hourSelect(a.nightClose), e1 = errBox();
      var sv1 = button('Save hours', '', function () {
        if (open.value === close.value) { fail(e1, 'The opening and closing hours must be different.'); return; }
        run(sv1, e1, { action: 'adminSetNightHours', open: Number(open.value), close: Number(close.value) }, { title: 'Night hold hours', verb: 'save the night hold hours', go: 'Save hours' }, function () { ctx.toast('Night hours saved.'); settingsReload(); });
      });
      var nightCard = panel('Night hold hours', 'Now: ' + (a.label || hourText(a.nightOpen) + ' to ' + hourText(a.nightClose)), [h('p', { class: 'note', text: 'People can report where they stay overnight only between these hours (Dhaka time).' }),
        h('div', { class: 'hform' }, [field('Opens', open), field('Closes', close), sv1]), e1]);
      /* safeguards */
      function switchRow(title, text, on, action, key) {
        var err = errBox();
        var b = button(on ? 'Turn off' : 'Turn on', 'alt', function () {
          run(b, err, { action: action, on: !on }, { title: title, verb: (on ? 'turn off ' : 'turn on ') + title.toLowerCase(), go: on ? 'Turn off' : 'Turn on' }, function () { ctx.toast(title + (on ? ' is off.' : ' is on.')); settingsReload(); });
        });
        return h('div', { class: 'srow' }, [h('div', { class: 'grow' }, [h('b', { text: title }), h('p', { class: 'note', text: text })]), chip(on ? 'On' : 'Off', on ? 'good' : 'late'), b, err]);
      }
      var safeCard = panel('Safeguards', 'Turning one off lowers a protection, so it asks for your password', [
        switchRow('Live check', 'Before the selfie button shows, the phone asks the person to blink, turn their head or smile.', a.liveCheck !== false, 'adminSetLiveCheck'),
        switchRow('Meter check', 'For drivers: the phone must be able to read the km number from the meter picture before Submit shows. The picture itself is always needed.', a.meterCheck !== false, 'adminSetMeterCheck')]);
      /* leave days */
      var inputs = {}, e3 = errBox();
      var grid = h('div', { class: 'qgrid' });
      (q.types || []).forEach(function (t) {
        inputs[t] = h('input', { type: 'number', min: 0, max: q.max || 365, value: (q.quota || {})[t], inputmode: 'numeric' });
        grid.appendChild(field(t, inputs[t], q.standard && q.standard[t] !== undefined ? 'Starting value ' + q.standard[t] : null));
      });
      var sv3 = button('Save leave days', '', function () {
        var out = {};
        for (var i = 0; i < (q.types || []).length; i++) {
          var t = q.types[i], raw = String(inputs[t].value).trim();
          if (!/^\d{1,3}$/.test(raw) || Number(raw) > (q.max || 365)) { fail(e3, t + ': enter a whole number of days from 0 to ' + (q.max || 365) + '.'); return; }
          out[t] = Number(raw);
        }
        run(sv3, e3, { action: 'adminSetLeaveQuota', quota: out }, { title: 'Leave days per year', verb: 'save the leave days', go: 'Save leave days' }, function () { ctx.toast('Leave days saved.'); settingsReload(); });
      });
      var leaveCard = panel('Leave days per year', 'The same for everybody', [h('p', { class: 'note', text: 'Balances in the app and in the reports are worked out from these numbers every time, for every year.' }), grid, e3, h('div', { class: 'arow' }, [sv3])]);
      host.appendChild(nightCard); host.appendChild(safeCard); host.appendChild(leaveCard); ctx.rise([nightCard, safeCard, leaveCard]);
    }

    /* ---------- the way in from the page ---------- */
    function show(id) {
      var v = VIEWS.filter(function (x) { return x.id === id; })[0];
      if (!v) return;
      A.view = id; var rid = ++A.req;
      el.adDlg.open && hideDlg(el.adDlg);
      clear(host); say('');
      ctx.head(v.title, v.sub, 'Admin · ' + v.title);
      if (id === 'users') usersView(rid);
      else if (id === 'requests') requestsView(rid);
      else if (id === 'holidays') holidaysView(rid);
      else if (id === 'notices') noticesView(rid);
      else settingsView(rid);
    }
    function refresh() { if (A.view) { A.users = null; show(A.view); } }
    function leave() { A.view = ''; A.req++; hideDlg(el.adDlg); clear(host); }
    function reset() {
      A.view = ''; A.req++; A.users = null; A.items = null; A.elev = null; A.q = ''; A.filter = 'all'; A.showAll = false; A.flash = ''; A.year = '';
      pw = null; strip = null;
      hideDlg(el.pwDlg); hideDlg(el.adDlg); clear(el.adBody); clear(host); el.pwInput.value = '';
    }
    return { views: VIEWS, show: show, refresh: refresh, leave: leave, reset: reset, current: function () { return A.view; } };
  };
})();
