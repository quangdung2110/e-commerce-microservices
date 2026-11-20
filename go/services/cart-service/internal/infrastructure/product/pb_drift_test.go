package product

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestGeneratedStubsMatchCanonicalProto fails when a canonical .proto changed and the committed stubs were
// not regenerated (Cart ADR-015). `make generate` (scripts/generate-pb.sh) records the SHA-256 of each source
// proto, with line endings normalized, in pb/PROTO_SHA256; this compares them with the files in contracts/.
//
// The test skips when contracts/ is not next to the service, as in a Docker build whose context is only the
// service directory; CI and a developer checkout have it.
func TestGeneratedStubsMatchCanonicalProto(t *testing.T) {
	root := filepath.Join("..", "..", "..", "..", "..", "..", "contracts")
	canonical := map[string]string{
		"product_service.proto": filepath.Join(root, "grpc", "ecommerce", "product", "v1", "product_service.proto"),
		"money.proto":           filepath.Join(root, "shared", "proto", "ecommerce", "common", "v1", "money.proto"),
	}
	if _, err := os.Stat(canonical["product_service.proto"]); err != nil {
		t.Skip("contracts/ is not available next to this service")
	}

	recorded := map[string]string{}
	raw, err := os.ReadFile(filepath.Join("pb", "PROTO_SHA256"))
	if err != nil {
		t.Fatalf("pb/PROTO_SHA256 is missing, run scripts/generate-pb.sh: %v", err)
	}
	for _, line := range strings.Split(strings.TrimSpace(string(raw)), "\n") {
		fields := strings.Fields(line)
		if len(fields) == 2 {
			recorded[fields[1]] = fields[0]
		}
	}

	for name, path := range canonical {
		content, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("read %s: %v", path, err)
		}
		sum := sha256.Sum256([]byte(strings.ReplaceAll(string(content), "\r", "")))
		if got := hex.EncodeToString(sum[:]); recorded[name] != got {
			t.Errorf("%s changed since the stubs were generated (recorded %q, now %q): run scripts/generate-pb.sh",
				name, recorded[name], got)
		}
	}
}
