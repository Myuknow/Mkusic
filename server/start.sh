#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ ! -f .env ]; then node scripts/setup.js; fi
exec node server.js
