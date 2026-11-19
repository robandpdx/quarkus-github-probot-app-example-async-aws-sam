# quarkus-github-probot-app-example-async-aws-sam

A Java 25 / Quarkus GitHub App that acknowledges verified webhooks quickly and posts
`Hello word!` on newly opened issues in a separate Lambda invocation.

```text
GitHub -> API Gateway HTTP API -> receiver Lambda -> SQS -> worker Lambda -> GitHub comment
                                      |                     |
                                  HTTP 200              DynamoDB delivery ledger
                                  after enqueue             |
                                                     failed messages -> SQS DLQ
```

This is a separate example from
[the synchronous app](https://github.com/robandpdx/quarkus-github-probot-app-example-aws-sam).
It does not modify that repository, its AWS stack, or its GitHub App configuration.

## Stack and layout

* Amazon Corretto / Java 25, Quarkus **3.37.4**, Quarkus GitHub App **2.16.0**.
* Maven wrapper, AWS SAM, API Gateway HTTP API, SQS, DynamoDB.
* `receiver/`: `quarkus-amazon-lambda-http`, signature verification and synchronous enqueue.
* `worker/`: `quarkus-amazon-lambda`, synchronous SQS batch processing and GitHub comments.
* `shared/`: the minimal queue message and AWS SDK clients.
* `template.yaml`: two Lambdas, queue, dead-letter queue, and delivery table.
* `compose.yaml`, `local/`, `scripts/init-local.sh`: local SQS (ElasticMQ) and DynamoDB Local.
* `.vscode/`: local application and unit-test debug configurations.
* `.devcontainer/`: Corretto 25, Node.js, smee client, AWS CLI, SAM CLI, Docker, Python, and direnv.

The receiver and worker deliberately use different Quarkus Lambda extensions in separate modules:
an HTTP-provided Lambda handler cannot coexist with a custom SQS handler in one application.
Keep Quarkus pinned until both local dev modes have been checked with a newer version.

## Processing and failure semantics

The GitHub App extension validates the webhook signature. A synchronous
`@Observes GitHubEvent` receiver accepts only `issues.opened` and waits for SQS to accept
the message before returning HTTP 200. It sends only the delivery UUID, installation ID,
repository name, and issue number, not the issue body or credentials.

**HTTP 200 means accepted for processing, not that the comment has been posted.**
An enqueue failure returns HTTP 500 and is logged. GitHub does not automatically retry
failed webhook deliveries; inspect Recent deliveries and redeliver them when necessary.

The worker uses the extension's authenticated installation client. Each message is processed
to completion before acknowledging it. Partial batch responses retry only failed records;
after five receives, a repeatedly failing message goes to the DLQ. The worker timeout is
90 seconds and the queue visibility timeout is 540 seconds.

DynamoDB stores per-delivery ownership leases and completed records with a seven-day TTL.
An active competing claim is retried rather than acknowledged. Comments include a hidden
delivery marker so a retry can recognize a comment posted just before the worker crashed.
This substantially reduces duplicate comments, including after ledger expiry, but is **not
a mathematical exactly-once guarantee**: GitHub and DynamoDB cannot be updated in one atomic
transaction. Preserve the marker when editing comments.

Do not replace either worker completion or receiver enqueue with detached `fireAsync()` work:
Lambda can freeze background threads after returning an invocation response.

## Prerequisites

Install a Java 25 JDK, Docker with Compose, AWS CLI, and AWS SAM CLI. Maven is included.
Alternatively open this folder in the provided dev container.

Register a **separate GitHub App** if the synchronous example should continue working:

1. Create a channel at <https://smee.io/> for local development.
2. Register a GitHub App with that webhook URL and a random webhook secret.
3. Give it **Issues: read and write** permission and subscribe to **Issues** events.
4. Generate its private key and save it as `app.private-key.pem` in the repository root.
5. Install the App on your test repository.

```bash
cp .envrc-sample .envrc
# Edit .envrc: App ID, webhook secret, and smee channel.
source .envrc
# Alternatively, install direnv and run: direnv allow
```

The required settings are `QUARKUS_GITHUB_APP_APP_ID`, `QUARKUS_GITHUB_APP_WEBHOOK_SECRET`,
and `QUARKUS_GITHUB_APP_PRIVATE_KEY` (complete PEM). Set
`QUARKUS_GITHUB_APP_WEBHOOK_PROXY_URL` to the smee channel for local receiver development.
The worker does not need a webhook proxy or webhook secret.

`.envrc`, `*.pem`, and `samconfig.toml` are git-ignored. No credentials from the synchronous
example are included. Local AWS clients use dummy credentials with emulator endpoints;
deployed clients use their Lambda execution roles. Do not configure production endpoint overrides.

## Run locally

From the repository root:

```bash
docker compose up -d
bash scripts/init-local.sh
./mvnw -B install -DskipTests
source .envrc
```

Run the receiver in one terminal:

```bash
./mvnw -f receiver/pom.xml quarkus:dev -Ddebug=5005
```

Run the worker in another terminal, after loading `.envrc` there too:

```bash
env -u QUARKUS_GITHUB_APP_WEBHOOK_PROXY_URL ./mvnw -f worker/pom.xml quarkus:dev -Ddebug=5006
```

The receiver listens on `http://localhost:8080/` and connects to smee using the extension's
built-in proxy. The worker's development-only scheduler polls ElasticMQ and calls the same
SQS handler used on AWS; it deletes only successfully processed messages. The scheduler is
not included in production. Worker health is at `http://localhost:8082/q/health/ready`.
Its separate mock Lambda event server uses port 8083; keep it enabled so the development
Lambda poll loop stays alive while the local SQS scheduler runs.

Open an issue on the installed repository. The receiver logs its queued delivery UUID,
and the worker posts the comment independently. Stop the worker to see events accumulate;
restart it to drain them. **Local runs post real GitHub comments.**

Dev mode disables webhook signature enforcement, as in the synchronous example. Production
enforces it. The emulators require no AWS account and do not provide production durability:
recreating their containers loses their in-memory state.

If you prefer the optional smee CLI instead of the built-in proxy, unset
`QUARKUS_GITHUB_APP_WEBHOOK_PROXY_URL` before starting the receiver, then run
`smee --url https://smee.io/YOUR-CHANNEL --target http://localhost:8080/` in another terminal.
The CLI is preinstalled in the dev container.

To stop the local services:

```bash
docker compose down
```

### Debug in VS Code

Choose **Debug async GitHub App** in Run and Debug. Its preparation task starts the emulators,
initializes the table, and installs the reactor artifacts, then starts and attaches to both
dev-mode JVMs. Set breakpoints in the receiver or worker to follow the two stages separately.
Ports are 5005 (receiver) and 5006 (worker).

Individual **Debug receiver** / **Debug worker** configurations require running the
`prepare-local` task first. **Debug receiver tests** and **Debug worker tests** run their
respective suites; stop application debug sessions first to free the test debugger port 5005.
Stop dev-mode processes in their task terminals when finished; disconnecting a debugger
alone does not necessarily terminate Maven.

### Tests

```bash
./mvnw -B verify
```

Tests use mocked GitHub and AWS clients; no credentials, containers, GitHub comments, or
AWS resources are needed. They cover enqueue failures, event filtering, worker retries,
partial failures, and duplicate protection. CI runs the tests and SAM template lint.

## Build and deploy with AWS SAM

Configure AWS credentials using your normal profile or credential provider. This creates a
**new, separately named stack**; do not reuse the synchronous example's stack name.

```bash
./mvnw -B package
sam validate --lint --region us-west-2
cp samconfig.example.toml samconfig.toml
sam deploy --guided
```

Choose the new stack name `quarkus-github-app-async`. Supply the App ID, webhook secret,
and the complete private key. For the guided PEM prompt, replace newlines with spaces and
include its `-----BEGIN RSA PRIVATE KEY-----` / `-----END RSA PRIVATE KEY-----` markers.
SAM packages `receiver/target/function.zip` and `worker/target/function.zip` directly;
`sam build` is not required.

Alternatively, after loading `.envrc`:

```bash
sam deploy \
  --stack-name quarkus-github-app-async \
  --resolve-s3 --region us-west-2 --capabilities CAPABILITY_IAM \
  --parameter-overrides \
    GitHubAppId="$QUARKUS_GITHUB_APP_APP_ID" \
    GitHubAppWebhookSecret="\"$QUARKUS_GITHUB_APP_WEBHOOK_SECRET\"" \
    GitHubAppPrivateKey="\"$(tr '\n' ' ' < app.private-key.pem)\""
```

Keep the literal inner quotes: SAM otherwise truncates parameter values at spaces.
Guided mode does not save `NoEcho` secret parameters. Omitted values can be reused on
updates to an existing stack, but must be supplied again when creating or recreating one.

Set the new App's webhook URL to the stack's `WebhookUrl` output. Do not change the
synchronous App's webhook if it must remain active. Published versions use SnapStart;
`JAVA_TOOL_OPTIONS` supplies Quarkus's required Java 25 module opens in both functions.

### Operations

Inspect the receiver and worker CloudWatch logs independently. An accepted webhook followed
by a failed comment is a **worker** failure, not a reason to redeliver the webhook immediately.
Watch SQS queue age and the DLQ. After fixing the underlying problem, redrive failed messages
from the DLQ into the main queue. Keep the delivery IDs unchanged to retain duplicate protection.

The SAM outputs also include `QueueUrl`, `DeadLetterQueueUrl`, and `DeliveryTableName`.
No alarms, custom domains, or Secrets Manager resources are provisioned by this example.
Secret parameters are `NoEcho`, but execution-role/environment access must still be restricted.
For production, consider Secrets Manager and explicit monitoring.

To delete this example's AWS resources, use `sam delete --stack-name quarkus-github-app-async`.
Deleting the stack deletes queued messages and the delivery ledger.
