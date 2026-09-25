#!/usr/bin/env node
// Version-aware update of the BUNDLED .axe fallback from the marketplace clone at
// /tmp/marketplace. Updates only plugins already bundled (in each dir), never
// downgrades, adds, or removes — runtime PluginSyncService handles live delivery.
// One direction only (marketplace -> mobile); mobile never authors plugins.
const fs = require('fs');
const path = require('path');

const MARKET = process.env.MARKET_DIR || '/tmp/marketplace';
// The app bundles plugins in TWO places — keep BOTH in step with the marketplace.
// iOS uses `axe-plugins`, not `plugins` (iOS reserves `PlugIns/` in the bundle;
// see iosApp/AGENTS.md). If either folder moves again, update this list AND the
// `git add` line in .github/workflows/sync-plugins.yml.
const DIRS = [
  'app/src/main/assets/plugins',
  'iosApp/Parachord/Resources/axe-plugins',
];

// Fail loudly on a missing folder. This used to `continue`, so when the iOS
// folder was renamed the iOS copies silently stopped updating and the only
// symptom was the later `git add` failing on the old path.
const missing = DIRS.filter((d) => !fs.existsSync(d));
if (missing.length) {
  console.error(`Bundled plugin folder(s) not found: ${missing.join(', ')}. Update DIRS.`);
  process.exit(1);
}

// Strictly-greater semver compare; >0 if a > b (X.Y.Z, pre-release tolerant).
function cmp(a, b) {
  const split = (v) => {
    const [core, pre] = String(v || '0').split('-');
    const nums = core.split('.').map((n) => parseInt(n, 10) || 0);
    while (nums.length < 3) nums.push(0);
    return { nums, pre: pre || '' };
  };
  const A = split(a);
  const B = split(b);
  for (let i = 0; i < 3; i++) if (A.nums[i] !== B.nums[i]) return A.nums[i] - B.nums[i];
  if (A.pre === B.pre) return 0;
  if (!A.pre) return 1;
  if (!B.pre) return -1;
  return A.pre < B.pre ? -1 : 1;
}

function version(p) {
  try {
    return JSON.parse(fs.readFileSync(p, 'utf8'))?.manifest?.version || null;
  } catch {
    return null;
  }
}

let updated = 0;
let kept = 0;
for (const dir of DIRS) {
  for (const f of fs.readdirSync(dir).filter((f) => f.endsWith('.axe'))) {
    const mp = path.join(MARKET, f);
    if (!fs.existsSync(mp)) continue; // not in marketplace — leave the bundled copy
    const mv = version(mp);
    const lv = version(path.join(dir, f));
    if (mv && lv && cmp(mv, lv) > 0) {
      fs.copyFileSync(mp, path.join(dir, f));
      console.log(`[~] ${dir}/${f}: ${lv} -> ${mv}`);
      updated++;
    } else {
      kept++;
    }
  }
}
console.log(`Done: ${updated} updated, ${kept} kept.`);
