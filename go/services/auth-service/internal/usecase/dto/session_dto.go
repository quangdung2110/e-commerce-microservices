package dto

import (
	"time"

	"github.com/google/uuid"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
)

// ========================= SESSION MANAGEMENT DTOs =========================

type SessionInfo struct {
	SessionID    uuid.UUID         `json:"session_id"`
	DeviceID     *string           `json:"device_id,omitempty"`
	DeviceName   *string           `json:"device_name,omitempty"`
	DeviceType   entity.DeviceType `json:"device_type"`
	IPAddress    string            `json:"ip_address"`
	GeoCountry   *string           `json:"geo_country,omitempty"`
	GeoCity      *string           `json:"geo_city,omitempty"`
	LastActiveAt time.Time         `json:"last_active_at"`
	CreatedAt    time.Time         `json:"created_at"`
	ExpiresAt    time.Time         `json:"expires_at"`
	IsCurrent    bool              `json:"is_current"`
}

type ListSessionsResponse struct {
	Sessions []SessionInfo `json:"sessions"`
	Total    int           `json:"total"`
}

type ImpossibleTravelAlert struct {
	Detected            bool                `json:"detected"`
	PreviousLocation    *entity.GeoLocation `json:"previous_location"`
	CurrentLocation     *entity.GeoLocation `json:"current_location"`
	DistanceKm          float64             `json:"distance_km"`
	TimeDifferenceHours float64             `json:"time_difference_hours"`
	RequiredSpeedKmH    float64             `json:"required_speed_kmh"`
	Suspicious          bool                `json:"suspicious"`
}
