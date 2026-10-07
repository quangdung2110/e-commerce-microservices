package decide

import (
	"context"
	"encoding/json"
	"errors"
	"strconv"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/prometheus/client_golang/prometheus"
	dto "github.com/prometheus/client_model/go"

	"github.com/ecommerce/notification-service/internal/catalog"
	"github.com/ecommerce/notification-service/internal/common/clock"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// ---- fakes ---------------------------------------------------------------

type fakePrefs struct {
	rows map[uuid.UUID]entity.Preference
	err  error
}

func (f *fakePrefs) Get(_ context.Context, id uuid.UUID) (*entity.Preference, error) {
	if f.err != nil {
		return nil, f.err
	}
	p, ok := f.rows[id]
	if !ok {
		return nil, apperr.ErrNotFound
	}
	return &p, nil
}

type fakeSupp struct {
	rows []entity.Suppression
	err  error
}

func (f *fakeSupp) ActiveByUser(_ context.Context, _ uuid.UUID) ([]entity.Suppression, error) {
	return f.rows, f.err
}

type fakeDedup struct {
	claimed map[string]bool
	calls   []string
	err     error
}

func (f *fakeDedup) ClaimDelivery(_ context.Context, key string, ch entity.Channel) (bool, error) {
	if f.err != nil {
		return false, f.err
	}
	k := key + "|" + string(ch)
	f.calls = append(f.calls, k)
	if f.claimed == nil {
		f.claimed = map[string]bool{}
	}
	if f.claimed[k] {
		return false, nil
	}
	f.claimed[k] = true
	return true, nil
}

type fakeFreq struct {
	counts map[string]int64
	calls  int
	ok     bool
	err    error
}

func (f *fakeFreq) Increment(_ context.Context, id uuid.UUID, day string) (int64, bool, error) {
	f.calls++
	if f.err != nil || !f.ok {
		return 0, f.ok, f.err
	}
	if f.counts == nil {
		f.counts = map[string]int64{}
	}
	k := id.String() + ":" + day
	f.counts[k]++
	return f.counts[k], true, nil
}

func (f *fakeFreq) Forget(context.Context, uuid.UUID) error { return nil }

// ---- helpers -------------------------------------------------------------

var (
	userID = uuid.MustParse("0f6d3a8e-6a4e-4d0d-9c3b-2b1e6f7a8c9d")
	// 2026-09-20T10:15:30Z = 17:15:30 Asia/Ho_Chi_Minh (outside quiet hours).
	baseNow = time.Date(2026, 9, 20, 10, 15, 30, 0, time.UTC)
	hcm     = mustLoc("Asia/Ho_Chi_Minh")
)

func mustLoc(name string) *time.Location {
	l, err := time.LoadLocation(name)
	if err != nil {
		panic(err)
	}
	return l
}

func mustCatalog(t *testing.T) *catalog.Catalog {
	t.Helper()
	c, err := catalog.Load()
	if err != nil {
		t.Fatalf("catalog: %v", err)
	}
	return c
}

func orderConfirmedData() json.RawMessage {
	return json.RawMessage(`{"order_id":"1000234","order_code":"ORD-1000234","placed_at":"2026-09-20T10:15:02Z","items":[{"name":"Áo thun","quantity":2,"unit_price_minor":199000}],"subtotal_minor":398000,"discount_minor":0,"shipping_fee_minor":30000,"total_minor":428000,"currency":"VND","payment_method_label":"Visa","shipping_address":{"recipient_name":"A","ward":"P7","district":"Q3","city":"HCM"},"order_url":"https://shop.example.com/orders/1000234"}`)
}

func orderConfirmed() entity.IngestInput {
	return entity.IngestInput{
		Source: "/services/order", EventID: "evt-1", EventType: "com.ecommerce.notification.requested.v1",
		Subject: "orders/1000234", InputKind: entity.InputRequest, Lane: entity.LaneTransactional,
		Template: "ORDER_CONFIRMED", UserID: userID.String(), DedupKey: "order_confirmed:1000234",
		TemplateData: orderConfirmedData(),
	}
}

func cartAbandoned() entity.IngestInput {
	return entity.IngestInput{
		Source: "/services/cart", EventID: "evt-c", EventType: "com.ecommerce.cart.abandoned.v1",
		Subject: "carts/c1", InputKind: entity.InputFact, Lane: entity.LaneBulk,
		Template: "CART_ABANDONED", UserID: userID.String(), DedupKey: "cart_abandoned:c1:1",
		TemplateData: json.RawMessage(`{"cart_id":"c1","abandonment_cycle":1,"cart_url":"http://localhost:3000/cart"}`),
	}
}

func passwordReset(exp *time.Time) entity.IngestInput {
	return entity.IngestInput{
		Source: "/services/auth", EventID: "evt-a", EventType: "com.ecommerce.auth.password-reset-requested.v1",
		Subject: "accounts/x", InputKind: entity.InputRequest, Lane: entity.LaneCritical,
		Template: "PASSWORD_RESET", UserID: userID.String(), ExpiresAt: exp, SourceRef: "d1",
	}
}

func emailChanged() entity.IngestInput {
	return entity.IngestInput{
		Source: "/services/auth", EventID: "evt-e", EventType: "com.ecommerce.auth.user-email-changed.v1",
		InputKind: entity.InputRequest, Lane: entity.LaneCritical, Template: "EMAIL_CHANGED",
		UserID: userID.String(), DedupKey: "email_changed:d1",
		TemplateData: json.RawMessage(`{"event":"EMAIL_CHANGED","changed_at":"2026-09-20T10:00:00Z"}`),
	}
}

func ptr[T any](v T) *T { return &v }

type env struct {
	d       *Decider
	prefs   *fakePrefs
	supp    *fakeSupp
	dedup   *fakeDedup
	freq    *fakeFreq
	clock   *clock.Fake
	metrics *telemetry.Metrics
}

func newEnv(t *testing.T, now time.Time) *env {
	t.Helper()
	e := &env{
		prefs:   &fakePrefs{rows: map[uuid.UUID]entity.Preference{}},
		supp:    &fakeSupp{},
		dedup:   &fakeDedup{},
		freq:    &fakeFreq{ok: true},
		clock:   clock.NewFake(now),
		metrics: telemetry.New(),
	}
	e.d = New(Deps{
		Catalog: mustCatalog(t), FreqCap: e.freq, Clock: e.clock, Metrics: e.metrics,
		Policy: Policy{QuietFrom: "22:00", QuietTo: "08:00", DefaultTimezone: "Asia/Ho_Chi_Minh", FrequencyCapPerDay: 2, DefaultLocale: "vi"},
	}).WithRepos(e.prefs, e.supp, e.dedup)
	return e
}

func (e *env) setPref(p entity.Preference) {
	p.UserID = userID
	e.prefs.rows[userID] = p
}

func channelMap(res entity.DecideResult) map[entity.Channel]entity.ChannelDecision {
	m := map[entity.Channel]entity.ChannelDecision{}
	for _, c := range res.Channels {
		m[c.Channel] = c
	}
	return m
}

// ---- steps 1–6 -----------------------------------------------------------

func TestDecide_ProducerErrorsAndExpiry(t *testing.T) {
	past := baseNow.Add(-time.Minute)
	cases := []struct {
		name     string
		in       func() entity.IngestInput
		decision entity.Decision
		reason   string
		step     int
	}{
		{"unmapped fact → IGNORED before step 1", func() entity.IngestInput {
			in := cartAbandoned()
			in.Template = ""
			return in
		}, entity.DecisionIgnored, entity.ReasonUnmappedFact, 1},
		{"step 1 unknown template", func() entity.IngestInput {
			in := orderConfirmed()
			in.Template = "NOPE"
			return in
		}, entity.DecisionFailed, entity.ReasonUnknownTemplate, 1},
		{"step 1 request with empty template", func() entity.IngestInput {
			in := orderConfirmed()
			in.Template = ""
			return in
		}, entity.DecisionFailed, entity.ReasonUnknownTemplate, 1},
		{"step 2 lane mismatch", func() entity.IngestInput {
			in := orderConfirmed()
			in.Lane = entity.LaneBulk
			return in
		}, entity.DecisionFailed, entity.ReasonLaneMismatch, 2},
		{"step 3 template_data invalid", func() entity.IngestInput {
			in := orderConfirmed()
			in.TemplateData = json.RawMessage(`{"order_id":"1"}`)
			return in
		}, entity.DecisionFailed, entity.ReasonInvalidTemplateData, 3},
		{"step 3 template_data not json", func() entity.IngestInput {
			in := orderConfirmed()
			in.TemplateData = json.RawMessage(`{`)
			return in
		}, entity.DecisionFailed, entity.ReasonInvalidTemplateData, 3},
		{"step 3 nil template_data for non-secret", func() entity.IngestInput {
			in := orderConfirmed()
			in.TemplateData = nil
			return in
		}, entity.DecisionFailed, entity.ReasonInvalidTemplateData, 3},
		{"step 4 missing dedup_key", func() entity.IngestInput {
			in := orderConfirmed()
			in.DedupKey = ""
			return in
		}, entity.DecisionFailed, entity.ReasonMissingDedupKey, 4},
		{"step 5 user_id not uuid", func() entity.IngestInput {
			in := orderConfirmed()
			in.UserID = "user-42"
			return in
		}, entity.DecisionFailed, entity.ReasonNoRecipient, 5},
		{"step 5 user_id empty", func() entity.IngestInput {
			in := orderConfirmed()
			in.UserID = ""
			return in
		}, entity.DecisionFailed, entity.ReasonNoRecipient, 5},
		{"step 6 already expired", func() entity.IngestInput {
			in := orderConfirmed()
			in.ExpiresAt = &past
			return in
		}, entity.DecisionExpired, entity.ReasonAlreadyExpired, 6},
		{"step 6 auth template without expires_at", func() entity.IngestInput {
			return passwordReset(nil)
		}, entity.DecisionFailed, entity.ReasonInvalidTemplateData, 6},
		{"step 6 auth template already expired", func() entity.IngestInput {
			return passwordReset(&past)
		}, entity.DecisionExpired, entity.ReasonAlreadyExpired, 6},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			e := newEnv(t, baseNow)
			res, err := e.d.Decide(context.Background(), tc.in())
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if res.Decision != tc.decision || res.Reason != tc.reason || res.Step != tc.step {
				t.Fatalf("got %s/%s step %d, want %s/%s step %d", res.Decision, res.Reason, res.Step, tc.decision, tc.reason, tc.step)
			}
			if len(res.Channels) != 0 {
				t.Fatalf("no channel decisions expected, got %d", len(res.Channels))
			}
			if e.freq.calls != 0 || len(e.dedup.calls) != 0 {
				t.Fatalf("no side effects expected before step 7")
			}
			if got := metricValue(t, e.metrics.DecideStepRejections.WithLabelValues(string(tc.in().Lane), itoa(tc.step), tc.reason)); got != 1 {
				t.Fatalf("decide_step_rejections = %v", got)
			}
		})
	}
}

