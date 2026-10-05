package middleware

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/api/httpx"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/logging"
	"github.com/ecommerce/notification-service/internal/common/reqctx"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

const operationContextKey = "notification.operation"

// Operation names the route with its OpenAPI operationId so the audit trail
// and the ops_calls_total metric use stable names instead of path patterns.
func Operation(name string) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Set(operationContextKey, name)
		c.Next()
	}
}

// OperationFrom returns the operationId set by Operation, or method + route.
func OperationFrom(c *gin.Context) string {
	if v, ok := c.Get(operationContextKey); ok {
		if name, ok := v.(string); ok && name != "" {
			return name
		}
	}
	return c.Request.Method + " " + c.FullPath()
}

// Audit records every ops call after it completes: principal, operation,
// sanitised parameters and the result status, both in ops_audit and on the
// audit log stream (14-observability.md §6, 16-operations.md §2). Parameters
// never include an address: bodies are stripped of any email-like key, and
// the ops contract never accepts one anyway.
func Audit(ops port.OpsRepository, clk clock.Clock, metrics *telemetry.Metrics, logger *zap.Logger) gin.HandlerFunc {
	audit := logging.Audit(logger)
	return func(c *gin.Context) {
		// Read (and cache) the body before the handler consumes it.
		body, _ := readBodyQuietly(c)
		c.Next()

		ctx := c.Request.Context()
		operation := OperationFrom(c)
		status := c.Writer.Status()
		if status == 0 {
			status = http.StatusOK
		}
		result := strconv.Itoa(status)
		principal := PrincipalFrom(c)

		params := sanitisedParams(c, body)
		entry := &entity.AuditEntry{
			ID:        uuid.New(),
			Principal: principal,
			Operation: operation,
			Params:    params,
			Result:    result,
			CreatedAt: clk.Now(),
		}
		if err := ops.InsertAudit(ctx, entry); err != nil {
			logging.FromContext(ctx).Error("audit entry not persisted",
				zap.String("operation", operation), zap.Error(err))
		}
		audit.Info("ops call",
			zap.String("audit_id", entry.ID.String()),
			zap.String("principal", principal),
			zap.String("operation", operation),
			zap.String("result", result),
			zap.String("request_id", requestIDOf(c)),
			zap.ByteString("params", params),
		)
		if metrics != nil {
			metrics.OpsCallsTotal.WithLabelValues(operation, result).Inc()
		}
	}
}

func requestIDOf(c *gin.Context) string {
	return reqctx.RequestID(c.Request.Context())
}

func readBodyQuietly(c *gin.Context) ([]byte, error) {
	if c.Request.Method == http.MethodGet || c.Request.Method == http.MethodDelete {
		return nil, nil
	}
	return httpx.ReadBody(c)
}

// sanitisedParams builds {"path", "query", "body"}; body keys whose name
// mentions "email" are dropped defensively.
func sanitisedParams(c *gin.Context, body []byte) json.RawMessage {
	params := map[string]any{
		"path": c.Request.URL.Path,
	}
	if q := c.Request.URL.Query(); len(q) > 0 {
		query := make(map[string]string, len(q))
		for k, v := range q {
			if strings.Contains(strings.ToLower(k), "email") || len(v) == 0 {
				continue
			}
			query[k] = v[0]
		}
		params["query"] = query
	}
	if len(body) > 0 {
		var decoded map[string]any
		if err := json.Unmarshal(body, &decoded); err == nil {
			for k := range decoded {
				if strings.Contains(strings.ToLower(k), "email") {
					delete(decoded, k)
				}
			}
			params["body"] = decoded
		} else {
			params["body"] = "<non-object>"
		}
	}
	raw, err := json.Marshal(params)
	if err != nil {
		return json.RawMessage(`{}`)
	}
	return raw
}
