package config

import (
	"strings"
	"testing"
)

// Kafka requires SASL/SCRAM per service principal (mesh ADR-003); docker-compose
// and deploy/k8s set the principal through USER_KAFKA_SASL_*.
func TestKafkaSASLPrincipalFromEnv(t *testing.T) {
	t.Setenv("REDIS_PASSWORD", "redis-secret-from-env")
	t.Setenv("DB_PASSWORD", "postgres")
	t.Setenv("USER_KAFKA_SASL_USERNAME", "user-service")
	t.Setenv("USER_KAFKA_SASL_PASSWORD", "kafka-secret-from-env")

	cfg, err := Load("../../../configs/application.yaml")
	if err != nil {
		t.Fatalf("configs/application.yaml: %v", err)
	}
	if cfg.Kafka.SASLUsername != "user-service" || cfg.Kafka.SASLPassword != "kafka-secret-from-env" {
		t.Fatalf("kafka sasl = %q/%q, want the USER_KAFKA_SASL_* values", cfg.Kafka.SASLUsername, cfg.Kafka.SASLPassword)
	}

	t.Setenv("USER_KAFKA_SASL_PASSWORD", "")
	if _, err := Load("../../../configs/application.yaml"); err == nil || !strings.Contains(err.Error(), "kafka.sasl_password") {
		t.Fatalf("a username without a password must be rejected, got %v", err)
	}
}
