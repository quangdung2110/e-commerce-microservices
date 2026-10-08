// Package config_test checks the configuration invariants that decide how the
// service is deployed.
package config_test

import (
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/ecommerce/user-service/internal/common/config"
)

func TestDefaultsAreValid(t *testing.T) {
	cfg, err := config.Load("")

	require.NoError(t, err)
	require.Equal(t, []string{"checkout-service"}, cfg.Internal.AllowedCallers)
	require.Equal(t, "t.notification.outcomes.v1", cfg.Kafka.NotificationTopic)
	require.Equal(t, "t.notification.outcomes.v1.dlq", cfg.Kafka.NotificationDLQTopic,
		"complaints are parked apart from Auth events so a redrive never mixes them")
	require.Equal(t, 120, cfg.Security.RateLimit.InternalPerUser.Limit)
}

func TestShippedConfigFileLoadsWithTheSameValues(t *testing.T) {
	cfg, err := config.Load("../../../configs/application.yaml")

	require.NoError(t, err, "the file operators start from must pass Validate")
	require.Equal(t, []string{"checkout-service"}, cfg.Internal.AllowedCallers)
	require.Equal(t, "t.notification.outcomes.v1", cfg.Kafka.NotificationTopic)
	require.Equal(t, "t.notification.outcomes.v1.dlq", cfg.Kafka.NotificationDLQTopic)
	require.Equal(t, 120, cfg.Security.RateLimit.InternalPerUser.Limit)
}

func TestAllowedCallersCanBeOverriddenFromTheEnvironment(t *testing.T) {
	t.Setenv("USER_INTERNAL_ALLOWED_CALLERS", "checkout-service,order-service")

	cfg, err := config.Load("../../../configs/application.yaml")

	require.NoError(t, err)
	require.Equal(t, []string{"checkout-service", "order-service"}, cfg.Internal.AllowedCallers)
}

func TestInternalListenerNeedsAtLeastOneAllowedCaller(t *testing.T) {
	cfg, err := config.Load("")
	require.NoError(t, err)

	cfg.Internal.AllowedCallers = []string{"  ", ""}

	// An empty allowlist would refuse every caller: that is a misconfigured
	// deployment, not a valid closed mode.
	require.ErrorContains(t, cfg.Validate(), "internal.allowed_callers")
}

func TestProductionNoLongerRequiresApplicationLevelMTLS(t *testing.T) {
	cfg, err := config.Load("")
	require.NoError(t, err)

	cfg.App.Env = "production"

	// Transport security is the mesh's job (docs/mesh/runtime-contract.md): the
	// application never sees the caller certificate, so demanding one here would
	// make the service impossible to start behind the mesh.
	require.NoError(t, cfg.Validate())
}

func TestConsumerNeedsBothTopicsAndTheirDeadLetterQueues(t *testing.T) {
	cfg, err := config.Load("")
	require.NoError(t, err)

	cfg.Kafka.NotificationTopic = ""
	cfg.Kafka.NotificationDLQTopic = ""

	err = cfg.Validate()
	require.ErrorContains(t, err, "kafka.notification_topic")
	require.ErrorContains(t, err, "kafka.notification_dlq_topic")
}
