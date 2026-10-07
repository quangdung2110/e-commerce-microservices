package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"fmt"

	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

// nonceLength is fixed at 12 bytes by the schema CHECK constraint and by the
// AES-GCM standard nonce size.
const nonceLength = 12

// KeyCipher encrypts signing-key material with AES-256-GCM. The master key comes
// from a secret of the service and is never stored in the database, so a dump of
// auth.jwt_keys alone cannot yield a usable private key.
//
// encryption_key_version records which master key encrypted a row, so the master
// key can be rotated while older rows stay readable.
type KeyCipher struct {
	current  []byte
	version  int16
	previous map[int16][]byte
}

// NewKeyCipher wires the cipher from the configured master keys.
func NewKeyCipher(cfg config.KeysConfig) (*KeyCipher, error) {
	current, err := cfg.MasterKey()
	if err != nil {
		return nil, err
	}
	previous, err := cfg.PreviousMasterKeys()
	if err != nil {
		return nil, err
	}
	version := cfg.MasterKeyVersion
	if version <= 0 {
		version = 1
	}
	return &KeyCipher{current: current, version: version, previous: previous}, nil
}

// Version is the master-key version new rows are written with.
func (c *KeyCipher) Version() int16 { return c.version }

// Encrypt seals plaintext with a fresh nonce.
func (c *KeyCipher) Encrypt(plaintext []byte) (ciphertext, nonce []byte, err error) {
	gcm, err := newGCM(c.current)
	if err != nil {
		return nil, nil, err
	}

	nonce = make([]byte, nonceLength)
	if _, err := rand.Read(nonce); err != nil {
		return nil, nil, fmt.Errorf("crypto: read nonce: %w", err)
	}
	return gcm.Seal(nil, nonce, plaintext, nil), nonce, nil
}

// Decrypt opens a ciphertext written with the given master-key version.
func (c *KeyCipher) Decrypt(ciphertext, nonce []byte, version int16) ([]byte, error) {
	key := c.current
	if version != c.version {
		previous, ok := c.previous[version]
		if !ok {
			return nil, fmt.Errorf("crypto: master key version %d is not configured", version)
		}
		key = previous
	}

	gcm, err := newGCM(key)
	if err != nil {
		return nil, err
	}
	plaintext, err := gcm.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return nil, fmt.Errorf("crypto: decrypt private key: %w", err)
	}
	return plaintext, nil
}

func newGCM(key []byte) (cipher.AEAD, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("crypto: build cipher: %w", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("crypto: build gcm: %w", err)
	}
	return gcm, nil
}

var _ port.KeyGenerator = (*RSAKeyGenerator)(nil)

// RSAKeyGenerator creates RS256 signing material. The private key is encrypted
// before it leaves this type, so no caller ever holds it in the clear.
type RSAKeyGenerator struct {
	bits   int
	cipher *KeyCipher
}

// NewRSAKeyGenerator wires the key generator.
func NewRSAKeyGenerator(bits int, keyCipher *KeyCipher) *RSAKeyGenerator {
	if bits < 2048 {
		// Anything below 2048 bits is not acceptable for a signing key.
		bits = 2048
	}
	return &RSAKeyGenerator{bits: bits, cipher: keyCipher}
}

// Generate returns a new key pair: the public half as PEM for JWKS, the private
// half as AES-256-GCM ciphertext over its PKCS#8 encoding.
func (g *RSAKeyGenerator) Generate() (port.KeyMaterial, error) {
	privateKey, err := rsa.GenerateKey(rand.Reader, g.bits)
	if err != nil {
		return port.KeyMaterial{}, fmt.Errorf("crypto: generate rsa key: %w", err)
	}

	privateDER, err := x509.MarshalPKCS8PrivateKey(privateKey)
	if err != nil {
		return port.KeyMaterial{}, fmt.Errorf("crypto: marshal private key: %w", err)
	}
	ciphertext, nonce, err := g.cipher.Encrypt(privateDER)
	if err != nil {
		return port.KeyMaterial{}, err
	}

	publicDER, err := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)
	if err != nil {
		return port.KeyMaterial{}, fmt.Errorf("crypto: marshal public key: %w", err)
	}
	publicPEM := pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: publicDER})

	return port.KeyMaterial{
		PublicKeyPEM:         string(publicPEM),
		PrivateKeyCiphertext: ciphertext,
		PrivateKeyNonce:      nonce,
		EncryptionKeyVersion: g.cipher.Version(),
	}, nil
}

// parsePrivateKey decodes the PKCS#8 private key recovered from a ciphertext.
func parsePrivateKey(der []byte) (*rsa.PrivateKey, error) {
	parsed, err := x509.ParsePKCS8PrivateKey(der)
	if err != nil {
		return nil, fmt.Errorf("crypto: parse private key: %w", err)
	}
	privateKey, ok := parsed.(*rsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("crypto: signing key is not an RSA key")
	}
	return privateKey, nil
}

// parsePublicKeyPEM decodes a stored public key.
func parsePublicKeyPEM(publicPEM string) (*rsa.PublicKey, error) {
	block, _ := pem.Decode([]byte(publicPEM))
	if block == nil {
		return nil, fmt.Errorf("crypto: public key is not valid PEM")
	}
	parsed, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("crypto: parse public key: %w", err)
	}
	publicKey, ok := parsed.(*rsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("crypto: public key is not an RSA key")
	}
	return publicKey, nil
}
