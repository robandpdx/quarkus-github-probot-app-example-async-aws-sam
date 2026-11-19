#!/usr/bin/env bash
set -euo pipefail

export AWS_ACCESS_KEY_ID=local AWS_SECRET_ACCESS_KEY=local AWS_DEFAULT_REGION=us-west-2

wait_for() {
    for attempt in {1..30}; do
        if output=$("$@" 2>&1); then
            return
        fi
        printf 'Waiting for local service (%s/30): %s\n' "$attempt" "$output" >&2
        sleep 1
    done
    return 1
}

wait_for aws --endpoint-url http://localhost:9324 sqs list-queues
wait_for aws --endpoint-url http://localhost:8000 dynamodb list-tables

exists=$(aws --endpoint-url http://localhost:8000 dynamodb list-tables \
    --query 'contains(TableNames, `github-webhook-deliveries`)' --output text)
if [ "$exists" = "False" ]; then
    aws --endpoint-url http://localhost:8000 dynamodb create-table \
        --table-name github-webhook-deliveries \
        --attribute-definitions AttributeName=deliveryId,AttributeType=S \
        --key-schema AttributeName=deliveryId,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST
fi
printf 'Local queue and delivery table are ready.\n'
