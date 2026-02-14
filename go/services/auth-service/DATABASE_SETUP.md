# Auth Service - Database Setup

## Prerequisites

1. Install sqlc:
```bash
# Windows (using scoop)
scoop install sqlc

# Or download from: https://github.com/sqlc-dev/sqlc/releases
```

2. Install PostgreSQL driver:
```bash
go get github.com/jackc/pgx/v5
go get github.com/jackc/pgx/v5/pgxpool
```

## Generate Database Code

Run sqlc to generate Go code from SQL queries:

```bash
# From auth-service directory
sqlc generate
```

This will:
- Read `sqlc.yaml` configuration
- Parse SQL schemas in `sql/schema/`
- Parse SQL queries in `sql/queries/`
- Generate Go code in `internal/infrastructure/database/postgres/`

## Run Migrations

1. Create database:
```sql
CREATE DATABASE identity_db;
```

2. Run schema migrations:
```bash
psql -U postgres -d identity_db -f sql/schema/001_auth_tables.sql
```

Or use your preferred migration tool (e.g., golang-migrate, goose)

## Project Structure

```
auth-service/
├── sql/
│   ├── schema/          # SQL schemas (DDL)
│   │   └── 001_auth_tables.sql
│   └── queries/         # SQL queries (DML)
│       ├── sessions.sql
│       ├── login_attempts.sql
│       └── password_reset_tokens.sql
│
├── internal/
│   ├── domain/
│   │   ├── entity/      # Domain entities
│   │   └── repository/  # Repository interfaces (ports)
│   │
│   ├── infrastructure/
│   │   └── database/
│   │       └── postgres/
│   │           ├── db.go              # pgx connection pool
│   │           ├── models.go          # Generated models
│   │           ├── querier.go         # Generated interface
│   │           └── *.sql.go           # Generated queries
│   │
│   └── usecase/         # Business logic
│
└── sqlc.yaml           # sqlc configuration
```

## Usage Example

```go
import (
    "context"
    "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/infrastructure/database/postgres"
)

// Create connection
db, err := postgres.NewDB(ctx, cfg, logger)
if err != nil {
    // handle error
}
defer db.Close()

// Use generated queries
queries := postgres.New(db.Pool())

// Create session
session, err := queries.CreateSession(ctx, postgres.CreateSessionParams{
    SessionID: uuid.New(),
    UserID: userID,
    // ... other params
})
```

## Clean Architecture

- **Domain Layer**: Entities and repository interfaces (ports)
- **Infrastructure Layer**: PostgreSQL implementation with pgx + sqlc
- **Usecase Layer**: Business logic using repository interfaces
- **Dependency Rule**: Dependencies point inward (infrastructure depends on domain)
