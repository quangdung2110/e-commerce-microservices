// Command server is the single Notification Service binary. Which goroutine
// groups it runs is decided by app.role (all | api | consumer | deliver |
// track | jobs) and app.lane (docs/notification/13-development.md §1):
//
//	api       public inbox listener (8080)
//	consumer  Kafka consumers of every source (boundary 1)
//	deliver   deliver pools per lane + reaper (boundary 2)
//	track     SQS poller, track worker, outbox poller
//	jobs      partition and purge jobs
//
// The internal listener (8081: health, metrics, status, ops) runs in every
// role so each deployment is observable. Bulkheads are per role: every role
// gets its own pgx pool sized by database.pools.*.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	obs "github.com/ecommerce/observability"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	goredis "github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/api"
	"github.com/ecommerce/notification-service/internal/api/handler"
	"github.com/ecommerce/notification-service/internal/api/router"
	"github.com/ecommerce/notification-service/internal/catalog"
	"github.com/ecommerce/notification-service/internal/common/breaker"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/common/logging"
	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/domain/service/decide"
	"github.com/ecommerce/notification-service/internal/domain/service/deliver"
	"github.com/ecommerce/notification-service/internal/domain/service/inbox"
	"github.com/ecommerce/notification-service/internal/domain/service/ingest"
	"github.com/ecommerce/notification-service/internal/domain/service/lifecycle"
	"github.com/ecommerce/notification-service/internal/domain/service/ops"
	"github.com/ecommerce/notification-service/internal/domain/service/preference"
	"github.com/ecommerce/notification-service/internal/domain/service/status"
	tracksvc "github.com/ecommerce/notification-service/internal/domain/service/track"
	"github.com/ecommerce/notification-service/internal/infrastructure/auth"
	"github.com/ecommerce/notification-service/internal/infrastructure/kafka"
	"github.com/ecommerce/notification-service/internal/infrastructure/kafka/handlers"
	"github.com/ecommerce/notification-service/internal/infrastructure/postgres"
	nredis "github.com/ecommerce/notification-service/internal/infrastructure/redis"
	"github.com/ecommerce/notification-service/internal/infrastructure/ses"
	"github.com/ecommerce/notification-service/internal/infrastructure/smtp"
	"github.com/ecommerce/notification-service/internal/infrastructure/sqs"
	"github.com/ecommerce/notification-service/internal/telemetry"
	"github.com/ecommerce/notification-service/internal/templates"
	deliverworker "github.com/ecommerce/notification-service/internal/workers/deliver"
	outboxworker "github.com/ecommerce/notification-service/internal/workers/outbox"
	"github.com/ecommerce/notification-service/internal/workers/partition"
	"github.com/ecommerce/notification-service/internal/workers/providerwatch"
	"github.com/ecommerce/notification-service/internal/workers/purge"
	"github.com/ecommerce/notification-service/internal/workers/reaper"
	trackworker "github.com/ecommerce/notification-service/internal/workers/track"
)

func main() {
	configPath := flag.String("config", "", "path to application.yaml (or NOTIFICATION_CONFIG_FILE)")
	flag.Parse()

	if err := run(*configPath); err != nil {
		fmt.Fprintln(os.Stderr, "notification-service:", err)
		os.Exit(1)
	}
}

// app holds everything shared between roles.
type app struct {
	cfg     *config.Config
	logger  *zap.Logger
	clk     clock.Clock
	metrics *telemetry.Metrics
	catalog *catalog.Catalog
	render  *templates.Registry
	keyring *pii.Keyring

	redis    *goredis.Client
	keys     nredis.Keys
	control  *postgres.Control
	contacts *auth.Client
	pools    []*pgxpool.Pool
	closers  []func() error
	ready    map[string]handler.ReadinessCheck

	wg      sync.WaitGroup
	errOnce sync.Once
	errCh   chan error
}

