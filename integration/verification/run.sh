#!/usr/bin/env bash
# 本地验证：只依赖 ~/.m2 里已有的 POI / Lombok / Jackson，不需要起后端。
# 用途：确认「归一化 → 填业务值 → 还原占位符」链路与字段注册表反射结果。
set -e
M2="$HOME/.m2/repository"
BK="${BK:-$HOME/work/shmj-backend/shmj-backend-server/src/main/java/shop/shmj/backend}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"
rm -rf "$OUT" && mkdir -p "$OUT"

pick() { ls "$M2/$1" 2>/dev/null | sort -V | tail -1; }
CP=""
add()  { local g=$1 a=$2 v; v=$(pick "$g/$a"); [ -n "$v" ] && CP="$CP$M2/$g/$a/$v/$a-$v.jar:"; }
addv() { local g=$1 a=$2 v=$3; CP="$CP$M2/$g/$a/$v/$a-$v.jar:"; }
add org/apache/poi poi
add org/apache/poi poi-ooxml
add org/apache/poi poi-ooxml-lite
add org/apache/xmlbeans xmlbeans
addv commons-io commons-io 2.16.1   # 2.18+ 移除了 POI 依赖的 UnsynchronizedByteArrayOutputStream
add org/apache/commons commons-compress
add org/apache/commons commons-collections4
add commons-codec commons-codec
add org/apache/logging/log4j log4j-api
add org/slf4j slf4j-api
add org/projectlombok lombok
add com/jayway/jsonpath json-path
add net/minidev json-smart
add net/minidev accessors-smart
add org/ow2/asm asm
add com/fasterxml/jackson/core jackson-databind
add com/fasterxml/jackson/core jackson-core
add com/fasterxml/jackson/core jackson-annotations
add com/fasterxml/jackson/datatype jackson-datatype-jsr310
add com/github/virtuald curvesapi
add org/springframework spring-context
add org/springframework spring-beans
add org/springframework spring-core
add jakarta/validation jakarta.validation-api

javac -nowarn -encoding UTF-8 -proc:full -cp "$CP" -d "$OUT" \
  "$BK/utils/JsonUtils.java" "$BK/utils/SdtTemplateUtil.java" \
  "$HERE/../src/main/java/shop/shmj/backend/utils/SdtTokenRestoreUtil.java" \
  "$BK/service/template/TwoDecimalSerializer.java" "$BK/service/template/V1ContractParam.java" \
  "$HERE/../src/main/java/shop/shmj/backend/service/contract/TemplateFieldRegistry.java" \
  "$HERE/SdtRoundTripCheck.java" "$HERE/FieldRegistryCheck.java"

echo "==== 1. 占位符往返 ===="
java -cp "$OUT:$CP" SdtRoundTripCheck 2>/dev/null | grep -E "^\[|RESULT"
echo "==== 2. 字段注册表 ===="
java -cp "$OUT:$CP" FieldRegistryCheck 2>/dev/null | grep -E "^(count|unknown|sample|RESULT)"
