//go:build integration

package integration

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/IBM/sarama"
	"github.com/aws/aws-sdk-go-v2/aws"
	awssqs "github.com/aws/aws-sdk-go-v2/service/sqs"
	sqstypes "github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"github.com/google/uuid"
	goredis "github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/require"
	"github.com/testcontainers/testcontainers-go"
	kafkamodule "github.com/testcontainers/testcontainers-go/modules/kafka"
	redismodule "github.com/testcontainers/testcontainers-go/modules/redis"
	"github.com/testcontainers/testcontainers-go/wait"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/catalog"
	"github.com/ecommerce/notification-service/internal/common/breaker"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/service/decide"
	"github.com/ecommerce/notification-service/internal/domain/service/deliver"
	"github.com/ecommerce/notification-service/internal/domain/service/ingest"
	"github.com/ecommerce/notification-service/internal/domain/service/lifecycle"
	"github.com/ecommerce/notification-service/internal/domain/service/preference"
	tracksvc "github.com/ecommerce/notification-service/internal/domain/service/track"
	"github.com/ecommerce/notification-service/internal/infrastructure/auth"
	"github.com/ecommerce/notification-service/internal/infrastructure/kafka"
	"github.com/ecommerce/notification-service/internal/infrastructure/kafka/handlers"
	"github.com/ecommerce/notification-service/internal/infrastructure/postgres"
	nredis "github.com/ecommerce/notification-service/internal/infrastructure/redis"
	"github.com/ecommerce/notification-service/internal/infrastructure/smtp"
	"github.com/ecommerce/notification-service/internal/infrastructure/sqs"
	"github.com/ecommerce/notification-service/internal/telemetry"
	"github.com/ecommerce/notification-service/internal/templates"
	deliverworker "github.com/ecommerce/notification-service/internal/workers/deliver"
	outboxworker "github.com/ecommerce/notification-service/internal/workers/outbox"
	"github.com/ecommerce/notification-service/internal/workers/reaper"
	trackworker "github.com/ecommerce/notification-service/internal/workers/track"
)

// The end-to-end harness runs the service's real components in-process —
// consumers, deliver pools, reaper, track worker, outbox poller — against
// testcontainers Kafka/Redis/Mailpit, the Postgres of TestMain and an
// httptest Auth. It is what 15-verification.md §2 calls the integration
// tier; each scenario is named after its IT-* id.

const (
	kafkaImage   = "confluentinc/confluent-local:7.5.0"
	redisImage   = "redis:7-alpine"
	mailpitImage = "axllent/mailpit:v1.21"
)

var (
	e2eOnce    sync.Once
	e2eShared  *e2eEnv
	e2eSetupErr error
)

// e2eEnv is the shared infrastructure + running components.
type e2eEnv struct {
	cfg      *config.Config
	brokers  []string
	redis    *goredis.Client
	keys     nredis.Keys
	mailpit  string // http base
	authSrv  *httptest.Server
	auth     *fakeAuth
	producer *kafka.Producer
	control  *postgres.Control
	contacts *auth.Client
	catalog  *catalog.Catalog
	keyring  *pii.Keyring
	sender   *gatedSender
	metrics  *telemetry.Metrics
	trackW   *trackworker.Worker
	outboxW  *outboxworker.Worker
	sqsQueue *fakeSQS
	poller   *sqs.Poller
	outcomes *outcomeSink

	cancel     context.CancelFunc
	wg         sync.WaitGroup
	containers []testcontainers.Container
}

