# Go Interface Design Rules

> **Status:** Standard  
> **Applies To:** All Golang microservices

---

## 1. Define Where Consumed, Not Implemented

```
domain/port/    → Define interfaces HERE
domain/service/ → Use interfaces
infrastructure/ → Implement interfaces
```

```go
// ✅ CORRECT: domain/port/user_repository.go
package port

type UserRepository interface {
    GetByID(ctx context.Context, id string) (*entity.User, error)
    Create(ctx context.Context, user *entity.User) error
}

// ❌ WRONG: infrastructure/postgres/user_repo.go
package postgres

type UserRepository interface { ... }  // Don't define here!
```

---

## 2. Small Interfaces (1-10 methods)

```go
// ✅ GOOD: Focused, composable
type Reader[T any] interface {
    GetByID(ctx context.Context, id string) (T, error)
    List(ctx context.Context, limit, offset int) ([]T, error)
}

type Writer[T any] interface {
    Create(ctx context.Context, entity T) error
    Update(ctx context.Context, entity T) error
    Delete(ctx context.Context, id string) error
}

type UserRepository interface {
    Reader[*entity.User]
    Writer[*entity.User]
    GetByEmail(ctx context.Context, email string) (*entity.User, error)
}

// ❌ BAD: 30+ methods in one interface
type UserRepository interface {
    GetByID(ctx context.Context, id string) error
    GetByEmail(ctx context.Context, email string) error
    GetByPhone(ctx context.Context, phone string) error
    // ... 30 more methods
}
```

---

## 3. Naming Conventions

| Pattern | Example | Use For |
|---------|---------|---------|
| `Noun + er` | `Reader`, `Closer` | Single operation |
| `Domain + Repository` | `UserRepository`, `OrderRepository` | Data access |
| `Domain + Cache` | `TokenCache`, `SessionCache` | Caching |
| `Domain + Publisher` | `EventPublisher` | Event publishing |
| `Action + er` | `Hasher`, `Validator` | Single purpose |

```go
// ✅ Standard names
type PasswordHasher interface {
    Hash(ctx context.Context, password string) (string, error)
    Verify(ctx context.Context, password, hash string) (bool, error)
}

type EventPublisher interface {
    Publish(ctx context.Context, event domain.Event) error
}

// ❌ Avoid
type IUserRepository interface {}      // No "I" prefix
type UserRepositoryInterface {}        // No "Interface" suffix
```

---

## 4. Verify at Compile Time

```go
// internal/infrastructure/postgres/user_repo.go
package postgres

// Compile-time check: ensures *UserRepository implements port.UserRepository
var _ port.UserRepository = (*UserRepository)(nil)

type UserRepository struct {
    db *gorm.DB
}

func NewUserRepository(db *gorm.DB) port.UserRepository {
    return &UserRepository{db: db}
}
```

---

## 5. Constructor Injection

```go
// ✅ CORRECT: Constructor takes interfaces
func NewAuthService(
    userRepo port.UserRepository,
    cache port.Cache,
    hasher port.PasswordHasher,
) *AuthService {
    return &AuthService{
        userRepo: userRepo,
        cache:    cache,
        hasher:   hasher,
    }
}

// ❌ WRONG: Constructor takes concrete types
func NewAuthService(userRepo *postgres.UserRepository) {
    // Domain now depends on infrastructure!
}
```

---

## 6. Return Interfaces, Accept Concrete Types

```go
// ✅ GOOD: Return interface, accept concrete
type UserService struct {}

func (s *UserService) CreateProfile(ctx context.Context, req CreateProfileRequest) (*entity.Profile, error) {
    // ...
}

func (s *UserService) GetRepository() port.UserRepository {
    return s.repo  // Returns interface
}

// ❌ Unnecessary: Interface for single implementation
func NewService() SomeInterface {}  // Only one impl? May not need interface.
```

---

## 7. Null Object for Optional Dependencies

```go
// ✅ GOOD: Null object instead of nil
type nopCache struct{}

func (nopCache) Get(ctx context.Context, key string) (string, error) {
    return "", ErrCacheMiss
}

func NewService(cache port.Cache) *Service {
    if cache == nil {
        cache = nopCache{}
    }
    return &Service{cache: cache}
}

// ❌ BAD: Nil interface causes panic
func (s *Service) Get(id string) {
    s.cache.Get(...)  // Panic if s.cache is nil interface!
}
```

---

## 8. Method Signatures

```go
// Standard context pattern
type Repository interface {
    GetByID(ctx context.Context, id string) (*Entity, error)
    Create(ctx context.Context, entity *Entity) error
    // ...
}

// Use domain types, not primitives
type UserRepository interface {
    Create(ctx context.Context, user *entity.User) error  // ✅ Domain type
    Create(ctx context.Context, id, email, name string) error  // ❌ Primitives
}

// Return errors explicitly
type Cache interface {
    Get(ctx context.Context, key string) (string, error)  // ✅ Returns error
    // Get(ctx context.Context, key string) string  // ❌ No error
}
```

---

## 9. Test Mocks

```go
// test/mocks/user_repo_mock.go
package mocks

import (
    "context"
    "sync"
    "github.com/stretchr/testify/mock"
    "ecommerce/auth-service/internal/domain/entity"
    "ecommerce/auth-service/internal/domain/port"
)

type MockUserRepository struct {
    mock.Mock
    mu    sync.RWMutex
    users map[string]*entity.User
}

func (m *MockUserRepository) GetByID(ctx context.Context, id string) (*entity.User, error) {
    args := m.Called(ctx, id)
    if args.Get(0) == nil {
        return nil, args.Error(1)
    }
    return args.Get(0).(*entity.User), args.Error(1)
}

func (m *MockUserRepository) Create(ctx context.Context, user *entity.User) error {
    args := m.Called(ctx, user)
    return args.Error(0)
}
```

---

## Summary

| Rule | Do | Don't |
|------|-----|-------|
| Location | Define in `domain/port/` | Define in `infrastructure/` |
| Size | Small (1-10 methods) | Large (20+ methods) |
| Naming | `UserRepository`, `Cache` | `IUserRepository` |
| Verification | `var _ Interface = (*Impl)(nil)` | Skip verification |
| Injection | Constructor with interfaces | Constructor with concrete types |
| Optional deps | Null object | Nil interface |
| Returns | Interfaces | Concrete types |

---

*Last updated: May 20, 2026*
