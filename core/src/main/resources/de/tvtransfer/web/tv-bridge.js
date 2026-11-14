/* TV-App-Transfer: Fernbedienungs-Hilfe für umgewandelte Apps (läuft auch auf älteren Fernsehern, ES5).
   - Pfeiltasten springen zum nächstgelegenen bedienbaren Element, OK klickt es an
   - Zurück-Taste: eine Seite zurück, auf der Startseite App beenden
   - Medientasten werden für die App freigeschaltet */
(function () {
  if (window.__tvBridge) return;
  window.__tvBridge = true;

  var KEY = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 10009, ESC: 27 };

  try {
    if (window.tizen && tizen.tvinputdevice) {
      var wanted = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaFastForward', 'MediaRewind',
        'MediaTrackPrevious', 'MediaTrackNext', 'ColorF0Red', 'ColorF1Green', 'ColorF2Yellow', 'ColorF3Blue',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'ChannelUp', 'ChannelDown'];
      for (var i = 0; i < wanted.length; i++) { try { tizen.tvinputdevice.registerKey(wanted[i]); } catch (e) {} }
    }
  } catch (e) {}

  function addStyle() {
    var s = document.createElement('style');
    s.innerHTML = '.tv-focus,:focus{outline:4px solid #4da3ff !important;outline-offset:2px !important;}';
    (document.head || document.documentElement).appendChild(s);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', addStyle, false); else addStyle();

  var SELECTOR = 'a[href],button,input,select,textarea,[tabindex],[onclick],[role="button"],[role="link"],[role="tab"],[role="menuitem"],ion-item,ion-button,ion-tab-button,.button,.btn,.item';

  function visible(el) {
    if (el.disabled) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return false;
    if (r.bottom < 0 || r.right < 0 || r.top > window.innerHeight * 3 || r.left > window.innerWidth) return false;
    var st = window.getComputedStyle(el);
    return st.visibility !== 'hidden' && st.display !== 'none' && st.opacity !== '0';
  }

  function candidates() {
    var list = document.querySelectorAll(SELECTOR), out = [];
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el.getAttribute('tabindex') === '-1' && !el.hasAttribute('onclick')) continue;
      if (visible(el)) out.push(el);
    }
    return out;
  }

  var current = null;
  function center(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  function move(dir) {
    var list = candidates();
    if (!list.length) return false;
    var from = (current && document.body.contains(current)) ? current : document.activeElement;
    if (!from || from === document.body || list.indexOf(from) < 0) { focusEl(list[0]); return true; }
    var a = center(from.getBoundingClientRect()), best = null, bestScore = Infinity;
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el === from || from.contains(el) || el.contains(from)) continue;
      var b = center(el.getBoundingClientRect());
      var dx = b.x - a.x, dy = b.y - a.y, main, side;
      if (dir === KEY.LEFT) { main = -dx; side = Math.abs(dy); }
      else if (dir === KEY.RIGHT) { main = dx; side = Math.abs(dy); }
      else if (dir === KEY.UP) { main = -dy; side = Math.abs(dx); }
      else { main = dy; side = Math.abs(dx); }
      if (main <= 1) continue;
      var score = main + side * 2.5;
      if (score < bestScore) { bestScore = score; best = el; }
    }
    if (best) { focusEl(best); return true; }
    // nichts mehr in dieser Richtung: Seite scrollen
    if (dir === KEY.DOWN) { window.scrollBy(0, window.innerHeight * 0.6); return true; }
    if (dir === KEY.UP) { window.scrollBy(0, -window.innerHeight * 0.6); return true; }
    return false;
  }

  function focusEl(el) {
    if (current && current.classList) current.classList.remove('tv-focus');
    current = el;
    if (!el.hasAttribute('tabindex') && !/^(A|BUTTON|INPUT|SELECT|TEXTAREA)$/.test(el.tagName)) el.setAttribute('tabindex', '0');
    try { el.focus(); } catch (e) {}
    if (el.classList) el.classList.add('tv-focus');
    var r = el.getBoundingClientRect();
    if (r.top < 0 || r.bottom > window.innerHeight) {
      try { el.scrollIntoView({ block: 'center' }); } catch (e) { el.scrollIntoView(); }
    }
  }

  function isTextInput(el) {
    return el && (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && !/^(button|submit|checkbox|radio|range|reset)$/i.test(el.type)) || el.isContentEditable);
  }

  var startUrl = location.href;

  window.addEventListener('keydown', function (e) {
    if (e.defaultPrevented) return;
    var k = e.keyCode;
    if (k === KEY.BACK || k === KEY.ESC) {
      if (isTextInput(document.activeElement)) { document.activeElement.blur(); e.preventDefault(); return; }
      if (location.href !== startUrl || location.hash.length > 1) { history.back(); }
      else { try { tizen.application.getCurrentApplication().exit(); } catch (err) {} }
      e.preventDefault();
      return;
    }
    if (k === KEY.LEFT || k === KEY.RIGHT || k === KEY.UP || k === KEY.DOWN) {
      if (isTextInput(document.activeElement) && (k === KEY.LEFT || k === KEY.RIGHT)) return;
      if (move(k)) e.preventDefault();
      return;
    }
    if (k === KEY.ENTER) {
      var el = (current && document.body.contains(current)) ? current : document.activeElement;
      if (el && el !== document.body && !isTextInput(el) && el.tagName !== 'BUTTON' && el.tagName !== 'A') {
        el.click();
        e.preventDefault();
      }
    }
  }, false);
})();
