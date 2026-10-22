package kafka

import (
	"testing"

	"github.com/IBM/sarama"
	"github.com/xdg-go/scram"
)

func TestApplySASLLeavesPlaintextWithoutUsername(t *testing.T) {
	cfg := sarama.NewConfig()
	applySASL(cfg, "", "")
	if cfg.Net.SASL.Enable {
		t.Fatal("SASL enabled without a username")
	}
}

func TestApplySASLUsesSCRAMSHA512(t *testing.T) {
	cfg := sarama.NewConfig()
	applySASL(cfg, "svc", "secret")
	sasl := cfg.Net.SASL
	if !sasl.Enable || !sasl.Handshake || sasl.User != "svc" || sasl.Password != "secret" ||
		sasl.Mechanism != sarama.SASLTypeSCRAMSHA512 || sasl.SCRAMClientGeneratorFunc == nil {
		t.Fatalf("unexpected SASL config: %+v", sasl)
	}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("sarama rejects the config: %v", err)
	}
}

// The adapter must complete a real SCRAM-SHA-512 exchange, and a wrong password
// must fail it, as the broker would.
func TestSCRAMClientAuthenticatesAgainstAServer(t *testing.T) {
	for _, tc := range []struct {
		password string
		valid    bool
	}{{"secret", true}, {"wrong", false}} {
		stored, err := scram.SHA512.NewClient("svc", "secret", "")
		if err != nil {
			t.Fatal(err)
		}
		creds := stored.GetStoredCredentials(scram.KeyFactors{Salt: "salt-of-svc", Iters: 4096})
		server, err := scram.SHA512.NewServer(func(string) (scram.StoredCredentials, error) { return creds, nil })
		if err != nil {
			t.Fatal(err)
		}
		conversation := server.NewConversation()

		cfg := sarama.NewConfig()
		applySASL(cfg, "svc", tc.password)
		client := cfg.Net.SASL.SCRAMClientGeneratorFunc()
		if err := client.Begin("svc", tc.password, ""); err != nil {
			t.Fatal(err)
		}
		message, err := client.Step("")
		for err == nil && !client.Done() {
			var challenge string
			if challenge, err = conversation.Step(message); err != nil {
				break
			}
			message, err = client.Step(challenge)
		}
		if tc.valid && (err != nil || !conversation.Valid()) {
			t.Fatalf("password %q: exchange failed: %v", tc.password, err)
		}
		if !tc.valid && err == nil && conversation.Valid() {
			t.Fatalf("password %q: exchange succeeded", tc.password)
		}
	}
}