func itoa(i int) string { return strconv.Itoa(i) }

// ---- §9 example and dedup ------------------------------------------------

func TestDecide_Example08Section9(t *testing.T) {
	e := newEnv(t, baseNow)
	in := orderConfirmed()
	exp := time.Date(2026, 9, 20, 16, 15, 0, 0, time.UTC) // request 16:15 < now+6h (16:15:30)
	in.ExpiresAt = &exp

	res, err := e.d.Decide(context.Background(), in)
	if err != nil {
		t.Fatal(err)
	}
	if res.Decision != entity.DecisionDecided || res.Step != 0 || res.Reason != "" {
		t.Fatalf("got %+v", res)
	}
	if !res.EffectiveExpiresAt.Equal(exp) {
		t.Fatalf("effective_expires_at = %v, want %v", res.EffectiveExpiresAt, exp)
	}
	if res.UserID != userID {
		t.Fatalf("user id not carried")
	}
	if len(res.Kept()) != 2 || len(res.Channels) != 2 {
		t.Fatalf("expected email + in_app kept: %+v", res.Channels)
	}
	for _, c := range res.Channels {
		if !c.NextAttemptAt.Equal(baseNow) {
			t.Fatalf("next_attempt_at = %v, want now", c.NextAttemptAt)
		}
	}
	if e.freq.calls != 0 {
		t.Fatalf("transactional must not touch the frequency cap")
	}
	if len(e.dedup.calls) != 2 {
		t.Fatalf("dedup claims = %v", e.dedup.calls)
	}

	// Second arrival (Order replayed with a new event id): steps 1–11 pass,
	// step 12 conflicts on both channels → SKIPPED (duplicate), no alert.
	in.EventID = "evt-2"
	res, err = e.d.Decide(context.Background(), in)
	if err != nil {
		t.Fatal(err)
	}
	if res.Decision != entity.DecisionSkipped || res.Reason != entity.ReasonDuplicate || res.Step != 12 {
		t.Fatalf("second arrival: %+v", res)
	}
	if len(res.Kept()) != 0 || len(res.Channels) != 2 {
		t.Fatalf("expected two SKIPPED channel rows: %+v", res.Channels)
	}
	for _, c := range res.Channels {
		if c.SkipReason != entity.ReasonDuplicate {
			t.Fatalf("channel %s skip reason %q", c.Channel, c.SkipReason)
		}
	}
}

