// Package telemetry declares every Prometheus metric of the service in one
// place so names and labels match docs/notification/14-observability.md §2.
// Labels never carry user_id, delivery_id, subject or email_hash (11-privacy §3).
package telemetry

import (
	"github.com/prometheus/client_golang/prometheus"
)

const namespace = "notification"

// Metrics is the registry of all instruments. Construct once with New and
// share it; tests may construct their own with a fresh registry.
type Metrics struct {
	Registry *prometheus.Registry

	// 2.1 consumer
	IngestEventsTotal    *prometheus.CounterVec   // lane, source, decision, reason_code
	IngestDuration       *prometheus.HistogramVec // lane
	KafkaConsumerLag     *prometheus.GaugeVec     // lane, topic, partition
	DecideStepRejections *prometheus.CounterVec   // lane, step, reason_code
	DeadLetterTotal      *prometheus.CounterVec   // topic, reason

	// 2.2 deliver
	DeliveryPendingCount     *prometheus.GaugeVec     // lane, channel
	DeliveryPendingAge       *prometheus.GaugeVec     // lane, channel (seconds, oldest ready)
	DeliveryScheduledCount   *prometheus.GaugeVec     // lane
	DeliveryClaimDuration    *prometheus.HistogramVec // lane
	DeliveryAttemptsTotal    *prometheus.CounterVec   // lane, channel, provider, outcome
	DeliverySendDuration     *prometheus.HistogramVec // provider, lane
	DeliveryFinishedTotal    *prometheus.CounterVec   // lane, channel, template, final_status, reason_code
	DeliveryEndToEnd         *prometheus.HistogramVec // lane, channel
	DeliveryPossiblyDupTotal *prometheus.CounterVec   // lane
	DeliveryLeaseExpired     *prometheus.CounterVec   // lane
	RetryBudgetRatio         *prometheus.GaugeVec     // lane
	LanePaused               *prometheus.GaugeVec     // lane, channel (1 paused)

	// 2.3 dependencies
	DependencyDuration   *prometheus.HistogramVec // dependency, lane, status
	CircuitBreakerState  *prometheus.GaugeVec     // dependency
	BulkheadInUse        *prometheus.GaugeVec     // lane, channel, resource
	BulkheadCapacity     *prometheus.GaugeVec     // lane, channel, resource
	RateLimiterWait      *prometheus.HistogramVec // lane, channel
	RateLimiterTokens    *prometheus.GaugeVec     // lane, channel
	ProviderDailyQuota   *prometheus.GaugeVec     // provider (ratio)
	ContactCacheHits     *prometheus.CounterVec   // result=hit|miss
	FrequencyCapDegraded prometheus.Gauge
	RenderLocaleFallback *prometheus.CounterVec // template

	// 2.4 track
	SQSMessagesReceived       *prometheus.CounterVec // event_type
	SQSOldestMessageAge       prometheus.Gauge
	ProviderEventProcessed    *prometheus.CounterVec // event_type, outcome
	ProviderEventUnmatchedAge prometheus.Gauge
	SuppressionAdded          *prometheus.CounterVec // reason
	BounceTotal               *prometheus.CounterVec // lane, bounce_type
	ComplaintTotal            *prometheus.CounterVec // lane

	// 2.5 data & jobs
	PartitionFutureMonths *prometheus.GaugeVec   // table
	PurgeRowsDeleted      *prometheus.CounterVec // table
	OutboxUnpublished     prometheus.Gauge
	OutboxOldestAge       prometheus.Gauge
	TableDeadTupleRatio   *prometheus.GaugeVec // table
	InboxUnreadRecompute  prometheus.Counter
	OpsCallsTotal         *prometheus.CounterVec // operation, result
}

