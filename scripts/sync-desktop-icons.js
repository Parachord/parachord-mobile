#!/usr/bin/env node
// Copies desktop's icon set and service logos into the shared module, so both
// mobile apps draw the same icons as desktop (docs/DESIGN_SYSTEM.md "Icons").
//
//   node scripts/sync-desktop-icons.js <path to a Parachord/parachord checkout> [git ref]
//
// With a ref (a branch, tag or commit, e.g. origin/main), the files are read
// from that ref with `git show`, whatever the checkout has checked out.
//
// Reads desktop's src/renderer/components/icons.js (Lucide paths, plus
// Spinner, Spinoff and Playlist) and brand-logos.js (SERVICE_LOGO_PATHS,
// SERVICE_LOGO_IMAGES), renders each with a stand-in React.createElement that
// records what it draws, turns every shape into SVG path data, and writes
// shared/src/commonMain/kotlin/com/parachord/shared/ui/icons/DesktopIcons.kt.
// Never edit that file by hand: change the icon on desktop, then run this.
// PlayTop10 is left out (it draws text), and so are the 64px SERVICE_LOGOS.

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const os = require('os');
const desktop = process.argv[2];
const ref = process.argv[3];
if (!desktop) {
  console.error('usage: node scripts/sync-desktop-icons.js <desktop checkout> [git ref]');
  process.exit(1);
}
let components = path.join(desktop, 'src/renderer/components');
if (ref) {
  // Read the two modules at the ref into a temporary folder.
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'parachord-icons-'));
  for (const f of ['icons.js', 'brand-logos.js']) {
    const src = execSync(`git show ${ref}:src/renderer/components/${f}`, { cwd: desktop, maxBuffer: 1 << 26 });
    fs.writeFileSync(path.join(tmp, f), src);
  }
  components = tmp;
}
const OUT = path.join(__dirname, '../shared/src/commonMain/kotlin/com/parachord/shared/ui/icons/DesktopIcons.kt');

global.React = {
  createElement: (tag, props, ...children) => ({ tag, props: props || {}, children: children.flat(Infinity).filter(Boolean) }),
};
const Icons = require(path.join(components, 'icons.js'));
const Logos = require(path.join(components, 'brand-logos.js'));

const num = (v) => Number(v);
const fmt = (n) => String(Math.round(n * 1000) / 1000);

function points(str) {
  const ns = String(str).trim().split(/[\s,]+/).map(num);
  const out = [];
  for (let i = 0; i + 1 < ns.length; i += 2) out.push([ns[i], ns[i + 1]]);
  return out;
}

// One SVG shape as path data.
function shapeToD(tag, a) {
  switch (tag) {
    case 'path': return a.d;
    case 'circle': {
      const cx = num(a.cx), cy = num(a.cy), r = num(a.r);
      return `M${fmt(cx - r)} ${fmt(cy)}a${fmt(r)} ${fmt(r)} 0 1 0 ${fmt(2 * r)} 0a${fmt(r)} ${fmt(r)} 0 1 0 ${fmt(-2 * r)} 0Z`;
    }
    case 'ellipse': {
      const cx = num(a.cx), cy = num(a.cy), rx = num(a.rx), ry = num(a.ry);
      return `M${fmt(cx - rx)} ${fmt(cy)}a${fmt(rx)} ${fmt(ry)} 0 1 0 ${fmt(2 * rx)} 0a${fmt(rx)} ${fmt(ry)} 0 1 0 ${fmt(-2 * rx)} 0Z`;
    }
    case 'line': return `M${fmt(num(a.x1))} ${fmt(num(a.y1))}L${fmt(num(a.x2))} ${fmt(num(a.y2))}`;
    case 'polygon':
    case 'polyline': {
      const ps = points(a.points);
      const d = ps.map(([x, y], i) => `${i ? 'L' : 'M'}${fmt(x)} ${fmt(y)}`).join('');
      return tag === 'polygon' ? d + 'Z' : d;
    }
    case 'rect': {
      const x = num(a.x || 0), y = num(a.y || 0), w = num(a.width), h = num(a.height);
      const r = Math.min(num(a.rx || a.ry || 0), w / 2, h / 2);
      if (!r) return `M${fmt(x)} ${fmt(y)}h${fmt(w)}v${fmt(h)}h${fmt(-w)}Z`;
      return `M${fmt(x + r)} ${fmt(y)}h${fmt(w - 2 * r)}a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(r)} ${fmt(r)}` +
        `v${fmt(h - 2 * r)}a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(-r)} ${fmt(r)}h${fmt(-(w - 2 * r))}` +
        `a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(-r)} ${fmt(-r)}v${fmt(-(h - 2 * r))}a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(r)} ${fmt(-r)}Z`;
    }
    default: throw new Error(`unsupported shape <${tag}>`);
  }
}

