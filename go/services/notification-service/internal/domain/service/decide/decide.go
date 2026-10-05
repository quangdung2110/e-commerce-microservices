// Package decide implements stage Decide (docs/notification/08-decide.md): it
// answers "send or not", "on which channels" and "no earlier than when" from
// local data only — the catalog, the replicated preference, the suppression
// ledger, the delivery dedup ledger and one Redis INCR for the frequency cap.
// It never calls Auth or a provider (08-decide.md §1) and it runs inside the
// consumer transaction (§2), so every repository it receives is tx-bound.
package decide

import (
	"context"
	"fmt"
	"strconv"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/catalog"
	"github.com/ecommerce/notification-service/internal/common/clock"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// PreferenceReader is the slice of port.PreferenceRepository Decide needs.
type PreferenceReader interface {
	Get(ctx context.Context, userID uuid.UUID) (*entity.Preference, error)
}

// SuppressionReader is the slice of port.SuppressionRepository Decide needs.
type SuppressionReader interface {
	ActiveByUser(ctx context.Context, userID uuid.UUID) ([]entity.Suppression, error)
}

// DedupClaimer is the slice of port.DedupRepository Decide needs (step 12).
type DedupClaimer interface {
	ClaimDelivery(ctx context.Context, dedupKey string, channel entity.Channel) (bool, error)
}

// Policy is the bulk policy from configuration (configs/application.yaml
// `bulk`). Zero values fall back to the catalog lane policy so the catalog
// stays the single source of truth unless ops override it.
type Policy struct {
	QuietFrom          string // "22:00"
	QuietTo            string // "08:00"
	DefaultTimezone    string // Asia/Ho_Chi_Minh
	FrequencyCapPerDay int
	DefaultLocale      string // vi (carried for completeness; render uses it)
}

// Deps is everything Decide reads. Preferences, Suppressions and Dedup are the
// tx-bound repositories of the consumer transaction (ingest swaps them per
// message with WithRepos); the rest is process-wide.
type Deps struct {
	Catalog      *catalog.Catalog
	Preferences  PreferenceReader
	Suppressions SuppressionReader
	Dedup        DedupClaimer
	FreqCap      port.FrequencyCap
	Clock        clock.Clock
	Policy       Policy
	Metrics      *telemetry.Metrics // nilable
	Logger       *zap.Logger        // nilable
}

// Decider runs the 13 steps of 08-decide.md §3.
type Decider struct {
	deps Deps
}

// New builds a Decider. Catalog and Clock are mandatory.
func New(deps Deps) *Decider {
	if deps.Logger == nil {
		deps.Logger = zap.NewNop()
	}
	return &Decider{deps: deps}
}

// WithRepos returns a copy bound to the given transaction repositories.
func (d *Decider) WithRepos(prefs PreferenceReader, supp SuppressionReader, dedup DedupClaimer) *Decider {
	deps := d.deps
	deps.Preferences = prefs
	deps.Suppressions = supp
	deps.Dedup = dedup
	return &Decider{deps: deps}
}

// channelState tracks one template channel through steps 8–12.
type channelState struct {
	channel  entity.Channel
	keep     bool
	reason   string
	dropStep int
}

// Decide evaluates the request. It returns an error only for infrastructure
// failures (repository errors) so the consumer retries the message; every
// business outcome is expressed in the DecideResult.
func (d *Decider) Decide(ctx context.Context, in entity.IngestInput) (entity.DecideResult, error) {
	now := d.deps.Clock.Now().UTC()
	lane := in.Lane

	// Facts without a mapping are not an error (02-pipeline.md §2.2): the
	// request row records IGNORED and nobody is alerted.
	if in.InputKind == entity.InputFact && in.Template == "" {
		return d.reject(lane, 1, entity.DecisionIgnored, entity.ReasonUnmappedFact), nil
	}

	// Step 1 — template exists and is active.
	tpl, ok := d.deps.Catalog.Get(in.Template)
	if !ok || !tpl.Active() {
		return d.reject(lane, 1, entity.DecisionFailed, entity.ReasonUnknownTemplate), nil
	}
	if lane == "" {
		lane = tpl.Lane
	}

	// Step 2 — lane of the template matches the topic. Facts get their lane
	// from the mapping (the template itself), so only requests can mismatch.
	if in.InputKind == entity.InputRequest && in.Lane != tpl.Lane {
		return d.reject(lane, 2, entity.DecisionFailed, entity.ReasonLaneMismatch), nil
	}

	// Step 3 — template_data valid. nil is only legal for the secret Auth
	// templates whose content is resolved at send time (brief: OTP/reset).
	if in.TemplateData == nil {
		if !tpl.IsSecret() {
			return d.reject(lane, 3, entity.DecisionFailed, entity.ReasonInvalidTemplateData), nil
		}
	} else if err := d.deps.Catalog.ValidateTemplateData(tpl.ID, in.TemplateData); err != nil {
		d.deps.Logger.Info("template_data rejected by schema",
			zap.String("template", tpl.ID), zap.String("event_id", in.EventID), zap.Error(err))
		return d.reject(lane, 3, entity.DecisionFailed, entity.ReasonInvalidTemplateData), nil
	}

	// Step 4 — dedup_key present when required. A key sent for a `none`
	// template is ignored: the catalog says these must never dedup (OTP resend).
	dedupKey := in.DedupKey
	switch tpl.DedupMode {
	case catalog.DedupRequired:
		if dedupKey == "" {
			return d.reject(lane, 4, entity.DecisionFailed, entity.ReasonMissingDedupKey), nil
		}
	case catalog.DedupNone:
		dedupKey = ""
	}

	// Step 5 — recipient is a UUID.
	userID, err := uuid.Parse(in.UserID)
	if err != nil || userID == uuid.Nil {
		return d.reject(lane, 5, entity.DecisionFailed, entity.ReasonNoRecipient), nil
	}

	// Step 6 — effective_expires_at = min(request.expires_at, now + max_age).
	// Auth OTP/reset templates take the Auth expiry as is (max_age
	// auth-expires_at); a missing one is a producer error.
	var effective time.Time
	if tpl.MaxAgeFromRequest {
		if in.ExpiresAt == nil {
			return d.rejectUser(lane, 6, entity.DecisionFailed, entity.ReasonInvalidTemplateData, userID), nil
		}
		effective = in.ExpiresAt.UTC()
	} else {
		effective = now.Add(tpl.MaxAge)
		if in.ExpiresAt != nil && in.ExpiresAt.Before(effective) {
			effective = in.ExpiresAt.UTC()
		}
	}
	if !effective.After(now) {
		res := d.rejectUser(lane, 6, entity.DecisionExpired, entity.ReasonAlreadyExpired, userID)
		res.EffectiveExpiresAt = effective
		return res, nil
	}

	// Step 7 — preference, default when absent (§5).
	pref, err := d.loadPreference(ctx, userID)
	if err != nil {
		return entity.DecideResult{}, err
	}

	lanePolicy := d.deps.Catalog.Lane(tpl.Lane)
	channels := make([]channelState, 0, len(tpl.Channels))
	for _, ch := range tpl.Channels {
		channels = append(channels, channelState{channel: ch, keep: true})
	}

	// Step 8 — governed_by per channel under the lane policy (07-templates §4).
	for i := range channels {
		if reason := blockedByPreference(tpl, lanePolicy, pref, channels[i].channel); reason != "" {
			channels[i].drop(8, reason)
		}
	}

	// Step 9 — suppression. Decide knows the user, not the address, and a
	// suppression is bound to an address: dropping the email channel here would
	// silence a user forever after they changed address (08-decide.md §4,
	// IT-SUPP-02). The email delivery is therefore still created and the
	// deliver worker enforces the suppression after resolving the address —
	// equal hash → SKIPPED (suppressed), different hash → superseded. The
	// lookup by user_id is only used to tag the decision for observability.
	if !lanePolicy.IgnoresSuppression {
		if idx := keptIndex(channels, entity.ChannelEmail); idx >= 0 {
			suppressed, err := d.isSuppressed(ctx, userID, now)
			if err != nil {
				return entity.DecideResult{}, err
			}
			if suppressed {
				d.record(lane, 9, entity.ReasonSuppressed)
				d.deps.Logger.Debug("user has an active suppression; worker will confirm by address hash", zap.String("user_id", userID.String()))
			}
		}
	}

	// Every channel already dropped: stop before touching Redis or the dedup
	// ledger — a bulk that cannot go must not consume a frequency-cap slot.
	if countKept(channels) == 0 {
		return d.finish(lane, tpl, userID, effective, now, channels), nil
	}

	// Step 10 — frequency cap (bulk): counted at Decide, fail-open (§6).
	tz := d.location(pref)
	local := now.In(tz)
	if !lanePolicy.IgnoresFrequencyCap {
		if over, err := d.overFrequencyCap(ctx, userID, local, lanePolicy); err != nil {
			return entity.DecideResult{}, err
		} else if over {
			return d.rejectUser(lane, 10, entity.DecisionSkipped, entity.ReasonFrequencyCap, userID), nil
		}
	}

	// Step 11 — quiet hours (bulk): defer to the next window end once; the
	// worker never recomputes (§6).
	nextAttemptAt := now
	if !lanePolicy.IgnoresQuietHours {
		from, to := d.quietWindow(lanePolicy)
		if deferred, ok := NextQuietHoursEnd(local, from, to); ok {
			nextAttemptAt = deferred.UTC()
			if nextAttemptAt.After(effective) {
				res := d.rejectUser(lane, 11, entity.DecisionExpired, entity.ReasonQuietHoursPastExpiry, userID)
				res.EffectiveExpiresAt = effective
				return res, nil
			}
		}
	}

	// Step 12 — delivery dedup per (dedup_key, channel); conflict is not an
	// error (§3 "Vì sao bước 12 unique theo (dedup_key, channel)").
	if dedupKey != "" {
		for i := range channels {
			if !channels[i].keep {
				continue
			}
			claimed, err := d.deps.Dedup.ClaimDelivery(ctx, dedupKey, channels[i].channel)
			if err != nil {
				return entity.DecideResult{}, fmt.Errorf("decide: claim delivery dedup: %w", err)
			}
			if !claimed {
				channels[i].drop(12, entity.ReasonDuplicate)
			}
		}
	}

	// Step 13 — one decision per template channel.
	return d.finish(lane, tpl, userID, effective, nextAttemptAt, channels), nil
}

// finish builds the result for the outcomes that carry channel decisions
// (DECIDED, SKIPPED all_channels_off, SKIPPED duplicate). Request-level rule
// from the brief: DECIDED if ≥1 kept; else duplicate if every drop was
// duplicate, otherwise all_channels_off.
func (d *Decider) finish(lane entity.Lane, tpl catalog.Template, userID uuid.UUID, effective, nextAttemptAt time.Time, channels []channelState) entity.DecideResult {
	res := entity.DecideResult{
		Decision:           entity.DecisionDecided,
		EffectiveExpiresAt: effective,
		UserID:             userID,
		Channels:           make([]entity.ChannelDecision, 0, len(channels)),
	}
	kept, allDuplicate, lastDropStep := 0, true, 0
	for _, c := range channels {
		cd := entity.ChannelDecision{Channel: c.channel, Keep: c.keep, NextAttemptAt: nextAttemptAt}
		if c.keep {
			kept++
		} else {
			cd.SkipReason = c.reason
			if c.reason != entity.ReasonDuplicate {
				allDuplicate = false
			}
			if c.dropStep > lastDropStep {
				lastDropStep = c.dropStep
			}
		}
		res.Channels = append(res.Channels, cd)
	}
	if kept > 0 {
		return res
	}
	res.Decision = entity.DecisionSkipped
	res.Step = lastDropStep
	if allDuplicate {
		res.Reason = entity.ReasonDuplicate
	} else {
		res.Reason = entity.ReasonAllChannelsOff
	}
	d.record(lane, res.Step, res.Reason)
	return res
}

func (d *Decider) reject(lane entity.Lane, step int, decision entity.Decision, reason string) entity.DecideResult {
	d.record(lane, step, reason)
	return entity.DecideResult{Decision: decision, Reason: reason, Step: step}
}

func (d *Decider) rejectUser(lane entity.Lane, step int, decision entity.Decision, reason string, userID uuid.UUID) entity.DecideResult {
	res := d.reject(lane, step, decision, reason)
	res.UserID = userID
	return res
}

func (d *Decider) record(lane entity.Lane, step int, reason string) {
	if d.deps.Metrics == nil {
		return
	}
	l := string(lane)
	if l == "" {
		l = "unknown"
	}
	d.deps.Metrics.DecideStepRejections.WithLabelValues(l, strconv.Itoa(step), reason).Inc()
}

func (d *Decider) loadPreference(ctx context.Context, userID uuid.UUID) (entity.Preference, error) {
	p, err := d.deps.Preferences.Get(ctx, userID)
	if err != nil {
		if apperr.Is(err, apperr.ErrNotFound) {
			return entity.DefaultPreference(userID), nil
		}
		return entity.Preference{}, fmt.Errorf("decide: load preference: %w", err)
	}
	if p == nil {
		return entity.DefaultPreference(userID), nil
	}
	return *p, nil
}

func (d *Decider) isSuppressed(ctx context.Context, userID uuid.UUID, now time.Time) (bool, error) {
	rows, err := d.deps.Suppressions.ActiveByUser(ctx, userID)
	if err != nil {
		return false, fmt.Errorf("decide: load suppressions: %w", err)
	}
	for _, s := range rows {
		if s.ActiveAt(now) {
			return true, nil
		}
	}
	return false, nil
}

// blockedByPreference applies 07-templates.md §4 to one channel. Order matters
// only for the reason, which is preference_off in every case.
func blockedByPreference(tpl catalog.Template, lane catalog.LanePolicy, pref entity.Preference, ch entity.Channel) string {
	// marketing_enabled=false blocks every channel of a template it governs.
	if tpl.GovernedByFlag("marketing_enabled") && !pref.MarketingEnabled {
		return entity.ReasonPreferenceOff
	}
	switch ch {
	case entity.ChannelEmail:
		// Critical ignores email_enabled: "không phải từ chối reset password
		// của chính tôi".
		if tpl.GovernedByFlag("email_enabled") && !lane.IgnoresEmailEnabled && !pref.EmailEnabled {
			return entity.ReasonPreferenceOff
		}
	case entity.ChannelInApp:
		if tpl.GovernedByFlag("in_app_enabled") && !pref.InAppEnabled {
			return entity.ReasonPreferenceOff
		}
	}
	return ""
}

func (d *Decider) overFrequencyCap(ctx context.Context, userID uuid.UUID, local time.Time, lane catalog.LanePolicy) (bool, error) {
	if d.deps.FreqCap == nil {
		return false, nil
	}
	limit := d.deps.Policy.FrequencyCapPerDay
	if limit <= 0 {
		limit = lane.FrequencyCapPerDay
	}
	if limit <= 0 {
		return false, nil
	}
	count, ok, err := d.deps.FreqCap.Increment(ctx, userID, local.Format("2006-01-02"))
	if err != nil || !ok {
		// Fail-open: bulk is the least risky lane; blocking all of it because
		// Redis is down is an over-reaction (08-decide.md §6). Alert instead.
		if d.deps.Metrics != nil {
			d.deps.Metrics.FrequencyCapDegraded.Set(1)
		}
		d.deps.Logger.Warn("frequency cap unavailable, failing open", zap.Error(err))
		return false, nil
	}
	if d.deps.Metrics != nil {
		d.deps.Metrics.FrequencyCapDegraded.Set(0)
	}
	return count > int64(limit), nil
}

// location resolves the user's timezone, then the configured default, then
// the catalog default; an unknown IANA name falls back the same way.
func (d *Decider) location(pref entity.Preference) *time.Location {
	candidates := []string{pref.TimezoneOr(""), d.deps.Policy.DefaultTimezone, d.deps.Catalog.Lane(entity.LaneBulk).DefaultTimezone}
	for _, name := range candidates {
		if name == "" {
			continue
		}
		if loc, err := time.LoadLocation(name); err == nil {
			return loc
		}
		d.deps.Logger.Warn("unknown timezone, falling back", zap.String("timezone", name))
	}
	return time.UTC
}

func (d *Decider) quietWindow(lane catalog.LanePolicy) (from, to string) {
	from, to = d.deps.Policy.QuietFrom, d.deps.Policy.QuietTo
	if from == "" || to == "" {
		from, to = lane.QuietFrom, lane.QuietTo
	}
	return from, to
}

// NextQuietHoursEnd reports whether local falls inside the quiet window
// [from, to) and, when it does, the next `to` boundary in the same location.
// The window may wrap midnight (22:00 → 08:00). Boundaries are half-open, so
// exactly `to` is outside and exactly `from` is inside. Exported so tests and
// ops tooling can reproduce the arithmetic.
func NextQuietHoursEnd(local time.Time, from, to string) (time.Time, bool) {
	fromH, fromM, okF := parseHHMM(from)
	toH, toM, okT := parseHHMM(to)
	if !okF || !okT {
		return time.Time{}, false
	}
	minute := local.Hour()*60 + local.Minute()
	fromMin := fromH*60 + fromM
	toMin := toH*60 + toM
	if fromMin == toMin {
		return time.Time{}, false // empty window
	}
	y, m, dd := local.Date()
	todayTo := time.Date(y, m, dd, toH, toM, 0, 0, local.Location())
	if fromMin < toMin {
		// Same-day window, e.g. 13:00–15:00.
		if minute >= fromMin && minute < toMin {
			return todayTo, true
		}
		return time.Time{}, false
	}
	// Wrapping window, e.g. 22:00–08:00.
	if minute >= fromMin {
		return todayTo.AddDate(0, 0, 1), true
	}
	if minute < toMin {
		return todayTo, true
	}
	return time.Time{}, false
}

func parseHHMM(s string) (h, m int, ok bool) {
	if len(s) != 5 || s[2] != ':' {
		return 0, 0, false
	}
	h, err1 := strconv.Atoi(s[:2])
	m, err2 := strconv.Atoi(s[3:])
	if err1 != nil || err2 != nil || h < 0 || h > 23 || m < 0 || m > 59 {
		return 0, 0, false
	}
	return h, m, true
}

func (c *channelState) drop(step int, reason string) {
	if !c.keep {
		return
	}
	c.keep = false
	c.reason = reason
	c.dropStep = step
}

func keptIndex(channels []channelState, ch entity.Channel) int {
	for i, c := range channels {
		if c.channel == ch && c.keep {
			return i
		}
	}
	return -1
}

func countKept(channels []channelState) int {
	n := 0
	for _, c := range channels {
		if c.keep {
			n++
		}
	}
	return n
}
