package media

import "testing"

func TestImage(t *testing.T) {
	urls := NewURLs("https://cdn.example.test/media", "https://cdn.example.test/media/placeholder/product.svg")

	cases := []struct {
		name      string
		reference string
		want      string
	}{
		{"storage key", "products/watch/hero.jpg", "https://cdn.example.test/media/products/watch/hero.jpg"},
		{"leading slash is not doubled", "/products/watch/hero.jpg", "https://cdn.example.test/media/products/watch/hero.jpg"},
		{"segments are percent-encoded", "products/áo thun/ảnh 1?.jpg", "https://cdn.example.test/media/products/%C3%A1o%20thun/%E1%BA%A3nh%201%3F.jpg"},
		{"no image yields the placeholder", "", "https://cdn.example.test/media/placeholder/product.svg"},
		{"legacy absolute https URL is kept", "https://example.test/products/tshirt-red.png", "https://example.test/products/tshirt-red.png"},
		{"legacy absolute http URL is kept", "http://localhost:8088/media/a.png", "http://localhost:8088/media/a.png"},
		{"a key that only looks like a scheme is still a key", "products:archive/a.png", "https://cdn.example.test/media/products:archive/a.png"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := urls.Image(tc.reference); got != tc.want {
				t.Fatalf("Image(%q) = %q, want %q", tc.reference, got, tc.want)
			}
		})
	}
}
