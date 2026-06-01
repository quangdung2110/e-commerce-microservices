package entity

import (
	"testing"
	"time"
)

func TestCartStatus_IsValid(t *testing.T) {
	tests := []struct {
		status  CartStatus
		isValid bool
	}{
		{CartStatusActive, true},
		{CartStatusAbandoned, true},
		{CartStatusDeleted, true},
		{CartStatusCheckedOut, true},
		{CartStatus("INVALID"), false},
		{CartStatus(""), false},
	}

	for _, tt := range tests {
		if got := tt.status.IsValid(); got != tt.isValid {
			t.Errorf("CartStatus(%q).IsValid() = %v, want %v", tt.status, got, tt.isValid)
		}
	}
}

func TestNewCart(t *testing.T) {
	cart := NewCart("{cart}:user1", "user1")

	if cart.CartID() != "{cart}:user1" {
		t.Errorf("CartID = %q, want {cart}:user1", cart.CartID())
	}
	if cart.UserID() != "user1" {
		t.Errorf("UserID = %q, want user1", cart.UserID())
	}
	if cart.Status() != CartStatusActive {
		t.Errorf("Status = %v, want ACTIVE", cart.Status())
	}
	if cart.LastStreamID() != "" {
		t.Errorf("LastStreamID = %q, want empty", cart.LastStreamID())
	}
	if len(cart.Items()) != 0 {
		t.Errorf("Items len = %d, want 0", len(cart.Items()))
	}
}

func TestRestoreCart(t *testing.T) {
	now := time.Now().UTC()
	cart := RestoreCart("{cart}:user1", "user1", CartStatusAbandoned, "1000-0", now, now)

	if cart.CartID() != "{cart}:user1" {
		t.Errorf("CartID = %q, want {cart}:user1", cart.CartID())
	}
	if cart.Status() != CartStatusAbandoned {
		t.Errorf("Status = %v, want ABANDONED", cart.Status())
	}
	if cart.LastStreamID() != "1000-0" {
		t.Errorf("LastStreamID = %q, want 1000-0", cart.LastStreamID())
	}
}

func TestRestoreCartWithAbandoned(t *testing.T) {
	now := time.Now().UTC()
	abandonedAt := now.Add(-1 * time.Hour)
	cart := RestoreCartWithAbandoned("{cart}:user1", "user1", CartStatusAbandoned, "1000-0", now, now, &abandonedAt)

	if cart.AbandonedEventPubAt() == nil {
		t.Fatal("AbandonedEventPubAt should not be nil")
	}
	if cart.AbandonedEventPubAt().Unix() != abandonedAt.Unix() {
		t.Errorf("AbandonedEventPubAt = %v, want %v", cart.AbandonedEventPubAt(), abandonedAt)
	}
}

