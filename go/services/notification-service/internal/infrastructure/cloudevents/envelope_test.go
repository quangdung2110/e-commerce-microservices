package cloudevents

import (
	"errors"
	"testing"
	"time"
)

func TestParse(t *testing.T) {
	valid := `{"specversion":"1.0","id":"evt-1","type":"com.ecommerce.cart.abandoned.v1","source":"/services/cart","subject":"carts/1","time":"2026-08-30T14:30:00Z","datacontenttype":"application/json","dataschema":"/x","correlationid":"corr","traceparent":"00-abc-def-01","sequence":3,"data":{"cart_id":"c1"}}`

	cases := []struct {
		name    string
		raw     string
		wantErr bool
		check   func(t *testing.T, e *Envelope)
	}{
		{name: "valid full envelope", raw: valid, check: func(t *testing.T, e *Envelope) {
			if e.ID != "evt-1" || e.Type != "com.ecommerce.cart.abandoned.v1" || e.Source != "/services/cart" || e.Subject != "carts/1" {
				t.Fatalf("unexpected envelope %+v", e)
			}
			if !e.Time.Equal(time.Date(2026, 8, 30, 14, 30, 0, 0, time.UTC)) {
				t.Fatalf("time = %v", e.Time)
			}
			if e.Sequence == nil || *e.Sequence != 3 {
				t.Fatalf("sequence = %v", e.Sequence)
			}
			if e.CorrelationID != "corr" || e.TraceParent != "00-abc-def-01" {
				t.Fatalf("correlation/trace not carried: %+v", e)
			}
			if string(e.Data) != `{"cart_id":"c1"}` {
				t.Fatalf("data = %s", e.Data)
			}
		}},
		{name: "time optional", raw: `{"specversion":"1.0","id":"a","type":"t","source":"s","data":{}}`, check: func(t *testing.T, e *Envelope) {
			if !e.Time.IsZero() || e.Sequence != nil {
				t.Fatalf("expected zero time and nil sequence: %+v", e)
			}
		}},
		{name: "empty message", raw: "", wantErr: true},
		{name: "not json", raw: "{", wantErr: true},
		{name: "wrong specversion", raw: `{"specversion":"0.3","id":"a","type":"t","source":"s","data":{}}`, wantErr: true},
		{name: "missing id", raw: `{"specversion":"1.0","type":"t","source":"s","data":{}}`, wantErr: true},
		{name: "missing type", raw: `{"specversion":"1.0","id":"a","source":"s","data":{}}`, wantErr: true},
		{name: "missing source", raw: `{"specversion":"1.0","id":"a","type":"t","data":{}}`, wantErr: true},
		{name: "missing data", raw: `{"specversion":"1.0","id":"a","type":"t","source":"s"}`, wantErr: true},
		{name: "null data", raw: `{"specversion":"1.0","id":"a","type":"t","source":"s","data":null}`, wantErr: true},
		{name: "bad time", raw: `{"specversion":"1.0","id":"a","type":"t","source":"s","time":"yesterday","data":{}}`, wantErr: true},
		{name: "negative sequence", raw: `{"specversion":"1.0","id":"a","type":"t","source":"s","sequence":-1,"data":{}}`, wantErr: true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			e, err := Parse([]byte(tc.raw))
			if tc.wantErr {
				if err == nil {
					t.Fatalf("expected error")
				}
				if !errors.Is(err, ErrInvalidEnvelope) || !IsInvalid(err) {
					t.Fatalf("error must wrap ErrInvalidEnvelope: %v", err)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if tc.check != nil {
				tc.check(t, e)
			}
		})
	}
}
