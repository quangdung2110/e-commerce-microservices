// Package templates is the Renderer (docs/notification/12-render.md): the 15
// catalog templates live in this directory, are compiled into the binary
// with embed, parsed with html/template (email HTML) and text/template
// (subject, text, in-app), test-rendered at start-up with the fixtures in
// testdata/ so a broken template never reaches the DLQ, and versioned by a
// content hash. Render has no I/O and every error is permanent.
package templates

import (
	"bytes"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	htmltpl "html/template"
	"io/fs"
	"path"
	"regexp"
	"sort"
	"strings"
	texttpl "text/template"
	"unicode/utf8"

	"github.com/google/uuid"
	"gopkg.in/yaml.v3"

	"github.com/ecommerce/notification-service/internal/catalog"
	"github.com/ecommerce/notification-service/internal/common/constants"
	apperrors "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// content holds every template directory (UPPER_SNAKE ids), the shared
// layout and the start-up fixtures. Golden files are not embedded: the
// fixture pattern only matches *.json and Load skips *.golden.*.
//
//go:embed [A-Z]* _layout testdata/*/*.json
var content embed.FS

const (
	layoutDir  = "_layout"
	fixtureDir = "testdata"

	fileSubject = "email.subject.txt"
	fileHTML    = "email.html"
	fileText    = "email.txt"
	fileInApp   = "inapp.yaml"

	layoutHTMLFile = "email.base.html"
	layoutTextFile = "email.base.txt"

	// defaultLocale is mandatory for every template (07-templates.md §2).
	defaultLocale = "vi"

	// brandName is the sender shown in header, footer and subjects.
	brandName = "E-Commerce"

	// Limits from 07-templates.md §2 and 12-render.md §4/§5.
	maxSubjectRunes = 200
	maxTitleRunes   = 80
	maxBodyRunes    = 200
	maxHTMLBytes    = 100 * 1024

	versionLen = 12
)

// Registry implements port.Renderer over the embedded templates.
type Registry struct {
	catalog   *catalog.Catalog
	metrics   *telemetry.Metrics
	templates map[string]*compiledTemplate
}

var _ port.Renderer = (*Registry)(nil)

type compiledTemplate struct {
	def     catalog.Template
	version string
	locales map[string]*localeBundle
}

type localeBundle struct {
	locale  string
	subject *texttpl.Template
	html    *htmltpl.Template // layout + "content"
	text    *texttpl.Template // layout + "content"
	inapp   *inappBundle      // nil when the template has no in_app channel
}

type inappBundle struct {
	title         *texttpl.Template
	body          *texttpl.Template
	deepLinkField string
}

// inappSpec is the inapp.yaml file.
type inappSpec struct {
	Title         string `yaml:"title"`
	Body          string `yaml:"body"`
	DeepLinkField string `yaml:"deep_link_field"`
}

type layoutFiles struct {
	html string
	text string
}

// Load parses every embedded template, checks the directory tree against
// the catalog, renders every fixture and computes template versions
// (12-render.md §4). It runs in main() before any consumer opens; an error
// means the process must not start. metrics may be nil (tests).
func Load(cat *catalog.Catalog, metrics *telemetry.Metrics) (*Registry, error) {
	if cat == nil {
		return nil, errors.New("templates: nil catalog")
	}
	layout, err := readLayout()
	if err != nil {
		return nil, err
	}
	r := &Registry{catalog: cat, metrics: metrics, templates: map[string]*compiledTemplate{}}

	// A directory that is not a catalog template is a typo or a leftover
	// (12-render.md §3: "không có thư mục thừa ngoài catalog").
	roots, err := fs.ReadDir(content, ".")
	if err != nil {
		return nil, fmt.Errorf("templates: read root: %w", err)
	}
	for _, e := range roots {
		if !e.IsDir() || e.Name() == layoutDir || e.Name() == fixtureDir {
			continue
		}
		if _, ok := cat.Get(e.Name()); !ok {
			return nil, fmt.Errorf("templates: directory %s is not a catalog template", e.Name())
		}
	}

	for _, def := range cat.Templates() {
		if _, err := fs.Stat(content, def.ID); err != nil {
			if def.Status == "retired" {
				continue
			}
			return nil, fmt.Errorf("templates: %s: missing template directory", def.ID)
		}
		ct, err := compile(def, layout)
		if err != nil {
			return nil, err
		}
		r.templates[def.ID] = ct
	}

	for _, def := range cat.Templates() {
		ct, ok := r.templates[def.ID]
		if !ok {
			continue
		}
		if err := r.renderFixtures(ct); err != nil {
			return nil, err
		}
	}
	return r, nil
}

func readLayout() (layoutFiles, error) {
	html, err := fs.ReadFile(content, path.Join(layoutDir, layoutHTMLFile))
	if err != nil {
		return layoutFiles{}, fmt.Errorf("templates: layout: %w", err)
	}
	text, err := fs.ReadFile(content, path.Join(layoutDir, layoutTextFile))
	if err != nil {
		return layoutFiles{}, fmt.Errorf("templates: layout: %w", err)
	}
	return layoutFiles{html: string(html), text: string(text)}, nil
}

// compile parses one template directory: every locale sub-directory, with
// the mandatory vi and per-file fallback to vi for other locales
// (12-render.md §7).
func compile(def catalog.Template, layout layoutFiles) (*compiledTemplate, error) {
	needInApp := def.AllowsChannel(entity.ChannelInApp)
	entries, err := fs.ReadDir(content, def.ID)
	if err != nil {
		return nil, fmt.Errorf("templates: %s: %w", def.ID, err)
	}
	files := map[string]map[string][]byte{} // locale → file name → content
	for _, e := range entries {
		if !e.IsDir() {
			return nil, fmt.Errorf("templates: %s: unexpected file %s (expected locale directories)", def.ID, e.Name())
		}
		loc := e.Name()
		if loc != strings.ToLower(loc) {
			return nil, fmt.Errorf("templates: %s: locale directory %q must be lower-case", def.ID, loc)
		}
		locFiles, err := readLocaleDir(def.ID, loc, needInApp)
		if err != nil {
			return nil, err
		}
		files[loc] = locFiles
	}
	vi, ok := files[defaultLocale]
	if !ok {
		return nil, fmt.Errorf("templates: %s: missing mandatory locale directory %s", def.ID, defaultLocale)
	}
	for _, name := range requiredFiles(needInApp) {
		if _, ok := vi[name]; !ok {
			return nil, fmt.Errorf("templates: %s/%s: missing %s", def.ID, defaultLocale, name)
		}
	}

	ct := &compiledTemplate{def: def, locales: map[string]*localeBundle{}}
	for loc, locFiles := range files {
		if loc != defaultLocale {
			for name, body := range vi { // per-file fallback to vi
				if _, ok := locFiles[name]; !ok {
					locFiles[name] = body
				}
			}
		}
		b, err := parseBundle(def, loc, locFiles, layout, needInApp)
		if err != nil {
			return nil, err
		}
		ct.locales[loc] = b
	}
	ct.version, err = computeVersion(def.ID)
	if err != nil {
		return nil, err
	}
	return ct, nil
}

func requiredFiles(needInApp bool) []string {
	names := []string{fileSubject, fileHTML, fileText}
	if needInApp {
		names = append(names, fileInApp)
	}
	return names
}

func readLocaleDir(id, loc string, needInApp bool) (map[string][]byte, error) {
	dir := path.Join(id, loc)
	entries, err := fs.ReadDir(content, dir)
	if err != nil {
		return nil, fmt.Errorf("templates: %s: %w", dir, err)
	}
	out := map[string][]byte{}
	for _, e := range entries {
		if e.IsDir() {
			return nil, fmt.Errorf("templates: %s: unexpected sub-directory %s", dir, e.Name())
		}
		switch e.Name() {
		case fileSubject, fileHTML, fileText:
		case fileInApp:
			if !needInApp {
				return nil, fmt.Errorf("templates: %s: %s present but the catalog template has no in_app channel", dir, fileInApp)
			}
		default:
			return nil, fmt.Errorf("templates: %s: unexpected file %s", dir, e.Name())
		}
		body, err := fs.ReadFile(content, path.Join(dir, e.Name()))
		if err != nil {
			return nil, fmt.Errorf("templates: %s: %w", dir, err)
		}
		out[e.Name()] = body
	}
	return out, nil
}

func parseBundle(def catalog.Template, loc string, files map[string][]byte, layout layoutFiles, needInApp bool) (*localeBundle, error) {
	where := func(name string) string { return def.ID + "/" + loc + "/" + name }
	b := &localeBundle{locale: loc}
	var err error

	b.subject, err = texttpl.New("subject").Funcs(funcMap()).Option("missingkey=error").Parse(string(files[fileSubject]))
	if err != nil {
		return nil, fmt.Errorf("templates: %s: %w", where(fileSubject), err)
	}

	b.html, err = htmltpl.New("layout").Funcs(funcMap()).Option("missingkey=error").Parse(layout.html)
	if err != nil {
		return nil, fmt.Errorf("templates: %s/%s: %w", layoutDir, layoutHTMLFile, err)
	}
	if _, err = b.html.New("content").Parse(string(files[fileHTML])); err != nil {
		return nil, fmt.Errorf("templates: %s: %w", where(fileHTML), err)
	}

	b.text, err = texttpl.New("layout").Funcs(funcMap()).Option("missingkey=error").Parse(layout.text)
	if err != nil {
		return nil, fmt.Errorf("templates: %s/%s: %w", layoutDir, layoutTextFile, err)
	}
	if _, err = b.text.New("content").Parse(string(files[fileText])); err != nil {
		return nil, fmt.Errorf("templates: %s: %w", where(fileText), err)
	}

	if needInApp {
		var spec inappSpec
		dec := yaml.NewDecoder(bytes.NewReader(files[fileInApp]))
		dec.KnownFields(true)
		if err := dec.Decode(&spec); err != nil {
			return nil, fmt.Errorf("templates: %s: %w", where(fileInApp), err)
		}
		if strings.TrimSpace(spec.Title) == "" || strings.TrimSpace(spec.Body) == "" {
			return nil, fmt.Errorf("templates: %s: title and body are required", where(fileInApp))
		}
		ia := &inappBundle{deepLinkField: strings.TrimSpace(spec.DeepLinkField)}
		if ia.deepLinkField != "" {
			m, _ := newModel(def.ID)
			if m == nil || !hasJSONField(m, ia.deepLinkField) {
				return nil, fmt.Errorf("templates: %s: deep_link_field %q is not a template_data field", where(fileInApp), ia.deepLinkField)
			}
		}
		ia.title, err = texttpl.New("title").Funcs(funcMap()).Option("missingkey=error").Parse(spec.Title)
		if err != nil {
			return nil, fmt.Errorf("templates: %s: title: %w", where(fileInApp), err)
		}
		ia.body, err = texttpl.New("body").Funcs(funcMap()).Option("missingkey=error").Parse(spec.Body)
		if err != nil {
			return nil, fmt.Errorf("templates: %s: body: %w", where(fileInApp), err)
		}
		b.inapp = ia
	}
	return b, nil
}

// computeVersion is sha256 over every file of the template directory (all
// locales) plus _layout, sorted by path, path and content both hashed, first
// 12 hex chars (12-render.md §3).
func computeVersion(id string) (string, error) {
	var paths []string
	for _, root := range []string{id, layoutDir} {
		err := fs.WalkDir(content, root, func(p string, d fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if !d.IsDir() {
				paths = append(paths, p)
			}
			return nil
		})
		if err != nil {
			return "", fmt.Errorf("templates: %s: version: %w", id, err)
		}
	}
	sort.Strings(paths)
	h := sha256.New()
	for _, p := range paths {
		body, err := fs.ReadFile(content, p)
		if err != nil {
			return "", fmt.Errorf("templates: %s: version: %w", id, err)
		}
		h.Write([]byte(p))
		h.Write([]byte{0})
		h.Write(body)
		h.Write([]byte{0})
	}
	return hex.EncodeToString(h.Sum(nil))[:versionLen], nil
}

// fixtureOptions are the RenderOptions used for start-up rendering; bulk
// gets an unsubscribe URL so the footer branch is exercised.
func fixtureOptions(def catalog.Template) port.RenderOptions {
	opts := port.RenderOptions{
		DeliveryID: uuid.MustParse("00000000-0000-4000-8000-000000000001"),
		Lane:       def.Lane,
		Timezone:   defaultTimezone,
	}
	if def.Lane == entity.LaneBulk {
		opts.UnsubscribeURL = "https://example.invalid/profile/preferences"
	}
	return opts
}

// renderFixtures renders every testdata/<ID>/*.json fixture through every
// locale and enforces the build-time limits (12-render.md §4 steps 2–3).
func (r *Registry) renderFixtures(ct *compiledTemplate) error {
	id := ct.def.ID
	names, err := fs.Glob(content, path.Join(fixtureDir, id, "*.json"))
	if err != nil {
		return fmt.Errorf("templates: %s: fixtures: %w", id, err)
	}
	var fixtures []string
	for _, n := range names {
		if !strings.Contains(path.Base(n), ".golden.") {
			fixtures = append(fixtures, n)
		}
	}
	if len(fixtures) == 0 {
		return fmt.Errorf("templates: %s: no fixture in %s/%s", id, fixtureDir, id)
	}
	opts := fixtureOptions(ct.def)
	for _, f := range fixtures {
		data, err := fs.ReadFile(content, f)
		if err != nil {
			return fmt.Errorf("templates: %s: %w", f, err)
		}
		if err := r.catalog.ValidateTemplateData(id, data); err != nil {
			return fmt.Errorf("templates: fixture %s does not match the schema: %w", f, err)
		}
		for loc, b := range ct.locales {
			email, err := r.renderEmail(ct, b, data, opts)
			if err != nil {
				return fmt.Errorf("templates: fixture %s locale %s: %w", f, loc, err)
			}
			if email.Subject == "" {
				return fmt.Errorf("templates: fixture %s locale %s: empty subject", f, loc)
			}
			if n := len(email.HTML); n > maxHTMLBytes {
				return fmt.Errorf("templates: fixture %s locale %s: html is %d bytes, limit %d", f, loc, n, maxHTMLBytes)
			}
			if strings.Contains(strings.ToLower(email.HTML), "<style") {
				return fmt.Errorf("templates: fixture %s locale %s: html contains a <style> tag; CSS must be inlined", f, loc)
			}
			if b.inapp == nil {
				continue
			}
			title, body, _, err := r.renderInAppRaw(ct, b, data, opts)
			if err != nil {
				return fmt.Errorf("templates: fixture %s locale %s: in-app: %w", f, loc, err)
			}
			if n := utf8.RuneCountInString(title); n == 0 || n > maxTitleRunes {
				return fmt.Errorf("templates: fixture %s locale %s: in-app title is %d runes, limit 1..%d", f, loc, n, maxTitleRunes)
			}
			if n := utf8.RuneCountInString(body); n == 0 || n > maxBodyRunes {
				return fmt.Errorf("templates: fixture %s locale %s: in-app body is %d runes, limit 1..%d", f, loc, n, maxBodyRunes)
			}
		}
	}
	return nil
}

// ---- port.Renderer --------------------------------------------------------

// RenderEmail renders subject, HTML and text plus the transport headers.
func (r *Registry) RenderEmail(templateID, locale string, data json.RawMessage, opts port.RenderOptions) (*entity.EmailContent, error) {
	ct, b, err := r.bundle(templateID, locale)
	if err != nil {
		return nil, apperrors.NewPermanent(entity.ReasonRenderFailed, err)
	}
	out, err := r.renderEmail(ct, b, data, opts)
	if err != nil {
		return nil, apperrors.NewPermanent(entity.ReasonRenderFailed, err)
	}
	return out, nil
}

// RenderInApp renders title, body and deep link. Title/body are truncated to
// the inbox limits as a last safety net (12-render.md §9); Load already
// proved the fixtures fit.
func (r *Registry) RenderInApp(templateID, locale string, data json.RawMessage, opts port.RenderOptions) (*entity.InAppContent, error) {
	ct, b, err := r.bundle(templateID, locale)
	if err != nil {
		return nil, apperrors.NewPermanent(entity.ReasonRenderFailed, err)
	}
	if b.inapp == nil {
		return nil, apperrors.NewPermanent(entity.ReasonRenderFailed, fmt.Errorf("template %s has no in_app render", templateID))
	}
	title, body, link, err := r.renderInAppRaw(ct, b, data, opts)
	if err != nil {
		return nil, apperrors.NewPermanent(entity.ReasonRenderFailed, err)
	}
	return &entity.InAppContent{
		Title:    truncate(title, maxTitleRunes),
		Body:     truncate(body, maxBodyRunes),
		DeepLink: link,
	}, nil
}

// Version returns the 12-hex-char content hash of the template directory,
// or "" for an unknown template.
func (r *Registry) Version(templateID string) string {
	if ct, ok := r.templates[templateID]; ok {
		return ct.version
	}
	return ""
}

// Templates lists the loaded template ids in catalog order (CLI preview).
func (r *Registry) Templates() []string {
	ids := make([]string, 0, len(r.templates))
	for _, def := range r.catalog.Templates() {
		if _, ok := r.templates[def.ID]; ok {
			ids = append(ids, def.ID)
		}
	}
	return ids
}

// bundle resolves template + locale with fallback to vi (12-render.md §7).
// An empty locale is the default, not a fallback; any other locale without
// a directory falls back and counts render_locale_fallback_total.
func (r *Registry) bundle(templateID, locale string) (*compiledTemplate, *localeBundle, error) {
	ct, ok := r.templates[templateID]
	if !ok {
		return nil, nil, fmt.Errorf("unknown template %q", templateID)
	}
	loc := normalizeLocale(locale)
	if b, ok := ct.locales[loc]; ok {
		return ct, b, nil
	}
	if r.metrics != nil && r.metrics.RenderLocaleFallback != nil {
		r.metrics.RenderLocaleFallback.WithLabelValues(templateID).Inc()
	}
	b, ok := ct.locales[defaultLocale]
	if !ok {
		return nil, nil, fmt.Errorf("template %s: no %s locale", templateID, defaultLocale)
	}
	return ct, b, nil
}

// normalizeLocale maps "", "VI", "en-US", "en_GB" to "vi", "vi", "en", "en".
func normalizeLocale(l string) string {
	l = strings.ToLower(strings.TrimSpace(l))
	if i := strings.IndexAny(l, "-_"); i > 0 {
		l = l[:i]
	}
	if l == "" {
		return defaultLocale
	}
	return l
}

func (r *Registry) common(opts port.RenderOptions) Common {
	tz := strings.TrimSpace(opts.Timezone)
	if tz == "" {
		tz = defaultTimezone
	}
	return Common{
		DeliveryID:     opts.DeliveryID.String(),
		Lane:           string(opts.Lane),
		Timezone:       tz,
		UnsubscribeURL: strings.TrimSpace(opts.UnsubscribeURL),
		Brand:          brandName,
	}
}

func (r *Registry) renderEmail(ct *compiledTemplate, b *localeBundle, data json.RawMessage, opts port.RenderOptions) (*entity.EmailContent, error) {
	m, err := buildModel(ct.def.ID, data)
	if err != nil {
		return nil, err
	}
	c := r.common(opts)
	m.setCommon(c)

	var buf bytes.Buffer
	if err := b.subject.Execute(&buf, m); err != nil {
		return nil, fmt.Errorf("template %s/%s: subject: %w", ct.def.ID, b.locale, err)
	}
	c.Subject = sanitizeSubject(buf.String())
	if c.Subject == "" {
		return nil, fmt.Errorf("template %s/%s: subject rendered empty", ct.def.ID, b.locale)
	}
	m.setCommon(c)

	buf.Reset()
	if err := b.html.ExecuteTemplate(&buf, "layout", m); err != nil {
		return nil, fmt.Errorf("template %s/%s: html: %w", ct.def.ID, b.locale, err)
	}
	html := strings.TrimSpace(buf.String()) + "\n"

	buf.Reset()
	if err := b.text.ExecuteTemplate(&buf, "layout", m); err != nil {
		return nil, fmt.Errorf("template %s/%s: text: %w", ct.def.ID, b.locale, err)
	}
	text := normalizeText(buf.String())

	headers := map[string]string{constants.HeaderDeliveryID: c.DeliveryID}
	// 12-render.md §5 + brief: List-Unsubscribe (URL form only) on bulk.
	if opts.Lane == entity.LaneBulk && c.UnsubscribeURL != "" {
		headers["List-Unsubscribe"] = "<" + c.UnsubscribeURL + ">"
	}
	return &entity.EmailContent{Subject: c.Subject, HTML: html, Text: text, Headers: headers}, nil
}

func (r *Registry) renderInAppRaw(ct *compiledTemplate, b *localeBundle, data json.RawMessage, opts port.RenderOptions) (title, body string, link *string, err error) {
	m, err := buildModel(ct.def.ID, data)
	if err != nil {
		return "", "", nil, err
	}
	m.setCommon(r.common(opts))

	var buf bytes.Buffer
	if err := b.inapp.title.Execute(&buf, m); err != nil {
		return "", "", nil, fmt.Errorf("template %s/%s: in-app title: %w", ct.def.ID, b.locale, err)
	}
	title = collapseSpace(buf.String())
	buf.Reset()
	if err := b.inapp.body.Execute(&buf, m); err != nil {
		return "", "", nil, fmt.Errorf("template %s/%s: in-app body: %w", ct.def.ID, b.locale, err)
	}
	body = collapseSpace(buf.String())
	link, err = deepLink(data, b.inapp.deepLinkField)
	if err != nil {
		return "", "", nil, fmt.Errorf("template %s/%s: %w", ct.def.ID, b.locale, err)
	}
	return title, body, link, nil
}

// sanitizeSubject makes the subject a single trimmed line of at most 200
// runes; CR/LF from template_data can never become a header injection.
func sanitizeSubject(s string) string {
	return truncate(collapseSpace(s), maxSubjectRunes)
}

// collapseSpace joins whitespace runs (including newlines) into one space.
func collapseSpace(s string) string {
	return strings.Join(strings.Fields(s), " ")
}

var blankLines = regexp.MustCompile(`\n{3,}`)

// normalizeText trims the plain-text body, strips trailing spaces on each
// line and collapses runs of blank lines so template whitespace control
// does not have to be perfect.
func normalizeText(s string) string {
	s = strings.ReplaceAll(s, "\r\n", "\n")
	lines := strings.Split(s, "\n")
	for i, l := range lines {
		lines[i] = strings.TrimRight(l, " \t")
	}
	s = strings.Join(lines, "\n")
	s = blankLines.ReplaceAllString(s, "\n\n")
	return strings.TrimSpace(s) + "\n"
}
