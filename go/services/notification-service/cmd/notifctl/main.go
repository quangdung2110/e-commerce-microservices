// Command notifctl is the dev/ops CLI of the Notification Service
// (docs/notification/13-development.md §5):
//
//	notifctl migrate up|down --dsn ...
//	notifctl render --template ORDER_CONFIRMED --data fixture.json [--out dir] [--send-to mailpit]
//	notifctl publish-example --file contracts/examples/events/notification-requested.v1.json [--brokers ...]
//	notifctl status --request <uuid> | --subject orders/1000234
//	notifctl lane <lane> pause|resume|show [--channel email|in_app] [--reason ...]
//	notifctl redrive <delivery_id> [--expires-at RFC3339]
//	notifctl partitions ensure [--months 2] --dsn ...
//
// The ops/status subcommands call the internal HTTP API (default
// http://localhost:8092) with X-Service-Identity, the allowlisted caller name the
// service checks in every environment; in a cluster the mesh authenticates the
// same calls (decisions.md D-21).
package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/catalog"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/common/constants"
	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/infrastructure/cloudevents"
	"github.com/ecommerce/notification-service/internal/infrastructure/kafka"
	"github.com/ecommerce/notification-service/internal/infrastructure/postgres"
	"github.com/ecommerce/notification-service/internal/infrastructure/smtp"
	"github.com/ecommerce/notification-service/internal/templates"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "migrate":
		err = cmdMigrate(os.Args[2:])
	case "render":
		err = cmdRender(os.Args[2:])
	case "publish-example":
		err = cmdPublishExample(os.Args[2:])
	case "status":
		err = cmdStatus(os.Args[2:])
	case "lane":
		err = cmdLane(os.Args[2:])
	case "redrive":
		err = cmdRedrive(os.Args[2:])
	case "partitions":
		err = cmdPartitions(os.Args[2:])
	case "help", "-h", "--help":
		usage()
	default:
		usage()
		err = fmt.Errorf("unknown command %q", os.Args[1])
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "notifctl:", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, `usage: notifctl <command> [flags]
  migrate up|down          apply / roll back one migration (--dsn)
  render                   render a template to files or Mailpit
  publish-example          publish a CloudEvents JSON file to the right lane topic
  status                   query the status API (--request | --subject)
  lane <lane> pause|resume|show
  redrive <delivery_id>
  partitions ensure        create monthly partitions (--dsn, --months)`)
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// ---- migrate ---------------------------------------------------------------

func cmdMigrate(args []string) error {
	if len(args) < 1 {
		return fmt.Errorf("migrate up|down")
	}
	fs := flag.NewFlagSet("migrate", flag.ContinueOnError)
	dsn := fs.String("dsn", envOr("NOTIFICATION_DATABASE_DSN", "postgres://postgres:postgres@localhost:5432/notification_db?sslmode=disable"), "postgres DSN")
	if err := fs.Parse(args[1:]); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	switch args[0] {
	case "up":
		if err := postgres.Migrate(ctx, *dsn); err != nil {
			return err
		}
		fmt.Println("migrations applied")
	case "down":
		if err := postgres.MigrateDown(ctx, *dsn); err != nil {
			return err
		}
		fmt.Println("one migration rolled back")
	default:
		return fmt.Errorf("migrate up|down")
	}
	return nil
}

// ---- render ----------------------------------------------------------------

