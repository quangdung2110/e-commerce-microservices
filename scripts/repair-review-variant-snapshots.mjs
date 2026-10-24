import { spawnSync } from 'node:child_process';

// Operator-only repair from authoritative purchase snapshots. No catalog lookup,
// domain transition, entitlement creation or event replay. Default is dry-run.
const args = process.argv.slice(2);
const apply = args.includes('--apply');
const order = args.find(x => x.startsWith('--order='))?.slice(8);
if (order !== undefined && !/^\d+$/.test(order)) throw new Error('--order must be an integer');
if (apply && !order) throw new Error('--apply requires an explicit --order=<id>');
if (args.some(x => x !== '--apply' && !x.startsWith('--order='))) throw new Error('Unknown argument');
function sql(database, query) {
  const result = spawnSync('docker', ['exec', '-i', 'postgres', 'psql', '-U', 'postgres', '-d', database,
    '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-c', query], { encoding: 'utf8' });
  if (result.status !== 0) throw new Error(result.stderr || 'Database command failed');
  return result.stdout.trim();
}
const quote = value => "'" + String(value).replaceAll("'", "''") + "'";
const orders = JSON.parse(sql('order_service', `SELECT coalesce(json_agg(x),'[]'::json) FROM (
  SELECT l.order_id::text, o.user_id::text, l.product_id, l.sku_id,
         l.snapshot_variant_name, l.snapshot_attributes
  FROM order_item_lines l JOIN orders o ON o.id=l.order_id
  WHERE o.status='COMPLETED' ${order ? `AND o.id=${order}` : ''} ORDER BY l.id) x`));
const labels = new Map();
for (const row of orders) {
  const key = [row.order_id, row.user_id, row.product_id, row.sku_id].join('\0');
  const full = row.snapshot_variant_name?.trim() ? row.snapshot_variant_name : Object.entries(row.snapshot_attributes)
    .filter(([,value]) => typeof value === 'string' && value.trim())
    .sort(([a],[b]) => a < b ? -1 : a > b ? 1 : 0)
    .map(([key,value]) => `${key}: ${value.trim()}`).join(' / ');
  let label = full.slice(0, 255);
  if (label.length === 255 && /[\uD800-\uDBFF]/.test(label.at(-1))) label = label.slice(0, -1);
  if (labels.has(key) && labels.get(key) !== label) throw new Error('Conflicting split-line snapshots; manual review required');
  labels.set(key, label);
}
const eligible = JSON.parse(sql('review_service', `SELECT coalesce(json_agg(x),'[]'::json) FROM (
  SELECT id::text, order_id, owner_user_id::text AS user_id, product_id, sku_id FROM review.review_eligibilities
  WHERE variant_name_snapshot IS NULL AND owner_user_id IS NOT NULL ${order ? `AND order_id=${quote(order)}` : ''}) x`));
const repairs = eligible.flatMap(row => {
  const label = labels.get([row.order_id, row.user_id, row.product_id, row.sku_id].join('\0'));
  return label ? [{...row, variant_name: label}] : [];
});
let repaired = [];
if (apply && repairs.length) {
  const values = repairs.map(row => `(${quote(row.id)}::uuid,${quote(row.user_id)}::uuid,${quote(row.variant_name)})`).join(',');
  const output = sql('review_service', `WITH repaired AS (
    UPDATE review.review_eligibilities e SET variant_name_snapshot=v.label
    FROM (VALUES ${values}) v(id,user_id,label)
    WHERE e.id=v.id AND e.owner_user_id=v.user_id AND e.variant_name_snapshot IS NULL
    RETURNING e.id::text) SELECT coalesce(json_agg(id),'[]'::json) FROM repaired`);
  repaired = JSON.parse(output);
}
console.log(JSON.stringify({mode: apply ? 'apply' : 'dry-run', source: 'completed Order purchase snapshots',
  planned: repairs.map(({id,order_id,sku_id,variant_name}) => ({eligibility_id:id,order_id,sku_id,variant_name})), repaired}, null, 2));