func TestDecide_DedupPairSemantics(t *testing.T) {
	t.Run("one channel already claimed → other still goes", func(t *testing.T) {
		e := newEnv(t, baseNow)
		e.dedup.claimed = map[string]bool{"order_confirmed:1000234|in_app": true}
		res, err := e.d.Decide(context.Background(), orderConfirmed())
		if err != nil {
			t.Fatal(err)
		}
		if res.Decision != entity.DecisionDecided {
			t.Fatalf("got %+v", res)
		}
		m := channelMap(res)
		if !m[entity.ChannelEmail].Keep || m[entity.ChannelInApp].Keep || m[entity.ChannelInApp].SkipReason != entity.ReasonDuplicate {
			t.Fatalf("channels: %+v", res.Channels)
		}
	})
	t.Run("dedup mode none ignores a producer key", func(t *testing.T) {
		e := newEnv(t, baseNow)
		in := passwordReset(ptr(baseNow.Add(15 * time.Minute)))
		in.DedupKey = "otp:should-be-ignored"
		res, err := e.d.Decide(context.Background(), in)
		if err != nil {
			t.Fatal(err)
		}
		if res.Decision != entity.DecisionDecided || len(e.dedup.calls) != 0 {
			t.Fatalf("got %+v dedup calls %v", res, e.dedup.calls)
		}
		if !res.EffectiveExpiresAt.Equal(baseNow.Add(15 * time.Minute)) {
			t.Fatalf("auth expiry must be used as is: %v", res.EffectiveExpiresAt)
		}
	})
	t.Run("dedup only claimed for kept channels", func(t *testing.T) {
		e := newEnv(t, baseNow)
		e.setPref(entity.Preference{EmailEnabled: false, InAppEnabled: true})
		res, err := e.d.Decide(context.Background(), orderConfirmed())
		if err != nil {
			t.Fatal(err)
		}
		if res.Decision != entity.DecisionDecided || len(e.dedup.calls) != 1 || e.dedup.calls[0] != "order_confirmed:1000234|in_app" {
			t.Fatalf("got %+v calls %v", res, e.dedup.calls)
		}
	})
	t.Run("mixed preference_off + duplicate → all_channels_off", func(t *testing.T) {
		e := newEnv(t, baseNow)
		e.setPref(entity.Preference{EmailEnabled: false, InAppEnabled: true})
		e.dedup.claimed = map[string]bool{"order_confirmed:1000234|in_app": true}
		res, err := e.d.Decide(context.Background(), orderConfirmed())
		if err != nil {
			t.Fatal(err)
		}
		if res.Decision != entity.DecisionSkipped || res.Reason != entity.ReasonAllChannelsOff || res.Step != 12 {
			t.Fatalf("got %+v", res)
		}
	})
	t.Run("dedup repository error propagates", func(t *testing.T) {
		e := newEnv(t, baseNow)
		e.dedup.err = errors.New("pg down")
		if _, err := e.d.Decide(context.Background(), orderConfirmed()); err == nil {
			t.Fatal("expected error")
		}
	})
}