func cmdRender(args []string) error {
	fs := flag.NewFlagSet("render", flag.ContinueOnError)
	template := fs.String("template", "", "template id, e.g. ORDER_CONFIRMED")
	locale := fs.String("locale", "vi", "locale")
	dataPath := fs.String("data", "", "template_data JSON file")
	out := fs.String("out", "", "output directory (email.html, email.txt, email.subject, inapp.json)")
	sendTo := fs.String("send-to", "", "mailpit → send through SMTP host:port (default localhost:1025)")
	to := fs.String("to", "preview@example.com", "recipient for --send-to")
	lane := fs.String("lane", "", "lane override for headers/unsubscribe (default: catalog lane)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *template == "" || *dataPath == "" {
		return fmt.Errorf("--template and --data are required")
	}
	cat, err := catalog.Load()
	if err != nil {
		return err
	}
	def, ok := cat.Get(*template)
	if !ok {
		return fmt.Errorf("unknown template %s", *template)
	}
	reg, err := templates.Load(cat, nil)
	if err != nil {
		return err
	}
	data, err := os.ReadFile(*dataPath)
	if err != nil {
		return err
	}
	l := def.Lane
	if *lane != "" {
		if l, err = entity.ParseLane(*lane); err != nil {
			return err
		}
	}
	opts := port.RenderOptions{DeliveryID: uuid.New(), Lane: l, UnsubscribeURL: "http://localhost:3000/profile/preferences"}
	email, err := reg.RenderEmail(def.ID, *locale, data, opts)
	if err != nil {
		return err
	}
	var inapp *entity.InAppContent
	if def.AllowsChannel(entity.ChannelInApp) {
		if inapp, err = reg.RenderInApp(def.ID, *locale, data, opts); err != nil {
			return err
		}
	}

	if *out != "" {
		if err := os.MkdirAll(*out, 0o755); err != nil {
			return err
		}
		files := map[string]string{"email.subject": email.Subject + "\n", "email.html": email.HTML, "email.txt": email.Text}
		if inapp != nil {
			b, _ := json.MarshalIndent(inapp, "", "  ")
			files["inapp.json"] = string(b) + "\n"
		}
		for name, content := range files {
			if err := os.WriteFile(filepath.Join(*out, name), []byte(content), 0o644); err != nil {
				return err
			}
		}
		fmt.Printf("rendered %s (version %s) → %s\n", def.ID, reg.Version(def.ID), *out)
	} else {
		fmt.Printf("Subject: %s\n\n%s\n", email.Subject, email.Text)
		if inapp != nil {
			fmt.Printf("--- in-app ---\n%s\n%s\n", inapp.Title, inapp.Body)
		}
	}

	if *sendTo != "" {
		cfg := config.EmailConfig{Provider: "smtp", FromAddress: "no-reply@ecommerce.local", FromName: "E-Commerce",
			ConnectTimeout: 5 * time.Second, SendTimeout: 15 * time.Second,
			SMTP: config.SMTPConfig{Host: "localhost", Port: 1025, MaxConnsPerLane: 1}}
		if host, port, ok := strings.Cut(*sendTo, ":"); ok && *sendTo != "mailpit" {
			cfg.SMTP.Host = host
			fmt.Sscanf(port, "%d", &cfg.SMTP.Port)
		}
		sender := smtp.NewSender(cfg, clock.System{}, nil, zap.NewNop())
		res, err := sender.Send(context.Background(), &entity.EmailMessage{
			DeliveryID: opts.DeliveryID, Lane: l, To: pii.MustEmail(*to), Content: *email,
		})
		if err != nil {
			return err
		}
		fmt.Printf("sent via %s (%s) — open http://localhost:8025\n", res.Provider, res.MessageID)
	}
	return nil
}

// ---- publish-example -------------------------------------------------------

