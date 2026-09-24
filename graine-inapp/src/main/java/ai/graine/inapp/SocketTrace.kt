package ai.graine.inapp

/**
 * DEBUG ONLY: what the engine's conversation socket actually carries.
 *
 * "The agent did not speak" has three different causes — no audio arrived,
 * audio arrived and was not played, or the server closed the socket — and
 * from outside they look identical. This counts frames by type, prints the
 * non-audio ones, and prints the close code and reason.
 *
 * The URL is cut at '?', so a ticket or a context token never reaches logcat.
 * Installed at page-finished: the page opens its socket only when a call
 * starts, which is after that.
 */
internal object SocketTrace {
    const val JS = """
(function(){
  if (window.__graineTrace) return; window.__graineTrace = true;
  var WS = window.WebSocket;
  function Traced(url, protocols) {
    var ws = protocols ? new WS(url, protocols) : new WS(url);
    var counts = {}; var path = String(url).split('?')[0];
    ws.addEventListener('open', function(){ console.log('[ws] open ' + path); });
    ws.addEventListener('message', function(e){
      var type = 'binary', text = '';
      if (typeof e.data === 'string') {
        try { var d = JSON.parse(e.data); type = d.type || d.event || '?'; if (typeof d.data === 'string') text = d.data.slice(0, 90); }
        catch(_) { type = 'text/raw'; }
      }
      counts[type] = (counts[type] || 0) + 1;
      var noisy = type === 'audio' || type === 'mark' || type === 'ping' || type === 'pong' || type === 'binary';
      if (!noisy) console.log('[ws] recv ' + type + (text ? ': ' + text : ''));
      else if (counts[type] === 1 || counts[type] % 100 === 0) console.log('[ws] recv ' + type + ' #' + counts[type]);
    });
    ws.addEventListener('close', function(e){
      console.log('[ws] close code=' + e.code + ' reason=' + (e.reason || '-') + ' clean=' + e.wasClean + ' frames=' + JSON.stringify(counts));
    });
    return ws;
  }
  Traced.prototype = WS.prototype;
  Traced.CONNECTING = WS.CONNECTING; Traced.OPEN = WS.OPEN; Traced.CLOSING = WS.CLOSING; Traced.CLOSED = WS.CLOSED;
  window.WebSocket = Traced;
})(); true;
"""
}
