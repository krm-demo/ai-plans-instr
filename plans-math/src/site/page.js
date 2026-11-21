/* page.js — the GitHub Pages of plans-math: the palette chooser for the Swept In slices (?palette=…). */
(function () {
  'use strict';
  var api = window.sweptAmountMath;
  if (!api) return;
  var current = new URLSearchParams(location.search).get('palette') || 'current';
  if (!api.PALETTES[current]) current = 'current';
  // The status names in the colours of the chosen palette.
  var ink = { SWEPT_IN_PARTIAL: api.ZONES.inp.ink, SWEPT_IN_FULL: api.ZONES.in.ink };
  document.querySelectorAll('.st[data-zone]').forEach(function (el) {
    var c = ink[el.getAttribute('data-zone')];
    if (c) el.style.color = c;
  });
  var box = document.getElementById('palettes');
  if (!box) return;
  box.innerHTML = Object.keys(api.PALETTES).map(function (k) {
    var p = api.PALETTES[k];
    var sw = function (c, op) { return '<span class="sw" style="background:' + c[0] + ';opacity:' + op + '"></span>'; };
    return '<a class="pal' + (k === current ? ' on' : '') + '" href="?palette=' + k + '#palette">' +
      '<span class="sws">' + sw(p.inp, 0.55) + sw(p.in, 0.55) + '</span>' +
      '<span class="pal-t">' + p.title + '</span>' +
      '<span class="pal-n"><span style="color:' + p.inp[1] + '">Swept <b>In</b> Partial</span> · <span style="color:' + p.in[1] + '">Swept <b>In</b> Full</span></span></a>';
  }).join('');
})();