func TestCartStatus_Transition(t *testing.T) {
	cart := NewCart("{cart}:user1", "user1")

	// ACTIVE -> ABANDONED: allowed
	if !cart.CanTransitionTo(CartStatusAbandoned) {
		t.Error("ACTIVE should transition to ABANDONED")
	}

	// ACTIVE -> CHECKED_OUT: not allowed directly
	if cart.CanTransitionTo(CartStatusCheckedOut) {
		t.Error("ACTIVE should not transition directly to CHECKED_OUT")
	}

	// ACTIVE -> DELETED: not allowed directly
	if cart.CanTransitionTo(CartStatusDeleted) {
		t.Error("ACTIVE should not transition to DELETED")
	}

	// ACTIVE -> ACTIVE: not allowed
	if cart.CanTransitionTo(CartStatusActive) {
		t.Error("ACTIVE should not transition to itself")
	}

	// Transition to ABANDONED first
	err := cart.TransitionTo(CartStatusAbandoned, "1000-0")
	if err != nil {
		t.Errorf("TransitionTo(ABANDONED) unexpected error: %v", err)
	}
	if cart.Status() != CartStatusAbandoned {
		t.Errorf("Status = %v, want ABANDONED", cart.Status())
	}

	// ABANDONED -> ACTIVE: allowed (re-activation)
	if !cart.CanTransitionTo(CartStatusActive) {
		t.Error("ABANDONED should transition to ACTIVE (re-activation)")
	}

	err = cart.TransitionTo(CartStatusActive, "1001-0")
	if err != nil {
		t.Errorf("TransitionTo(ACTIVE) unexpected error: %v", err)
	}
	if cart.Status() != CartStatusActive {
		t.Errorf("Status = %v, want ACTIVE", cart.Status())
	}

	// From ACTIVE again, go to ABANDONED, then back to ACTIVE
	cart.TransitionTo(CartStatusAbandoned, "1002-0")
	if cart.Status() != CartStatusAbandoned {
		t.Errorf("Status = %v, want ABANDONED", cart.Status())
	}

	// ABANDONED -> ACTIVE: allowed (re-activation)
	if !cart.CanTransitionTo(CartStatusActive) {
		t.Error("ABANDONED should transition to ACTIVE")
	}
	err = cart.TransitionTo(CartStatusActive, "1003-0")
	if err != nil {
		t.Errorf("TransitionTo(ACTIVE) unexpected error: %v", err)
	}
	if cart.Status() != CartStatusActive {
		t.Errorf("Status = %v, want ACTIVE", cart.Status())
	}

	// DELETED -> ACTIVE: allowed (re-activation)
	cart.TransitionTo(CartStatusDeleted, "1004-0") // DELETED from DB
	cart.TransitionTo(CartStatusActive, "1005-0")
	if cart.Status() != CartStatusActive {
		t.Errorf("Status = %v, want ACTIVE", cart.Status())
	}

	// From ACTIVE -> ABANDONED -> ACTIVE
	cart.TransitionTo(CartStatusAbandoned, "1006-0")
	if cart.Status() != CartStatusAbandoned {
		t.Errorf("Status = %v, want ABANDONED", cart.Status())
	}

	// ABANDONED -> ACTIVE: allowed
	if !cart.CanTransitionTo(CartStatusActive) {
		t.Error("ABANDONED should transition to ACTIVE")
	}
	err = cart.TransitionTo(CartStatusActive, "1007-0")
	if err != nil {
		t.Errorf("TransitionTo(ACTIVE) unexpected error: %v", err)
	}
	if cart.Status() != CartStatusActive {
		t.Errorf("Status = %v, want ACTIVE", cart.Status())
	}

	// CHECKED_OUT -> ACTIVE: allowed (re-activation)
	cart.TransitionTo(CartStatusCheckedOut, "1008-0")
	cart.TransitionTo(CartStatusActive, "1009-0")
	if cart.Status() != CartStatusActive {
		t.Errorf("Status = %v, want ACTIVE", cart.Status())
	}
}

func TestCart_TransitionTo_InvalidStatus(t *testing.T) {
	cart := NewCart("{cart}:user1", "user1")

	err := cart.TransitionTo(CartStatus("INVALID"), "1000-0")
	if err != ErrInvalidCartStatus {
		t.Errorf("expected ErrInvalidCartStatus, got %v", err)
	}
}

func TestCart_TransitionTo_InvalidTransition(t *testing.T) {
	cart := NewCart("{cart}:user1", "user1")

	// ACTIVE -> CHECKED_OUT is not a valid transition
	err := cart.TransitionTo(CartStatusCheckedOut, "1000-0")
	if err != ErrInvalidCartTransition {
		t.Errorf("expected ErrInvalidCartTransition, got %v", err)
	}
}

func TestStreamIDComparison(t *testing.T) {
	older := "1708530000123-0"
	newer := "1708616400456-0"
	muchNewer := "1708702800789-0"

	if !streamIDNewer(older, newer) {
		t.Error("newer should be greater than older")
	}
	if streamIDNewer(newer, older) {
		t.Error("older should not be greater than newer")
	}
	if !streamIDNewer(newer, muchNewer) {
		t.Error("muchNewer should be greater than newer")
	}
	if streamIDNewer(newer, newer) {
		t.Error("same ID should not be considered newer")
	}

	// Empty string comparison: Go string comparison is lexicographic
	// "" < "0" in Go, so streamIDNewer("", "1000-0") returns true (correct)
	if !streamIDNewer("", "1000-0") {
		t.Error("empty candidate should be newer than empty current")
	}
}