func run(configPath string) error {
	cfg, err := config.Load(configPath)
	if err != nil {
		return err
	}
	logger, err := logging.New(cfg.Logging.Level, cfg.Logging.Format, cfg.App.Name, cfg.App.Role)
	if err != nil {
		return err
	}
	defer func() { _ = logger.Sync() }()
	shutdownTelemetry, err := obs.Init("notification-service")
	if err != nil {
		return err
	}
	defer shutdownTelemetry()

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	a := &app{cfg: cfg, logger: logger, clk: clock.System{}, metrics: telemetry.New(),
		ready: map[string]handler.ReadinessCheck{}, errCh: make(chan error, 1)}
	defer a.closeAll()

	// Fail fast: catalog and every template must load before a single
	// consumer starts (12-render.md §4).
	if a.catalog, err = catalog.Load(); err != nil {
		return err
	}
	if a.render, err = templates.Load(a.catalog, a.metrics); err != nil {
		return err
	}
	// Privacy keyring: HMAC of Order subjects and deleted user ids (09-data-model.md §3, §10).
	if a.keyring, err = cfg.Keyring(); err != nil {
		return err
	}

	// Redis is shared by every role (cache, limiter, counters). Its absence
	// degrades gracefully (fail-closed limiter, fail-open cap), so a failed
	// ping is a warning, not a start-up error.
	a.redis = nredis.NewClient(cfg.Redis)
	a.keys = nredis.NewKeys(cfg.Redis.KeyPrefix)
	if err := a.redis.Ping(ctx).Err(); err != nil {
		logger.Warn("redis unreachable at start-up; limiter fails closed and caps fail open", zap.Error(err))
	}
	a.closers = append(a.closers, a.redis.Close)

	// Control switches (pause lane, replay mode) are read by consumers and
	// workers of every role through a small dedicated pool.
	ctlStore, err := a.store(ctx, 2, "control")
	if err != nil {
		return err
	}
	a.control = postgres.NewControl(ctlStore.Ops(), a.clk, cfg.Deliver.ControlPollInterval, logger)
	a.ready["postgres"] = ctlStore.Ping

	a.contacts, err = auth.NewClient(cfg.Auth, cfg.Contact.CacheTTL, nredis.NewContactCache(a.redis, a.keys), a.metrics, logger, a.clk)
	if err != nil {
		return err
	}

	if cfg.App.RunsRole(config.RoleConsumer) {
		if err := a.startConsumers(ctx); err != nil {
			return err
		}
	}
	if cfg.App.RunsRole(config.RoleDeliver) {
		if err := a.startDeliver(ctx); err != nil {
			return err
		}
	}
	if cfg.App.RunsRole(config.RoleTrack) {
		if err := a.startTrack(ctx); err != nil {
			return err
		}
	}
	if cfg.App.RunsRole(config.RoleJobs) {
		if err := a.startJobs(ctx); err != nil {
			return err
		}
	}
	// HTTP last so /readyz only turns green once the workers exist.
	if err := a.startHTTP(ctx); err != nil {
		return err
	}

	logger.Info("notification-service started", zap.String("role", cfg.App.Role), zap.String("lane", cfg.App.Lane),
		zap.Int("public_port", cfg.Server.Port), zap.Int("internal_port", cfg.Internal.Port))

	select {
	case <-ctx.Done():
		logger.Info("shutdown signal received")
	case err := <-a.errCh:
		logger.Error("component failed, shutting down", zap.Error(err))
		stop()
		a.wg.Wait()
		return err
	}
	stop()
	a.wg.Wait()
	return nil
}

// store opens a dedicated pool (bulkhead) and wraps it in a Store.
func (a *app) store(ctx context.Context, maxConns int32, name string) (*postgres.Store, error) {
	pool, err := postgres.NewPool(ctx, a.cfg.Database, maxConns, a.cfg.App.Name+"-"+name)
	if err != nil {
		return nil, fmt.Errorf("postgres pool %s: %w", name, err)
	}
	a.pools = append(a.pools, pool)
	return postgres.New(pool, a.clk, a.logger), nil
}

// spawn runs fn in the group; a returned error stops the process.
func (a *app) spawn(name string, fn func() error) {
	a.wg.Add(1)
	go func() {
		defer a.wg.Done()
		if err := fn(); err != nil && !errors.Is(err, context.Canceled) {
			a.errOnce.Do(func() { a.errCh <- fmt.Errorf("%s: %w", name, err) })
		}
	}()
}

func (a *app) closeAll() {
	for i := len(a.closers) - 1; i >= 0; i-- {
		_ = a.closers[i]()
	}
	for _, p := range a.pools {
		p.Close()
	}
}

// runsLanelessConsumers reports whether the lane-independent consumers
// (preferences, user lifecycle) belong to this process: every process when no
// lane is pinned, otherwise only the critical one so they run exactly once.
func (a *app) runsLanelessConsumers() bool {
	lane := a.cfg.App.Lane
	return lane == "" || lane == "-" || lane == string(entity.LaneCritical)
}