const painted = (v) => v !== undefined && v !== null && v !== 'none';
// A paint the icon's tint replaces (white on a coloured square, currentColor),
// else a fixed colour as 0xRRGGBB (Achordion's own colours).
const TINTED = new Set(['currentcolor', 'white', '#fff', '#ffffff']);
function fixedColor(v) {
  if (!painted(v)) return null;
  const s = String(v).trim().toLowerCase();
  if (TINTED.has(s)) return null;
  const m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/.exec(s);
  if (!m) throw new Error(`unsupported paint ${v}`);
  const h = m[1].length === 3 ? m[1].split('').map((c) => c + c).join('') : m[1];
  return parseInt(h, 16);
}

// An icon component's <svg> as { viewBox, parts }.
function fromSvg(name, svg) {
  const p = svg.props;
  const parts = svg.children.filter((c) => c && typeof c === 'object' && c.tag !== 'title').map((c) => {
    if (c.tag === 'text') throw new Error(`${name}: draws text`);
    const a = c.props;
    const fill = a.fill !== undefined ? a.fill : p.fill;
    const stroke = a.stroke !== undefined ? a.stroke : p.stroke;
    return {
      d: shapeToD(c.tag, a),
      fill: painted(fill),
      stroke: painted(stroke),
      strokeWidth: num(a.strokeWidth !== undefined ? a.strokeWidth : (p.strokeWidth !== undefined ? p.strokeWidth : 1)),
      alpha: a.opacity !== undefined ? num(a.opacity) : 1,
      evenOdd: (a.fillRule || p.fillRule) === 'evenodd',
      fillColor: fixedColor(fill),
      strokeColor: fixedColor(stroke),
    };
  });
  return { viewBox: (p.viewBox || '0 0 24 24').split(/\s+/).map(num), parts };
}

const kstr = (s) => JSON.stringify(s).replace(/\$/g, '\\$');
const kfloat = (n) => `${fmt(n)}f`;
function specLiteral({ viewBox, parts }) {
  const color = (c) => (c === null || c === undefined ? 'null' : `0x${c.toString(16).toUpperCase().padStart(6, '0')}`);
  const ps = parts.map((x) => `IconPart(${kstr(x.d)}, fill = ${x.fill}, stroke = ${x.stroke}, strokeWidth = ${kfloat(x.strokeWidth)}, alpha = ${kfloat(x.alpha)}, evenOdd = ${x.evenOdd}, fillColor = ${color(x.fillColor)}, strokeColor = ${color(x.strokeColor)})`);
  return `IconSpec(${viewBox.map(kfloat).join(', ')}, listOf(\n            ${ps.join(',\n            ')},\n        ))`;
}

const names = Icons.ICON_NAMES.filter((n) => n !== 'PlayTop10');
const icons = names.map((name) => [name, fromSvg(name, Icons[name]({}))]);

// Fixes for desktop logos that draw wrong, applied until desktop's own path
// is fixed. Each one is logged when it's used; delete it once desktop matches.
const LOGO_FIXES = {
  // Desktop's SERVICE_LOGO_PATHS.wikipedia has a malformed bottom-left
  // vertex on the W (it renders cut on desktop too). Simple Icons' path
  // (https://simpleicons.org/icons/wikipedia.svg, CC0), which Android drew
  // before the shared set.
  wikipedia: 'M12.09 13.119c-.936 1.932-2.217 4.548-2.853 5.728-.616 1.074-1.127.931-1.532.029-1.406-3.321-4.293-9.144-5.651-12.409-.251-.601-.441-.987-.619-1.139-.181-.15-.554-.24-1.122-.271C.103 5.033 0 4.982 0 4.898v-.455l.052-.045c.924-.005 5.401 0 5.401 0l.051.045v.434c0 .119-.075.176-.225.176l-.564.031c-.485.029-.727.164-.727.436 0 .135.053.33.166.601 1.082 2.646 4.818 10.521 4.818 10.521l.136.046 2.411-4.81-.482-1.067-1.658-3.264s-.318-.654-.428-.872c-.728-1.443-.712-1.518-1.447-1.617-.207-.023-.313-.05-.313-.149v-.468l.06-.045h4.292l.113.037v.451c0 .105-.076.15-.227.15l-.308.047c-.792.061-.661.381-.136 1.422l1.582 3.252 1.758-3.504c.293-.64.233-.801.111-.947-.07-.084-.305-.22-.812-.24l-.201-.021c-.052 0-.098-.015-.145-.051-.045-.031-.067-.076-.067-.129v-.427l.061-.045c1.247-.008 4.043 0 4.043 0l.059.045v.436c0 .121-.059.178-.193.178-.646.03-.782.095-1.023.439-.12.186-.375.589-.646 1.039l-2.301 4.273-.065.135 2.792 5.712.17.048 4.396-10.438c.154-.422.129-.722-.064-.895-.197-.172-.346-.273-.857-.295l-.42-.016c-.061 0-.105-.014-.152-.045-.043-.029-.072-.075-.072-.119v-.436l.059-.045h4.961l.041.045v.437c0 .119-.074.18-.209.18-.648.03-1.127.18-1.443.421-.314.255-.557.616-.736 1.067 0 0-4.043 9.258-5.426 12.339-.525 1.007-1.053.917-1.503-.031-.571-1.171-1.773-3.786-2.646-5.71l.053-.036z',
};

