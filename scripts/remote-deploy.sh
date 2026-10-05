#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "Usage: remote-deploy.sh <upload-dir> <install-dir> <service-name> <health-url>" >&2
  exit 64
fi

upload_dir=$1
install_dir=$2
service_name=$3
health_url=$4
artifact="$upload_dir/VinylMatch.jar"
checksum="$upload_dir/VinylMatch.jar.sha256"
target="$install_dir/VinylMatch.jar"
next="$install_dir/.VinylMatch.jar.next"
previous="$install_dir/.VinylMatch.jar.previous"

[[ -s "$artifact" ]] || { echo "Uploaded JAR is missing or empty" >&2; exit 1; }
[[ -s "$checksum" ]] || { echo "Uploaded checksum is missing or empty" >&2; exit 1; }

(
  cd "$upload_dir"
  sha256sum --check --strict VinylMatch.jar.sha256
)

sudo install -d -m 0755 "$install_dir"
sudo install -m 0644 "$artifact" "$next"

expected_sha=$(awk 'NR == 1 { print $1 }' "$checksum")
installed_sha=$(sudo sha256sum "$next" | awk '{ print $1 }')
[[ -n "$expected_sha" && "$installed_sha" == "$expected_sha" ]] || {
  sudo rm -f "$next"
  echo "Installed JAR checksum does not match the release artifact" >&2
  exit 1
}

had_previous=false
if sudo test -f "$target"; then
  sudo cp -p "$target" "$previous"
  had_previous=true
fi

rollback() {
  if [[ "$had_previous" == true ]] && sudo test -f "$previous"; then
    echo "Restoring previous JAR after failed deployment" >&2
    sudo install -m 0644 "$previous" "$next"
    sudo mv -f "$next" "$target"
    sudo systemctl restart "$service_name" || true
  fi
}

# The final rename stays on one filesystem, so readers see either the old or new complete JAR.
sudo mv -f "$next" "$target"

if ! sudo systemctl restart "$service_name"; then
  rollback
  exit 1
fi
if ! sudo systemctl is-active --quiet "$service_name"; then
  rollback
  echo "Service did not become active after restart" >&2
  exit 1
fi

healthy=false
for _ in $(seq 1 20); do
  if curl --fail --silent --show-error --max-time 5 "$health_url" >/dev/null; then
    healthy=true
    break
  fi
  sleep 3
done

if [[ "$healthy" != true ]]; then
  rollback
  echo "Simple health check failed after deployment" >&2
  exit 1
fi

echo "Deployment completed with SHA-256 $installed_sha"