// ---- steps 7–9: preferences and suppression (IT-PREF-01..04, IT-SUPP-01) --

func TestDecide_Preferences(t *testing.T) {
	cases := []struct {
		name     string
		pref     *entity.Preference
		in       entity.IngestInput
		decision entity.Decision
		reason   string
		step     int
		email    string // "" kept, else skip reason; "-" absent
		inApp    string
	}{
		{"IT-PREF-01 email off, ORDER_CONFIRMED → in-app only", ptr(entity.Preference{EmailEnabled: false, InAppEnabled: true}),
			orderConfirmed(), entity.DecisionDecided, "", 0, entity.ReasonPreferenceOff, ""},
		{"IT-PREF-02 email off, PASSWORD_RESET (critical) → email still sent", ptr(entity.Preference{EmailEnabled: false, InAppEnabled: true}),
			passwordReset(ptr(baseNow.Add(15 * time.Minute))), entity.DecisionDecided, "", 0, "", "-"},
		{"critical warning template ignores both flags", ptr(entity.Preference{EmailEnabled: false, InAppEnabled: false}),
			emailChanged(), entity.DecisionDecided, "", 0, "", ""},
		{"IT-PREF-03 marketing off, CART_ABANDONED → all_channels_off", ptr(entity.Preference{EmailEnabled: true, InAppEnabled: true, MarketingEnabled: false}),
			cartAbandoned(), entity.DecisionSkipped, entity.ReasonAllChannelsOff, 8, entity.ReasonPreferenceOff, entity.ReasonPreferenceOff},
		{"IT-PREF-04a no preference row, ORDER_CONFIRMED → both", nil,
			orderConfirmed(), entity.DecisionDecided, "", 0, "", ""},
		{"IT-PREF-04b no preference row, CART_ABANDONED → SKIPPED (marketing default off)", nil,
			cartAbandoned(), entity.DecisionSkipped, entity.ReasonAllChannelsOff, 8, entity.ReasonPreferenceOff, entity.ReasonPreferenceOff},
		{"bulk marketing on, email off → in-app only", ptr(entity.Preference{EmailEnabled: false, InAppEnabled: true, MarketingEnabled: true}),
			cartAbandoned(), entity.DecisionDecided, "", 0, entity.ReasonPreferenceOff, ""},
		{"in_app off transactional → email only", ptr(entity.Preference{EmailEnabled: true, InAppEnabled: false}),
			orderConfirmed(), entity.DecisionDecided, "", 0, "", entity.ReasonPreferenceOff},
		{"both off transactional → all_channels_off", ptr(entity.Preference{EmailEnabled: false, InAppEnabled: false}),
			orderConfirmed(), entity.DecisionSkipped, entity.ReasonAllChannelsOff, 8, entity.ReasonPreferenceOff, entity.ReasonPreferenceOff},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			e := newEnv(t, baseNow)
			if tc.pref != nil {
				e.setPref(*tc.pref)
			}
			res, err := e.d.Decide(context.Background(), tc.in)
			if err != nil {
				t.Fatal(err)
			}
			if res.Decision != tc.decision || res.Reason != tc.reason || res.Step != tc.step {
				t.Fatalf("got %s/%s step %d, want %s/%s step %d", res.Decision, res.Reason, res.Step, tc.decision, tc.reason, tc.step)
			}
			m := channelMap(res)
			checkChannel(t, m, entity.ChannelEmail, tc.email)
			checkChannel(t, m, entity.ChannelInApp, tc.inApp)
			if tc.decision == entity.DecisionSkipped && e.freq.calls != 0 {
				t.Fatalf("frequency cap must not be consumed when every channel is off")
			}
		})
	}
}