func cmdPublishExample(args []string) error {
	fs := flag.NewFlagSet("publish-example", flag.ContinueOnError)
	file := fs.String("file", "", "CloudEvents JSON file")
	brokers := fs.String("brokers", envOr("NOTIFICATION_KAFKA_BROKERS", "localhost:9092"), "comma-separated brokers")
	topic := fs.String("topic", "", "override topic (default: by template lane / source type)")
	newID := fs.Bool("new-id", false, "replace the CloudEvents id with a fresh UUID (bypass ingest dedup)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *file == "" {
		return fmt.Errorf("--file is required")
	}
	raw, err := os.ReadFile(*file)
	if err != nil {
		return err
	}
	env, err := cloudevents.Parse(raw)
	if err != nil {
		return err
	}
	if *newID {
		var doc map[string]any
		if err := json.Unmarshal(raw, &doc); err != nil {
			return err
		}
		env.ID = uuid.NewString()
		doc["id"] = env.ID
		if raw, err = json.Marshal(doc); err != nil {
			return err
		}
	}
	cat, err := catalog.Load()
	if err != nil {
		return err
	}
	cfg := config.KafkaConfig{Brokers: strings.Split(*brokers, ","), ClientID: "notifctl", Version: "3.5.0"}
	dest := *topic
	key := env.Subject
	if dest == "" {
		switch {
		case env.Type == "com.ecommerce.notification.requested.v1":
			var data struct {
				Template string `json:"template"`
			}
			_ = json.Unmarshal(env.Data, &data)
			def, ok := cat.Get(data.Template)
			if !ok {
				return fmt.Errorf("template %q not in catalog; pass --topic", data.Template)
			}
			dest = "t.notification." + string(def.Lane) + ".v1"
		case strings.HasPrefix(env.Type, "com.ecommerce.cart."):
			dest = "t.cart.events"
		case strings.HasPrefix(env.Type, "com.ecommerce.review."):
			dest = "t.review.events"
		case strings.HasPrefix(env.Type, "com.ecommerce.user."):
			dest = "t.user.events"
		case env.Type == "com.ecommerce.auth.user-deleted.v1" || env.Type == "com.ecommerce.auth.user-verified-success.v1":
			dest = "t.auth.events"
		case strings.HasPrefix(env.Type, "com.ecommerce.auth."):
			dest = "t.auth.notification.events"
		default:
			return fmt.Errorf("cannot infer topic for type %s; pass --topic", env.Type)
		}
	}
	producer, err := kafka.NewProducer(cfg, zap.NewNop())
	if err != nil {
		return err
	}
	defer producer.Close()
	if err := producer.PublishRaw(context.Background(), dest, key, env.ID, env.Type, raw); err != nil {
		return err
	}
	fmt.Printf("published %s (id %s) → %s key=%s\n", env.Type, env.ID, dest, key)
	return nil
}

// ---- internal API helpers --------------------------------------------------

type apiClient struct {
	base     string
	identity string
	http     *http.Client
}

func newAPIClient(fs *flag.FlagSet) (*apiClient, func() error) {
	base := fs.String("base-url", envOr("NOTIFICATION_INTERNAL_URL", "http://localhost:8092"), "internal listener base URL")
	identity := fs.String("identity", "", "X-Service-Identity (default: notification-ops for ops, order-service for status)")
	c := &apiClient{http: &http.Client{Timeout: 15 * time.Second}}
	return c, func() error {
		c.base = strings.TrimRight(*base, "/")
		c.identity = *identity
		return nil
	}
}

func (c *apiClient) do(method, path string, body any, idempotent bool) (int, []byte, error) {
	var reader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return 0, nil, err
		}
		reader = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, c.base+path, reader)
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set(constants.HeaderServiceIdentity, c.identity)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", constants.ContentTypeJSON)
	}
	if idempotent {
		var buf [8]byte
		_, _ = rand.Read(buf[:])
		req.Header.Set(constants.HeaderIdempotencyKey, "notifctl-"+hex.EncodeToString(buf[:]))
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	out, err := io.ReadAll(resp.Body)
	return resp.StatusCode, out, err
}

func printJSON(status int, body []byte) error {
	var pretty bytes.Buffer
	if json.Indent(&pretty, body, "", "  ") == nil {
		fmt.Println(pretty.String())
	} else if len(body) > 0 {
		fmt.Println(string(body))
	}
	if status >= 400 {
		return fmt.Errorf("HTTP %d", status)
	}
	return nil
}

// ---- status ----------------------------------------------------------------

func cmdStatus(args []string) error {
	fs := flag.NewFlagSet("status", flag.ContinueOnError)
	request := fs.String("request", "", "request id")
	subject := fs.String("subject", "", "aggregate subject, e.g. orders/1000234")
	template := fs.String("template", "", "filter by template (with --subject)")
	client, finish := newAPIClient(fs)
	if err := fs.Parse(args); err != nil {
		return err
	}
	_ = finish()
	if client.identity == "" {
		client.identity = "order-service"
	}
	switch {
	case *request != "":
		status, body, err := client.do(http.MethodGet, "/internal/v1/notifications/requests/"+*request, nil, false)
		if err != nil {
			return err
		}
		return printJSON(status, body)
	case *subject != "":
		q := "?subject=" + *subject
		if *template != "" {
			q += "&template=" + *template
		}
		status, body, err := client.do(http.MethodGet, "/internal/v1/notifications/deliveries"+q, nil, false)
		if err != nil {
			return err
		}
		return printJSON(status, body)
	}
	return fmt.Errorf("--request or --subject is required")
}

