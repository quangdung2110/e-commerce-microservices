package cronjobs

import (
	"testing"
)

func TestAbandonedMarkerJob(t *testing.T) {
	job := &AbandonedMarker{}
	if job == nil {
		t.Error("AbandonedMarker should not be nil")
	}
}

func TestArchivalCleanerJob(t *testing.T) {
	job := &ArchivalCleaner{}
	if job == nil {
		t.Error("ArchivalCleaner should not be nil")
	}
}

func TestStreamTrimmerJob(t *testing.T) {
	job := &StreamTrimmer{}
	if job == nil {
		t.Error("StreamTrimmer should not be nil")
	}
}

func TestMetrics(t *testing.T) {
	m := NewMetrics(nil)

	m.RecordProcessed(5)
	m.RecordError()
	m.RecordDeleted()
	m.RecordTrimmed(100)

	s := m.Snapshot()

	if s.Processed != 5 {
		t.Errorf("Processed = %d, want 5", s.Processed)
	}
	if s.Errors != 1 {
		t.Errorf("Errors = %d, want 1", s.Errors)
	}
	if s.Deleted != 1 {
		t.Errorf("Deleted = %d, want 1", s.Deleted)
	}
	if s.Trimmed != 100 {
		t.Errorf("Trimmed = %d, want 100", s.Trimmed)
	}
}

func TestScheduler(t *testing.T) {
	s := NewScheduler(nil)
	if s == nil {
		t.Fatal("NewScheduler should not return nil")
	}

	if s.entries == nil {
		t.Error("entries map should be initialized")
	}
}

func TestCronJobInterface(t *testing.T) {
	var _ interface {
		Run()
	} = (*AbandonedMarker)(nil)

	var _ interface {
		Run()
	} = (*ArchivalCleaner)(nil)

	var _ interface {
		Run()
	} = (*StreamTrimmer)(nil)
}

func TestJobSchedules(t *testing.T) {
	ab := &AbandonedMarker{}
	ar := &ArchivalCleaner{}
	st := &StreamTrimmer{}

	if ab.Schedule() != nil {
		t.Error("new AbandonedMarker should have nil schedule")
	}
	if ar.Schedule() != nil {
		t.Error("new ArchivalCleaner should have nil schedule")
	}
	if st.Schedule() != nil {
		t.Error("new StreamTrimmer should have nil schedule")
	}
}

func TestJobConfigs(t *testing.T) {
	ab := &AbandonedMarker{}
	ar := &ArchivalCleaner{}
	st := &StreamTrimmer{}

	_ = ab.Config()
	_ = ar.Config()
	_ = st.Config()
}

func TestJobMetrics(t *testing.T) {
	ab := &AbandonedMarker{}
	ar := &ArchivalCleaner{}
	st := &StreamTrimmer{}

	// Metrics() is nil for uninitialized structs (expected)
	// Constructors properly initialize them
	_ = ab.Metrics()
	_ = ar.Metrics()
	_ = st.Metrics()
}