func checkChannel(t *testing.T, m map[entity.Channel]entity.ChannelDecision, ch entity.Channel, want string) {
	t.Helper()
	c, ok := m[ch]
	if want == "-" {
		if ok {
			t.Fatalf("channel %s must not appear (not in template.channels)", ch)
		}
		return
	}
	if !ok {
		t.Fatalf("channel %s missing from decisions", ch)
	}
	if want == "" {
		if !c.Keep {
			t.Fatalf("channel %s expected kept, got skip %q", ch, c.SkipReason)
		}
		return
	}
	if c.Keep || c.SkipReason != want {
		t.Fatalf("channel %s expected skipped %q, got keep=%v reason=%q", ch, want, c.Keep, c.SkipReason)
	}
}

func TestDecide_Suppression(t *testing.T) {
	active := entity.Suppression{ID: uuid.New(), UserID: ptr(userID), EmailHash: "h", Reason: entity.SuppressionHardBounce}
	expired := active
	expired.Until = ptr(baseNow.Add(-time.Hour))
	superseded := active
	superseded.SupersededAt = ptr(baseNow.Add(-time.Hour))
	future := active
	future.Until = ptr(baseNow.Add(time.Hour))

	cases := []struct {
		name  string
		rows  []entity.Suppression
		pref  *entity.Preference
		in    entity.IngestInput
		dec   entity.Decision
		step  int
		email string
		inApp string
	}{
		{"IT-SUPP-01: suppression is enforced by the worker by hash, Decide keeps the email channel", []entity.Suppression{active}, nil, orderConfirmed(), entity.DecisionDecided, 0, "", ""},
		{"until in future still active (still kept at Decide)", []entity.Suppression{future}, nil, orderConfirmed(), entity.DecisionDecided, 0, "", ""},
		{"expired suppression ignored", []entity.Suppression{expired}, nil, orderConfirmed(), entity.DecisionDecided, 0, "", ""},
		{"superseded suppression ignored", []entity.Suppression{superseded}, nil, orderConfirmed(), entity.DecisionDecided, 0, "", ""},
		{"critical still tries email", []entity.Suppression{active}, nil, passwordReset(ptr(baseNow.Add(15 * time.Minute))), entity.DecisionDecided, 0, "", "-"},
		{"suppressed + in_app off → email still created for the worker check", []entity.Suppression{active}, ptr(entity.Preference{EmailEnabled: true, InAppEnabled: false}), orderConfirmed(), entity.DecisionDecided, 0, "", entity.ReasonPreferenceOff},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			e := newEnv(t, baseNow)
			e.supp.rows = tc.rows
			if tc.pref != nil {
				e.setPref(*tc.pref)
			}
			res, err := e.d.Decide(context.Background(), tc.in)
			if err != nil {
				t.Fatal(err)
			}
			if res.Decision != tc.dec || res.Step != tc.step {
				t.Fatalf("got %s/%s step %d", res.Decision, res.Reason, res.Step)
			}
			m := channelMap(res)
			checkChannel(t, m, entity.ChannelEmail, tc.email)
			checkChannel(t, m, entity.ChannelInApp, tc.inApp)
		})
	}

	t.Run("suppression not queried when email already off", func(t *testing.T) {
		e := newEnv(t, baseNow)
		e.supp.err = errors.New("must not be called")
		e.setPref(entity.Preference{EmailEnabled: false, InAppEnabled: true})
		if _, err := e.d.Decide(context.Background(), orderConfirmed()); err != nil {
			t.Fatalf("unexpected: %v", err)
		}
	})
	t.Run("preference repository error propagates", func(t *testing.T) {
		e := newEnv(t, baseNow)
		e.prefs.err = errors.New("pg down")
		if _, err := e.d.Decide(context.Background(), orderConfirmed()); err == nil {
			t.Fatal("expected error")
		}
	})
}

