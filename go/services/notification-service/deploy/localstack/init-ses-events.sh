#!/bin/bash
# Chạy bởi LocalStack sau khi SNS/SQS sẵn sàng (/etc/localstack/init/ready.d).
# Dựng đường Track ở dev: SNS topic (đích của SES configuration set) -> SQS queue
# mà notification-service poll, kèm DLQ. Ở dev không có SES thật; test bơm event
# SES mẫu thẳng vào SNS topic bằng `awslocal sns publish`.
# Thiết kế: docs/notification/03-tracking.md §3, docs/notification/13-development.md.
set -euo pipefail

REGION="${DEFAULT_REGION:-ap-southeast-1}"
ACCOUNT="000000000000"
TOPIC_NAME="ses-events"
QUEUE_NAME="notification-ses-events"
DLQ_NAME="notification-ses-events-dlq"

awslocal sns create-topic --name "$TOPIC_NAME" --region "$REGION" >/dev/null

DLQ_URL=$(awslocal sqs create-queue --queue-name "$DLQ_NAME" --region "$REGION" --query QueueUrl --output text)
DLQ_ARN="arn:aws:sqs:${REGION}:${ACCOUNT}:${DLQ_NAME}"

# maxReceiveCount 5: poller chưa INSERT xong thì không DeleteMessage; sau 5 lần
# nhận mà vẫn không xử lý được thì sang DLQ để ops xem, không kẹt queue chính.
QUEUE_URL=$(awslocal sqs create-queue --queue-name "$QUEUE_NAME" --region "$REGION" \
  --attributes "{
    \"VisibilityTimeout\": \"60\",
    \"MessageRetentionPeriod\": \"1209600\",
    \"ReceiveMessageWaitTimeSeconds\": \"20\",
    \"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"
  }" --query QueueUrl --output text)
QUEUE_ARN="arn:aws:sqs:${REGION}:${ACCOUNT}:${QUEUE_NAME}"
TOPIC_ARN="arn:aws:sns:${REGION}:${ACCOUNT}:${TOPIC_NAME}"

# RawMessageDelivery=false để giữ envelope SNS (Type, MessageId, Signature...) đúng
# như production; poller verify chữ ký SNS trước khi INSERT provider_event.
awslocal sns subscribe --topic-arn "$TOPIC_ARN" --protocol sqs --notification-endpoint "$QUEUE_ARN" \
  --attributes '{"RawMessageDelivery":"false"}' --region "$REGION" >/dev/null

awslocal sqs set-queue-attributes --queue-url "$QUEUE_URL" --region "$REGION" --attributes "{
  \"Policy\": \"{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[{\\\"Effect\\\":\\\"Allow\\\",\\\"Principal\\\":{\\\"Service\\\":\\\"sns.amazonaws.com\\\"},\\\"Action\\\":\\\"sqs:SendMessage\\\",\\\"Resource\\\":\\\"${QUEUE_ARN}\\\",\\\"Condition\\\":{\\\"ArnEquals\\\":{\\\"aws:SourceArn\\\":\\\"${TOPIC_ARN}\\\"}}}]}\"
}"

echo "notification: SNS ${TOPIC_ARN} -> SQS ${QUEUE_URL} (dlq ${DLQ_URL})"