// ---- lane ------------------------------------------------------------------

func cmdLane(args []string) error {
	if len(args) < 2 {
		return fmt.Errorf("lane <critical|transactional|bulk> pause|resume|show")
	}
	lane, action := args[0], args[1]
	if _, err := entity.ParseLane(lane); err != nil {
		return err
	}
	fs := flag.NewFlagSet("lane", flag.ContinueOnError)
	channel := fs.String("channel", "", "email|in_app (empty = both)")
	reason := fs.String("reason", "", "audit reason")
	client, finish := newAPIClient(fs)
	if err := fs.Parse(args[2:]); err != nil {
		return err
	}
	_ = finish()
	if client.identity == "" {
		client.identity = "notification-ops"
	}
	path := "/internal/v1/notifications/ops/lanes/" + lane
	switch action {
	case "show":
		status, body, err := client.do(http.MethodGet, path, nil, false)
		if err != nil {
			return err
		}
		return printJSON(status, body)
	case "pause", "resume":
		state := "paused"
		if action == "resume" {
			state = "active"
		}
		body := map[string]any{"state": state}
		if *channel != "" {
			body["channel"] = *channel
		}
		if *reason != "" {
			body["reason"] = *reason
		}
		status, out, err := client.do(http.MethodPut, path, body, true)
		if err != nil {
			return err
		}
		return printJSON(status, out)
	}
	return fmt.Errorf("lane action must be pause|resume|show")
}

// ---- redrive ---------------------------------------------------------------

func cmdRedrive(args []string) error {
	if len(args) < 1 {
		return fmt.Errorf("redrive <delivery_id>")
	}
	id, err := uuid.Parse(args[0])
	if err != nil {
		return fmt.Errorf("delivery id: %w", err)
	}
	fs := flag.NewFlagSet("redrive", flag.ContinueOnError)
	expires := fs.String("expires-at", "", "new expires_at (RFC3339) when the delivery is already expired")
	reason := fs.String("reason", "", "audit reason")
	client, finish := newAPIClient(fs)
	if err := fs.Parse(args[1:]); err != nil {
		return err
	}
	_ = finish()
	if client.identity == "" {
		client.identity = "notification-ops"
	}
	body := map[string]any{}
	if *expires != "" {
		if _, err := time.Parse(time.RFC3339, *expires); err != nil {
			return fmt.Errorf("--expires-at: %w", err)
		}
		body["expires_at"] = *expires
	}
	if *reason != "" {
		body["reason"] = *reason
	}
	status, out, err := client.do(http.MethodPost, "/internal/v1/notifications/ops/deliveries/"+id.String()+"/redrive", body, true)
	if err != nil {
		return err
	}
	return printJSON(status, out)
}

// ---- partitions ------------------------------------------------------------

func cmdPartitions(args []string) error {
	if len(args) < 1 || args[0] != "ensure" {
		return fmt.Errorf("partitions ensure [--months N] --dsn ...")
	}
	fs := flag.NewFlagSet("partitions", flag.ContinueOnError)
	dsn := fs.String("dsn", envOr("NOTIFICATION_DATABASE_DSN", "postgres://postgres:postgres@localhost:5432/notification_db?sslmode=disable"), "postgres DSN")
	months := fs.Int("months", 2, "months ahead of the current one")
	if err := fs.Parse(args[1:]); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	cfg := config.DatabaseConfig{ConnectTimeout: 5 * time.Second}
	pool, err := postgres.NewPoolDSN(ctx, *dsn, cfg, 1, "notifctl")
	if err != nil {
		return err
	}
	defer pool.Close()
	store := postgres.New(pool, clock.System{}, zap.NewNop())
	if err := store.Maintenance().EnsurePartitions(ctx, time.Now().UTC(), *months); err != nil {
		return err
	}
	future, err := store.Maintenance().FutureMonths(ctx, time.Now().UTC())
	if err != nil {
		return err
	}
	for table, n := range future {
		fmt.Printf("%-24s future months: %d\n", table, n)
	}
	return nil
}
