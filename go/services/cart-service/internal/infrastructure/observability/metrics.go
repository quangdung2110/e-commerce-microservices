// Package observability holds the logger and the metric registry.
//
// No metric here is labelled by user id or cart id: cart activity is
// behavioural data, and a per-user label would also blow up cardinality
// (docs/cart/operations.md §5). Bucket is a bounded label with 64 values and
// is safe.
package observability

import (
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"

	"cart-service/internal/infrastructure/product"
)

// Metrics holds every collector the service exports.
type Metrics struct {
	registry *prometheus.Registry

	scriptResults   *prometheus.CounterVec
	validationCache *prometheus.CounterVec
	productCalls    *prometheus.CounterVec
	breakerRejects  *prometheus.CounterVec

	markerOutcomes *prometheus.CounterVec
	markerOverdue  *prometheus.GaugeVec

	relayOutcomes *prometheus.CounterVec
	trimOutcomes  *prometheus.CounterVec
	trimmedTotal  *prometheus.CounterVec

	orderPaidOutcomes *prometheus.CounterVec
	orderPaidDeleted  prometheus.Counter

	userDeletedOutcomes *prometheus.CounterVec
}

// NewMetrics builds the registry and every collector.
func NewMetrics() *Metrics {
	registry := prometheus.NewRegistry()

	m := &Metrics{
		registry: registry,
		scriptResults: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "cart_script_results_total",
			Help: "Outcomes of the Redis Lua scripts, by script and result code.",
		}, []string{"script", "result"}),
		validationCache: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "cart_product_validation_cache_total",
			Help: "Hits and misses of the per-pod product validation cache.",
		}, []string{"outcome"}),
		productCalls: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "cart_product_calls_total",
			Help: "Product Service gRPC calls, by rpc, outcome (success, rejected, error) and ErrorInfo reason.",
		}, []string{"rpc", "outcome", "reason"}),
		breakerRejects: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "cart_product_breaker_rejections_total",
			Help: "Calls rejected because the Product circuit breaker was open.",
		}, []string{"rpc"}),
		markerOutcomes: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "cart_marker_outcomes_total",
			Help: "Abandoned marker outcomes, by bucket and outcome.",
		}, []string{"bucket", "outcome"}),
		markerOverdue: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: "cart_marker_overdue_age_seconds",
			Help: "Age of the oldest overdue abandonment deadline in a bucket.",
		}, []string{"bucket"}),
		relayOutcomes: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "cart_relay_outcomes_total",
			Help: "Outbox relay outcomes, by bucket and outcome.",
		}, []string{"bucket", "outcome"}),
		trimOutcomes: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "cart_trimmer_outcomes_total",
			Help: "Stream trimmer outcomes, by bucket and outcome.",
		}, []string{"bucket", "outcome"}),
		trimmedTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "cart_trimmed_entries_total",
			Help: "Outbox entries removed by the trimmer, by bucket.",
		}, []string{"bucket"}),
		orderPaidOutcomes: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "cart_order_paid_outcomes_total",
			Help: "ORDER_PAID handling outcomes.",
		}, []string{"outcome"}),
		orderPaidDeleted: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "cart_order_paid_deleted_items_total",
			Help: "Cart lines removed because an order was paid.",
		}),
		userDeletedOutcomes: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "cart_user_deleted_outcomes_total",
			Help: "USER_DELETED handling outcomes.",
		}, []string{"outcome"}),
	}

	registry.MustRegister(
		m.scriptResults,
		m.validationCache,
		m.productCalls,
		m.breakerRejects,
		m.markerOutcomes,
		m.markerOverdue,
		m.relayOutcomes,
		m.trimOutcomes,
		m.trimmedTotal,
		m.orderPaidOutcomes,
		m.orderPaidDeleted,
		m.userDeletedOutcomes,
	)

	return m
}

// Registry exposes the gatherer for the /metrics endpoint.
func (m *Metrics) Registry() *prometheus.Registry { return m.registry }

// ScriptResult records the outcome of one Lua invocation.
func (m *Metrics) ScriptResult(script, result string) {
	m.scriptResults.WithLabelValues(script, result).Inc()
}

// ObserveValidationCache records a product validation cache hit or miss.
func (m *Metrics) ObserveValidationCache(hit bool) {
	outcome := "miss"
	if hit {
		outcome = "hit"
	}
	m.validationCache.WithLabelValues(outcome).Inc()
}

