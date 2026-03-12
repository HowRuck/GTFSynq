#!/bin/bash
set -e

exec java \
  --enable-native-access=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-exports java.base/sun.nio.ch=ALL-UNNAMED \
  -jar ./SiriAnalyzer.jar