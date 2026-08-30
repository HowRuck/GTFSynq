#!/bin/sh
set -eu

exec java \
  -XX:+UseG1GC \
  -XX:+UseStringDeduplication \
  -XX:+UnlockExperimentalVMOptions \
  -XX:+UseCompactObjectHeaders \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+ExitOnOutOfMemoryError \
  -Dserver.port=8888 \
  -Dspring.aot.enabled=true \
  -Dhibernate.bytecode.use_reflection_optimizer=true \
  -Djava.security.egd=file:/dev/./urandom \
  -jar /app/app.jar
