// Package catalog embeds the template catalog (a byte-equal copy of
// contracts/events/notification/v1/templates/catalog.yaml, verified by test)
// and the template_data JSON schemas, and exposes the typed view Decide and
// Render need (docs/notification/07-templates.md).
package catalog

import (
	"bytes"
	"embed"
	"encoding/json"
	"fmt"
	"io/fs"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/santhosh-tekuri/jsonschema/v6"
	"gopkg.in/yaml.v3"

	"github.com/ecommerce/notification-service/internal/domain/entity"
)

//go:embed catalog.yaml
var catalogYAML []byte

//go:embed schemas/*.schema.json
var schemaFS embed.FS

// MaxAgeAuthExpiresAt marks templates whose expiry comes from the Auth event
// (max_age: auth-expires_at) — Decide uses the request expires_at as is.
const MaxAgeAuthExpiresAt = "auth-expires_at"

// DedupMode is required | optional | none.
type DedupMode string

const (
	DedupRequired DedupMode = "required"
	DedupOptional DedupMode = "optional"
	DedupNone     DedupMode = "none"
)

// DataSource tells the consumer where template_data comes from.
type DataSource string

const (
	SourceKafkaInline    DataSource = "kafka-inline"
	SourceAuthContentAPI DataSource = "auth-content-api"
	SourceReviewEvent    DataSource = "review-event"
	SourceCartFact       DataSource = "cart-fact"
)

// Template is one catalog entry.
type Template struct {
	ID         string
	Status     string
	Lane       entity.Lane
	Channels   []entity.Channel
	GovernedBy []string // email_enabled | in_app_enabled | marketing_enabled; empty = none
	DataSource DataSource
	// MaxAge is zero when MaxAgeFromRequest is true.
	MaxAge            time.Duration
	MaxAgeFromRequest bool
	DedupMode         DedupMode
	DedupFormula      string
	SourceMessage     string
	SourceType        string
	Producer          string
}

// Active reports whether producers may send this template.
func (t Template) Active() bool { return t.Status == "active" || t.Status == "proposed" }

// AllowsChannel reports whether the channel is in the template's channel set.
func (t Template) AllowsChannel(ch entity.Channel) bool {
	for _, c := range t.Channels {
		if c == ch {
			return true
		}
	}
	return false
}

// GovernedByFlag reports whether the given preference flag may block this
// template.
func (t Template) GovernedByFlag(flag string) bool {
	for _, g := range t.GovernedBy {
		if g == flag {
			return true
		}
	}
	return false
}

// IsSecret reports whether template_data must never be persisted (OTP/reset).
func (t Template) IsSecret() bool {
	return t.DataSource == SourceAuthContentAPI && t.MaxAgeFromRequest
}

// LanePolicy is the per-lane rule set from catalog `lanes`.
type LanePolicy struct {
	IgnoresEmailEnabled bool
	IgnoresSuppression  bool
	IgnoresQuietHours   bool
	IgnoresFrequencyCap bool
	QuietFrom           string
	QuietTo             string
	DefaultTimezone     string
	FrequencyCapPerDay  int
}

// Catalog is the parsed, validated catalog.
type Catalog struct {
	Version   int
	templates map[string]Template
	ordered   []Template
	lanes     map[entity.Lane]LanePolicy
	schemas   map[string]*jsonschema.Schema
}

type rawCatalog struct {
	Version int `yaml:"version"`
	Lanes   map[string]struct {
		Ignores    []string `yaml:"ignores"`
		QuietHours struct {
			From            string `yaml:"from"`
			To              string `yaml:"to"`
			DefaultTimezone string `yaml:"default_timezone"`
		} `yaml:"quiet_hours"`
		FrequencyCap struct {
			PerUserPerDay int `yaml:"per_user_per_day"`
		} `yaml:"frequency_cap"`
	} `yaml:"lanes"`
	Templates []struct {
		ID            string   `yaml:"id"`
		Status        string   `yaml:"status"`
		Lane          string   `yaml:"lane"`
		Channels      []string `yaml:"channels"`
		GovernedBy    any      `yaml:"governed_by"`
		DataSource    string   `yaml:"data_source"`
		Schema        string   `yaml:"schema"`
		Producer      string   `yaml:"producer"`
		SourceMessage string   `yaml:"source_message"`
		SourceType    string   `yaml:"source_type"`
		MaxAge        string   `yaml:"max_age"`
		DedupKey      struct {
			Mode    string `yaml:"mode"`
			Formula string `yaml:"formula"`
		} `yaml:"dedup_key"`
	} `yaml:"templates"`
}

