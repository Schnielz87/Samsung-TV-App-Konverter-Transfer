/* TV-App-Transfer: Ersatz für cordova.js auf Samsung-Fernsehern.
   Handy-Plugins gibt es auf dem Fernseher nicht – Aufrufe melden einen Fehler statt die App abstürzen zu lassen. */
(function () {
  if (window.cordova && window.cordova.__tvShim) return;
  var defs = {}, mods = {};
  function define(id, factory) { defs[id] = factory; }
  function req(id) {
    if (mods[id]) return mods[id].exports;
    var m = { exports: {} };
    mods[id] = m;
    if (typeof defs[id] === 'function') defs[id](req, m.exports, m);
    return m.exports;
  }
  function exec(success, fail, service, action) {
    if (typeof fail === 'function') setTimeout(function () { fail('Auf dem Fernseher nicht verfügbar: ' + service + '.' + action); }, 0);
  }
  define('cordova/exec', function (r, e, m) { m.exports = exec; });
  define('cordova/channel', function (r, e, m) {
    m.exports = { onDeviceReady: { subscribe: function (f) { document.addEventListener('deviceready', f, false); } } };
  });
  window.cordova = { __tvShim: true, platformId: 'tizen', version: 'tv', define: define, require: req, exec: exec, plugins: {} };
  window.device = window.device || { platform: 'tizen', model: 'Samsung TV', cordova: 'tv' };

  var fired = false;
  var origAdd = document.addEventListener;
  document.addEventListener = function (type, fn, opt) {
    if (type === 'deviceready' && fired) { setTimeout(function () { fn.call(document, { type: 'deviceready' }); }, 0); return; }
    return origAdd.call(document, type, fn, opt);
  };
  function fire() {
    if (fired) return;
    fired = true;
    var ev = document.createEvent('Event');
    ev.initEvent('deviceready', true, false);
    document.dispatchEvent(ev);
  }
  if (document.readyState === 'complete' || document.readyState === 'interactive') setTimeout(fire, 0);
  else origAdd.call(document, 'DOMContentLoaded', function () { setTimeout(fire, 0); }, false);
})();