// ---------------------------------------------------------------------------
// consumer role (boundary 1)
// ---------------------------------------------------------------------------

func (a *app) startConsumers(ctx context.Context) error {
	cfg := a.cfg
	store, err := a.store(ctx, cfg.Database.Pools.Consumer, "consumer")
	if err != nil {
		return err
	}
	producer, err := kafka.NewProducer(cfg.Kafka, a.logger)
	if err != nil {
		return err
	}
	a.closers = append(a.closers, producer.Close)

	decider := decide.New(decide.Deps{
		Catalog: a.catalog,
		FreqCap: nredis.NewFrequencyCap(a.redis, a.keys),
		Clock:   a.clk,
		Policy: decide.Policy{
			QuietFrom: cfg.Bulk.QuietFrom, QuietTo: cfg.Bulk.QuietTo,
			DefaultTimezone: cfg.Bulk.DefaultTimezone, FrequencyCapPerDay: cfg.Bulk.FrequencyCapPerDay,
			DefaultLocale: cfg.Templates.DefaultLocale,
		},
		Metrics: a.metrics, Logger: a.logger,
	})
	deps := handlers.HandlerDeps{
		Ingest:      ingest.New(store, decider, a.catalog, a.keyring, a.control, a.clk, a.metrics, a.logger),
		Preferences: preference.New(store, a.keyring, a.clk, a.logger),
		Lifecycle: lifecycle.New(lifecycle.Deps{Keyring: a.keyring,
			Store: store, Contacts: a.contacts,
			FrequencyCap:  nredis.NewFrequencyCap(a.redis, a.keys),
			UnreadCounter: nredis.NewUnreadCounter(a.redis, a.keys),
			Clock:         a.clk, Logger: a.logger,
		}),
		Catalog: a.catalog, Frontend: cfg.Frontend, Logger: a.logger, Clock: a.clk,
	}

	type source struct {
		src     config.KafkaSourceConfig
		lane    string
		handler kafka.MessageHandler
		enabled bool
	}
	srcs := cfg.Kafka.Sources
	sources := []source{
		{srcs.Critical, "critical", handlers.Request(deps, entity.LaneCritical), cfg.App.RunsLane("critical")},
		{srcs.Auth, "critical", handlers.Auth(deps), cfg.App.RunsLane("critical")},
		{srcs.Transactional, "transactional", handlers.Request(deps, entity.LaneTransactional), cfg.App.RunsLane("transactional")},
		{srcs.Review, "transactional", handlers.Review(deps), cfg.App.RunsLane("transactional")},
		{srcs.Bulk, "bulk", handlers.Request(deps, entity.LaneBulk), cfg.App.RunsLane("bulk")},
		{srcs.Cart, "bulk", handlers.Cart(deps), cfg.App.RunsLane("bulk")},
		{srcs.Preferences, "", handlers.Preferences(deps), a.runsLanelessConsumers()},
		{srcs.UserLifecycle, "", handlers.UserLifecycle(deps), a.runsLanelessConsumers()},
	}
	started := 0
	for _, s := range sources {
		if !s.enabled || !s.src.Enabled {
			continue
		}
		c, err := kafka.NewConsumer(cfg.Kafka, s.src, s.lane, s.handler, producer, a.metrics, a.logger)
		if err != nil {
			return fmt.Errorf("consumer %s: %w", s.src.Group, err)
		}
		a.closers = append(a.closers, c.Close)
		name := "consumer " + s.src.Group
		a.spawn(name, func() error { return c.Run(ctx) })
		started++
	}
	a.logger.Info("consumers started", zap.Int("count", started))
	return nil
}

// ---------------------------------------------------------------------------
// deliver role (boundary 2)
// ---------------------------------------------------------------------------