// New builds and registers every metric on a fresh registry.
func New() *Metrics {
	reg := prometheus.NewRegistry()
	f := promauto{reg}
	m := &Metrics{Registry: reg}

	m.IngestEventsTotal = f.counter("ingest_events_total", "Events received per decision", "lane", "source", "decision", "reason_code")
	m.IngestDuration = f.histogram("ingest_duration_seconds", "Poll to offset commit", []float64{.005, .01, .015, .025, .05, .1, .25, .5, 1, 2.5}, "lane")
	m.KafkaConsumerLag = f.gauge("kafka_consumer_lag", "Consumer lag", "lane", "topic", "partition")
	m.DecideStepRejections = f.counter("decide_step_rejections_total", "Decide step that rejected", "lane", "step", "reason_code")
	m.DeadLetterTotal = f.counter("dead_letter_total", "Messages parked on a DLQ topic", "topic", "reason")

	m.DeliveryPendingCount = f.gauge("delivery_pending_count", "Ready rows not yet claimed", "lane", "channel")
	m.DeliveryPendingAge = f.gauge("delivery_pending_age_seconds", "Age of the oldest ready row", "lane", "channel")
	m.DeliveryScheduledCount = f.gauge("delivery_scheduled_count", "Rows scheduled in the future", "lane")
	m.DeliveryClaimDuration = f.histogram("delivery_claim_duration_seconds", "Claim query", []float64{.001, .0025, .005, .01, .02, .05, .1, .25, .5, 1}, "lane")
	m.DeliveryAttemptsTotal = f.counter("delivery_attempts_total", "Attempts by outcome", "lane", "channel", "provider", "outcome")
	m.DeliverySendDuration = f.histogram("delivery_send_duration_seconds", "Provider call", []float64{.05, .1, .2, .5, 1, 2, 5, 10, 20}, "provider", "lane")
	m.DeliveryFinishedTotal = f.counter("delivery_finished_total", "Final status", "lane", "channel", "template", "final_status", "reason_code")
	m.DeliveryEndToEnd = f.histogram("delivery_end_to_end_seconds", "sent_at - request.received_at", []float64{.5, 1, 2, 5, 10, 30, 60, 300, 900, 3600}, "lane", "channel")
	m.DeliveryPossiblyDupTotal = f.counter("delivery_possibly_duplicate_total", "Resent after lease expiry", "lane")
	m.DeliveryLeaseExpired = f.counter("delivery_lease_expired_total", "Rows reaped back to RETRY", "lane")
	m.RetryBudgetRatio = f.gauge("retry_budget_ratio", "Retries / sends in the last minute", "lane")
	m.LanePaused = f.gauge("lane_paused", "1 when lane×channel is paused", "lane", "channel")

	m.DependencyDuration = f.histogram("dependency_request_duration_seconds", "Outbound call", []float64{.01, .025, .05, .1, .25, .5, 1, 2, 5, 10}, "dependency", "lane", "status")
	m.CircuitBreakerState = f.gauge("circuit_breaker_state", "0 closed, 1 half-open, 2 open", "dependency")
	m.BulkheadInUse = f.gauge("bulkhead_in_use", "Resources in use", "lane", "channel", "resource")
	m.BulkheadCapacity = f.gauge("bulkhead_capacity", "Resource capacity", "lane", "channel", "resource")
	m.RateLimiterWait = f.histogram("rate_limiter_wait_seconds", "Wait for a token", []float64{.001, .01, .05, .1, .5, 1, 2, 5}, "lane", "channel")
	m.RateLimiterTokens = f.gauge("rate_limiter_tokens_available", "Tokens available", "lane", "channel")
	m.ProviderDailyQuota = f.gauge("provider_daily_quota_used_ratio", "Daily sends / Max24HourSend", "provider")
	m.ContactCacheHits = f.counter("contact_cache_total", "Contact cache lookups", "result")
	m.FrequencyCapDegraded = f.plainGauge("frequency_cap_degraded", "1 while the frequency cap fails open")
	m.RenderLocaleFallback = f.counter("render_locale_fallback_total", "Locale fell back to vi", "template")

	m.SQSMessagesReceived = f.counter("sqs_messages_received_total", "SQS messages", "event_type")
	m.SQSOldestMessageAge = f.plainGauge("sqs_oldest_message_age_seconds", "Approximate age of oldest message")
	m.ProviderEventProcessed = f.counter("provider_event_processed_total", "Track outcomes", "event_type", "outcome")
	m.ProviderEventUnmatchedAge = f.plainGauge("provider_event_unmatched_age_seconds", "Oldest UNMATCHED")
	m.SuppressionAdded = f.counter("suppression_added_total", "Suppressions written", "reason")
	m.BounceTotal = f.counter("bounce_total", "Bounces", "lane", "bounce_type")
	m.ComplaintTotal = f.counter("complaint_total", "Complaints", "lane")

	m.PartitionFutureMonths = f.gauge("partition_future_months", "Future months with a partition", "table")
	m.PurgeRowsDeleted = f.counter("purge_rows_deleted_total", "Rows purged", "table")
	m.OutboxUnpublished = f.plainGauge("outbox_unpublished_count", "Unpublished outbox rows (poller mode)")
	m.OutboxOldestAge = f.plainGauge("outbox_oldest_age_seconds", "Oldest unpublished outbox row")
	m.TableDeadTupleRatio = f.gauge("table_dead_tuple_ratio", "Dead tuples / live", "table")
	m.InboxUnreadRecompute = f.plainCounter("inbox_unread_recompute_total", "Unread counter recomputed from DB")
	m.OpsCallsTotal = f.counter("ops_calls_total", "Ops API calls", "operation", "result")
	return m
}

type promauto struct{ reg prometheus.Registerer }

func (p promauto) counter(name, help string, labels ...string) *prometheus.CounterVec {
	c := prometheus.NewCounterVec(prometheus.CounterOpts{Namespace: namespace, Name: name, Help: help}, labels)
	p.reg.MustRegister(c)
	return c
}

func (p promauto) plainCounter(name, help string) prometheus.Counter {
	c := prometheus.NewCounter(prometheus.CounterOpts{Namespace: namespace, Name: name, Help: help})
	p.reg.MustRegister(c)
	return c
}

func (p promauto) gauge(name, help string, labels ...string) *prometheus.GaugeVec {
	g := prometheus.NewGaugeVec(prometheus.GaugeOpts{Namespace: namespace, Name: name, Help: help}, labels)
	p.reg.MustRegister(g)
	return g
}

func (p promauto) plainGauge(name, help string) prometheus.Gauge {
	g := prometheus.NewGauge(prometheus.GaugeOpts{Namespace: namespace, Name: name, Help: help})
	p.reg.MustRegister(g)
	return g
}

func (p promauto) histogram(name, help string, buckets []float64, labels ...string) *prometheus.HistogramVec {
	h := prometheus.NewHistogramVec(prometheus.HistogramOpts{Namespace: namespace, Name: name, Help: help, Buckets: buckets}, labels)
	p.reg.MustRegister(h)
	return h
}