// ObserveProductCall records a Product gRPC call outcome.
//
// "rejected" is Product refusing the request itself (identity, invalid argument), which says nothing about
// Product's health; "error" is Product or the path to it failing. reason is Product's ErrorInfo.reason
// (contracts ADR-006), empty when the status carried none.
func (m *Metrics) ObserveProductCall(rpc string, err error) {
	outcome := "success"
	if err != nil {
		outcome = "error"
		if product.CallerFault(err) {
			outcome = "rejected"
		}
	}
	m.productCalls.WithLabelValues(rpc, outcome, product.Reason(err)).Inc()
}

// ObserveBreakerRejection records a call the breaker refused.
func (m *Metrics) ObserveBreakerRejection(rpc string) {
	m.breakerRejects.WithLabelValues(rpc).Inc()
}

// MarkerMarked records a completed abandoned transition.
func (m *Metrics) MarkerMarked(bucket int) {
	m.markerOutcomes.WithLabelValues(label(bucket), "marked").Inc()
}

// MarkerSkipped records a transition the script refused.
func (m *Metrics) MarkerSkipped(bucket int, reason string) {
	if reason == "" {
		reason = "unknown"
	}
	m.markerOutcomes.WithLabelValues(label(bucket), "skipped_"+reason).Inc()
}

// MarkerError records a marker failure.
func (m *Metrics) MarkerError(bucket int) {
	m.markerOutcomes.WithLabelValues(label(bucket), "error").Inc()
}

// MarkerOverdueAge reports how far past its deadline a bucket's oldest
// unprocessed cart is. This is the marker's lag alert signal.
func (m *Metrics) MarkerOverdueAge(bucket int, age time.Duration) {
	m.markerOverdue.WithLabelValues(label(bucket)).Set(age.Seconds())
}

// RelayPublished records an entry that reached Kafka and was acked.
func (m *Metrics) RelayPublished(bucket int) {
	m.relayOutcomes.WithLabelValues(label(bucket), "published").Inc()
}

// RelayPublishRetry records a failed produce attempt.
func (m *Metrics) RelayPublishRetry(bucket int) {
	m.relayOutcomes.WithLabelValues(label(bucket), "publish_retry").Inc()
}

// RelayDeadLettered records an entry routed to the DLQ.
func (m *Metrics) RelayDeadLettered(bucket int) {
	m.relayOutcomes.WithLabelValues(label(bucket), "dead_lettered").Inc()
}

// RelayError records a relay failure by stage.
func (m *Metrics) RelayError(bucket int, stage string) {
	m.relayOutcomes.WithLabelValues(label(bucket), "error_"+stage).Inc()
}

// Trimmed records entries the trimmer removed.
func (m *Metrics) Trimmed(bucket int, removed int64) {
	m.trimOutcomes.WithLabelValues(label(bucket), "trimmed").Inc()
	m.trimmedTotal.WithLabelValues(label(bucket)).Add(float64(removed))
}

// TrimError records a trimmer failure.
func (m *Metrics) TrimError(bucket int) {
	m.trimOutcomes.WithLabelValues(label(bucket), "error").Inc()
}

// OrderPaidApplied records a successful cart cleanup.
func (m *Metrics) OrderPaidApplied(deleted int) {
	m.orderPaidOutcomes.WithLabelValues("applied").Inc()
	m.orderPaidDeleted.Add(float64(deleted))
}

// OrderPaidSkipped records a message Cart chose not to act on.
func (m *Metrics) OrderPaidSkipped(reason string) {
	m.orderPaidOutcomes.WithLabelValues("skipped_" + reason).Inc()
}

// OrderPaidError records a cleanup that failed and will be redelivered.
func (m *Metrics) OrderPaidError() {
	m.orderPaidOutcomes.WithLabelValues("error").Inc()
}

// UserDeletedApplied records a handled USER_DELETED. purged is false when the
// user had no cart (or it was already purged by a redelivery).
func (m *Metrics) UserDeletedApplied(purged bool) {
	outcome := "no_cart"
	if purged {
		outcome = "purged"
	}
	m.userDeletedOutcomes.WithLabelValues(outcome).Inc()
}

// UserDeletedSkipped records a message Cart chose not to act on.
func (m *Metrics) UserDeletedSkipped(reason string) {
	m.userDeletedOutcomes.WithLabelValues("skipped_" + reason).Inc()
}

// UserDeletedError records a purge that failed and will be redelivered.
func (m *Metrics) UserDeletedError() {
	m.userDeletedOutcomes.WithLabelValues("error").Inc()
}

func label(bucket int) string {
	return strconv.Itoa(bucket)
}