func e2eTeardown() {
	if e2eShared == nil {
		return
	}
	e2eShared.cancel()
	e2eShared.wg.Wait()
	if e2eShared.producer != nil {
		_ = e2eShared.producer.Close()
	}
	if e2eShared.authSrv != nil {
		e2eShared.authSrv.Close()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	for _, c := range e2eShared.containers {
		if err := c.Terminate(ctx); err != nil {
			log.Printf("integration: terminate: %v", err)
		}
	}
}

func e2e(t *testing.T) *e2eEnv {
	t.Helper()
	e2eOnce.Do(func() { e2eShared, e2eSetupErr = startE2E() })
	if e2eSetupErr != nil {
		t.Fatalf("e2e setup: %v", e2eSetupErr)
	}
	return e2eShared
}

func startE2E() (*e2eEnv, error) {
	ctx := context.Background()
	env := &e2eEnv{auth: newFakeAuth(), sqsQueue: &fakeSQS{}, outcomes: newOutcomeSink()}

	kafkaC, err := kafkamodule.Run(ctx, kafkaImage, kafkamodule.WithClusterID("notification-e2e"))
	if err != nil {
		return nil, fmt.Errorf("kafka: %w", err)
	}
	env.containers = append(env.containers, kafkaC)
	if env.brokers, err = kafkaC.Brokers(ctx); err != nil {
		return nil, fmt.Errorf("kafka brokers: %w", err)
	}

	redisC, err := redismodule.Run(ctx, redisImage)
	if err != nil {
		return nil, fmt.Errorf("redis: %w", err)
	}
	env.containers = append(env.containers, redisC)
	redisHost, _ := redisC.Host(ctx)
	redisPort, _ := redisC.MappedPort(ctx, "6379/tcp")

	mailpitC, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: testcontainers.ContainerRequest{
			Image:        mailpitImage,
			ExposedPorts: []string{"1025/tcp", "8025/tcp"},
			Env:          map[string]string{"MP_SMTP_AUTH_ACCEPT_ANY": "1", "MP_SMTP_AUTH_ALLOW_INSECURE": "1"},
			WaitingFor:   wait.ForHTTP("/livez").WithPort("8025/tcp").WithStartupTimeout(time.Minute),
		},
		Started: true,
	})
	if err != nil {
		return nil, fmt.Errorf("mailpit: %w", err)
	}
	env.containers = append(env.containers, mailpitC)
	mpHost, _ := mailpitC.Host(ctx)
	mpSMTP, _ := mailpitC.MappedPort(ctx, "1025/tcp")
	mpHTTP, _ := mailpitC.MappedPort(ctx, "8025/tcp")
	env.mailpit = fmt.Sprintf("http://%s:%d", mpHost, mpHTTP.Num())

	env.authSrv = httptest.NewServer(env.auth)

	cfg, err := config.Load("")
	if err != nil {
		return nil, err
	}
	cfg.App.Env = "development"
	cfg.App.InstanceID = "e2e"
	cfg.Database = testDB
	cfg.Redis.Host, cfg.Redis.Port = redisHost, int(redisPort.Num())
	cfg.Kafka.Brokers = env.brokers
	cfg.Kafka.ConsumeMaxAttempts = 2
	cfg.Email.Provider = "smtp"
	cfg.Email.SMTP.Host, cfg.Email.SMTP.Port = mpHost, int(mpSMTP.Num())
	cfg.Email.UnsubscribeURL = "http://localhost:3000/profile/preferences"
	cfg.Auth.BaseURL = env.authSrv.URL
	cfg.Contact.CacheTTL = time.Minute
	cfg.Deliver.IdleMinSleep, cfg.Deliver.IdleMaxSleep = 20*time.Millisecond, 200*time.Millisecond
	cfg.Deliver.ControlPollInterval = 200 * time.Millisecond
	cfg.Deliver.UseListenNotify = false
	cfg.Deliver.RetryBase, cfg.Deliver.RetryJitter = 200*time.Millisecond, 50*time.Millisecond
	// Order ordering: fast heartbeat so a lost lease is noticed within a test,
	// short busy retry so a waiting delivery follows quickly.
	cfg.Deliver.OrderingHeartbeatInterval = time.Second
	cfg.Deliver.OrderingBusyRetry = 300 * time.Millisecond
	for _, l := range []*config.LaneConfig{&cfg.Lanes.Critical, &cfg.Lanes.Transactional, &cfg.Lanes.Bulk} {
		l.EmailWorkers, l.InAppWorkers, l.BatchSize = 1, 1, 5
		l.EmailQuotaPerSecond = 50
		l.MaxAttempts = 3
	}
	// Two transactional email workers: while one holds an Order provider call
	// open, the other proves a different order is not blocked (IT-ORD-01).
	cfg.Lanes.Transactional.EmailWorkers = 2
	// A one-minute quiet window that the wall clock practically never hits,
	// so bulk scenarios are deterministic regardless of when CI runs.
	cfg.Bulk.QuietFrom, cfg.Bulk.QuietTo = "03:17", "03:18"
	cfg.Track.VerifySignature = false
	cfg.Track.CorrelateProvider = "mailpit"
	cfg.Track.WorkerPoll = 100 * time.Millisecond
	cfg.Outbox.Publisher = "poller"
	cfg.Outbox.PollInterval = 100 * time.Millisecond
	env.cfg = cfg

	clk := clock.System{}
	logger := zap.NewNop()
	env.metrics = telemetry.New()
	if env.catalog, err = catalog.Load(); err != nil {
		return nil, err
	}
	if env.keyring, err = cfg.Keyring(); err != nil {
		return nil, err
	}
	render, err := templates.Load(env.catalog, env.metrics)
	if err != nil {
		return nil, err
	}
	env.redis = nredis.NewClient(cfg.Redis)
	env.keys = nredis.NewKeys(cfg.Redis.KeyPrefix)
	if err := env.redis.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("redis ping: %w", err)
	}

	if err := createTopics(cfg); err != nil {
		return nil, err
	}
	if env.producer, err = kafka.NewProducer(cfg.Kafka, logger); err != nil {
		return nil, err
	}

	store := testStore
	env.control = postgres.NewControl(store.Ops(), clk, cfg.Deliver.ControlPollInterval, logger)
	if env.contacts, err = auth.NewClient(cfg.Auth, cfg.Contact.CacheTTL, nredis.NewContactCache(env.redis, env.keys), env.metrics, logger, clk); err != nil {
		return nil, err
	}

	runCtx, cancel := context.WithCancel(ctx)
	env.cancel = cancel

	// ---- boundary 1: consumers ----
	decider := decide.New(decide.Deps{
		Catalog: env.catalog, FreqCap: nredis.NewFrequencyCap(env.redis, env.keys), Clock: clk,
		Policy: decide.Policy{QuietFrom: cfg.Bulk.QuietFrom, QuietTo: cfg.Bulk.QuietTo, DefaultTimezone: cfg.Bulk.DefaultTimezone,
			FrequencyCapPerDay: cfg.Bulk.FrequencyCapPerDay, DefaultLocale: "vi"},
		Metrics: env.metrics, Logger: logger,
	})
	deps := handlers.HandlerDeps{
		Ingest:      ingest.New(store, decider, env.catalog, env.keyring, env.control, clk, env.metrics, logger),
		Preferences: preference.New(store, env.keyring, clk, logger),
		Lifecycle: lifecycle.New(lifecycle.Deps{Store: store, Keyring: env.keyring, Contacts: env.contacts,
			FrequencyCap: nredis.NewFrequencyCap(env.redis, env.keys), UnreadCounter: nredis.NewUnreadCounter(env.redis, env.keys),
			Clock: clk, Logger: logger}),
		Catalog: env.catalog, Frontend: cfg.Frontend, Logger: logger, Clock: clk,
	}
	srcs := cfg.Kafka.Sources
	for _, s := range []struct {
		src  config.KafkaSourceConfig
		lane string
		h    kafka.MessageHandler
	}{
		{srcs.Critical, "critical", handlers.Request(deps, entity.LaneCritical)},
		{srcs.Auth, "critical", handlers.Auth(deps)},
		{srcs.Transactional, "transactional", handlers.Request(deps, entity.LaneTransactional)},
		{srcs.Review, "transactional", handlers.Review(deps)},
		{srcs.Bulk, "bulk", handlers.Request(deps, entity.LaneBulk)},
		{srcs.Cart, "bulk", handlers.Cart(deps)},
		{srcs.Preferences, "", handlers.Preferences(deps)},
		{srcs.UserLifecycle, "", handlers.UserLifecycle(deps)},
	} {
		c, err := kafka.NewConsumer(cfg.Kafka, s.src, s.lane, s.h, env.producer, env.metrics, logger)
		if err != nil {
			return nil, err
		}
		env.wg.Add(1)
		go func() { defer env.wg.Done(); _ = c.Run(runCtx) }()
	}

	// ---- boundary 2: deliver pools + reaper ----
	env.sender = newGatedSender(smtp.NewSender(cfg.Email, clk, env.metrics, logger))
	providerBreaker := breaker.New(cfg.Deliver.Breaker, clk)
	rates := map[entity.Lane]float64{}
	for _, lane := range entity.AllLanes {
		rates[lane] = cfg.Lanes.For(string(lane)).EmailQuotaPerSecond
	}
	limiter := nredis.NewTokenBucket(env.redis, env.keys, clk, rates, cfg.Deliver.LimiterFallbackReplicas, env.metrics, logger)
	svc := deliver.New(deliver.Deps{
		Store: store, Renderer: render, Email: env.sender, Contacts: env.contacts, Limiter: limiter,
		Daily: nredis.NewDailyQuota(env.redis, env.keys), RetryBudget: nredis.NewRetryBudget(env.redis, env.keys, clk, env.metrics, logger),
		Unread: nredis.NewUnreadCounter(env.redis, env.keys), Control: env.control, Breaker: providerBreaker,
		Catalog: env.catalog, Keyring: env.keyring, Clock: clk, Metrics: env.metrics, Logger: logger,
	}, deliver.Config{Lanes: cfg.Lanes, Deliver: cfg.Deliver, Email: cfg.Email, Frontend: cfg.Frontend, Bulk: cfg.Bulk, Templates: cfg.Templates})
	for _, lane := range entity.AllLanes {
		pool := deliverworker.NewPool(lane, deliverworker.Config{Lane: cfg.Lanes.For(string(lane)), Deliver: cfg.Deliver, InstanceID: "e2e", OtherLanesInProcess: true},
			deliverworker.Deps{Store: store, Processor: svc, Breaker: providerBreaker, Clock: clk, Metrics: env.metrics, Logger: logger})
		env.wg.Add(1)
		go func() { defer env.wg.Done(); pool.Run(runCtx) }()
	}
	r := reaper.New(store.Deliveries(), store.Ordering(), env.control, time.Second, time.Second, clk, env.metrics, logger)
	env.wg.Add(1)
	go func() { defer env.wg.Done(); r.Run(runCtx) }()

	// ---- track: fake SQS → real poller (signature verification off) → real worker ----
	verifier := sqs.NewVerifier(false, nil, cfg.Track.MaxClockSkew, clk, nil)
	env.poller = sqs.NewPoller(cfg.Track, env.sqsQueue, store, verifier, clk, env.metrics, logger)
	env.trackW = trackworker.New(cfg.Track, store, tracksvc.New(cfg.Track, clk, env.metrics, logger), clk, env.metrics, logger)
	env.wg.Add(1)
	go func() { defer env.wg.Done(); _ = env.trackW.Run(runCtx) }()

	// ---- outbox poller → outcomes topic → sink ----
	env.outboxW = outboxworker.New(cfg.Outbox, cfg.Kafka.Outcomes.Topic, time.Second, store, env.producer, clk, env.metrics, logger)
	env.wg.Add(1)
	go func() { defer env.wg.Done(); _ = env.outboxW.Run(runCtx) }()
	if err := env.outcomes.start(runCtx, &env.wg, cfg.Kafka.Brokers, cfg.Kafka.Outcomes.Topic); err != nil {
		return nil, err
	}
	return env, nil
}

