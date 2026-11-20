// Run after mvn verify. Uses the pinned contracts/tools dependency tree (npm ci).
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../../..');
const Ajv = require(path.join(root, 'contracts/tools/node_modules/ajv/dist/2020')).default;
const addFormats = require(path.join(root, 'contracts/tools/node_modules/ajv-formats'));
const yaml = require(path.join(root, 'contracts/tools/node_modules/yaml'));
const ajv = new Ajv({ strict: false, allErrors: true });
addFormats(ajv);
ajv.addFormat('int64', true);
const read = p => JSON.parse(fs.readFileSync(p, 'utf8'));
const schemas = path.join(root, 'contracts/events/payment/v2/schemas');
ajv.addSchema(read(path.join(root, 'contracts/shared/json-schema/v1/cloud-event.schema.json')));
for (const file of fs.readdirSync(schemas).filter(f => f.endsWith('.json')))
  ajv.addSchema(read(path.join(schemas, file)));
let checked = 0;
function checkEvent(file) {
  const value = read(file);
  const schema = read(path.join(schemas, path.basename(value.dataschema)));
  const validate = ajv.getSchema(schema.$id);
  if (!validate(value)) throw new Error(`${path.basename(file)}: ${JSON.stringify(validate.errors)}`);
  checked++;
}
const examples = path.join(root, 'contracts/examples/events');
for (const file of fs.readdirSync(examples).filter(f => /^payment-.*\.v2\.json$/.test(f)))
  checkEvent(path.join(examples, file));
for (const name of ['payment-failed', 'payment-canceled', 'refund-processing'])
  checkEvent(path.join(root, 'java/services/payment-service/target', `audit-${name}.json`));

const ops = yaml.parse(fs.readFileSync(path.join(root, 'contracts/http/payment/internal/v1/openapi.yaml'), 'utf8'));
ajv.addSchema(ops, 'urn:payment:internal');
const validateCorrection = ajv.compile({ $ref: 'urn:payment:internal#/components/schemas/ManualCorrectionRequest' });
const base = { aggregate_type: 'PAYMENT', aggregate_id: 'TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABE',
  amount_minor: 5000, currency: 'VND', provider_status: 'canceled', provider_object_id: 'pi_manual',
  provider_response_json: '{"id":"pi_manual","status":"canceled"}' };
for (const [trigger, field, code] of [['PAYMENT_FAILED_CONFIRMED', 'error_code', 'DECLINE_LIMIT'],
  ['PAYMENT_CANCELED', 'reason_code', 'BUYER_CANCELLED']]) {
  const valid = { ...base, trigger, [field]: code };
  if (!validateCorrection(valid)) throw new Error(JSON.stringify(validateCorrection.errors));
  const missing = { ...valid }; delete missing[field];
  if (validateCorrection(missing)) throw new Error(`${trigger} accepts missing ${field}`);
  if (validateCorrection({ ...valid, provider_status: 'requires_payment_method' }))
    throw new Error(`${trigger} accepts a capturable intent`);
}
const success = { ...base, trigger: 'PAYMENT_SUCCESS_CONFIRMED', provider_status: 'succeeded',
  provider_object_id: 'ch_manual', provider_response_json: '{"id":"pi_manual","status":"succeeded","latest_charge":"ch_manual"}' };
if (!validateCorrection(success)) throw new Error(JSON.stringify(validateCorrection.errors));
if (validateCorrection({ ...success, provider_object_id: 'pi_manual' }))
  throw new Error('SUCCESS accepts a PaymentIntent as Charge');
if (validateCorrection({ ...success, provider_object_id: 'ch_' + 'x'.repeat(98) }))
  throw new Error('Correction accepts provider identity longer than the database limit');
process.stdout.write(`PASS: ${checked} Payment event payloads and correction conditional schema\n`);
