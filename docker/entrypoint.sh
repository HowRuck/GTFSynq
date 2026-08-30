#!/bin/sh
set -eu

exec java \
  -XX:+UseG1GC \
  -XX:+UseStringDeduplication \
  -XX:+UseCompactObjectHeaders \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+ExitOnOutOfMemoryError \
  -Dspring.aot.enabled=true \
  -jar /app/app.jar