func (a *app) startDeliver(ctx context.Context) error {
	cfg := a.cfg
	var sender port.EmailSender
	switch cfg.Email.Provider {
	case "ses":
		s, err := ses.NewSender(ctx, cfg.Email, a.clk, a.metrics, a.logger)
		if err != nil {
			return err
		}
		sender = s
	default:
		sender = smtp.NewSender(cfg.Email, a.clk, a.metrics, a.logger)
	}
	providerName := string(sender.Provider())
	// One breaker per provider, shared by every lane (06-backpressure.md §5).
	providerBreaker := breaker.New(cfg.Deliver.Breaker, a.clk, breaker.WithStateChange(func(state int) {
		a.metrics.CircuitBreakerState.WithLabelValues(providerName).Set(float64(state))
	}))

	rates := map[entity.Lane]float64{}
	for _, lane := range entity.AllLanes {
		rates[lane] = cfg.Lanes.For(string(lane)).EmailQuotaPerSecond
	}
	limiter := nredis.NewTokenBucket(a.redis, a.keys, a.clk, rates, cfg.Deliver.LimiterFallbackReplicas, a.metrics, a.logger)
	daily := nredis.NewDailyQuota(a.redis, a.keys)
	budget := nredis.NewRetryBudget(a.redis, a.keys, a.clk, a.metrics, a.logger)
	unread := nredis.NewUnreadCounter(a.redis, a.keys)

	var lanes []entity.Lane
	for _, lane := range entity.AllLanes {
		if cfg.App.RunsLane(string(lane)) {
			lanes = append(lanes, lane)
		}
	}
	var wake port.Wake
	if cfg.Deliver.UseListenNotify && cfg.App.RunsLane("critical") {
		wakePool, err := postgres.NewPool(ctx, cfg.Database, 1, cfg.App.Name+"-listen")
		if err != nil {
			return err
		}
		a.pools = append(a.pools, wakePool)
		wake = postgres.NewListener(ctx, wakePool, []entity.Lane{entity.LaneCritical}, a.logger)
	}

	var reaperStore *postgres.Store
	for _, lane := range lanes {
		store, err := a.store(ctx, cfg.Database.Pools.For(string(lane)), "deliver-"+string(lane))
		if err != nil {
			return err
		}
		if reaperStore == nil {
			reaperStore = store
		}
		svc := deliver.New(deliver.Deps{
			Store: store, Renderer: a.render, Email: sender, Contacts: a.contacts,
			Limiter: limiter, Daily: daily, RetryBudget: budget, Unread: unread,
			Control: a.control, Breaker: providerBreaker, Catalog: a.catalog, Keyring: a.keyring,
			Clock: a.clk, Metrics: a.metrics, Logger: a.logger,
		}, deliver.Config{
			Lanes: cfg.Lanes, Deliver: cfg.Deliver, Email: cfg.Email,
			Frontend: cfg.Frontend, Bulk: cfg.Bulk, Templates: cfg.Templates,
		})
		var laneWake port.Wake
		if lane == entity.LaneCritical {
			laneWake = wake
		}
		workers := deliverworker.NewPool(lane, deliverworker.Config{
			Lane: cfg.Lanes.For(string(lane)), Deliver: cfg.Deliver,
			InstanceID: cfg.App.InstanceID, OtherLanesInProcess: len(lanes) > 1,
		}, deliverworker.Deps{
			Store: store, Processor: svc, Breaker: providerBreaker, Wake: laneWake,
			Clock: a.clk, Metrics: a.metrics, Logger: a.logger,
		})
		lane := lane
		a.spawn("deliver "+string(lane), func() error { workers.Run(ctx); return nil })
	}
	if reaperStore != nil {
		r := reaper.New(reaperStore.Deliveries(), reaperStore.Ordering(), a.control, cfg.Deliver.ReaperInterval, cfg.Jobs.MetricsInterval, a.clk, a.metrics, a.logger)
		a.spawn("reaper", func() error { r.Run(ctx); return nil })

		// Provider watchdog (06-backpressure.md §5, D-23): health-probes a quiet
		// half-open breaker and pauses bulk email after a long outage.
		var health port.HealthChecker
		if hc, ok := sender.(port.HealthChecker); ok {
			health = hc
		}
		wd := providerwatch.New(providerwatch.Config{
			ProbeInterval:  cfg.Deliver.HealthProbeInterval,
			ProbeIdle:      cfg.Deliver.HealthProbeIdle,
			ProbeTimeout:   cfg.Email.ConnectTimeout + cfg.Email.SendTimeout,
			AutoPauseAfter: cfg.Deliver.BreakerAutoPauseAfter,
		}, providerwatch.Deps{
			Breaker: providerBreaker, Health: health, Lanes: a.control, Audit: reaperStore.Ops(),
			Provider: sender.Provider(), Clock: a.clk, Metrics: a.metrics, Logger: a.logger,
		})
		a.spawn("provider watchdog", func() error { wd.Run(ctx); return nil })
	}
	a.logger.Info("deliver pools started", zap.Int("lanes", len(lanes)), zap.String("provider", providerName))
	return nil
}