func createTopics(cfg *config.Config) error {
	sc := sarama.NewConfig()
	sc.Version = sarama.V3_5_0_0
	admin, err := sarama.NewClusterAdmin(cfg.Kafka.Brokers, sc)
	if err != nil {
		return fmt.Errorf("kafka admin: %w", err)
	}
	defer admin.Close()
	s := cfg.Kafka.Sources
	for _, src := range []config.KafkaSourceConfig{s.Critical, s.Transactional, s.Bulk, s.Auth, s.Review, s.Cart, s.Preferences, s.UserLifecycle} {
		for _, topic := range []string{src.Topic, src.DLQ} {
			err := admin.CreateTopic(topic, &sarama.TopicDetail{NumPartitions: 1, ReplicationFactor: 1}, false)
			if err != nil && !strings.Contains(err.Error(), "already exists") {
				return fmt.Errorf("create topic %s: %w", topic, err)
			}
		}
	}
	err = admin.CreateTopic(cfg.Kafka.Outcomes.Topic, &sarama.TopicDetail{NumPartitions: 1, ReplicationFactor: 1}, false)
	if err != nil && !strings.Contains(err.Error(), "already exists") {
		return err
	}
	return nil
}

// ---- fake Auth internal API -----------------------------------------------

type fakeContact struct {
	Email    string
	Verified bool
}

