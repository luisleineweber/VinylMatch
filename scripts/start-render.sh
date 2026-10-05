#!/bin/sh
set -eu

if [ -z "${PUBLIC_BASE_URL:-}" ]; then
    : "${RENDER_EXTERNAL_URL:?Set PUBLIC_BASE_URL or run this image on Render}"
    PUBLIC_BASE_URL="$RENDER_EXTERNAL_URL"
fi
export PUBLIC_BASE_URL

exec java -Djava.net.preferIPv4Stack=true -jar target/VinylMatch.jar
