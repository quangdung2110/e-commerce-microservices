# Implementation Plan: Identity Services Group

> **Services**: Auth Service, User Service, Seller Service  
> **Priority**: ⭐⭐⭐⭐⭐ CRITICAL (Foundation for all other services)  
> **Estimated Time**: 3-4 weeks  
> **Dependencies**: Common module (Event Sourcing base classes)

---

## 📋 Table of Contents
1. [Overview & Objectives](#overview--objectives)
2. [Architecture Decisions](#architecture-decisions)
3. [Phase 1: Foundation Setup](#phase-1-foundation-setup)
4. [Phase 2: Auth Service Implementation](#phase-2-auth-service-implementation)
5. [Phase 3: User Service Implementation](#phase-3-user-service-implementation)
6. [Phase 4: Seller Service Implementation](#phase-4-seller-service-implementation)
7. [Phase 5: Integration & Security](#phase-5-integration--security)
8. [Phase 6: Testing & Optimization](#phase-6-testing--optimization)
9. [AI Agent Task Breakdown](#ai-agent-task-breakdown)
10. [Validation Checklist](#validation-checklist)

---

## Overview & Objectives

### Business Goals
- Secure authentication and authorization for all users
- Support multiple auth methods (password, OAuth, biometric)
- Manage user profiles and preferences
- Handle seller registration and verification
- Track user behavior for fraud detection

### Technical Goals
- JWT-based stateless authentication
- Redis for session management and OTP storage
- Event Sourcing for audit trail
- Rate limiting to prevent abuse
- Multi-database strategy (PostgreSQL + Redis)

### Success Metrics
- Login success rate > 99%
- OTP delivery time < 30 seconds
- Session validation time < 50ms
- Zero unauthorized access incidents

---

## Architecture Decisions

### 1. Shared Database Strategy
**Decision**: All three services share `identity_db` database

**Rationale**:
- Strong consistency for User-Seller relationships
- ACID transactions for critical operations
- Simplified deployment and backup

**Trade-offs**:
- Tight coupling between services
- Requires careful schema migration coordination

### 2. Redis Strategy
**Decision**: Single Redis cluster with namespace prefixes

**Prefixes**:
- `auth:*` - Auth service keys
- `user:*` - User service cache
- `seller:*` - Seller service cache

### 3. Security Strategy
**Decision**: Multi-layer security approach

**Layers**:
1. Network: IP whitelisting, rate limiting
2. Application: JWT validation, RBAC
3. Data: Field-level encryption (PII)
4. Audit: Event Sourcing for all sensitive operations

---

## Phase 1: Foundation Setup

### 1.1 Database Setup (Agent Task: DB-IDENTITY-001)

**Objective**: Create shared `identity_db` with all tables

**Tasks**:
```bash
# 1. Create database
CREATE DATABASE identity_db;

# 2. Create tables in order (respect FK dependencies):
- users
- user_addresses
- user_preferences
- user_restrictions
- auth_sessions
- auth_login_attempts
- auth_password_reset_tokens
- sellers
- seller_verification
- seller_ai_settings
- seller_staff
```

**Deliverables**:
- SQL migration script: `V1__create_identity_schema.sql`
- Rollback script: `V1__rollback.sql`
- Seed data script: `V1__seed_test_data.sql`

**Validation**:
```sql
-- Check all tables created
SELECT table_name FROM information_schema.tables 
WHERE table_schema = 'public';

-- Verify constraints
SELECT constraint_name, table_name 
FROM information_schema.table_constraints;
```

---

### 1.2 Redis Configuration (Agent Task: REDIS-IDENTITY-001)

**Objective**: Configure Redis with proper namespaces and TTLs

**Configuration** (`application.properties`):
```properties
# Redis connection
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.password=${REDIS_PASSWORD}
spring.data.redis.database=0

# Connection pool
spring.data.redis.lettuce.pool.max-active=50
spring.data.redis.lettuce.pool.max-idle=20
spring.data.redis.lettuce.pool.min-idle=5

# Timeouts
spring.data.redis.timeout=3000ms
spring.data.redis.connect-timeout=2000ms

# Serialization
spring.data.redis.serializer=json
```

**Key Patterns** (Document in code):
```java
public class RedisKeyPatterns {
    // Auth Service
    public static final String ACCESS_TOKEN_BLACKLIST = "token:blacklist:{jti}";
    public static final String REFRESH_TOKEN = "refresh:{userId}:{deviceId}";
    public static final String USER_SESSIONS = "user:sessions:{userId}";
    public static final String OTP_EMAIL = "otp:email:{purpose}:{identifierHash}";
    public static final String OTP_PHONE = "otp:phone:{purpose}:{phoneHash}";
    public static final String RATE_LIMIT_LOGIN = "ratelimit:login:{ip}";
    public static final String RATE_LIMIT_OTP = "ratelimit:otp:{type}:{identifier}";
    public static final String BLOCKED_IP = "blocked:ip:{ip}";
    public static final String BLOCKED_USER = "blocked:user:{userId}";
    
    // User Service
    public static final String USER_PROFILE = "user:profile:{userId}";
    public static final String USER_ADDRESSES = "user:addresses:{userId}";
    public static final String USER_PREFERENCES = "user:preferences:{userId}";
    
    // Seller Service
    public static final String SELLER_PROFILE = "seller:profile:{sellerId}";
    public static final String SELLER_VERIFICATION = "seller:verification:{sellerId}";
}
```

**Deliverables**:
- `RedisConfig.java` with connection pool and serializers
- `RedisKeyPatterns.java` with all key templates
- `RedisService.java` with common operations (get, set, delete, TTL)

---

### 1.3 Common Module Dependencies (Agent Task: COMMON-IDENTITY-001)

**Objective**: Set up base classes from `common` module

**Required Base Classes**:
1. `BaseEvent` - Parent for all domain events
2. `KafkaEventPublisher` - Abstract publisher
3. `JpaEventStore` - Event persistence
4. `BaseAggregate` - Aggregate root pattern
5. `EventStoreEntity` - JPA entity for event_store

**pom.xml**:
```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>common</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

**Verify** common module is installed:
```bash
cd common
./mvnw clean install
# Check ~/.m2/repository/com/example/common/
```

---

## Phase 2: Auth Service Implementation

### 2.1 Domain Model (Agent Task: AUTH-DOMAIN-001)

**Objective**: Create domain entities and events

**Entities** (Package: `com.example.auth_service.domain.entity`):
```java
@Entity
@Table(name = "auth_sessions")
public class AuthSession {
    @Id
    private UUID sessionId;
    private UUID userId;
    private String deviceId;
    private String deviceName;
    private String deviceType;
    private InetAddress ipAddress;
    private String userAgent;
    private String geoCountry;
    private String geoCity;
    private boolean isActive;
    private Instant lastActivity;
    private String refreshTokenHash;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant revokedAt;
}

@Entity
@Table(name = "auth_login_attempts")
public class LoginAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long attemptId;
    private UUID userId;
    private String loginIdentifier;
    private String attemptType;
    private boolean isSuccessful;
    private String failureReason;
    private InetAddress ipAddress;
    private String userAgent;
    private String deviceId;
    private boolean isSuspicious;
    private Instant attemptedAt;
}

@Entity
@Table(name = "auth_password_reset_tokens")
public class PasswordResetToken {
    @Id
    private UUID tokenId;
    private UUID userId;
    private String tokenHash;
    private boolean isUsed;
    private Instant usedAt;
    private InetAddress requestedIp;
    private String requestedUserAgent;
    private Instant createdAt;
    private Instant expiresAt;
}
```

**Domain Events** (Package: `com.example.auth_service.domain.event`):
```java
@SuperBuilder
@Data
@EqualsAndHashCode(callSuper = true)
@DomainEvent(aggregateType = "Auth")
public class UserLoggedInEvent extends BaseEvent {
    private String userId;
    private String sessionId;
    private String deviceId;
    private String ipAddress;
    private String loginMethod; // PASSWORD, OTP, OAUTH
}

@DomainEvent(aggregateType = "Auth")
public class UserLoggedOutEvent extends BaseEvent {
    private String userId;
    private String sessionId;
    private String reason; // USER_REQUEST, TIMEOUT, SECURITY
}

@DomainEvent(aggregateType = "Auth")
public class LoginFailedEvent extends BaseEvent {
    private String loginIdentifier;
    private String ipAddress;
    private String failureReason;
    private boolean isSuspicious;
}

@DomainEvent(aggregateType = "Auth")
public class OTPGeneratedEvent extends BaseEvent {
    private String userId;
    private String purpose; // REGISTER, LOGIN, RESET_PASSWORD
    private String channel; // EMAIL, SMS
    private String destinationMasked; // "n***@gmail.com"
    private Instant expiresAt;
}

@DomainEvent(aggregateType = "Auth")
public class OTPVerifiedEvent extends BaseEvent {
    private String userId;
    private String purpose;
    private boolean isSuccess;
    private int attemptsUsed;
}

@DomainEvent(aggregateType = "Auth")
public class RefreshTokenRotatedEvent extends BaseEvent {
    private String userId;
    private String sessionId;
    private String oldTokenHash;
    private String newTokenHash;
}

@DomainEvent(aggregateType = "Auth")
public class SessionExpiredEvent extends BaseEvent {
    private String sessionId;
    private String userId;
    private String reason; // TTL_EXPIRED, INACTIVITY, SECURITY
}

@DomainEvent(aggregateType = "Auth")
public class SuspiciousActivityDetectedEvent extends BaseEvent {
    private String userId;
    private String activityType;
    private String ipAddress;
    private String deviceId;
    private Map<String, Object> signals;
}
```

**Deliverables**:
- All entity classes with JPA annotations
- All event classes extending `BaseEvent`
- Repository interfaces for each entity

---

### 2.2 Event Sourcing Setup (Agent Task: AUTH-ES-001)

**Objective**: Implement event store and publisher for Auth Service

**Event Publisher**:
```java
@Component
public class AuthEventPublisher extends KafkaEventPublisher<BaseEvent> {
    public AuthEventPublisher(
        KafkaTemplate<String, BaseEvent> kafkaTemplate,
        @Value("${auth.topicName}") String topic
    ) {
        super(kafkaTemplate, topic);
    }
}
```

**Event Store**:
```java
@Repository
public interface AuthEventStoreRepository extends JpaRepository<EventStoreEntity, UUID> {
    List<EventStoreEntity> findByAggregateIdOrderByVersionAsc(String aggregateId);
    List<EventStoreEntity> findByAggregateTypeAndOccurredOnBetween(
        String aggregateType, 
        Instant start, 
        Instant end
    );
}
```

**Configuration** (`application.properties`):
```properties
# Kafka
auth.topicName=auth-events
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer
spring.kafka.producer.properties.spring.json.add.type.headers=false
```

---

### 2.3 Core Services (Agent Task: AUTH-SERVICE-001)

**Objective**: Implement business logic for authentication

**Services to Implement**:

#### A. `AuthenticationService`
```java
@Service
@RequiredArgsConstructor
public class AuthenticationService {
    private final UserRepository userRepository;
    private final AuthSessionRepository sessionRepository;
    private final LoginAttemptRepository attemptRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RedisService redisService;
    private final AuthEventPublisher eventPublisher;
    private final PasswordEncoder passwordEncoder;
    
    public LoginResponse login(LoginRequest request) {
        // 1. Rate limit check
        checkRateLimit(request.getIpAddress());
        
        // 2. Find user by identifier (email/phone/username)
        User user = findUserByIdentifier(request.getIdentifier())
            .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        
        // 3. Check account status
        validateUserStatus(user);
        
        // 4. Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            recordLoginAttempt(user, request, false, "WRONG_PASSWORD");
            throw new BadCredentialsException("Invalid credentials");
        }
        
        // 5. Create session
        AuthSession session = createSession(user, request);
        
        // 6. Generate tokens
        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(session);
        
        // 7. Store refresh token in Redis
        storeRefreshToken(user.getUserId(), session.getDeviceId(), refreshToken);
        
        // 8. Publish event
        eventPublisher.publish(UserLoggedInEvent.builder()
            .userId(user.getUserId().toString())
            .sessionId(session.getSessionId().toString())
            .deviceId(session.getDeviceId())
            .ipAddress(request.getIpAddress())
            .loginMethod("PASSWORD")
            .build());
        
        // 9. Record successful attempt
        recordLoginAttempt(user, request, true, null);
        
        return LoginResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .tokenType("Bearer")
            .expiresIn(jwtTokenProvider.getAccessTokenExpiry())
            .user(UserDto.from(user))
            .build();
    }
    
    // Additional methods:
    // - logout()
    // - refreshToken()
    // - validateToken()
    // - revokeAllSessions()
}
```

#### B. `OTPService`
```java
@Service
@RequiredArgsConstructor
public class OTPService {
    private final RedisService redisService;
    private final NotificationServiceClient notificationClient;
    private final AuthEventPublisher eventPublisher;
    private final BCryptPasswordEncoder otpEncoder;
    
    @Value("${otp.length}")
    private int otpLength = 6;
    
    @Value("${otp.ttl.email}")
    private Duration emailTTL = Duration.ofMinutes(15);
    
    @Value("${otp.ttl.phone}")
    private Duration phoneTTL = Duration.ofMinutes(5);
    
    public SendOTPResponse sendEmailOTP(String email, OTPPurpose purpose) {
        // 1. Check rate limit
        checkOTPRateLimit("email", email);
        
        // 2. Generate 6-digit code
        String code = generateNumericCode(otpLength);
        
        // 3. Hash code
        String codeHash = otpEncoder.encode(code);
        
        // 4. Store in Redis
        String requestId = UUID.randomUUID().toString();
        String redisKey = String.format("otp:email:%s:%s", 
            purpose, 
            hashIdentifier(email)
        );
        
        OTPData otpData = OTPData.builder()
            .requestId(requestId)
            .codeHash(codeHash)
            .attempts(0)
            .createdAt(Instant.now())
            .purpose(purpose.name())
            .build();
        
        redisService.set(redisKey, otpData, emailTTL);
        
        // 5. Send via Notification Service
        notificationClient.sendEmail(EmailNotificationRequest.builder()
            .to(email)
            .templateCode("OTP_EMAIL")
            .variables(Map.of(
                "otp_code", code,
                "purpose", purpose.getDisplayName(),
                "ttl_minutes", emailTTL.toMinutes()
            ))
            .build());
        
        // 6. Publish event
        eventPublisher.publish(OTPGeneratedEvent.builder()
            .purpose(purpose.name())
            .channel("EMAIL")
            .destinationMasked(maskEmail(email))
            .expiresAt(Instant.now().plus(emailTTL))
            .build());
        
        // 7. Increment rate limit counter
        incrementRateLimitCounter("email", email);
        
        return SendOTPResponse.builder()
            .requestId(requestId)
            .expiresIn(emailTTL.toSeconds())
            .destinationMasked(maskEmail(email))
            .build();
    }
    
    public boolean verifyOTP(String requestId, String code, OTPPurpose purpose, String identifier) {
        // 1. Get OTP data from Redis
        String redisKey = String.format("otp:email:%s:%s", 
            purpose, 
            hashIdentifier(identifier)
        );
        
        OTPData otpData = redisService.get(redisKey, OTPData.class)
            .orElseThrow(() -> new OTPExpiredException("OTP expired or not found"));
        
        // 2. Check request ID match
        if (!otpData.getRequestId().equals(requestId)) {
            throw new InvalidOTPException("Invalid request ID");
        }
        
        // 3. Check max attempts
        if (otpData.getAttempts() >= 3) {
            redisService.delete(redisKey);
            throw new OTPAttemptsExceededException("Too many failed attempts");
        }
        
        // 4. Verify code
        boolean isValid = otpEncoder.matches(code, otpData.getCodeHash());
        
        if (!isValid) {
            // Increment attempts
            otpData.setAttempts(otpData.getAttempts() + 1);
            redisService.set(redisKey, otpData, Duration.ofSeconds(
                redisService.getTTL(redisKey)
            ));
            
            eventPublisher.publish(OTPVerifiedEvent.builder()
                .purpose(purpose.name())
                .isSuccess(false)
                .attemptsUsed(otpData.getAttempts())
                .build());
            
            return false;
        }
        
        // 5. Success - delete from Redis
        redisService.delete(redisKey);
        
        // 6. Publish event
        eventPublisher.publish(OTPVerifiedEvent.builder()
            .purpose(purpose.name())
            .isSuccess(true)
            .attemptsUsed(otpData.getAttempts() + 1)
            .build());
        
        return true;
    }
    
    // Helper methods:
    // - generateNumericCode()
    // - hashIdentifier()
    // - maskEmail()
    // - maskPhone()
    // - checkOTPRateLimit()
    // - incrementRateLimitCounter()
}
```

#### C. `SessionManagementService`
```java
@Service
@RequiredArgsConstructor
public class SessionManagementService {
    private final AuthSessionRepository sessionRepository;
    private final RedisService redisService;
    private final AuthEventPublisher eventPublisher;
    
    public List<SessionDto> getUserActiveSessions(UUID userId) {
        return sessionRepository.findByUserIdAndIsActive(userId, true)
            .stream()
            .map(SessionDto::from)
            .collect(Collectors.toList());
    }
    
    public void revokeSession(UUID sessionId, UUID userId, String reason) {
        AuthSession session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new SessionNotFoundException("Session not found"));
        
        // Verify ownership
        if (!session.getUserId().equals(userId)) {
            throw new UnauthorizedException("Not your session");
        }
        
        // Update in DB
        session.setIsActive(false);
        session.setRevokedAt(Instant.now());
        sessionRepository.save(session);
        
        // Delete from Redis
        String redisKey = String.format("refresh:%s:%s", 
            userId, 
            session.getDeviceId()
        );
        redisService.delete(redisKey);
        
        // Publish event
        eventPublisher.publish(UserLoggedOutEvent.builder()
            .userId(userId.toString())
            .sessionId(sessionId.toString())
            .reason(reason)
            .build());
    }
    
    public void revokeAllSessions(UUID userId, String reason) {
        List<AuthSession> sessions = sessionRepository.findByUserIdAndIsActive(userId, true);
        
        sessions.forEach(session -> {
            session.setIsActive(false);
            session.setRevokedAt(Instant.now());
        });
        
        sessionRepository.saveAll(sessions);
        
        // Delete all refresh tokens from Redis
        String pattern = String.format("refresh:%s:*", userId);
        redisService.deletePattern(pattern);
        
        // Publish event for each session
        sessions.forEach(session -> {
            eventPublisher.publish(UserLoggedOutEvent.builder()
                .userId(userId.toString())
                .sessionId(session.getSessionId().toString())
                .reason(reason)
                .build());
        });
    }
    
    // Additional methods:
    // - updateSessionActivity()
    // - cleanupExpiredSessions()
}
```

**Deliverables**:
- `AuthenticationService.java`
- `OTPService.java`
- `SessionManagementService.java`
- `PasswordResetService.java`
- `RateLimitService.java`

---

### 2.4 REST Controllers (Agent Task: AUTH-API-001)

**Objective**: Create RESTful endpoints

**Controllers**:

```java
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {
    private final AuthenticationService authService;
    private final OTPService otpService;
    private final SessionManagementService sessionService;
    
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
        @Valid @RequestBody LoginRequest request,
        HttpServletRequest httpRequest
    ) {
        request.setIpAddress(getClientIP(httpRequest));
        request.setUserAgent(httpRequest.getHeader("User-Agent"));
        
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> logout(
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        authService.logout(principal.getSessionId());
        return ResponseEntity.noContent().build();
    }
    
    @PostMapping("/refresh")
    public ResponseEntity<RefreshTokenResponse> refreshToken(
        @Valid @RequestBody RefreshTokenRequest request
    ) {
        RefreshTokenResponse response = authService.refreshToken(request.getRefreshToken());
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/otp/send")
    public ResponseEntity<SendOTPResponse> sendOTP(
        @Valid @RequestBody SendOTPRequest request
    ) {
        SendOTPResponse response = otpService.sendEmailOTP(
            request.getEmail(), 
            OTPPurpose.valueOf(request.getPurpose())
        );
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/otp/verify")
    public ResponseEntity<VerifyOTPResponse> verifyOTP(
        @Valid @RequestBody VerifyOTPRequest request
    ) {
        boolean isValid = otpService.verifyOTP(
            request.getRequestId(),
            request.getCode(),
            OTPPurpose.valueOf(request.getPurpose()),
            request.getIdentifier()
        );
        
        return ResponseEntity.ok(VerifyOTPResponse.builder()
            .isValid(isValid)
            .build());
    }
    
    @GetMapping("/sessions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<SessionDto>> getMySessions(
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        List<SessionDto> sessions = sessionService.getUserActiveSessions(principal.getUserId());
        return ResponseEntity.ok(sessions);
    }
    
    @DeleteMapping("/sessions/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> revokeSession(
        @PathVariable UUID sessionId,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        sessionService.revokeSession(sessionId, principal.getUserId(), "USER_REQUEST");
        return ResponseEntity.noContent().build();
    }
    
    @DeleteMapping("/sessions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> revokeAllSessions(
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        sessionService.revokeAllSessions(principal.getUserId(), "USER_REQUEST");
        return ResponseEntity.noContent().build();
    }
}
```

**Deliverables**:
- `AuthController.java`
- All DTOs (request/response classes)
- Input validation annotations
- Exception handlers (`@ControllerAdvice`)

---

### 2.5 Security Configuration (Agent Task: AUTH-SECURITY-001)

**Objective**: Configure Spring Security with JWT

**SecurityConfig**:
```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/otp/**").permitAll()
                .requestMatchers("/api/auth/refresh").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new JwtAuthenticationEntryPoint())
                .accessDeniedHandler(new JwtAccessDeniedHandler())
            );
        
        return http.build();
    }
    
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
    
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList("http://localhost:3000", "https://app.example.com"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(Arrays.asList("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

**JWT Filter**:
```java
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenProvider tokenProvider;
    private final RedisService redisService;
    
    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String token = extractToken(request);
            
            if (token != null && tokenProvider.validateToken(token)) {
                // Check if token is blacklisted
                String jti = tokenProvider.getJti(token);
                if (redisService.exists("token:blacklist:" + jti)) {
                    throw new TokenBlacklistedException("Token has been revoked");
                }
                
                // Set authentication
                UserPrincipal principal = tokenProvider.getUserPrincipal(token);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    principal.getAuthorities()
                );
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        } catch (Exception ex) {
            logger.error("Cannot set user authentication", ex);
        }
        
        filterChain.doFilter(request, response);
    }
    
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
```

**Deliverables**:
- `SecurityConfig.java`
- `JwtAuthenticationFilter.java`
- `JwtTokenProvider.java`
- `UserPrincipal.java` (implements UserDetails)

---

## Phase 3: User Service Implementation

### 3.1 Domain Model (Agent Task: USER-DOMAIN-001)

**Entities**:
```java
@Entity
@Table(name = "users")
public class User {
    @Id
    private UUID userId;
    private String email;
    private String phone;
    private String username;
    private String passwordHash;
    private String firstName;
    private String lastName;
    private Date dateOfBirth;
    private String gender;
    private String avatarUrl;
    
    @Enumerated(EnumType.STRING)
    private AccountType accountType;
    
    private UUID parentUserId;
    
    @Enumerated(EnumType.STRING)
    private RestrictionStatus restrictionStatus;
    
    private boolean isActive;
    private boolean emailVerified;
    private boolean phoneVerified;
    
    private Instant emailVerifiedAt;
    private Instant phoneVerifiedAt;
    private Instant lastLoginAt;
    private Instant createdAt;
    private Instant updatedAt;
    
    @Version
    private Integer version;
}

@Entity
@Table(name = "user_addresses")
public class UserAddress {
    @Id
    private UUID addressId;
    private UUID userId;
    private String label;
    private String recipientName;
    private String recipientPhone;
    private String addressLine1;
    private String addressLine2;
    private String district;
    private String city;
    private String province;
    private String postalCode;
    private String countryCode;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private boolean isDefault;
    private String addressType;
    private Instant createdAt;
    private Instant updatedAt;
}

@Entity
@Table(name = "user_preferences")
public class UserPreferences {
    @Id
    private UUID userId;
    private String language;
    private String currency;
    private String timezone;
    private String theme;
    private boolean marketingEmails;
    private boolean orderUpdates;
    private boolean promotionAlerts;
    private boolean reviewReminders;
    private Map<String, Object> aiPreferences;
    private Instant updatedAt;
}
```

**Domain Events**:
```java
@DomainEvent(aggregateType = "User")
public class UserRegisteredEvent extends BaseEvent {
    private String userId;
    private String email;
    private String phone;
    private String accountType;
    private String registrationSource;
}

@DomainEvent(aggregateType = "User")
public class UserProfileUpdatedEvent extends BaseEvent {
    private String userId;
    private Map<String, Object> changedFields;
}

@DomainEvent(aggregateType = "User")
public class EmailVerifiedEvent extends BaseEvent {
    private String userId;
    private String email;
}

@DomainEvent(aggregateType = "User")
public class PhoneVerifiedEvent extends BaseEvent {
    private String userId;
    private String phone;
}

@DomainEvent(aggregateType = "User")
public class AddressAddedEvent extends BaseEvent {
    private String userId;
    private String addressId;
    private boolean isDefault;
}

@DomainEvent(aggregateType = "User")
public class UserRestrictionAppliedEvent extends BaseEvent {
    private String userId;
    private String previousStatus;
    private String newStatus;
    private String reason;
    private String appliedBy;
}
```

---

### 3.2 Service Implementation (Agent Task: USER-SERVICE-001)

**UserService**:
```java
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final UserPreferencesRepository preferencesRepository;
    private final UserEventPublisher eventPublisher;
    private final RedisService redisService;
    
    @Transactional
    public UserDto createUser(CreateUserRequest request) {
        // 1. Validate uniqueness
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException();
        }
        
        // 2. Create user entity
        User user = User.builder()
            .userId(UUID.randomUUID())
            .email(request.getEmail())
            .phone(request.getPhone())
            .passwordHash(request.getPasswordHash())
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .accountType(AccountType.INDIVIDUAL)
            .restrictionStatus(RestrictionStatus.NONE)
            .isActive(true)
            .emailVerified(false)
            .phoneVerified(false)
            .createdAt(Instant.now())
            .build();
        
        userRepository.save(user);
        
        // 3. Create default preferences
        UserPreferences prefs = UserPreferences.builder()
            .userId(user.getUserId())
            .language("vi")
            .currency("VND")
            .timezone("Asia/Ho_Chi_Minh")
            .theme("light")
            .marketingEmails(true)
            .orderUpdates(true)
            .promotionAlerts(true)
            .reviewReminders(true)
            .build();
        
        preferencesRepository.save(prefs);
        
        // 4. Cache in Redis
        cacheUserProfile(user);
        
        // 5. Publish event
        eventPublisher.publish(UserRegisteredEvent.builder()
            .userId(user.getUserId().toString())
            .email(user.getEmail())
            .phone(user.getPhone())
            .accountType(user.getAccountType().name())
            .registrationSource("WEB")
            .build());
        
        return UserDto.from(user);
    }
    
    public UserDto getUserById(UUID userId) {
        // Try cache first
        return redisService.get("user:profile:" + userId, UserDto.class)
            .orElseGet(() -> {
                User user = userRepository.findById(userId)
                    .orElseThrow(() -> new UserNotFoundException());
                
                UserDto dto = UserDto.from(user);
                cacheUserProfile(user);
                return dto;
            });
    }
    
    @Transactional
    public UserDto updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException());
        
        Map<String, Object> changes = new HashMap<>();
        
        if (request.getFirstName() != null) {
            changes.put("firstName", user.getFirstName());
            user.setFirstName(request.getFirstName());
        }
        
        if (request.getLastName() != null) {
            changes.put("lastName", user.getLastName());
            user.setLastName(request.getLastName());
        }
        
        // ... other fields
        
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        
        // Update cache
        cacheUserProfile(user);
        
        // Publish event
        eventPublisher.publish(UserProfileUpdatedEvent.builder()
            .userId(userId.toString())
            .changedFields(changes)
            .build());
        
        return UserDto.from(user);
    }
    
    private void cacheUserProfile(User user) {
        redisService.set(
            "user:profile:" + user.getUserId(),
            UserDto.from(user),
            Duration.ofHours(24)
        );
    }
}
```

**Deliverables**:
- `UserService.java`
- `AddressService.java`
- `PreferencesService.java`
- `UserController.java`
- All DTOs

---

## Phase 4: Seller Service Implementation

### 4.1 Domain Model (Agent Task: SELLER-DOMAIN-001)

**Entities**:
```java
@Entity
@Table(name = "sellers")
public class Seller {
    @Id
    private UUID sellerId;
    private UUID ownerId;
    private String shopName;
    private String shopSlug;
    private String shopDescription;
    private String logoUrl;
    private String bannerUrl;
    
    @Enumerated(EnumType.STRING)
    private BusinessType businessType;
    
    @Enumerated(EnumType.STRING)
    private SellerTier tier;
    
    @Enumerated(EnumType.STRING)
    private SellerStatus status;
    
    private String businessLicenseNumber;
    private BigDecimal rating;
    private int totalProducts;
    private int totalOrders;
    private BigDecimal totalRevenue;
    
    private boolean isVerified;
    private Instant verifiedAt;
    
    private Instant createdAt;
    private Instant updatedAt;
    
    @Version
    private Integer version;
}

@Entity
@Table(name = "seller_verification")
public class SellerVerification {
    @Id
    private UUID verificationId;
    private UUID sellerId;
    private String taxId;
    private String idCardNumber;
    private Map<String, String> documents;
    
    @Enumerated(EnumType.STRING)
    private VerificationStatus status;
    
    private UUID verifiedBy;
    private Instant submittedAt;
    private Instant verifiedAt;
    private String rejectionReason;
}
```

**Domain Events**:
```java
@DomainEvent(aggregateType = "Seller")
public class SellerRegisteredEvent extends BaseEvent {
    private String sellerId;
    private String ownerId;
    private String shopName;
    private String businessType;
}

@DomainEvent(aggregateType = "Seller")
public class SellerVerificationSubmittedEvent extends BaseEvent {
    private String sellerId;
    private String verificationId;
    private Map<String, String> documents;
}

@DomainEvent(aggregateType = "Seller")
public class SellerVerifiedEvent extends BaseEvent {
    private String sellerId;
    private String verifiedBy;
}

@DomainEvent(aggregateType = "Seller")
public class SellerStatusChangedEvent extends BaseEvent {
    private String sellerId;
    private String previousStatus;
    private String newStatus;
    private String reason;
}
```

**Deliverables**:
- All seller entities
- All seller events
- `SellerRepository` interfaces

---

### 4.2 Service Implementation (Agent Task: SELLER-SERVICE-001)

Similar structure to User Service with:
- `SellerService.java`
- `SellerVerificationService.java`
- `SellerStaffService.java`
- `SellerController.java`

---

## Phase 5: Integration & Security

### 5.1 Kafka Integration (Agent Task: KAFKA-IDENTITY-001)

**Event Listeners**:
```java
@Component
@RequiredArgsConstructor
public class IdentityEventHandler {
    
    @KafkaListener(topics = "order-events", groupId = "identity-service-group")
    public void handleOrderEvent(@Payload BaseEvent event) {
        if (event instanceof OrderCompletedEvent) {
            // Update user stats, seller revenue, etc.
        }
    }
    
    @KafkaListener(topics = "analytics-events", groupId = "identity-service-group")
    public void handleAnalyticsEvent(@Payload BaseEvent event) {
        if (event instanceof SuspiciousActivityDetectedEvent) {
            // Update user restriction status
        }
    }
}
```

---

### 5.2 Circuit Breaker (Agent Task: RESILIENCE-IDENTITY-001)

**Configuration**:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      notification-service:
        registerHealthIndicator: true
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true
        waitDurationInOpenState: 5s
        failureRateThreshold: 50
        eventConsumerBufferSize: 10
```

---

## Phase 6: Testing & Optimization

### 6.1 Unit Tests (Agent Task: TEST-IDENTITY-001)

**Test Classes**:
- `AuthenticationServiceTest`
- `OTPServiceTest`
- `SessionManagementServiceTest`
- `UserServiceTest`
- `SellerServiceTest`

**Example**:
```java
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationServiceTest {
    
    @Autowired
    private AuthenticationService authService;
    
    @MockBean
    private UserRepository userRepository;
    
    @MockBean
    private RedisService redisService;
    
    @Test
    void testLogin_Success() {
        // Arrange
        User user = createTestUser();
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(user));
        
        LoginRequest request = LoginRequest.builder()
            .identifier("test@example.com")
            .password("password123")
            .build();
        
        // Act
        LoginResponse response = authService.login(request);
        
        // Assert
        assertNotNull(response.getAccessToken());
        assertNotNull(response.getRefreshToken());
        assertEquals("Bearer", response.getTokenType());
    }
    
    @Test
    void testLogin_InvalidCredentials() {
        // Arrange
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        
        LoginRequest request = LoginRequest.builder()
            .identifier("test@example.com")
            .password("wrongpassword")
            .build();
        
        // Act & Assert
        assertThrows(BadCredentialsException.class, () -> {
            authService.login(request);
        });
    }
}
```

---

### 6.2 Integration Tests (Agent Task: TEST-IDENTITY-INT-001)

**Test Scenarios**:
1. End-to-end registration flow with OTP
2. Login → Access protected endpoint → Refresh token
3. Multi-device session management
4. Rate limiting triggers
5. Seller verification workflow

---

### 6.3 Performance Testing (Agent Task: PERF-IDENTITY-001)

**Load Test Scenarios**:
```bash
# Using Apache JMeter or Gatling
1. Login endpoint: 1000 req/s for 5 minutes
2. Token validation: 5000 req/s for 10 minutes
3. OTP generation: 100 req/s for 2 minutes
4. Session queries: 500 req/s for 5 minutes

# Success criteria:
- P95 latency < 200ms
- Error rate < 0.1%
- Zero memory leaks
```

---

## AI Agent Task Breakdown

### Priority 1: Foundation (Week 1)
| Task ID | Description | Estimated Time | Dependencies |
|---------|-------------|----------------|--------------|
| DB-IDENTITY-001 | Create database schema | 4h | Common module |
| REDIS-IDENTITY-001 | Configure Redis | 2h | None |
| COMMON-IDENTITY-001 | Verify common module | 1h | None |

### Priority 2: Auth Service (Week 1-2)
| Task ID | Description | Estimated Time | Dependencies |
|---------|-------------|----------------|--------------|
| AUTH-DOMAIN-001 | Domain model & events | 6h | DB-IDENTITY-001 |
| AUTH-ES-001 | Event sourcing setup | 4h | AUTH-DOMAIN-001 |
| AUTH-SERVICE-001 | Core services | 12h | AUTH-ES-001 |
| AUTH-API-001 | REST controllers | 6h | AUTH-SERVICE-001 |
| AUTH-SECURITY-001 | Security config | 8h | AUTH-API-001 |

### Priority 3: User Service (Week 2)
| Task ID | Description | Estimated Time | Dependencies |
|---------|-------------|----------------|--------------|
| USER-DOMAIN-001 | Domain model & events | 4h | DB-IDENTITY-001 |
| USER-SERVICE-001 | Service implementation | 8h | USER-DOMAIN-001 |
| USER-API-001 | REST controllers | 4h | USER-SERVICE-001 |

### Priority 4: Seller Service (Week 2-3)
| Task ID | Description | Estimated Time | Dependencies |
|---------|-------------|----------------|--------------|
| SELLER-DOMAIN-001 | Domain model & events | 4h | DB-IDENTITY-001 |
| SELLER-SERVICE-001 | Service implementation | 8h | SELLER-DOMAIN-001 |
| SELLER-API-001 | REST controllers | 4h | SELLER-SERVICE-001 |

### Priority 5: Integration (Week 3)
| Task ID | Description | Estimated Time | Dependencies |
|---------|-------------|----------------|--------------|
| KAFKA-IDENTITY-001 | Kafka listeners | 4h | All services |
| RESILIENCE-IDENTITY-001 | Circuit breakers | 3h | All services |

### Priority 6: Testing (Week 3-4)
| Task ID | Description | Estimated Time | Dependencies |
|---------|-------------|----------------|--------------|
| TEST-IDENTITY-001 | Unit tests | 16h | All services |
| TEST-IDENTITY-INT-001 | Integration tests | 12h | All services |
| PERF-IDENTITY-001 | Performance tests | 8h | All services |

---

## Validation Checklist

### Database
- [ ] All tables created with proper constraints
- [ ] Indexes added for common queries
- [ ] Foreign keys configured correctly
- [ ] Sample data inserted successfully

### Redis
- [ ] Connection pool configured
- [ ] All key patterns documented
- [ ] TTL policies set correctly
- [ ] Can perform basic operations (get, set, delete)

### Auth Service
- [ ] User can register with email/phone
- [ ] OTP sent and verified correctly
- [ ] Login returns valid JWT tokens
- [ ] Refresh token rotation works
- [ ] Rate limiting blocks excessive attempts
- [ ] Sessions tracked in database
- [ ] Logout invalidates tokens
- [ ] Password reset flow works

### User Service
- [ ] User profile CRUD operations work
- [ ] Addresses can be added/updated/deleted
- [ ] Preferences saved correctly
- [ ] Profile cached in Redis
- [ ] Events published to Kafka

### Seller Service
- [ ] Seller registration works
- [ ] Verification submission accepted
- [ ] Verification approval/rejection works
- [ ] Staff management functional
- [ ] AI settings configurable

### Integration
- [ ] Kafka events published successfully
- [ ] Event listeners process messages
- [ ] Circuit breakers trigger on failures
- [ ] Service-to-service calls work

### Security
- [ ] JWT validation works
- [ ] Unauthorized requests blocked
- [ ] CORS configured correctly
- [ ] Rate limiting prevents abuse
- [ ] PII fields encrypted
- [ ] Audit trail captured

### Testing
- [ ] All unit tests pass (>80% coverage)
- [ ] Integration tests pass
- [ ] Performance meets SLA targets
- [ ] No memory leaks detected
- [ ] Load testing completed successfully

---

## Success Criteria

### Functional
✅ Users can register, login, and manage profiles  
✅ Sellers can register and get verified  
✅ All authentication methods work (password, OTP)  
✅ Session management functional  
✅ Events published to Kafka  

### Non-Functional
✅ Login response time < 100ms (P95)  
✅ OTP delivery < 30 seconds  
✅ Redis cache hit rate > 90%  
✅ Zero security vulnerabilities  
✅ 99.9% uptime  

### Documentation
✅ API documentation (Swagger)  
✅ Database schema documented  
✅ Redis key patterns documented  
✅ Event schemas documented  
✅ Deployment guide written  

---

**End of Identity Services Implementation Plan**