type fakeContent struct {
	Email        string
	Template     string
	ExpiresAt    time.Time
	TemplateData map[string]any
}

type fakeAuth struct {
	mu       sync.Mutex
	contacts map[uuid.UUID]fakeContact
	contents map[uuid.UUID]fakeContent
	calls    map[string]int
}

func newFakeAuth() *fakeAuth {
	return &fakeAuth{contacts: map[uuid.UUID]fakeContact{}, contents: map[uuid.UUID]fakeContent{}, calls: map[string]int{}}
}

func (f *fakeAuth) setContact(user uuid.UUID, email string, verified bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.contacts[user] = fakeContact{Email: email, Verified: verified}
}

func (f *fakeAuth) setContent(deliveryID uuid.UUID, c fakeContent) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.contents[deliveryID] = c
}

func (f *fakeAuth) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	w.Header().Set("Cache-Control", "no-store")
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	// /internal/v1/auth/users/{id}/notification-contact
	if len(parts) == 6 && parts[3] == "users" && parts[5] == "notification-contact" && r.Method == http.MethodGet {
		f.calls["contact"]++
		id, err := uuid.Parse(parts[4])
		c, ok := f.contacts[id]
		if err != nil || !ok {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"user_id": id, "email": c.Email, "email_verified": c.Verified, "locale": nil})
		return
	}
	// /internal/v1/auth/notification-deliveries/{id}/content
	if len(parts) == 6 && parts[3] == "notification-deliveries" && parts[5] == "content" && r.Method == http.MethodPost {
		f.calls["content"]++
		id, err := uuid.Parse(parts[4])
		c, ok := f.contents[id]
		if err != nil || !ok {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"delivery_id": id, "recipient_email": c.Email, "template": c.Template,
			"expires_at": c.ExpiresAt.UTC().Format(time.RFC3339), "template_data": c.TemplateData})
		return
	}
	w.WriteHeader(http.StatusNotFound)
}