// ---- step 10: frequency cap (IT-PREF-06, IT-PREF-07) -----------------------

func TestDecide_FrequencyCap(t *testing.T) {
	e := newEnv(t, baseNow)
	e.setPref(entity.Preference{EmailEnabled: true, InAppEnabled: true, MarketingEnabled: true})
	for i := 1; i <= 3; i++ {
		in := cartAbandoned()
		in.DedupKey = "cart_abandoned:c1:" + itoa(i)
		res, err := e.d.Decide(context.Background(), in)
		if err != nil {
			t.Fatal(err)
		}
		if i <= 2 && res.Decision != entity.DecisionDecided {
			t.Fatalf("bulk #%d: %+v", i, res)
		}
		if i == 3 && (res.Decision != entity.DecisionSkipped || res.Reason != entity.ReasonFrequencyCap || res.Step != 10 || len(res.Channels) != 0) {
			t.Fatalf("bulk #3: %+v", res)
		}
	}
	if e.freq.calls != 3 {
		t.Fatalf("freq calls = %d", e.freq.calls)
	}
	if got := metricValue(t, e.metrics.FrequencyCapDegraded); got != 0 {
		t.Fatalf("degraded gauge = %v", got)
	}

	t.Run("IT-PREF-07 Redis down → fail-open + degraded gauge", func(t *testing.T) {
		e := newEnv(t, baseNow)
		e.setPref(entity.Preference{EmailEnabled: true, InAppEnabled: true, MarketingEnabled: true})
		e.freq.ok = false
		res, err := e.d.Decide(context.Background(), cartAbandoned())
		if err != nil {
			t.Fatal(err)
		}
		if res.Decision != entity.DecisionDecided || len(res.Kept()) != 2 {
			t.Fatalf("fail-open expected: %+v", res)
		}
		if got := metricValue(t, e.metrics.FrequencyCapDegraded); got != 1 {
			t.Fatalf("degraded gauge = %v", got)
		}
	})
	t.Run("local date uses user timezone", func(t *testing.T) {
		// 2026-09-20T18:30Z is 2026-09-21 01:30 in Ho Chi Minh (outside quiet hours? no: 01:30 is quiet)
		// so use 2026-09-20T17:30Z = 00:30 next day HCM? also quiet. Use Pacific/Auckland (+12): 10:15Z = 22:15 → quiet.
		// Take America/Sao_Paulo (-3): 2026-09-21T01:00Z = 2026-09-20 22:00 → quiet. Use Asia/Tokyo (+9): 2026-09-20T16:30Z = 01:30 next day → quiet.
		// Simplest: 2026-09-20T23:30Z with Asia/Ho_Chi_Minh is 06:30 next day → quiet as well; choose 2026-09-20T02:00Z = 09:00 HCM, day 2026-09-20; UTC date also 09-20.
		// To prove the tz matters use 2026-09-20T16:59Z → HCM 23:59 (quiet) vs Europe/London 17:59 (not quiet) — date same. Use 2026-09-21T00:30Z with Pacific/Honolulu (-10) → 2026-09-20 14:30.
		e := newEnv(t, time.Date(2026, 9, 21, 0, 30, 0, 0, time.UTC))
		e.setPref(entity.Preference{EmailEnabled: true, InAppEnabled: true, MarketingEnabled: true, Timezone: ptr("Pacific/Honolulu")})
		if _, err := e.d.Decide(context.Background(), cartAbandoned()); err != nil {
			t.Fatal(err)
		}
		if _, ok := e.freq.counts[userID.String()+":2026-09-20"]; !ok {
			t.Fatalf("expected local date 2026-09-20, got %v", e.freq.counts)
		}
	})
}

