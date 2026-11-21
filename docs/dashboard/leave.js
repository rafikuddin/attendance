/* SAM BOND Reports: Leave applications on the website (a menu for line managers).
 *
 * Shown to a person who has a team and whose Team menu is not locked (the server decides, the page only follows).
 * - The list: the leave applications of everybody below the manager, pending ones first. Approve or reject one (a second press confirms), or open it to review
 *   it: dates, reason, the person's balance, who else is off on the same days and the picture of the application.
 * - The server runs the same checks as the app for every decision (only a supervisor above the person, never for oneself, only while it is still pending).
 * - The picture is asked for only when "Show the picture" is pressed, kept in this tab's memory while the window is open and forgotten when it closes.
 * - What the server sends is put on the page as plain text only. No HTML is built from it and no style markup is written (the widths of the balance bar are set through
 *   the element's style object, as for the bars on the report page, which the page's Content-Security-Policy allows).
 */
(function () {
  'use strict';

  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  var WEEK = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  var P = {
    check: 'M5 12.5l4.5 4.5L19 7.5', x: 'M6 6l12 12M18 6L6 18', go: 'M9 6l6 6-6 6', back: 'M15 6l-6 6 6 6',
    warn: 'M12 4l9 16H3zM12 10v4M12 17.2v.01', find: 'M11 4.5a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13ZM20 20l-4.2-4.2',
    pic: 'M4 5h16v14H4zM8.5 10a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3ZM4 16l5-4.5 4 3.5 3-2.5 4 3.5',
    doc: 'M6 3.5h8.5L19 8v12.5H6zM14.5 3.5V8H19M9.5 13h6M9.5 16.5h6'
  };
  var STATUS = [['Pending', 'Pending'], ['Approved', 'Approved'], ['Rejected', 'Rejected']];
  var VIEW = { id: 'leave', label: 'Leave applications', title: 'Leave applications', sub: 'Approve or reject the leave of your team', icon: P.doc };

  window.SBLeaveFactory = function (ctx) {
    var h = ctx.h, clear = ctx.clear, host = ctx.host, el = ctx.el;
    var L = fresh();
    var ui = null;                                             // the parts of the page that change: { seg, year, yearSel, search, list, note }

    function fresh() { return { req: 0, active: false, data: null, status: 'Pending', q: '', year: '', cur: null, nav: [], rows: [], busy: false, pic: {} }; }

    /* ---------- small helpers ---------- */
    function pad(n) { return (n < 10 ? '0' : '') + n; }
    function num(n) { return Number(n) || 0; }
    function plural(n, one, many) { return n + ' ' + (n === 1 ? one : many); }
    function parts(iso) { return { y: Number(iso.slice(0, 4)), m: Number(iso.slice(5, 7)), d: Number(iso.slice(8, 10)) }; }
    function shortDay(iso) { var p = parts(iso); return p.d + ' ' + MONTHS[p.m - 1]; }
    function withDay(iso) { var p = parts(iso); return WEEK[new Date(Date.UTC(p.y, p.m - 1, p.d, 12)).getUTCDay()] + ' ' + shortDay(iso); }
    function dayText(iso) { return /^\d{4}-\d{2}-\d{2}$/.test(iso) ? withDay(iso) + ' ' + parts(iso).y : String(iso || ''); }
    function rangeText(x) { return x.from === x.to ? dayText(x.from) : withDay(x.from) + ' to ' + dayText(x.to); }
    function who(x) { return [x.username, x.designation].filter(Boolean).join(' · '); }
    function keyOf(x) { return x.username + '|' + x.appliedOn + '|' + x.from + '|' + x.to; }
    function say(text, isError) { ctx.msg(text, isError); }
    function sleepy(node, on) { if (node) node.disabled = !!on; }
    function button(text, cls, fn, svgPath) {
      var b = h('button', { type: 'button', class: 'btn' + (cls ? ' ' + cls : '') }, [svgPath ? ctx.icon(svgPath) : null, h('span', { text: text })]);
      if (fn) b.addEventListener('click', fn);
      return b;
    }
    function errBox() { return h('p', { class: 'lgm aerr', role: 'alert', hidden: true }); }
    function fail(box, text) { box.textContent = text; box.hidden = false; }
    function statusChip(status) { return h('span', { class: 'ch ' + (status === 'Approved' ? 'ch--good' : (status === 'Rejected' ? 'ch--bad' : 'ch--late')), text: status }); }
    function typeChip(t) { return h('span', { class: 'ch ch--leave', text: t }); }

    /* ---------- talking to the server ---------- */
    function call(body, ms) {
      return ctx.api(body, ms || 60000).then(function (r) {
        r = r || { ok: false, error: 'No answer from the server.' };
        if (r.auth) { ctx.ended(); return { ok: false, ended: true }; }
        if (r.ok === false && (r.locked === 'team' || /^Leave applications are for line managers/.test(String(r.error || '')))) {
          ctx.lost(r.error || 'Leave applications are not available for you.', r.locks); return { ok: false, lost: true, error: r.error };
        }
        return r;
      });
    }
    function load(quiet) {
      var id = ++L.req;
      if (!quiet) { clear(host); ui = null; host.appendChild(h('section', { class: 'glass card panel' }, [h('p', { class: 'note', text: 'Loading the leave applications…' })])); }
      var body = { action: 'webLeaves' };
      if (L.year) body.year = L.year;
      return call(body).then(function (r) {
        if (id !== L.req || !L.active) return;
        if (r.ended || r.lost) return;
        if (!r.ok || !Array.isArray(r.leaves)) {
          if (!quiet || !L.data) { clear(host); ui = null; }
          say(r.error || 'The leave applications could not be loaded.', true);
          return;
        }
        say('');
        L.data = r; L.year = r.year;
        ctx.badge(num(r.counts && r.counts.pending));
        remap();
        if (!ui) { clear(host); build(); }
        paint();
        if (L.cur && el.dlg.open) paintReview();
      });
    }
    // The menu shows how many applications are waiting; asked once after sign-in, and again whenever the list is read.
    function prefetch() {
      return call({ action: 'webLeaves' }).then(function (r) { if (r && r.ok && r.counts) ctx.badge(num(r.counts.pending)); });
    }
    // A fresh answer has new objects: the open window and its Previous / Next list point at the new ones.
    function remap() {
      var by = {};
      L.data.leaves.forEach(function (n) { (by[keyOf(n)] = by[keyOf(n)] || []).push(n); });
      function pick(o) { var c = by[keyOf(o)]; return c ? (c.filter(function (n) { return n.status === o.status; })[0] || c[0]) : o; }
      L.nav = L.nav.map(pick);
      if (L.cur) L.cur = pick(L.cur);
    }

    /* ---------- the page ---------- */
    function build() {
      var seg = h('div', { class: 'lvseg', role: 'group', 'aria-label': 'Show applications that are' });
      STATUS.forEach(function (s) {
        var b = h('button', { type: 'button', 'data-status': s[0], 'aria-pressed': s[0] === L.status ? 'true' : 'false' }, [h('span', { text: s[1] }), h('b', { text: '0' })]);
        b.addEventListener('click', function () { if (L.status === s[0]) return; L.status = s[0]; paint(); });
        seg.appendChild(b);
      });
      var search = h('input', { type: 'search', placeholder: 'Search name, ID or leave type', autocomplete: 'off', spellcheck: 'false', 'aria-label': 'Search leave applications', value: L.q });
      search.addEventListener('input', function () { L.q = search.value; paint(); });
      var yearSel = h('select', { 'aria-label': 'Year' });
      yearSel.addEventListener('change', function () { L.year = yearSel.value; load(true); });
      var year = h('label', { class: 'af slim' }, [h('span', { text: 'Year' }), yearSel]);
      var tools = h('div', { class: 'atools' }, [h('label', { class: 'psearch' }, [h('span', { class: 'sr', text: 'Search leave applications' }), ctx.icon(P.find), search]), year]);
      var list = h('ul', { class: 'lvl' });
      var note = h('p', { class: 'note', 'aria-live': 'polite' });
      var card = h('section', { class: 'glass card panel' }, [h('div', { class: 'ph' }, [h('h2', { text: 'Applications' }), h('small', { id: 'lvSub' })]), seg, tools, list, note]);
      host.appendChild(card);
      ui = { seg: seg, year: year, yearSel: yearSel, search: search, list: list, note: note, sub: card.querySelector('#lvSub') };
      ctx.rise([card]);
    }

    function paint() {
      var d = L.data;
      if (!d || !ui) return;
      var counts = d.counts || {};
      $all('button', ui.seg).forEach(function (b) {
        var st = b.getAttribute('data-status'), n = num(counts[st.toLowerCase()]), on = st === L.status;
        b.setAttribute('aria-pressed', on ? 'true' : 'false');
        b.lastChild.textContent = String(n);
        b.classList.toggle('hot', st === 'Pending' && n > 0 && !on);
      });
      ui.year.hidden = L.status === 'Pending';
      clear(ui.yearSel);
      (d.years || [d.year]).forEach(function (y) { ui.yearSel.appendChild(h('option', { value: y, selected: y === d.year, text: y })); });
      ui.sub.textContent = plural(num(d.people), 'person', 'people') + ' in your team';
      var q = L.q.trim().toLowerCase();
      L.rows = d.leaves.filter(function (x) {
        if (x.status !== L.status) return false;
        return !q || [x.name, x.username, x.type, x.designation].join(' ').toLowerCase().indexOf(q) >= 0;
      });
      clear(ui.list);
      L.rows.forEach(function (x) { ui.list.appendChild(card(x)); });
      var total = num(counts[L.status.toLowerCase()]);
      if (!L.rows.length) {
        ui.note.textContent = q ? 'Nobody matches.' : (L.status === 'Pending'
          ? 'Nothing is waiting for you. New leave applications from your team appear here.'
          : 'No ' + L.status.toLowerCase() + ' applications in ' + d.year + '.');
      } else {
        ui.note.textContent = plural(L.rows.length, 'application', 'applications') + (L.status === 'Pending' ? ' waiting' : ' in ' + d.year) + (q && L.rows.length < total ? ' of ' + total : '') + '. Press Review to see everything, including the picture.' +
          (d.truncated ? ' The list is cut here; the pending ones always come first.' : '');
      }
    }
    function $all(sel, root) { return Array.prototype.slice.call(root.querySelectorAll(sel)); }

    function balanceBar(x) {
      var b = x.bal;
      if (!b) return null;
      var used = num(b.used), wait = num(b.pending), left = Math.max(0, num(b.remaining));
      var bar = h('div', { class: 'sbar', 'aria-hidden': 'true' });
      [['leave', used], ['late', wait], ['left', left]].forEach(function (s) {
        if (s[1] <= 0) return;
        var seg = h('i', { class: 's-' + s[0] }); seg.style.flex = s[1] + ' 1 0px'; bar.appendChild(seg);
      });
      return bar;
    }
    function balanceText(x) {
      var b = x.bal;
      return b ? b.remaining + ' of ' + b.quota + ' days left' + (x.status === 'Pending' ? ' after this one' : '') : '';
    }

    function card(x) {
      var name = h('button', { type: 'button', class: 'lvname' }, [x.name]);
      name.addEventListener('click', function () { openReview(x); });
      var head = h('div', { class: 'lvh' }, [
        h('span', { class: 'av', 'aria-hidden': 'true', text: ctx.initials(x.name) }),
        h('div', { class: 'lvwho' }, [name, h('small', { text: who(x) })]),
        statusChip(x.status)
      ]);
      var when = h('div', { class: 'lvw' }, [typeChip(x.type), h('span', { text: rangeText(x) }), h('span', { class: 'd', text: plural(num(x.days), 'day', 'days') })]);
      var kids = [head, when];
      if (x.reason) kids.push(h('p', { class: 'lvr', text: x.reason }));
      var bar = balanceBar(x);
      if (bar) kids.push(h('div', { class: 'lvbal' }, [bar, h('p', { text: x.type + ': ' + balanceText(x) })]));
      if (x.clashCount) kids.push(h('p', { class: 'lvwarn' }, [ctx.icon(P.warn), x.clashCount === 1 && x.clash && x.clash[0] ? x.clash[0].name + ' is also off on these days.' : plural(x.clashCount, 'team mate is', 'team mates are') + ' also off on these days.']));
      var review = h('button', { type: 'button', class: 'lnk lvrev' }, ['Review', ctx.icon(P.go)]);
      review.addEventListener('click', function () { openReview(x); });
      if (x.status === 'Pending') kids.push(actionBar(x, review));
      else kids.push(h('div', { class: 'lvact' }, [h('p', { class: 'lvdec', text: decidedText(x) }), review]));
      return h('li', { class: 'lvi' }, kids);
    }
    function decidedText(x) { return (x.status === 'Approved' ? 'Approved' : 'Rejected') + (x.decidedBy ? ' by ' + x.decidedBy : '') + (x.decidedOn ? ' · ' + x.decidedOn : ''); }

    /* ---------- approve / reject (a second press confirms) ---------- */
    function actionBar(x, extra) {
      var box = h('div', { class: 'lvact' }), err = errBox();
      var wrap = h('div', { class: 'lvdo' }, [box, err]);
      function idle(focus) {
        clear(box);
        var yes = button('Approve', 'small', function () { ask('Approved'); }, P.check);
        var no = button('Reject', 'alt small rej', function () { ask('Rejected'); }, P.x);
        box.appendChild(yes); box.appendChild(no);
        if (extra) box.appendChild(extra);
        if (focus) { try { (focus === 'Rejected' ? no : yes).focus(); } catch (e) { /* hidden */ } }
      }
      function ask(decision) {
        err.hidden = true; clear(box);
        var yes = button(decision === 'Approved' ? 'Yes, approve' : 'Yes, reject', 'small' + (decision === 'Rejected' ? ' danger' : ''), null);
        var no = button('Cancel', 'alt small', function () { idle(decision); });
        yes.addEventListener('click', function () { decide(x, decision, err, [yes, no]); });
        box.appendChild(h('p', { class: 'lvq', text: (decision === 'Approved' ? 'Approve ' : 'Reject ') + x.name + '’s ' + x.type + ' (' + plural(num(x.days), 'day', 'days') + ')?' }));
        box.appendChild(yes); box.appendChild(no);
        try { yes.focus(); } catch (e) { /* hidden */ }
      }
      idle(false);
      return wrap;
    }

    function decide(x, decision, err, btns) {
      if (L.busy) return;
      L.busy = true; btns.forEach(function (b) { sleepy(b, true); });
      err.hidden = true;
      call({ action: 'webLeaveDecide', decision: decision, username: x.username, appliedOn: x.appliedOn, from: x.from, to: x.to }).then(function (r) {
        L.busy = false;
        if (r.ended || r.lost) return;
        if (!r.ok) {
          btns.forEach(function (b) { sleepy(b, false); });
          fail(err, r.error || 'That did not work. Please try again.');
          if (r.decided) { ctx.toast(r.error || 'Somebody already decided this application.'); load(true); }      // somebody else was first: say so and show the newest list
          return;
        }
        x.status = r.status || decision; x.decidedBy = r.decidedBy || ''; x.decidedOn = r.decidedOn || '';
        ctx.toast((decision === 'Approved' ? 'Approved: ' : 'Rejected: ') + x.name + ', ' + x.type + ', ' + shortDay(x.from) + (x.from !== x.to ? ' to ' + shortDay(x.to) : '') + '.');
        if (L.data) {                                          // the numbers change at once; the answer from the server follows
          var c = L.data.counts, k = decision.toLowerCase();
          if (c) { c.pending = Math.max(0, num(c.pending) - 1); if (x.from.slice(0, 4) === L.data.year) c[k] = num(c[k]) + 1; ctx.badge(c.pending); }
        }
        paint();
        if (L.cur && el.dlg.open) paintReview();
        load(true);
      });
    }

    /* ---------- the review window ---------- */
    function openReview(x) { L.cur = x; L.nav = L.rows.slice(); if (L.nav.indexOf(x) < 0) L.nav = [x]; paintReview(); }
    function step(by) {
      var i = L.nav.indexOf(L.cur), n = L.nav[i + by];
      if (n) { L.cur = n; paintReview(); }
    }
    function paintReview() {
      var x = L.cur;
      if (!x) return;
      var body = h('div', { class: 'lvdlg' });
      var i = L.nav.indexOf(x);
      if (L.nav.length > 1 && i >= 0) {
        body.appendChild(h('div', { class: 'dnav' }, [
          h('button', { type: 'button', class: 'lnk', disabled: i === 0, onclick: function () { step(-1); } }, [ctx.icon(P.back), 'Previous']),
          h('span', { class: 'note', text: (i + 1) + ' of ' + L.nav.length }),
          h('button', { type: 'button', class: 'lnk', disabled: i === L.nav.length - 1, onclick: function () { step(1); } }, ['Next', ctx.icon(P.go)])
        ]));
      }
      body.appendChild(h('div', { class: 'chs lvchips' }, [statusChip(x.status), typeChip(x.type)]));
      var kv = h('dl', { class: 'dkv' });
      [['Leave type', x.type], ['When', rangeText(x) + ' · ' + plural(num(x.days), 'day', 'days')], ['Applied on', dayText(x.appliedOn)], ['Reason', x.reason || '–'],
        x.status !== 'Pending' ? ['Decision', decidedText(x)] : null].forEach(function (f) {
        if (f) kv.appendChild(h('div', null, [h('dt', { text: f[0] }), h('dd', { class: f[0] === 'Reason' ? 'lvreason' : '', text: f[1] })]));
      });
      body.appendChild(kv);

      if (x.bal) {
        var b = x.bal, sec = h('div', { class: 'asec' }, [h('h3', { text: x.type + ' in ' + x.from.slice(0, 4) })]);
        sec.appendChild(h('div', { class: 'lvbal' }, [balanceBar(x)]));
        sec.appendChild(h('ul', { class: 'slg' }, [
          legend('leave', 'Used', b.used), legend('late', 'Pending', b.pending), legend('left', 'Left', b.remaining), legend('', 'Yearly days', b.quota)
        ]));
        sec.appendChild(h('p', { class: 'note', text: 'A pending application is counted as used already, so Left is what remains if you approve it.' }));
        body.appendChild(sec);
      }
      if (x.clashCount) {
        var cs = h('div', { class: 'asec' }, [h('h3', { text: 'Also off on these days' })]), ul = h('ul', { class: 'dlist' });
        (x.clash || []).forEach(function (c) {
          ul.appendChild(h('li', { class: 'drow static' }, [h('span', { class: 'dn' }, [c.name, h('small', { text: rangeText(c) })]), h('span', { class: 'dc' }, [statusChip(c.status)])]));
        });
        cs.appendChild(ul);
        if (x.clashCount > (x.clash || []).length) cs.appendChild(h('p', { class: 'note', text: 'and ' + (x.clashCount - x.clash.length) + ' more.' }));
        body.appendChild(cs);
      }
      body.appendChild(pictureSection(x));
      if (x.status === 'Pending') body.appendChild(h('div', { class: 'asec' }, [h('h3', { text: 'Your decision' }), actionBar(x, null)]));
      ctx.openDlg(x.name, ctx.initials(x.name), who(x), body);
    }
    function legend(kind, label, n) {
      return h('li', null, [kind ? h('i', { class: 'dot dot--' + (kind === 'left' ? 'off' : kind) }) : null, label + ' ', h('b', { text: String(num(n)) })]);
    }

    function pictureSection(x) {
      var sec = h('div', { class: 'asec' }, [h('h3', { text: 'Leave application picture' })]);
      if (!x.pic) { sec.appendChild(h('p', { class: 'note', text: 'No picture was attached to this application.' })); return sec; }
      var slot = h('div', { class: 'lvslot' }), err = errBox();
      sec.appendChild(slot);
      function showPic(data) {
        clear(slot);
        var mime = data.indexOf('/9j/') === 0 ? 'image/jpeg' : (data.indexOf('iVBOR') === 0 ? 'image/png' : '');
        if (!mime) { slot.appendChild(h('p', { class: 'note', text: 'This picture could not be shown.' })); return; }
        var img = h('img', { class: 'lvpic', alt: 'Leave application of ' + x.name, src: 'data:' + mime + ';base64,' + data });
        var zoom = h('button', { type: 'button', class: 'lvpw', 'aria-pressed': 'false', 'aria-label': 'Zoom the picture in or out' }, [img]);
        zoom.addEventListener('click', function () { var on = zoom.getAttribute('aria-pressed') !== 'true'; zoom.setAttribute('aria-pressed', on ? 'true' : 'false'); });
        slot.appendChild(zoom);
        slot.appendChild(h('p', { class: 'note', text: 'Press the picture to zoom. It is not saved on this device.' }));
      }
      function ask() {
        err.hidden = true; sleepy(go, true); go.lastChild.textContent = 'Loading the picture…';
        call({ action: 'webLeavePhoto', id: x.pic }, 90000).then(function (r) {
          if (r.ended || r.lost) return;
          if (!L.cur || L.cur.pic !== x.pic) return;           // another application is open now
          if (!r.ok || typeof r.data !== 'string') { sleepy(go, false); go.lastChild.textContent = 'Show the picture'; fail(err, r.error === 'Not found' ? 'The picture was not found.' : (r.error || 'The picture could not be loaded.')); return; }
          L.pic[x.pic] = r.data; showPic(r.data);
        });
      }
      var go = button('Show the picture', 'alt small', ask, P.pic);
      if (L.pic[x.pic]) showPic(L.pic[x.pic]); else { slot.appendChild(go); slot.appendChild(err); }
      return sec;
    }
    el.dlg.addEventListener('close', function () { L.cur = null; L.nav = []; L.pic = {}; });     // the picture is forgotten with the window

    /* ---------- the way in from the page ---------- */
    function show() {
      L.active = true;
      clear(host); ui = null; say('');
      ctx.head(VIEW.title, VIEW.sub, VIEW.title);
      load(false);
    }
    function refresh() { if (L.active) load(!!ui); }
    function leave() { L.active = false; L.req++; L.cur = null; L.nav = []; L.rows = []; L.pic = {}; L.data = null; ui = null; clear(host); }
    function reset() { leave(); L = fresh(); try { if (el.dlg.open) el.dlg.close(); } catch (e) { /* not open */ } }
    return { view: VIEW, show: show, refresh: refresh, leave: leave, reset: reset, prefetch: prefetch };
  };
})();