// ---- fake SQS ------------------------------------------------------------

type fakeSQS struct {
	mu   sync.Mutex
	msgs []sqstypes.Message
}

func (f *fakeSQS) push(body string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	handle := uuid.NewString()
	f.msgs = append(f.msgs, sqstypes.Message{
		MessageId: aws.String(uuid.NewString()), ReceiptHandle: aws.String(handle), Body: aws.String(body),
		Attributes: map[string]string{"SentTimestamp": fmt.Sprint(time.Now().UnixMilli())},
	})
}

func (f *fakeSQS) ReceiveMessage(_ context.Context, in *awssqs.ReceiveMessageInput, _ ...func(*awssqs.Options)) (*awssqs.ReceiveMessageOutput, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := int(in.MaxNumberOfMessages)
	if n <= 0 || n > len(f.msgs) {
		n = len(f.msgs)
	}
	out := &awssqs.ReceiveMessageOutput{Messages: append([]sqstypes.Message(nil), f.msgs[:n]...)}
	return out, nil
}

func (f *fakeSQS) DeleteMessage(_ context.Context, in *awssqs.DeleteMessageInput, _ ...func(*awssqs.Options)) (*awssqs.DeleteMessageOutput, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for i, m := range f.msgs {
		if aws.ToString(m.ReceiptHandle) == aws.ToString(in.ReceiptHandle) {
			f.msgs = append(f.msgs[:i], f.msgs[i+1:]...)
			break
		}
	}
	return &awssqs.DeleteMessageOutput{}, nil
}

// snsEnvelope wraps an SES event the way SNS → SQS delivers it (RawMessageDelivery=false).
func snsEnvelope(sesEvent []byte) string {
	env := map[string]any{
		"Type": "Notification", "MessageId": uuid.NewString(), "TopicArn": "arn:aws:sns:ap-southeast-1:000000000000:ses-events",
		"Message": string(sesEvent), "Timestamp": time.Now().UTC().Format("2006-01-02T15:04:05.000Z"),
		"SignatureVersion": "1", "Signature": "e2e", "SigningCertURL": "https://sns.ap-southeast-1.amazonaws.com/SimpleNotificationService-e2e.pem",
	}
	b, _ := json.Marshal(env)
	return string(b)
}

// sesEvent builds an SES event-publishing JSON of the given type for a
// delivery that was sent to `email` with the given provider message id.
func sesEvent(eventType string, messageID string, deliveryID uuid.UUID, email string, bounceType string) []byte {
	now := time.Now().UTC().Format("2006-01-02T15:04:05.000Z")
	ev := map[string]any{
		"eventType": eventType,
		"mail": map[string]any{
			"timestamp": now, "source": "no-reply@ecommerce.local", "messageId": messageID,
			"destination": []string{email},
			"tags":        map[string][]string{"ses:configuration-set": {"notification-critical-transactional"}, "delivery_id": {deliveryID.String()}},
		},
	}
	switch eventType {
	case "Delivery":
		ev["delivery"] = map[string]any{"timestamp": now, "recipients": []string{email}, "smtpResponse": "250 OK"}
	case "Bounce":
		ev["bounce"] = map[string]any{"bounceType": bounceType, "bounceSubType": "General", "timestamp": now,
			"bouncedRecipients": []map[string]any{{"emailAddress": email, "action": "failed", "status": "5.1.1", "diagnosticCode": "smtp; 550 5.1.1 user unknown"}}}
	case "Complaint":
		ev["complaint"] = map[string]any{"timestamp": now, "complaintFeedbackType": "abuse",
			"complainedRecipients": []map[string]any{{"emailAddress": email}}}
	}
	b, _ := json.Marshal(ev)
	return b
}