const logos = Object.entries(Logos.SERVICE_LOGO_PATHS).map(([id, entry]) => {
  let d = typeof entry === 'string' ? entry : entry.d;
  if (LOGO_FIXES[id] && LOGO_FIXES[id] !== d) {
    console.warn(`applying the mobile fix for desktop's ${id} logo (fix desktop's brand-logos.js, then delete it from LOGO_FIXES)`);
    d = LOGO_FIXES[id];
  }
  const viewBox = ((typeof entry === 'string' ? null : entry.viewBox) || '0 0 24 24').split(/\s+/).map(num);
  return [id, { viewBox, parts: [{ d, fill: true, stroke: false, strokeWidth: 1, alpha: 1, evenOdd: false }] }];
});
const bitmaps = Object.keys(Logos.SERVICE_LOGO_IMAGES);

// The large logos (Settings' plugin tiles, the meta services): SERVICE_LOGOS.
// An <img> entry is a bitmap each app bundles.
const large = [];
const largeBitmaps = [];
for (const [id, el] of Object.entries(Logos.SERVICE_LOGOS)) {
  if (el.tag === 'img') { largeBitmaps.push(id); continue; }
  large.push([id, fromSvg(id, el)]);
}

let commit = 'unknown';
try { commit = execSync(`git rev-parse --short ${ref || 'HEAD'}`, { cwd: desktop }).toString().trim(); } catch (_) {}
if (ref) commit = `${ref} (${commit})`;

const out = `// GENERATED by scripts/sync-desktop-icons.js from Parachord/parachord ${commit}
// (src/renderer/components/icons.js and brand-logos.js). Do not edit: change
// the icon on desktop, then run the script again.

package com.parachord.shared.ui.icons

/**
 * Desktop's icon set (Lucide paths, ISC licence; plus Spinner, Spinoff and
 * Playlist), named as on desktop. Draw one with Android's \`ParachordIcon\` or
 * iOS's \`PCIcon\`. Transport icons (Play, Pause, SkipBack, SkipForward) are
 * solid; the rest are 2-point outlines in a 24-point box.
 */
enum class AppIcon {
    ${names.join(',\n    ')};

    val spec: IconSpec get() = DesktopIcons.spec(this)
}

internal object DesktopIcons {
    fun spec(icon: AppIcon): IconSpec = when (icon) {
${icons.map(([n, s]) => `        AppIcon.${n} -> ${specLiteral(s)}`).join('\n')}
    }
}

/**
 * Desktop's service logos (\`SERVICE_LOGO_PATHS\`), white on the service's
 * colour square. A service in [BITMAPS] has no vector logo: each app bundles
 * desktop's image for it.
 */
object ServiceLogos {
    val BITMAPS: Set<String> = setOf(${bitmaps.map(kstr).join(', ')})

    fun spec(serviceId: String): IconSpec? = when (serviceId.lowercase()) {
${logos.map(([id, s]) => `        ${kstr(id)} -> ${specLiteral(s)}`).join('\n')}
        else -> null
    }

    val IDS: List<String> = listOf(${logos.map(([id]) => kstr(id)).join(', ')})

    /** Services whose large logo is a bitmap each app bundles. */
    val LARGE_BITMAPS: Set<String> = setOf(${largeBitmaps.map(kstr).join(', ')})

    /**
     * The large logo (desktop \`SERVICE_LOGOS\`): Settings' plugin tiles and
     * any service without a badge logo (the meta services).
     */
    fun large(serviceId: String): IconSpec? = when (serviceId.lowercase()) {
${large.map(([id, s]) => `        ${kstr(id)} -> ${specLiteral(s)}`).join('\n')}
        else -> null
    }

    val LARGE_IDS: List<String> = listOf(${large.map(([id]) => kstr(id)).join(', ')})

    /** The logo for a source badge: the badge logo, else the large one. */
    fun badgeOrLarge(serviceId: String): IconSpec? = spec(serviceId) ?: large(serviceId)
}
`;
fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, out);
console.log(`wrote ${icons.length} icons, ${logos.length} badge logos and ${large.length} large logos from ${commit} to ${path.relative(process.cwd(), OUT)}`);
