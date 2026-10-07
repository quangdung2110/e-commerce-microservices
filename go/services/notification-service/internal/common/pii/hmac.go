package pii

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
)

// MinHMACKeyBytes is the shortest privacy key accepted outside development.
const MinHMACKeyBytes = 32

// KeyedHash is one versioned HMAC of a subject or a user id.
type KeyedHash struct {
	Version int16
	Hash    string // hex HMAC-SHA256, 64 chars
}

// Keyring holds the versioned privacy HMAC keys (09-data-model.md §3, §10;
// 11-privacy.md §2). Long-lived tables keep HMAC(subject) / HMAC(user_id)
// instead of raw order ids or user UUIDs. Writes use the active version;
// reads look at every configured version so a key rotation never splits one
// subject into two sequence streams or loses a tombstone (dual-read). Old
// rows are folded into the active version when a subject is touched again
// (09-data-model.md §3, lazy merge).
type Keyring struct {
	active   int16
	keys     map[int16][]byte
	versions []int16 // active first, then the others in descending order
}

// NewKeyring builds a keyring. previous may be nil; it must not redefine the
// active version.
func NewKeyring(activeVersion int16, activeKey []byte, previous map[int16][]byte) (*Keyring, error) {
	if activeVersion < 1 {
		return nil, fmt.Errorf("pii: hmac key version must be >= 1, got %d", activeVersion)
	}
	if len(activeKey) == 0 {
		return nil, errors.New("pii: empty active hmac key")
	}
	k := &Keyring{active: activeVersion, keys: map[int16][]byte{activeVersion: activeKey}}
	others := make([]int16, 0, len(previous))
	for v, key := range previous {
		if v == activeVersion {
			return nil, fmt.Errorf("pii: previous hmac key redefines active version %d", v)
		}
		if v < 1 || len(key) == 0 {
			return nil, fmt.Errorf("pii: invalid previous hmac key version %d", v)
		}
		k.keys[v] = key
		others = append(others, v)
	}
	sort.Slice(others, func(i, j int) bool { return others[i] > others[j] })
	k.versions = append([]int16{activeVersion}, others...)
	return k, nil
}

// ParsePreviousKeys reads "version:key,version:key" (keys must not contain
// ',' or ':' — use hex or base64).
func ParsePreviousKeys(raw string) (map[int16][]byte, error) {
	out := map[int16][]byte{}
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return out, nil
	}
	for _, part := range strings.Split(raw, ",") {
		v, key, ok := strings.Cut(strings.TrimSpace(part), ":")
		if !ok || key == "" {
			return nil, errors.New("pii: previous hmac keys must look like version:key[,version:key]")
		}
		n, err := strconv.ParseInt(v, 10, 16)
		if err != nil {
			return nil, fmt.Errorf("pii: previous hmac key version %q: %w", v, err)
		}
		if _, dup := out[int16(n)]; dup {
			return nil, fmt.Errorf("pii: previous hmac key version %d listed twice", n)
		}
		out[int16(n)] = []byte(key)
	}
	return out, nil
}

// ActiveVersion is the version every new row is written with.
func (k *Keyring) ActiveVersion() int16 { return k.active }

// Versions lists every readable version, active first.
func (k *Keyring) Versions() []int16 { return append([]int16(nil), k.versions...) }

// SubjectHashes returns HMAC(source ‖ NUL ‖ subject) for every version,
// active first.
func (k *Keyring) SubjectHashes(source, subject string) []KeyedHash {
	return k.all(source + "\x00" + subject)
}

// UserHashes returns HMAC("user" ‖ NUL ‖ user_id) for every version, active
// first. The prefix keeps user keys and subject keys in separate domains.
func (k *Keyring) UserHashes(userID string) []KeyedHash {
	return k.all("user\x00" + strings.ToLower(userID))
}

// OrderingKey is the persisted per-subject serialisation key of
// notification_delivery.ordering_key / notification_ordering_lease:
// source:active_version:HMAC(source ‖ NUL ‖ subject). No raw subject.
func (k *Keyring) OrderingKey(source, subject string) string {
	h := k.sum(k.active, source+"\x00"+subject)
	return FormatOrderingKey(source, KeyedHash{Version: k.active, Hash: h})
}

// FormatOrderingKey renders source:version:hash.
func FormatOrderingKey(source string, h KeyedHash) string {
	return source + ":" + strconv.Itoa(int(h.Version)) + ":" + h.Hash
}

// ParseOrderingKey splits an ordering key from the right (the source itself
// contains no ':' today, but /services/x paths could).
func ParseOrderingKey(key string) (source string, h KeyedHash, ok bool) {
	i := strings.LastIndexByte(key, ':')
	if i <= 0 {
		return "", KeyedHash{}, false
	}
	j := strings.LastIndexByte(key[:i], ':')
	if j <= 0 {
		return "", KeyedHash{}, false
	}
	v, err := strconv.ParseInt(key[j+1:i], 10, 16)
	if err != nil || len(key[i+1:]) != 64 {
		return "", KeyedHash{}, false
	}
	return key[:j], KeyedHash{Version: int16(v), Hash: key[i+1:]}, true
}

func (k *Keyring) all(msg string) []KeyedHash {
	out := make([]KeyedHash, 0, len(k.versions))
	for _, v := range k.versions {
		out = append(out, KeyedHash{Version: v, Hash: k.sum(v, msg)})
	}
	return out
}

func (k *Keyring) sum(version int16, msg string) string {
	mac := hmac.New(sha256.New, k.keys[version])
	mac.Write([]byte(msg))
	return hex.EncodeToString(mac.Sum(nil))
}