// Load parses the embedded catalog and compiles every schema. It is called at
// start-up and in tests; an error means the process must not start.
func Load() (*Catalog, error) {
	return parse(catalogYAML)
}

// Raw returns the embedded catalog bytes (for the byte-equal contract test).
func Raw() []byte { return bytes.Clone(catalogYAML) }

// RawSchema returns an embedded schema file (for the byte-equal contract test).
func RawSchema(templateID string) ([]byte, error) {
	return fs.ReadFile(schemaFS, "schemas/"+templateID+".schema.json")
}

func parse(data []byte) (*Catalog, error) {
	var raw rawCatalog
	if err := yaml.Unmarshal(data, &raw); err != nil {
		return nil, fmt.Errorf("catalog: parse yaml: %w", err)
	}
	c := &Catalog{
		Version:   raw.Version,
		templates: map[string]Template{},
		lanes:     map[entity.Lane]LanePolicy{},
		schemas:   map[string]*jsonschema.Schema{},
	}
	for name, l := range raw.Lanes {
		lane, err := entity.ParseLane(name)
		if err != nil {
			return nil, fmt.Errorf("catalog: %w", err)
		}
		p := LanePolicy{
			QuietFrom:          l.QuietHours.From,
			QuietTo:            l.QuietHours.To,
			DefaultTimezone:    l.QuietHours.DefaultTimezone,
			FrequencyCapPerDay: l.FrequencyCap.PerUserPerDay,
		}
		for _, ig := range l.Ignores {
			switch ig {
			case "email_enabled":
				p.IgnoresEmailEnabled = true
			case "suppression":
				p.IgnoresSuppression = true
			case "quiet_hours":
				p.IgnoresQuietHours = true
			case "frequency_cap":
				p.IgnoresFrequencyCap = true
			default:
				return nil, fmt.Errorf("catalog: lane %s ignores unknown policy %q", name, ig)
			}
		}
		c.lanes[lane] = p
	}
	for _, lane := range entity.AllLanes {
		if _, ok := c.lanes[lane]; !ok {
			return nil, fmt.Errorf("catalog: lane %s missing from lanes", lane)
		}
	}

	compiler := jsonschema.NewCompiler()
	compiler.AssertFormat()
	for _, t := range raw.Templates {
		if !templateIDPattern.MatchString(t.ID) {
			return nil, fmt.Errorf("catalog: template id %q does not match %s", t.ID, templateIDPattern)
		}
		if _, dup := c.templates[t.ID]; dup {
			return nil, fmt.Errorf("catalog: duplicate template %s", t.ID)
		}
		lane, err := entity.ParseLane(t.Lane)
		if err != nil {
			return nil, fmt.Errorf("catalog: template %s: %w", t.ID, err)
		}
		tpl := Template{
			ID:            t.ID,
			Status:        t.Status,
			Lane:          lane,
			DataSource:    DataSource(t.DataSource),
			SourceMessage: t.SourceMessage,
			SourceType:    t.SourceType,
			Producer:      t.Producer,
			DedupMode:     DedupMode(t.DedupKey.Mode),
			DedupFormula:  t.DedupKey.Formula,
		}
		switch tpl.DedupMode {
		case DedupRequired, DedupOptional, DedupNone:
		default:
			return nil, fmt.Errorf("catalog: template %s: dedup_key.mode %q", t.ID, t.DedupKey.Mode)
		}
		for _, ch := range t.Channels {
			parsed, err := entity.ParseChannel(ch)
			if err != nil {
				return nil, fmt.Errorf("catalog: template %s: %w", t.ID, err)
			}
			tpl.Channels = append(tpl.Channels, parsed)
		}
		if len(tpl.Channels) == 0 {
			return nil, fmt.Errorf("catalog: template %s has no channels", t.ID)
		}
		switch g := t.GovernedBy.(type) {
		case nil:
		case string:
			if g != "none" && g != "" {
				tpl.GovernedBy = []string{g}
			}
		case []any:
			for _, item := range g {
				s, ok := item.(string)
				if !ok {
					return nil, fmt.Errorf("catalog: template %s: governed_by must be strings", t.ID)
				}
				tpl.GovernedBy = append(tpl.GovernedBy, s)
			}
		default:
			return nil, fmt.Errorf("catalog: template %s: governed_by has unexpected type", t.ID)
		}
		for _, g := range tpl.GovernedBy {
			switch g {
			case "email_enabled", "in_app_enabled", "marketing_enabled":
			default:
				return nil, fmt.Errorf("catalog: template %s: unknown governed_by %q", t.ID, g)
			}
		}
		if t.MaxAge == MaxAgeAuthExpiresAt {
			tpl.MaxAgeFromRequest = true
		} else {
			d, err := ParseISODuration(t.MaxAge)
			if err != nil {
				return nil, fmt.Errorf("catalog: template %s: max_age: %w", t.ID, err)
			}
			tpl.MaxAge = d
		}

		schemaBytes, err := fs.ReadFile(schemaFS, "schemas/"+t.ID+".schema.json")
		if err != nil {
			return nil, fmt.Errorf("catalog: template %s: missing embedded schema: %w", t.ID, err)
		}
		var doc any
		if err := json.Unmarshal(schemaBytes, &doc); err != nil {
			return nil, fmt.Errorf("catalog: template %s: schema json: %w", t.ID, err)
		}
		url := "schemas/" + t.ID + ".schema.json"
		if err := compiler.AddResource(url, doc); err != nil {
			return nil, fmt.Errorf("catalog: template %s: add schema: %w", t.ID, err)
		}
		schema, err := compiler.Compile(url)
		if err != nil {
			return nil, fmt.Errorf("catalog: template %s: compile schema: %w", t.ID, err)
		}
		c.schemas[t.ID] = schema
		c.templates[t.ID] = tpl
		c.ordered = append(c.ordered, tpl)
	}
	if len(c.ordered) == 0 {
		return nil, fmt.Errorf("catalog: no templates")
	}
	return c, nil
}

