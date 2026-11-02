package dev.odysseus.app

/**
 * Injected into the server's pages at document start. It talks to the app only
 * through `OdysseusNative.postMessage`, which WebView exposes to the server's
 * own origin and nothing else.
 *
 *  - {t:"run", s}   the page started (POST /api/chat_stream) or re-attached to
 *                   (GET /api/chat/resume/<s>) a reply, so the app follows it.
 *  - {t:"view", s}  the chat the page shows now (URL hash), so a reply finishing
 *                   in front of you doesn't also raise a notification.
 *  - {t:"file", name, mime, data}  a blob:/data: download, base64; Android's
 *                   download manager can't fetch those, so the app saves them.
 */
object ShellScript {
    val JS = """
(function () {
  if (window.__odysseusShell) return;
  window.__odysseusShell = true;
  var N = window.OdysseusNative;
  if (!N) return;
  function send(o) { try { N.postMessage(JSON.stringify(o)); } catch (e) {} }

  var resumeRe = /\/api\/chat\/resume\/([^\/?#]+)/;
  var origFetch = window.fetch;
  window.fetch = function (input, init) {
    try {
      var url = typeof input === 'string' ? input : (input && input.url) || String(input);
      var method = ((init && init.method) || (input && input.method) || 'GET').toUpperCase();
      if (method === 'POST' && /\/api\/chat_stream(\?|$)/.test(url) && init && init.body instanceof FormData) {
        var s = init.body.get('session');
        if (s) send({ t: 'run', s: String(s) });
      } else if (method === 'GET') {
        var m = resumeRe.exec(url);
        if (m) send({ t: 'run', s: decodeURIComponent(m[1]) });
      }
    } catch (e) {}
    return origFetch.apply(this, arguments);
  };

  function view() { send({ t: 'view', s: location.hash.replace('#', '') }); }
  ['pushState', 'replaceState'].forEach(function (k) {
    var orig = history[k];
    history[k] = function () { var r = orig.apply(this, arguments); view(); return r; };
  });
  window.addEventListener('hashchange', view);
  document.addEventListener('visibilitychange', view);
  view();

  // Keep each blob behind an object URL for a minute, because pages often
  // revoke the URL right after a.click(), before it could be read.
  var blobs = {};
  var origCreate = URL.createObjectURL, origRevoke = URL.revokeObjectURL;
  URL.createObjectURL = function (obj) {
    var u = origCreate.apply(this, arguments);
    if (obj instanceof Blob) blobs[u] = obj;
    return u;
  };
  URL.revokeObjectURL = function (u) {
    var self = this, args = arguments;
    setTimeout(function () { delete blobs[u]; origRevoke.apply(self, args); }, 60000);
  };

  function save(name, blob) {
    var fr = new FileReader();
    fr.onload = function () {
      var d = String(fr.result);
      send({ t: 'file', name: name, mime: blob.type || 'application/octet-stream', data: d.substring(d.indexOf(',') + 1) });
    };
    fr.readAsDataURL(blob);
  }
  function grab(a) {
    var h = a.href || '';
    if (h.indexOf('blob:') !== 0 && h.indexOf('data:') !== 0) return false;
    var name = a.getAttribute('download') || 'download';
    if (blobs[h]) { save(name, blobs[h]); return true; }
    origFetch(h).then(function (r) { return r.blob(); }).then(function (b) { save(name, b); });
    return true;
  }
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.hasAttribute('download') && grab(this)) return;
    return origClick.apply(this, arguments);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest && e.target.closest('a[download]');
    if (a && grab(a)) e.preventDefault();
  }, true);
})();
""".trimIndent()
}
