package eventstore

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"time"

	_ "github.com/lib/pq"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/pkg/events"
)

// EventStore interface for persisting domain events
// Equivalent to Java's com.example.common.repository.EventStore
type EventStore interface {
	Save(ctx context.Context, event events.DomainEvent) error
	FindByAggregateID(ctx context.Context, aggregateID string) ([]events.DomainEvent, error)
	FindByEventType(ctx context.Context, eventType string) ([]events.DomainEvent, error)
	Close() error
}

// PostgreSQLEventStore implements EventStore using PostgreSQL
type PostgreSQLEventStore struct {
	db     *sql.DB
	logger *zap.Logger
}

// EventStoreConfig holds configuration for the event store
type EventStoreConfig struct {
	Host     string
	Port     int
	Database string
	User     string
	Password string
	SSLMode  string
	Logger   *zap.Logger
}

// NewPostgreSQLEventStore creates a new PostgreSQL event store
func NewPostgreSQLEventStore(config EventStoreConfig) (*PostgreSQLEventStore, error) {
	if config.Logger == nil {
		config.Logger, _ = zap.NewProduction()
	}

	dsn := fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
		config.Host, config.Port, config.User, config.Password, config.Database, config.SSLMode)

	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to database: %w", err)
	}

	// Configure connection pool
	db.SetMaxOpenConns(25)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(5 * time.Minute)

	// Verify connection
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := db.PingContext(ctx); err != nil {
		return nil, fmt.Errorf("failed to ping database: %w", err)
	}

	config.Logger.Info("Connected to PostgreSQL event store",
		zap.String("host", config.Host),
		zap.Int("port", config.Port),
		zap.String("database", config.Database))

	return &PostgreSQLEventStore{
		db:     db,
		logger: config.Logger,
	}, nil
}

// Save persists an event to the event store
func (s *PostgreSQLEventStore) Save(ctx context.Context, event events.DomainEvent) error {
	// Serialize event to JSON
	eventData, err := json.Marshal(event)
	if err != nil {
		s.logger.Error("Failed to serialize event", zap.Error(err))
		return fmt.Errorf("failed to serialize event: %w", err)
	}

	// Insert into event_store table
	query := `
		INSERT INTO event_store (event_id, aggregate_id, event_type, event_data, occurred_on, version)
		VALUES ($1, $2, $3, $4, $5, $6)
	`

	_, err = s.db.ExecContext(ctx, query,
		event.GetEventID(),
		event.GetAggregateID(),
		event.GetEventType(),
		eventData,
		event.GetOccurredOn(),
		event.GetVersion(),
	)

	if err != nil {
		s.logger.Error("Failed to save event",
			zap.String("eventId", event.GetEventID()),
			zap.String("eventType", event.GetEventType()),
			zap.Error(err))
		return fmt.Errorf("failed to save event: %w", err)
	}

	s.logger.Info("Event saved to store",
		zap.String("eventId", event.GetEventID()),
		zap.String("eventType", event.GetEventType()),
		zap.String("aggregateId", event.GetAggregateID()))

	return nil
}

// FindByAggregateID retrieves all events for a specific aggregate
func (s *PostgreSQLEventStore) FindByAggregateID(ctx context.Context, aggregateID string) ([]events.DomainEvent, error) {
	query := `
		SELECT event_id, aggregate_id, event_type, event_data, occurred_on, version
		FROM event_store
		WHERE aggregate_id = $1
		ORDER BY occurred_on ASC, version ASC
	`

	rows, err := s.db.QueryContext(ctx, query, aggregateID)
	if err != nil {
		s.logger.Error("Failed to query events by aggregate ID",
			zap.String("aggregateId", aggregateID),
			zap.Error(err))
		return nil, fmt.Errorf("failed to query events: %w", err)
	}
	defer rows.Close()

	var eventList []events.DomainEvent
	for rows.Next() {
		var eventData []byte
		var baseEvent events.BaseEvent

		err := rows.Scan(
			&baseEvent.EventID,
			&baseEvent.AggregateID,
			&baseEvent.EventType,
			&eventData,
			&baseEvent.OccurredOn,
			&baseEvent.Version,
		)
		if err != nil {
			s.logger.Error("Failed to scan event row", zap.Error(err))
			return nil, fmt.Errorf("failed to scan event: %w", err)
		}

		// Deserialize full event data
		if err := json.Unmarshal(eventData, &baseEvent); err != nil {
			s.logger.Error("Failed to deserialize event data", zap.Error(err))
			return nil, fmt.Errorf("failed to deserialize event: %w", err)
		}

		eventList = append(eventList, &baseEvent)
	}

	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("error iterating rows: %w", err)
	}

	s.logger.Info("Retrieved events by aggregate ID",
		zap.String("aggregateId", aggregateID),
		zap.Int("count", len(eventList)))

	return eventList, nil
}

// FindByEventType retrieves all events of a specific type
func (s *PostgreSQLEventStore) FindByEventType(ctx context.Context, eventType string) ([]events.DomainEvent, error) {
	query := `
		SELECT event_id, aggregate_id, event_type, event_data, occurred_on, version
		FROM event_store
		WHERE event_type = $1
		ORDER BY occurred_on ASC
	`

	rows, err := s.db.QueryContext(ctx, query, eventType)
	if err != nil {
		s.logger.Error("Failed to query events by event type",
			zap.String("eventType", eventType),
			zap.Error(err))
		return nil, fmt.Errorf("failed to query events: %w", err)
	}
	defer rows.Close()

	var eventList []events.DomainEvent
	for rows.Next() {
		var eventData []byte
		var baseEvent events.BaseEvent

		err := rows.Scan(
			&baseEvent.EventID,
			&baseEvent.AggregateID,
			&baseEvent.EventType,
			&eventData,
			&baseEvent.OccurredOn,
			&baseEvent.Version,
		)
		if err != nil {
			s.logger.Error("Failed to scan event row", zap.Error(err))
			return nil, fmt.Errorf("failed to scan event: %w", err)
		}

		if err := json.Unmarshal(eventData, &baseEvent); err != nil {
			s.logger.Error("Failed to deserialize event data", zap.Error(err))
			return nil, fmt.Errorf("failed to deserialize event: %w", err)
		}

		eventList = append(eventList, &baseEvent)
	}

	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("error iterating rows: %w", err)
	}

	s.logger.Info("Retrieved events by event type",
		zap.String("eventType", eventType),
		zap.Int("count", len(eventList)))

	return eventList, nil
}

// Close closes the database connection
func (s *PostgreSQLEventStore) Close() error {
	s.logger.Info("Closing event store connection")
	return s.db.Close()
}