// ---- step 11: quiet hours (IT-PREF-05) -------------------------------------

func TestDecide_QuietHours(t *testing.T) {
	cases := []struct {
		name     string
		localNow time.Time // in HCM
		wantNext time.Time // in HCM
	}{
		{"23:00 → 08:00 next day", time.Date(2026, 9, 20, 23, 0, 0, 0, hcm), time.Date(2026, 9, 21, 8, 0, 0, 0, hcm)},
		{"22:00 boundary is inside → next day", time.Date(2026, 9, 20, 22, 0, 0, 0, hcm), time.Date(2026, 9, 21, 8, 0, 0, 0, hcm)},
		{"00:30 → 08:00 same day", time.Date(2026, 9, 21, 0, 30, 0, 0, hcm), time.Date(2026, 9, 21, 8, 0, 0, 0, hcm)},
		{"07:59 → 08:00 same day", time.Date(2026, 9, 21, 7, 59, 0, 0, hcm), time.Date(2026, 9, 21, 8, 0, 0, 0, hcm)},
		{"08:00 boundary is outside → now", time.Date(2026, 9, 21, 8, 0, 0, 0, hcm), time.Date(2026, 9, 21, 8, 0, 0, 0, hcm)},
		{"21:59 → now", time.Date(2026, 9, 20, 21, 59, 0, 0, hcm), time.Date(2026, 9, 20, 21, 59, 0, 0, hcm)},
		{"midday → now", time.Date(2026, 9, 20, 12, 0, 0, 0, hcm), time.Date(2026, 9, 20, 12, 0, 0, 0, hcm)},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			e := newEnv(t, tc.localNow)
			e.setPref(entity.Preference{EmailEnabled: true, InAppEnabled: true, MarketingEnabled: true, Timezone: ptr("Asia/Ho_Chi_Minh")})
			res, err := e.d.Decide(context.Background(), cartAbandoned())
			if err != nil {
				t.Fatal(err)
			}
			if res.Decision != entity.DecisionDecided {
				t.Fatalf("got %+v", res)
			}
			for _, c := range res.Channels {
				if !c.NextAttemptAt.Equal(tc.wantNext) {
					t.Fatalf("%s next_attempt_at = %v, want %v", c.Channel, c.NextAttemptAt.In(hcm), tc.wantNext)
				}
				if c.NextAttemptAt.Location() != time.UTC {
					t.Fatalf("next_attempt_at must be UTC")
				}
			}
		})
	}

	t.Run("IT-PREF-05 default timezone when preference has none", func(t *testing.T) {
		now := time.Date(2026, 9, 20, 23, 0, 0, 0, hcm)
		e := newEnv(t, now)
		e.setPref(entity.Preference{EmailEnabled: true, InAppEnabled: true, MarketingEnabled: true})
		res, err := e.d.Decide(context.Background(), cartAbandoned())
		if err != nil {
			t.Fatal(err)
		}
		want := time.Date(2026, 9, 21, 8, 0, 0, 0, hcm)
		if res.Decision != entity.DecisionDecided || !res.Channels[0].NextAttemptAt.Equal(want) {
			t.Fatalf("got %+v", res)
		}
	})
	t.Run("quiet hours past expiry → EXPIRED, no rows", func(t *testing.T) {
		now := time.Date(2026, 9, 20, 23, 0, 0, 0, hcm)
		e := newEnv(t, now)
		e.setPref(entity.Preference{EmailEnabled: true, InAppEnabled: true, MarketingEnabled: true})
		in := cartAbandoned()
		in.ExpiresAt = ptr(now.Add(2 * time.Hour))
		res, err := e.d.Decide(context.Background(), in)
		if err != nil {
			t.Fatal(err)
		}
		if res.Decision != entity.DecisionExpired || res.Reason != entity.ReasonQuietHoursPastExpiry || res.Step != 11 || len(res.Channels) != 0 {
			t.Fatalf("got %+v", res)
		}
		if len(e.dedup.calls) != 0 {
			t.Fatalf("dedup must not be claimed for an expired request")
		}
	})
	t.Run("transactional ignores quiet hours", func(t *testing.T) {
		now := time.Date(2026, 9, 20, 23, 0, 0, 0, hcm)
		e := newEnv(t, now)
		res, err := e.d.Decide(context.Background(), orderConfirmed())
		if err != nil {
			t.Fatal(err)
		}
		if !res.Channels[0].NextAttemptAt.Equal(now) {
			t.Fatalf("next_attempt_at = %v", res.Channels[0].NextAttemptAt)
		}
	})
	t.Run("unknown timezone falls back to default", func(t *testing.T) {
		now := time.Date(2026, 9, 20, 23, 0, 0, 0, hcm)
		e := newEnv(t, now)
		e.setPref(entity.Preference{EmailEnabled: true, InAppEnabled: true, MarketingEnabled: true, Timezone: ptr("Mars/Olympus")})
		res, err := e.d.Decide(context.Background(), cartAbandoned())
		if err != nil {
			t.Fatal(err)
		}
		want := time.Date(2026, 9, 21, 8, 0, 0, 0, hcm)
		if !res.Channels[0].NextAttemptAt.Equal(want) {
			t.Fatalf("got %v", res.Channels[0].NextAttemptAt.In(hcm))
		}
	})
}