func TestCart_Reactivate(t *testing.T) {
	// Test: new cart (no streamID) can be reactivated with a stream ID
	cart := NewCart("{cart}:user1", "user1")

	cart.Reactivate("1000-0")
	if cart.Status() != CartStatusActive {
		t.Errorf("Status = %v, want ACTIVE after reactivate", cart.Status())
	}
	if cart.LastStreamID() != "1000-0" {
		t.Errorf("LastStreamID = %q, want 1000-0", cart.LastStreamID())
	}

	// Test: re-activate a DELETED cart (restored from DB) with newer stream ID
	// Cart restored with DELETED status and streamID "1001-0"
	deletedCart := RestoreCart("{cart}:user2", "user2", CartStatusDeleted, "1001-0", nil)
	deletedCart.Reactivate("1002-0")
	if deletedCart.Status() != CartStatusActive {
		t.Errorf("Status = %v, want ACTIVE after reactivate from DELETED", deletedCart.Status())
	}

	// Test: re-activate a CHECKED_OUT cart with newer stream ID
	checkedOutCart := RestoreCart("{cart}:user3", "user3", CartStatusCheckedOut, "1003-0", nil)
	checkedOutCart.Reactivate("1004-0")
	if checkedOutCart.Status() != CartStatusActive {
		t.Errorf("Status = %v, want ACTIVE after reactivate from CHECKED_OUT", checkedOutCart.Status())
	}

	// Test: older stream ID should NOT update
	checkedOutCart.Reactivate("1002-0") // 1002-0 < 1004-0
	if checkedOutCart.Status() != CartStatusActive {
		t.Errorf("Status = %v, want ACTIVE (already active, no regression)", checkedOutCart.Status())
	}

	// Test: ACTIVE cart - Reactivate does nothing (function checks status internally)
	activeCart := RestoreCart("{cart}:user4", "user4", CartStatusActive, "1005-0", nil)
	activeCart.Reactivate("1006-0")
	if activeCart.Status() != CartStatusActive {
		t.Errorf("Status = %v, want ACTIVE (no change for active cart)", activeCart.Status())
	}

	// Test: ABANDONED cart - Reactivate does nothing (only DELETED/CHECKED_OUT)
	abandonedCart := RestoreCart("{cart}:user5", "user5", CartStatusAbandoned, "1007-0", nil)
	abandonedCart.Reactivate("1008-0")
	if abandonedCart.Status() != CartStatusAbandoned {
		t.Errorf("Status = %v, want ABANDONED (Reactivate has no effect on ABANDONED)", abandonedCart.Status())
	}
}

func TestCart_MarkAbandoned(t *testing.T) {
	cart := NewCart("{cart}:user1", "user1")
	cart.MarkAbandoned()

	if cart.Status() != CartStatusAbandoned {
		t.Errorf("Status = %v, want ABANDONED", cart.Status())
	}
}

func TestCart_Getters_Immutability(t *testing.T) {
	cart := NewCart("{cart}:user1", "user1")
	cart.TransitionTo(CartStatusAbandoned, "1000-0")

	if cart.CartID() != "{cart}:user1" {
		t.Errorf("CartID = %q", cart.CartID())
	}
	if cart.UserID() != "user1" {
		t.Errorf("UserID = %q", cart.UserID())
	}
	if cart.Status() != CartStatusAbandoned {
		t.Errorf("Status = %v", cart.Status())
	}
	if cart.LastStreamID() != "1000-0" {
		t.Errorf("LastStreamID = %q", cart.LastStreamID())
	}
	if cart.CreatedAt().IsZero() {
		t.Error("CreatedAt should not be zero")
	}
	if cart.UpdatedAt().IsZero() {
		t.Error("UpdatedAt should not be zero")
	}
}
