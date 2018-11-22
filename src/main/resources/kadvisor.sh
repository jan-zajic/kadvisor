#!/bin/sh
PATH="/bin:/usr/bin"
if [ -x "$(command -v pkill)" ]; then
  pkill ${NODE_EXPORTER_NAME}
elif [ -x "$(command -v killall)" ]; then
  killall ${NODE_EXPORTER_NAME}
fi
exec ${NODE_EXPORTER_PATH} "$@"