// ---- outcomes sink -------------------------------------------------------

type outcomeSink struct {
	mu     sync.Mutex
	events []entity.CloudEvent
}

func newOutcomeSink() *outcomeSink { return &outcomeSink{} }

func (s *outcomeSink) start(ctx context.Context, wg *sync.WaitGroup, brokers []string, topic string) error {
	sc := sarama.NewConfig()
	sc.Version = sarama.V3_5_0_0
	sc.Consumer.Offsets.Initial = sarama.OffsetOldest
	consumer, err := sarama.NewConsumer(brokers, sc)
	if err != nil {
		return err
	}
	pc, err := consumer.ConsumePartition(topic, 0, sarama.OffsetOldest)
	if err != nil {
		return err
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		defer consumer.Close()
		defer pc.Close()
		for {
			select {
			case <-ctx.Done():
				return
			case msg, ok := <-pc.Messages():
				if !ok {
					return
				}
				var ev entity.CloudEvent
				if json.Unmarshal(msg.Value, &ev) == nil {
					s.mu.Lock()
					s.events = append(s.events, ev)
					s.mu.Unlock()
				}
			}
		}
	}()
	return nil
}

// find returns outcome events of the given type for the given delivery id.
func (s *outcomeSink) find(eventType string, deliveryID uuid.UUID) []entity.CloudEvent {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []entity.CloudEvent
	for _, ev := range s.events {
		if ev.Type == eventType && ev.Subject == "deliveries/"+deliveryID.String() {
			out = append(out, ev)
		}
	}
	return out
}

// ---- Mailpit -------------------------------------------------------------

type mailpitMessage struct {
	ID      string `json:"ID"`
	Subject string `json:"Subject"`
	To      []struct {
		Address string `json:"Address"`
	} `json:"To"`
}

type mailpitDetail struct {
	ID      string            `json:"ID"`
	Subject string            `json:"Subject"`
	HTML    string            `json:"HTML"`
	Text    string            `json:"Text"`
	Headers map[string][]string
}

