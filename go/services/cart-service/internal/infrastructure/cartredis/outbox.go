package cartredis

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"

	"cart-service/internal/domain"
	"cart-service/internal/domain/port"
)

// RelayGroup is the consumer group name shared by all 64 outbox streams.
//
// The group is per-stream state, not a worker identity. Every stream carries
// its own instance of a group with this name (ADR-008).
const RelayGroup = "cart-outbox-relay-" + domain.Namespace

// Outbox is the Redis Streams implementation of port.OutboxStore.
type Outbox struct {
	client   redis.UniversalClient
	keys     Keys
	consumer string
}

// NewOutbox builds an outbox over an existing Redis client. consumer names
// this process inside the relay consumer group, so that XAUTOCLAIM can tell
// its own pending entries from a dead predecessor's.
func NewOutbox(client redis.UniversalClient, consumer string) *Outbox {
	return &Outbox{client: client, consumer: consumer}
}

var _ port.OutboxStore = (*Outbox)(nil)

// OutboxKey returns the Redis key of a bucket's outbox stream.
func (o *Outbox) OutboxKey(bucket int) string {
	return o.keys.Outbox(bucket)
}

// StreamExists reports whether a bucket's outbox stream has been created.
//
// Streams are created lazily by the first XADD in mark_abandoned.lua. Most
// buckets are empty most of the time, and pre-creating 64 streams would only
// add keys for the trimmer to walk.
func (o *Outbox) StreamExists(ctx context.Context, bucket int) (bool, error) {
	count, err := o.client.Exists(ctx, o.keys.Outbox(bucket)).Result()
	if err != nil {
		return false, fmt.Errorf("check outbox stream for bucket %d: %w", bucket, err)
	}
	return count > 0, nil
}

// EnsureGroup creates the relay consumer group for a bucket if it is absent.
//
// It starts at 0 so a group created after entries already exist still sees
// them. BUSYGROUP means a concurrent caller won the race, which is fine.
func (o *Outbox) EnsureGroup(ctx context.Context, bucket int) error {
	err := o.client.XGroupCreateMkStream(ctx, o.keys.Outbox(bucket), RelayGroup, "0").Err()
	if err != nil && !isBusyGroup(err) {
		return fmt.Errorf("create relay group for bucket %d: %w", bucket, err)
	}
	return nil
}

// Claim takes over entries a previous relay read but never acked.
//
// The relay drains claimed entries before reading new ones, so a crash
// between the Kafka produce and the XACK replays the same entry rather than
// stranding it behind newer work.
func (o *Outbox) Claim(ctx context.Context, bucket, count int, minIdle time.Duration) ([]domain.OutboxRecord, error) {
	messages, _, err := o.client.XAutoClaim(ctx, &redis.XAutoClaimArgs{
		Stream:   o.keys.Outbox(bucket),
		Group:    RelayGroup,
		Consumer: o.consumer,
		MinIdle:  minIdle,
		Start:    "0-0",
		Count:    int64(count),
	}).Result()
	if err != nil {
		if isNoGroup(err) {
			return nil, nil
		}
		return nil, fmt.Errorf("claim pending outbox entries for bucket %d: %w", bucket, err)
	}
	return decodeRecords(bucket, messages), nil
}

// Read fetches undelivered entries for the relay consumer group.
//
// It never blocks: one relay loop round-robins all 64 streams, and blocking on
// one empty stream would starve the other 63.
func (o *Outbox) Read(ctx context.Context, bucket, count int) ([]domain.OutboxRecord, error) {
	streams, err := o.client.XReadGroup(ctx, &redis.XReadGroupArgs{
		Group:    RelayGroup,
		Consumer: o.consumer,
		Streams:  []string{o.keys.Outbox(bucket), ">"},
		Count:    int64(count),
		Block:    -1,
	}).Result()
	if err != nil {
		if err == redis.Nil || isNoGroup(err) {
			return nil, nil
		}
		return nil, fmt.Errorf("read outbox entries for bucket %d: %w", bucket, err)
	}

	records := make([]domain.OutboxRecord, 0, count)
	for _, stream := range streams {
		records = append(records, decodeRecords(bucket, stream.Messages)...)
	}
	return records, nil
}

// Ack marks an entry delivered. The relay only calls it after Kafka has
// acknowledged the produce.
func (o *Outbox) Ack(ctx context.Context, bucket int, streamID string) error {
	if err := o.client.XAck(ctx, o.keys.Outbox(bucket), RelayGroup, streamID).Err(); err != nil {
		return fmt.Errorf("ack outbox entry %s in bucket %d: %w", streamID, bucket, err)
	}
	return nil
}

// TrimWatermark reports the oldest entry id the trimmer must keep.
//
// XACK does not free memory, so trimming is a separate job. It is only safe
// once the consumer group exists, and it must never drop an entry that is
// still pending: the watermark is the oldest pending id when there is one, and
// the group's last-delivered id otherwise.
func (o *Outbox) TrimWatermark(ctx context.Context, bucket int) (string, bool, error) {
	key := o.keys.Outbox(bucket)

	groups, err := o.client.XInfoGroups(ctx, key).Result()
	if err != nil {
		if isNoGroup(err) || err == redis.Nil {
			return "", false, nil
		}
		return "", false, fmt.Errorf("inspect groups for bucket %d: %w", bucket, err)
	}

	var group *redis.XInfoGroup
	for i := range groups {
		if groups[i].Name == RelayGroup {
			group = &groups[i]
			break
		}
	}
	if group == nil {
		return "", false, nil
	}

	if group.Pending > 0 {
		pending, err := o.client.XPending(ctx, key, RelayGroup).Result()
		if err != nil {
			return "", false, fmt.Errorf("inspect pending for bucket %d: %w", bucket, err)
		}
		if pending.Lower == "" {
			return "", false, nil
		}
		return pending.Lower, true, nil
	}

	if group.LastDeliveredID == "" || group.LastDeliveredID == "0-0" {
		return "", false, nil
	}
	return group.LastDeliveredID, true, nil
}

// Trim removes entries strictly older than minID. The entry at minID itself is
// kept, which is what makes an oldest-pending watermark safe.
func (o *Outbox) Trim(ctx context.Context, bucket int, minID string) (int64, error) {
	removed, err := o.client.XTrimMinID(ctx, o.keys.Outbox(bucket), minID).Result()
	if err != nil {
		return 0, fmt.Errorf("trim outbox for bucket %d: %w", bucket, err)
	}
	return removed, nil
}

// decodeRecords turns raw stream messages into outbox records. An entry whose
// payload cannot be decoded is still returned, with an empty payload, so the
// relay can route it to the dead-letter topic instead of retrying it forever.
func decodeRecords(bucket int, messages []redis.XMessage) []domain.OutboxRecord {
	records := make([]domain.OutboxRecord, 0, len(messages))
	for _, message := range messages {
		record := domain.OutboxRecord{
			StreamID:  message.ID,
			Bucket:    bucket,
			EventType: fieldString(message.Values, "event_type"),
		}
		if payload, err := domain.DecodeAbandonedPayload(fieldString(message.Values, "payload")); err == nil {
			record.Payload = payload
		}
		records = append(records, record)
	}
	return records
}

func fieldString(values map[string]any, field string) string {
	if raw, ok := values[field]; ok {
		if str, ok := raw.(string); ok {
			return str
		}
	}
	return ""
}

func isBusyGroup(err error) bool {
	return err != nil && strings.Contains(err.Error(), "BUSYGROUP")
}

func isNoGroup(err error) bool {
	return err != nil && strings.Contains(err.Error(), "NOGROUP")
}
