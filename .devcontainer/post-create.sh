#!/usr/bin/env bash
set -euo pipefail
pipx install aws-sam-cli
npm install --global smee-client
sudo apt-get update
sudo apt-get install -y --no-install-recommends direnv
if ! grep -q 'direnv hook bash' ~/.bashrc; then
    printf '\neval "$(direnv hook bash)"\n' >> ~/.bashrc
fi
./mvnw -B install -DskipTests
