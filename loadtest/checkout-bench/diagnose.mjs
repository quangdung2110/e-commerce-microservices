import { sql,dc } from './prepare.mjs';
console.log('order states',sql('order_service','SELECT status,payment_status,saga_phase,count(*) FROM orders GROUP BY 1,2,3;'));
console.log('pending preparation',sql('order_service',"SELECT checkout_token,state,created_at FROM order_checkout_submissions WHERE state='PREPARING' ORDER BY created_at LIMIT 5;"));
console.log('dispatch states',sql('order_service','SELECT status,count(*) FROM order_workflow_dispatches GROUP BY status;'));
console.log('message states',sql('order_service','SELECT status,count(*) FROM order_workflow_messages GROUP BY status;'));
console.log(dc(['exec','-T','provider','node','-e',"fetch('http://order-service:8080/actuator/prometheus').then(r=>r.text()).then(t=>console.log(t.split('\\n').filter(l=>l.startsWith('order_remote_http')||l.startsWith('hikaricp_connections_pending')).join('\\n')))"]));