var templateIDPattern = regexp.MustCompile(`^[A-Z][A-Z0-9_]{2,63}$`)

// Get returns the template by id.
func (c *Catalog) Get(id string) (Template, bool) {
	t, ok := c.templates[id]
	return t, ok
}

// Templates returns every template in catalog order.
func (c *Catalog) Templates() []Template { return append([]Template(nil), c.ordered...) }

// Lane returns the policy of a lane.
func (c *Catalog) Lane(lane entity.Lane) LanePolicy { return c.lanes[lane] }

// ByReviewMessage maps a Review message name to its template.
func (c *Catalog) ByReviewMessage(name string) (Template, bool) {
	for _, t := range c.ordered {
		if t.DataSource == SourceReviewEvent && t.SourceMessage == name {
			return t, true
		}
	}
	return Template{}, false
}

// ByFactType maps a fact CloudEvents type to its template (02-pipeline §2.2).
func (c *Catalog) ByFactType(eventType string) (Template, bool) {
	for _, t := range c.ordered {
		if t.DataSource == SourceCartFact && t.SourceType == eventType {
			return t, true
		}
	}
	return Template{}, false
}

// ValidateTemplateData checks template_data against the template schema.
func (c *Catalog) ValidateTemplateData(templateID string, data []byte) error {
	schema, ok := c.schemas[templateID]
	if !ok {
		return fmt.Errorf("no schema for template %s", templateID)
	}
	var doc any
	if err := json.Unmarshal(data, &doc); err != nil {
		return fmt.Errorf("template_data is not valid JSON: %w", err)
	}
	if err := schema.Validate(doc); err != nil {
		return err
	}
	return nil
}

// ParseISODuration parses the subset of ISO-8601 durations the catalog uses:
// PnD, PTnH, PTnM, PTnS and combinations (e.g. P1DT12H).
func ParseISODuration(s string) (time.Duration, error) {
	m := isoDuration.FindStringSubmatch(strings.TrimSpace(s))
	if m == nil || s == "P" || s == "PT" {
		return 0, fmt.Errorf("invalid ISO-8601 duration %q", s)
	}
	var d time.Duration
	add := func(v string, unit time.Duration) error {
		if v == "" {
			return nil
		}
		n, err := strconv.Atoi(v)
		if err != nil {
			return err
		}
		d += time.Duration(n) * unit
		return nil
	}
	if err := add(m[1], 24*time.Hour); err != nil {
		return 0, err
	}
	if err := add(m[2], time.Hour); err != nil {
		return 0, err
	}
	if err := add(m[3], time.Minute); err != nil {
		return 0, err
	}
	if err := add(m[4], time.Second); err != nil {
		return 0, err
	}
	if d == 0 {
		return 0, fmt.Errorf("zero duration %q", s)
	}
	return d, nil
}

var isoDuration = regexp.MustCompile(`^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$`)
