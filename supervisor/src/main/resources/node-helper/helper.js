// Guild Bridge Node helper. The supervisor starts this and talks to it with one JSON object per
// line on stdin/stdout: {"id":1,"op":"networth",...} -> {"id":1,"ok":true,...}.
// Only stdout carries replies; everything else goes to stderr so it ends up in the supervisor log.
'use strict';
const readline = require('readline');

let nw, lily, senSkill, senSlayer, senDungeon;
try {
  nw = require('skyhelper-networth');
  lily = require('lilyweight');
  senSkill = require('senitherweight/build/calculations/calcSkillWeight').skillWeight;
  senSlayer = require('senitherweight/build/calculations/calcSlayerWeight').slayerWeight;
  senDungeon = require('senitherweight/build/calculations/calcDungeonWeight').dungeonWeight;
} catch (e) {
  process.stderr.write('Node helper: libraries missing (' + e.message + '). Run npm install in this folder.\n');
}

function reply(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n');
}

async function networth(req) {
  if (!nw) throw new Error('networth library missing');
  const calc = new nw.ProfileNetworthCalculator(req.member, req.museum || null, req.bank || 0);
  const r = await calc.getNetworth({ onlyNetworth: true });
  const types = {};
  for (const [k, v] of Object.entries(r.types || {})) types[k] = v.total;
  return { networth: r.networth, unsoulbound: r.unsoulboundNetworth, purse: r.purse, bank: r.bank,
    noInventory: !!r.noInventory, types };
}

function weight(req) {
  if (!lily || !senSkill) throw new Error('weight libraries missing');
  // Lily: skill xp in its own order, its own level math.
  const utils = require('lilyweight/lib/utils');
  const levels = req.lilySkillXp.map(x => utils.getLevelFromXP(x));
  // Lily expects only floor numbers as keys; Hypixel adds a "total" entry.
  const floors = o => Object.fromEntries(Object.entries(o || {}).filter(([k]) => /^\d+$/.test(k)));
  const l = lily.getWeightRaw(levels, req.lilySkillXp, floors(req.cataCompl), floors(req.mCataCompl),
    req.cataXp || 0, req.lilySlayerXp);
  // Senither: sum of per-skill, per-slayer and per-dungeon weights.
  let total = 0, overflow = 0;
  const add = w => { if (w && isFinite(w.total)) { total += w.total; overflow += (w.overflow || 0); } };
  for (const [s, xp] of Object.entries(req.senSkills || {})) add(senSkill(s, xp));
  for (const [s, xp] of Object.entries(req.senSlayers || {})) add(senSlayer(s, xp));
  for (const [s, xp] of Object.entries(req.senDungeons || {})) add(senDungeon(s, xp));
  return { lily: l.total, senither: total, senitherOverflow: overflow };
}

const rl = readline.createInterface({ input: process.stdin });
rl.on('line', async line => {
  let req;
  try { req = JSON.parse(line); } catch { return; }
  try {
    let out;
    if (req.op === 'ping') out = { pong: true, libs: !!(nw && lily && senSkill) };
    else if (req.op === 'networth') out = await networth(req);
    else if (req.op === 'weight') out = weight(req);
    else throw new Error('unknown op ' + req.op);
    reply({ id: req.id, ok: true, ...out });
  } catch (e) {
    reply({ id: req.id, ok: false, error: String(e && e.message || e) });
  }
});
// Let replies still in progress finish before exiting.
rl.on('close', () => setTimeout(() => process.exit(0), 30000));
