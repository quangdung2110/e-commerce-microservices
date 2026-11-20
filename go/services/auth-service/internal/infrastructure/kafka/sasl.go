package kafka

import (
	"github.com/IBM/sarama"
	"github.com/xdg-go/scram"
)

// applySASL authenticates as the service's own principal with SASL/SCRAM-SHA-512
// (mesh ADR-003): the broker grants each principal only its own topics and
// consumer groups. An empty username keeps PLAINTEXT, for tests and a
// standalone broker.
func applySASL(c *sarama.Config, username, password string) {
	if username == "" {
		return
	}
	c.Net.SASL.Enable = true
	c.Net.SASL.Handshake = true
	c.Net.SASL.User = username
	c.Net.SASL.Password = password
	c.Net.SASL.Mechanism = sarama.SASLTypeSCRAMSHA512
	c.Net.SASL.SCRAMClientGeneratorFunc = func() sarama.SCRAMClient {
		return &scramClient{hash: scram.SHA512}
	}
}

// scramClient adapts xdg-go/scram to sarama.SCRAMClient.
type scramClient struct {
	hash         scram.HashGeneratorFcn
	conversation *scram.ClientConversation
}

func (s *scramClient) Begin(userName, password, authzID string) error {
	client, err := s.hash.NewClient(userName, password, authzID)
	if err != nil {
		return err
	}
	s.conversation = client.NewConversation()
	return nil
}

func (s *scramClient) Step(challenge string) (string, error) {
	return s.conversation.Step(challenge)
}

func (s *scramClient) Done() bool {
	return s.conversation.Done()
}
