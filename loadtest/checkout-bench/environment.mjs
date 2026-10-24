import fs from 'node:fs';
import path from 'node:path';
import {docker,sql,dc,generated} from './prepare.mjs';
const group=(database,query)=>JSON.parse(sql(database,`SELECT coalesce(json_agg(s),'[]'::json)::text FROM (${query})s;`));
const connectors=[];
for(const name of await(await fetch('http://127.0.0.1:38084/connectors')).json()){
  const status=await(await fetch(`http://127.0.0.1:38084/connectors/${encodeURIComponent(name)}/status`)).json();
  connectors.push({name,state:status.connector?.state,tasks:status.tasks?.map(t=>({id:t.id,state:t.state}))});
}
const result={at:new Date().toISOString(),docker:JSON.parse(docker(['info','--format','{"cpus":{{.NCPU}},"memoryBytes":{{.MemTotal}},"os":{{json .OperatingSystem}}}'])),providerClock:await(await fetch('http://127.0.0.1:38091/bench/stats')).json(),connectors,orderStates:group('order_service','SELECT status,payment_status,saga_phase,count(*) n FROM orders GROUP BY 1,2,3'),submissions:group('order_service','SELECT state,count(*) n FROM order_checkout_submissions GROUP BY state'),dispatches:group('order_service','SELECT status,count(*) n FROM order_workflow_dispatches GROUP BY status'),messages:group('order_service','SELECT status,count(*) n FROM order_workflow_messages GROUP BY status'),inventory:group('inventory_service','SELECT state,count(*) n FROM order_reservation GROUP BY state'),payments:group('payment_service','SELECT status,count(*) n FROM payment_transactions GROUP BY status'),serviceStates:dc(['ps','--format','json']).trim().split('\n').filter(Boolean).map(JSON.parse).map(s=>({service:s.Service,state:s.State,health:s.Health})),note:'Final global snapshot after all cohorts and explicit cleanup; not evidence of five-minute drain for an earlier cohort.'};
fs.writeFileSync(path.join(generated,'closing-environment.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