// ---------------------------------------------------------------------------
// track role
// ---------------------------------------------------------------------------

func (a *app) startTrack(ctx context.Context) error {
	cfg := a.cfg
	store, err := a.store(ctx, cfg.Database.Pools.Track, "track")
	if err != nil {
		return err
	}
	if cfg.Track.Enabled {
		client, err := sqs.NewClient(ctx, cfg.Track)
		if err != nil {
			return err
		}
		verifier := sqs.NewVerifier(cfg.Track.VerifySignature, cfg.Track.AllowedCertHosts, cfg.Track.MaxClockSkew, a.clk, nil)
		poller := sqs.NewPoller(cfg.Track, client, store, verifier, a.clk, a.metrics, a.logger)
		a.spawn("sqs poller", func() error { return poller.Run(ctx) })
	}
	worker := trackworker.New(cfg.Track, store, tracksvc.New(cfg.Track, a.clk, a.metrics, a.logger), a.clk, a.metrics, a.logger)
	a.spawn("track worker", func() error { return worker.Run(ctx) })

	var publisher port.EventPublisher
	if cfg.Outbox.Publisher == "poller" {
		producer, err := kafka.NewProducer(cfg.Kafka, a.logger)
		if err != nil {
			return err
		}
		a.closers = append(a.closers, producer.Close)
		publisher = producer
	}
	ob := outboxworker.New(cfg.Outbox, cfg.Kafka.Outcomes.Topic, cfg.Jobs.MetricsInterval, store, publisher, a.clk, a.metrics, a.logger)
	a.spawn("outbox", func() error { return ob.Run(ctx) })
	return nil
}

// ---------------------------------------------------------------------------
// jobs role
// ---------------------------------------------------------------------------

func (a *app) startJobs(ctx context.Context) error {
	cfg := a.cfg
	store, err := a.store(ctx, cfg.Database.Pools.Jobs, "jobs")
	if err != nil {
		return err
	}
	p := partition.New(store.Maintenance(), cfg.Jobs, a.clk, a.metrics, a.logger)
	a.spawn("partition", func() error { p.Run(ctx); return nil })
	pg := purge.New(store.Dedup(), store.Outbox(), store.Ops(), cfg.Jobs, cfg.Extensions.IdempotencyTTL, a.clk, a.metrics, a.logger)
	a.spawn("purge", func() error { pg.Run(ctx); return nil })
	return nil
}

// ---------------------------------------------------------------------------
// HTTP listeners
// ---------------------------------------------------------------------------

func (a *app) startHTTP(ctx context.Context) error {
	cfg := a.cfg
	store, err := a.store(ctx, cfg.Database.Pools.API, "api")
	if err != nil {
		return err
	}
	unread := nredis.NewUnreadCounter(a.redis, a.keys)

	statusSvc := status.New(store.Requests(), store.Deliveries())
	opsSvc := ops.New(store.Deliveries(), store.Suppressions(), a.control, a.contacts, a.clk, a.logger,
		ops.Config{ReplayModeDefaultTTL: cfg.Extensions.ReplayModeDefaultTTL}, ctx)
	health := handler.NewHealthHandler(a.ready, 3*time.Second)

	internal := router.NewInternal(router.InternalDeps{
		Config: cfg, Logger: a.logger, Clock: a.clk, Metrics: a.metrics, OpsRepo: store.Ops(),
		Status: handler.NewStatusHandler(statusSvc),
		Ops:    handler.NewOpsHandler(opsSvc, a.clk),
		Health: health,
	})
	a.serve("internal listener", ctx, api.NewInternalServer(cfg, internal))

	if cfg.App.RunsRole(config.RoleAPI) {
		inboxSvc := inbox.New(store.Inbox(), unread, a.clk, a.metrics)
		public := router.NewPublic(router.PublicDeps{Config: cfg, Logger: a.logger, Inbox: handler.NewInboxHandler(inboxSvc)})
		a.serve("public listener", ctx, api.NewPublicServer(cfg, public))
	}
	return nil
}

func (a *app) serve(name string, ctx context.Context, srv *http.Server) {
	a.spawn(name, func() error {
		err := srv.ListenAndServe()
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	})
	a.spawn(name+" shutdown", func() error {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), a.cfg.Server.ShutdownTimeout)
		defer cancel()
		return srv.Shutdown(shutdownCtx)
	})
}
