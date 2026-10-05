package templates

import (
	"strings"
	"testing"
	"time"
	"unicode/utf8"
)

func TestMoney(t *testing.T) {
	cases := []struct {
		name     string
		minor    int64
		currency string
		want     string
		wantErr  bool
	}{
		{"vnd basic", 428000, "VND", "428.000 ₫", false},
		{"vnd zero", 0, "VND", "0 ₫", false},
		{"vnd small", 999, "VND", "999 ₫", false},
		{"vnd thousands", 1000, "VND", "1.000 ₫", false},
		{"vnd millions", 1234567, "vnd", "1.234.567 ₫", false},
		{"vnd huge", 9999999999999, "VND", "9.999.999.999.999 ₫", false},
		{"usd generic", 12345, "USD", "123,45 USD", false},
		{"usd cents", 5, "USD", "0,05 USD", false},
		{"usd millions", 123456789, "usd", "1.234.567,89 USD", false},
		{"negative", -1, "VND", "", true},
		{"empty currency", 1, "", "", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := money(tc.minor, tc.currency)
			if (err != nil) != tc.wantErr {
				t.Fatalf("err = %v, wantErr %v", err, tc.wantErr)
			}
			if got != tc.want {
				t.Fatalf("got %q want %q", got, tc.want)
			}
		})
	}
}

func TestDate(t *testing.T) {
	utc := time.Date(2026, 9, 20, 10, 15, 0, 0, time.UTC)
	cases := []struct {
		name    string
		t       time.Time
		tz      string
		want    string
		wantErr bool
	}{
		{"hcm", utc, "Asia/Ho_Chi_Minh", "20/09/2026 17:15", false},
		{"empty tz falls back", utc, "", "20/09/2026 17:15", false},
		{"unknown tz falls back", utc, "Mars/Olympus", "20/09/2026 17:15", false},
		{"tokyo", utc, "Asia/Tokyo", "20/09/2026 19:15", false},
		{"day rollover", time.Date(2026, 12, 31, 17, 0, 0, 0, time.UTC), "Asia/Ho_Chi_Minh", "01/01/2027 00:00", false},
		{"zero time", time.Time{}, "Asia/Ho_Chi_Minh", "", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := date(tc.t, tc.tz)
			if (err != nil) != tc.wantErr {
				t.Fatalf("err = %v, wantErr %v", err, tc.wantErr)
			}
			if got != tc.want {
				t.Fatalf("got %q want %q", got, tc.want)
			}
		})
	}
}

func TestPlural(t *testing.T) {
	cases := []struct {
		name    string
		n       any
		one     string
		many    []string
		want    string
		wantErr bool
	}{
		{"vi one", 1, "sản phẩm", nil, "1 sản phẩm", false},
		{"vi many no inflection", 3, "sản phẩm", nil, "3 sản phẩm", false},
		{"en one", 1, "item", []string{"items"}, "1 item", false},
		{"en many", 2, "item", []string{"items"}, "2 items", false},
		{"en zero uses many", 0, "item", []string{"items"}, "0 items", false},
		{"int64", int64(7), "item", nil, "7 item", false},
		{"not int", "x", "item", nil, "", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := plural(tc.n, tc.one, tc.many...)
			if (err != nil) != tc.wantErr {
				t.Fatalf("err = %v, wantErr %v", err, tc.wantErr)
			}
			if got != tc.want {
				t.Fatalf("got %q want %q", got, tc.want)
			}
		})
	}
}

func TestTruncate(t *testing.T) {
	cases := []struct {
		name string
		s    string
		n    int
		want string
	}{
		{"short unchanged", "Áo thun", 10, "Áo thun"},
		{"exact unchanged", "Áo thun", 7, "Áo thun"},
		{"cut with ellipsis", "Áo thun cotton", 8, "Áo thun…"},
		{"trailing space trimmed before ellipsis", "Áo thun cotton", 9, "Áo thun…"},
		{"multibyte safe", "Bình giữ nhiệt", 5, "Bình…"},
		{"n one", "abc", 1, "…"},
		{"n zero", "abc", 0, ""},
		{"empty", "", 5, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := truncate(tc.s, tc.n)
			if got != tc.want {
				t.Fatalf("got %q want %q", got, tc.want)
			}
			if tc.n > 0 && utf8.RuneCountInString(got) > tc.n {
				t.Fatalf("result %q longer than %d runes", got, tc.n)
			}
		})
	}
}

func TestMask(t *testing.T) {
	cases := []struct {
		name    string
		in      string
		wantErr bool
	}{
		{"masked label passes", "Thẻ Visa •••• 4242", false},
		{"cod passes", "Thanh toán khi nhận hàng", false},
		{"twelve digits pass", "123456789012", false},
		{"thirteen digits fail", "1234567890123", true},
		{"sixteen digits fail", "4242424242424242", true},
		{"spaced pan fails", "4242 4242 4242 4242", true},
		{"dashed pan fails", "4242-4242-4242-4242", true},
		{"nineteen digits fail", "1234567890123456789", true},
		{"twenty digits still fail", "12345678901234567890", true},
		{"pan inside label fails", "Visa 4242424242424242 hết hạn", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := mask(tc.in)
			if (err != nil) != tc.wantErr {
				t.Fatalf("err = %v, wantErr %v", err, tc.wantErr)
			}
			if !tc.wantErr && got != tc.in {
				t.Fatalf("mask must return input unchanged: got %q", got)
			}
		})
	}
}

func TestReasonLabel(t *testing.T) {
	for _, reason := range []string{"buyer", "payment_timeout", "admin", "out_of_stock"} {
		got, err := reasonLabel(reason)
		if err != nil || strings.TrimSpace(got) == "" {
			t.Fatalf("%s: got %q err %v", reason, got, err)
		}
	}
	if _, err := reasonLabel("fraud"); err == nil {
		t.Fatal("unknown reason must error")
	}
	if _, err := reasonLabel(""); err == nil {
		t.Fatal("empty reason must error")
	}
}

func TestDict(t *testing.T) {
	m, err := dict("URL", "https://x", "Label", "Go")
	if err != nil || m["URL"] != "https://x" || m["Label"] != "Go" {
		t.Fatalf("dict: %v %v", m, err)
	}
	if _, err := dict("URL"); err == nil {
		t.Fatal("odd args must error")
	}
	if _, err := dict(1, "x"); err == nil {
		t.Fatal("non-string key must error")
	}
}

func TestGroupThousands(t *testing.T) {
	for in, want := range map[string]string{"0": "0", "12": "12", "123": "123", "1234": "1.234", "123456": "123.456", "1234567": "1.234.567"} {
		if got := groupThousands(in); got != want {
			t.Errorf("%s: got %s want %s", in, got, want)
		}
	}
}
