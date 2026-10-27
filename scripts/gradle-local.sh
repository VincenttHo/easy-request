#!/bin/sh
set -eu

EASY_REQUEST_IDEA_PATH=${EASY_REQUEST_IDEA:-/Applications/IntelliJ IDEA.app}
case "$EASY_REQUEST_IDEA_PATH" in
  *.app) EASY_REQUEST_IDEA_CONTENTS="$EASY_REQUEST_IDEA_PATH/Contents" ;;
  *) EASY_REQUEST_IDEA_CONTENTS="$EASY_REQUEST_IDEA_PATH" ;;
esac
EASY_REQUEST_JAVA="$EASY_REQUEST_IDEA_CONTENTS/jbr/Contents/Home/bin/java"
if [ ! -x "$EASY_REQUEST_JAVA" ]; then
  EASY_REQUEST_JAVA="$EASY_REQUEST_IDEA_CONTENTS/jbr/bin/java"
fi
if [ ! -x "$EASY_REQUEST_JAVA" ]; then
  printf '%s\n' "IDEA 的 JBR 不存在，请通过 EASY_REQUEST_IDEA 指定安装目录。" >&2
  exit 1
fi
EASY_REQUEST_PROJECT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$EASY_REQUEST_PROJECT"
exec "$EASY_REQUEST_JAVA" -classpath "$EASY_REQUEST_PROJECT/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "-PlocalIdePath=$EASY_REQUEST_IDEA_CONTENTS" "$@"