func TestNextQuietHoursEnd(t *testing.T) {
	at := func(h, m int) time.Time { return time.Date(2026, 9, 20, h, m, 0, 0, hcm) }
	cases := []struct {
		local    time.Time
		from, to string
		want     time.Time
		in       bool
	}{
		{at(23, 0), "22:00", "08:00", time.Date(2026, 9, 21, 8, 0, 0, 0, hcm), true},
		{at(7, 59), "22:00", "08:00", at(8, 0), true},
		{at(8, 0), "22:00", "08:00", time.Time{}, false},
		{at(13, 30), "13:00", "15:00", at(15, 0), true},
		{at(15, 0), "13:00", "15:00", time.Time{}, false},
		{at(12, 0), "13:00", "15:00", time.Time{}, false},
		{at(12, 0), "bad", "15:00", time.Time{}, false},
		{at(12, 0), "12:00", "12:00", time.Time{}, false},
	}
	for _, tc := range cases {
		got, in := NextQuietHoursEnd(tc.local, tc.from, tc.to)
		if in != tc.in || (in && !got.Equal(tc.want)) {
			t.Fatalf("NextQuietHoursEnd(%v, %s, %s) = %v,%v want %v,%v", tc.local, tc.from, tc.to, got, in, tc.want, tc.in)
		}
	}
}

// metricValue reads a counter or gauge without prometheus/testutil (which
// would pull an unpinned module into go.mod).
func metricValue(t *testing.T, m prometheus.Metric) float64 {
	t.Helper()
	var out dto.Metric
	if err := m.Write(&out); err != nil {
		t.Fatalf("write metric: %v", err)
	}
	if out.Counter != nil {
		return out.Counter.GetValue()
	}
	if out.Gauge != nil {
		return out.Gauge.GetValue()
	}
	t.Fatalf("metric is neither counter nor gauge")
	return 0
}