func (e *e2eEnv) mailpitMessagesTo(t *testing.T, email string) []mailpitMessage {
	t.Helper()
	resp, err := http.Get(e.mailpit + "/api/v1/messages?limit=500")
	require.NoError(t, err)
	defer resp.Body.Close()
	var body struct {
		Messages []mailpitMessage `json:"messages"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&body))
	var out []mailpitMessage
	for _, m := range body.Messages {
		for _, to := range m.To {
			if strings.EqualFold(to.Address, email) {
				out = append(out, m)
			}
		}
	}
	return out
}

func (e *e2eEnv) mailpitDetail(t *testing.T, id string) mailpitDetail {
	t.Helper()
	resp, err := http.Get(e.mailpit + "/api/v1/message/" + id)
	require.NoError(t, err)
	defer resp.Body.Close()
	var d mailpitDetail
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&d))
	hresp, err := http.Get(e.mailpit + "/api/v1/message/" + id + "/headers")
	require.NoError(t, err)
	defer hresp.Body.Close()
	raw, _ := io.ReadAll(hresp.Body)
	_ = json.Unmarshal(raw, &d.Headers)
	return d
}

// ---- DB helpers ----------------------------------------------------------

type requestRow struct {
	ID             uuid.UUID
	Decision       string
	DecisionReason *string
	Template       *string
}

func (e *e2eEnv) requestByEvent(t *testing.T, source, eventID string) *requestRow {
	t.Helper()
	var r requestRow
	err := testPool.QueryRow(context.Background(),
		`SELECT id, decision, decision_reason, template FROM notification_request WHERE source = $1 AND event_id = $2`, source, eventID).
		Scan(&r.ID, &r.Decision, &r.DecisionReason, &r.Template)
	if err != nil {
		return nil
	}
	return &r
}

func (e *e2eEnv) countRequests(t *testing.T, source, eventID string) int {
	t.Helper()
	var n int
	require.NoError(t, testPool.QueryRow(context.Background(),
		`SELECT count(*) FROM notification_request WHERE source = $1 AND event_id = $2`, source, eventID).Scan(&n))
	return n
}

func (e *e2eEnv) deliveries(t *testing.T, requestID uuid.UUID) map[entity.Channel]entity.Delivery {
	t.Helper()
	rows, err := testStore.Deliveries().ListByRequest(context.Background(), requestID)
	require.NoError(t, err)
	out := map[entity.Channel]entity.Delivery{}
	for _, d := range rows {
		out[d.Channel] = d
	}
	return out
}

// waitFor polls cond until it returns true or the timeout elapses.
func waitFor(t *testing.T, timeout time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

// waitDeliveryStatus waits until the delivery of the channel reaches one of the statuses.
func (e *e2eEnv) waitDeliveryStatus(t *testing.T, requestID uuid.UUID, ch entity.Channel, statuses ...entity.DeliveryStatus) entity.Delivery {
	t.Helper()
	var got entity.Delivery
	var last string
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		found := func() bool {
		d, ok := e.deliveries(t, requestID)[ch]
		if !ok {
			return false
		}
		last = string(d.Status)
		if d.SkipReason != nil {
			last += " skip=" + *d.SkipReason
		}
		if d.LastErrorCode != nil {
			last += " err=" + *d.LastErrorCode
		}
		for _, s := range statuses {
			if d.Status == s {
				got = d
				return true
			}
		}
		return false
		}()
		if found {
			return got
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for delivery %s of %s to reach %v (last seen: %s)", ch, requestID, statuses, last)
	return got
}

// waitRequest waits for the request row of an event and returns it.
func (e *e2eEnv) waitRequest(t *testing.T, source, eventID string) *requestRow {
	t.Helper()
	var r *requestRow
	waitFor(t, 30*time.Second, "request "+eventID, func() bool {
		r = e.requestByEvent(t, source, eventID)
		return r != nil
	})
	return r
}

// ---- event builders --------------------------------------------------------

func orderTemplateData(orderID string, name string) map[string]any {
	return map[string]any{
		"order_id": orderID, "order_code": "ORD-" + orderID, "placed_at": "2026-09-20T10:15:02Z",
		"items":          []map[string]any{{"name": name, "variant_name": "Đen / L", "quantity": 2, "unit_price_minor": 199000}},
		"subtotal_minor": 398000, "discount_minor": 0, "shipping_fee_minor": 30000, "total_minor": 428000, "currency": "VND",
		"payment_method_label": "Thẻ Visa •••• 4242",
		"shipping_address":     map[string]any{"recipient_name": "Nguyễn Văn A", "ward": "Phường 7", "district": "Quận 3", "city": "TP. Hồ Chí Minh"},
		"order_url":            "https://shop.example.com/orders/" + orderID,
	}
}

type requestOpts struct {
	id, template, dedupKey, orderID, subject string
	templateData                             map[string]any
	expiresAt                                *time.Time
	// sequence is the per-order notification sequence; 0 = next value of a
	// process-wide counter (monotonic, so it is also monotonic per order).
	sequence int64
}

// orderSequence feeds requestOpts.sequence when a test does not pin one.
var orderSequence atomic.Int64

func requestEvent(user uuid.UUID, o requestOpts) (string, []byte) {
	if o.id == "" {
		o.id = uuid.NewString()
	}
	if o.orderID == "" {
		o.orderID = fmt.Sprint(1000000 + time.Now().UnixNano()%8999999)
	}
	if o.template == "" {
		o.template = "ORDER_CONFIRMED"
	}
	if o.sequence == 0 {
		o.sequence = orderSequence.Add(1)
	}
	if o.subject == "" {
		o.subject = "orders/" + o.orderID
	}
	data := map[string]any{"template": o.template, "user_id": user.String(), "requested_at": time.Now().UTC().Format(time.RFC3339)}
	if o.dedupKey != "" {
		data["dedup_key"] = o.dedupKey
	}
	if o.templateData != nil {
		data["template_data"] = o.templateData
	} else {
		data["template_data"] = orderTemplateData(o.orderID, "Áo thun cotton")
	}
	if o.expiresAt != nil {
		data["expires_at"] = o.expiresAt.UTC().Format(time.RFC3339)
	}
	ev := map[string]any{
		"specversion": "1.0", "id": o.id, "type": "com.ecommerce.notification.requested.v1", "source": "/services/order",
		"subject": o.subject, "time": time.Now().UTC().Format(time.RFC3339), "datacontenttype": "application/json",
		"dataschema": "/contracts/events/notification/v1/schemas/notification-requested.schema.json",
		"correlationid": "e2e-" + o.id, "sequence": o.sequence, "data": data,
	}
	if o.sequence < 0 {
		delete(ev, "sequence") // IT-CTR-07: Order request without sequence
	}
	b, _ := json.Marshal(ev)
	return o.id, b
}

func cartEvent(user uuid.UUID, cartID string, cycle int) (string, []byte) {
	id := "cart-outbox:e2e:" + uuid.NewString()
	ev := map[string]any{
		"specversion": "1.0", "id": id, "type": "com.ecommerce.cart.abandoned.v1", "source": "/services/cart",
		"subject": "carts/" + cartID, "time": time.Now().UTC().Format(time.RFC3339), "datacontenttype": "application/json",
		"dataschema": "/contracts/events/cart/v1/schemas/cart-abandoned.schema.json",
		"data": map[string]any{"cart_id": cartID, "user_id": user.String(), "abandonment_cycle": cycle, "occurred_at": time.Now().UTC().Format(time.RFC3339)},
	}
	b, _ := json.Marshal(ev)
	return id, b
}

func authResetEvent(user, deliveryID uuid.UUID, expiresAt time.Time) (string, []byte) {
	id := uuid.NewString()
	ev := map[string]any{
		"specversion": "1.0", "id": id, "type": "com.ecommerce.auth.password-reset-requested.v1", "source": "/services/auth",
		"subject": "accounts/" + user.String(), "time": time.Now().UTC().Format(time.RFC3339), "datacontenttype": "application/json",
		"dataschema": "/contracts/events/auth/v1/schemas/password-reset-requested.schema.json", "correlationid": user.String(),
		"data": map[string]any{"delivery_id": deliveryID.String(), "user_id": user.String(),
			"requested_at": time.Now().UTC().Format(time.RFC3339), "expires_at": expiresAt.UTC().Format(time.RFC3339)},
	}
	b, _ := json.Marshal(ev)
	return id, b
}

func preferencesEvent(user uuid.UUID, email, inApp, marketing bool) []byte {
	ev := map[string]any{
		"specversion": "1.0", "id": uuid.NewString(), "type": "com.ecommerce.user.preferences-updated.v1", "source": "/services/user",
		"subject": "users/" + user.String(), "time": time.Now().UTC().Format(time.RFC3339), "datacontenttype": "application/json",
		"dataschema": "/contracts/events/user/v1/schemas/user-preferences-updated.schema.json", "correlationid": user.String(),
		"data": map[string]any{"user_id": user.String(), "email_enabled": email, "in_app_enabled": inApp, "marketing_enabled": marketing,
			"updated_at": time.Now().UTC().Format(time.RFC3339Nano)},
	}
	b, _ := json.Marshal(ev)
	return b
}

func userDeletedEvent(user uuid.UUID) []byte {
	ev := map[string]any{
		"specversion": "1.0", "id": uuid.NewString(), "type": "com.ecommerce.auth.user-deleted.v1", "source": "/services/auth",
		"subject": "accounts/" + user.String(), "time": time.Now().UTC().Format(time.RFC3339), "datacontenttype": "application/json",
		"dataschema": "/contracts/events/auth/v1/schemas/user-deleted.schema.json", "correlationid": user.String(),
		"data": map[string]any{"user_id": user.String(), "deleted_at": time.Now().UTC().Format(time.RFC3339), "reason": "GDPR_REQUEST",
			"hard_delete_after": time.Now().Add(90 * 24 * time.Hour).UTC().Format(time.RFC3339)},
	}
	b, _ := json.Marshal(ev)
	return b
}

func (e *e2eEnv) publish(t *testing.T, topic, key, ceID, ceType string, value []byte) {
	t.Helper()
	require.NoError(t, e.producer.PublishRaw(context.Background(), topic, key, ceID, ceType, value))
}

// setPreferences publishes a preferences event and waits until the replica shows it.
func (e *e2eEnv) setPreferences(t *testing.T, user uuid.UUID, email, inApp, marketing bool) {
	t.Helper()
	e.publish(t, e.cfg.Kafka.Sources.Preferences.Topic, user.String(), uuid.NewString(), "com.ecommerce.user.preferences-updated.v1", preferencesEvent(user, email, inApp, marketing))
	waitFor(t, 30*time.Second, "preference replica", func() bool {
		p, err := testStore.Preferences().Get(context.Background(), user)
		return err == nil && p.EmailEnabled == email && p.InAppEnabled == inApp && p.MarketingEnabled == marketing
	})
}

func newUser(e *e2eEnv) (uuid.UUID, string) {
	id := uuid.New()
	email := "u-" + strings.ReplaceAll(id.String(), "-", "")[:12] + "@example.com"
	e.auth.setContact(id, email, true)
	return id, email
}
