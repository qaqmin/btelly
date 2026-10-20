#!/bin/bash
# publish-release.sh <tag> [副标题]
# 一条命令完成双渠道发版：读取 Mac 构建机最新 DONE 构建的签名 APK，
# 从构建目录的 CHANGELOG.md 提取该版本发布说明，发布到 GitHub + Codeberg。
#
# 用法:
#   ~/bin/publish-release.sh v3.1.2 "弹幕性能优化"
#
# 前置条件（token 文件需用户事先创建，权限 600，内容为纯 token 无换行）:
#   ~/.btelly-publish/github_token
#   ~/.btelly-publish/codeberg_token
#
# 行为:
#   - 要求 ~/builds/btelly/status 为 DONE，并用其 ARTIFACTS 目录下的签名 APK
#   - tag 必须在构建目录 CHANGELOG.md 中存在（## <tag> 小节），否则拒绝发布
#   - Release 已存在时：删除旧资产并重传（刷新安装包），说明同步更新
set -euo pipefail

TAG="${1:-}"
SUBTITLE="${2:-}"
BUILD_DIR="$HOME/builds/btelly"
TOKEN_DIR="$HOME/.btelly-publish"
GH_OWNOREPO="qaqmin/btelly"
CB_OWNOREPO="min09577/btelly"

die() { echo "错误: $*" >&2; exit 1; }

[ -n "$TAG" ] || die "用法: publish-release.sh <tag> [副标题]，例如 v3.1.2"
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "tag 格式应为 vX.Y.Z，当前: $TAG"

GH_TOKEN_FILE="$TOKEN_DIR/github_token"
CB_TOKEN_FILE="$TOKEN_DIR/codeberg_token"
[ -r "$GH_TOKEN_FILE" ] || die "缺少 $GH_TOKEN_FILE（内容为 GitHub token，勿含换行）"
[ -r "$CB_TOKEN_FILE" ] || die "缺少 $CB_TOKEN_FILE（内容为 Codeberg token，勿含换行）"
GH_TOKEN="$(tr -d '\n\r' < "$GH_TOKEN_FILE")"
CB_TOKEN="$(tr -d '\n\r' < "$CB_TOKEN_FILE")"

STATUS_FILE="$BUILD_DIR/status"
[ -r "$STATUS_FILE" ] || die "找不到 $STATUS_FILE"
STATUS="$(grep '^STATUS:' "$STATUS_FILE" | awk '{print $2}')"
JOB="$(grep '^JOB:' "$STATUS_FILE" | awk '{print $2}')"
[ "$STATUS" = "DONE" ] || die "最新构建状态为 $STATUS（$JOB），不是 DONE，拒绝发布"
ART_DIR="$BUILD_DIR/artifacts/$JOB"
APK="$(ls "$ART_DIR"/BTelly-v*-release.apk 2>/dev/null | head -1)"
[ -n "$APK" ] || die "$ART_DIR 下没有 BTelly-v*-release.apk"

CHANGELOG="$BUILD_DIR/CHANGELOG.md"
[ -r "$CHANGELOG" ] || die "找不到 $CHANGELOG"
# 提取 ## <tag> 到下一个 ## 之间的内容
NOTES="$(awk -v tag="## $TAG" 'BEGIN{p=0} $0==tag{p=1;next} /^## /{p=0} p' "$CHANGELOG")"
[ -n "$(echo "$NOTES" | tr -d '[:space:]')" ] || die "CHANGELOG.md 中没有 [$TAG] 小节，拒绝发布"
NAME="B视机 $TAG${SUBTITLE:+ — $SUBTITLE}"
VERSION="${TAG#v}"
ASSET_NAME="BTellyV${VERSION}.apk"
LABEL="B视机V${VERSION}.apk"
FOOTER=$'\n\n> 安装：下载 APK 直接覆盖安装（签名钥匙不变，无需卸载旧版）。应用内更新：设置 → 关于本机 → 检查更新。'
BODY="${NOTES}${FOOTER}"

echo "== 发布 $NAME"
echo "   JOB=$JOB"
echo "   APK=$APK ($(du -h "$APK" | awk '{print $1}'))"

# ---------- GitHub ----------
publish_github() {
  local auth="Authorization: token $GH_TOKEN"
  local rel id asset_id existing
  rel="$(curl -sf -H "$auth" "https://api.github.com/repos/$GH_OWNOREPO/releases/tags/$TAG" || true)"
  if [ -n "$rel" ]; then
    id="$(echo "$rel" | jq -r .id)"
    echo "   GitHub Release 已存在，刷新资产与说明"
    for asset_id in $(echo "$rel" | jq -r '.assets[].id'); do
      curl -sf -X DELETE -H "$auth" "https://api.github.com/repos/$GH_OWNOREPO/releases/assets/$asset_id" >/dev/null
    done
    curl -sf -X PATCH -H "$auth" -H "Content-Type: application/json" \
      -d "$(jq -n --arg name "$NAME" --arg body "$BODY" '{name:$name, body:$body}')" \
      "https://api.github.com/repos/$GH_OWNOREPO/releases/$id" >/dev/null
  else
    id="$(curl -sf -H "$auth" -H "Content-Type: application/json" \
      -d "$(jq -n --arg tag "$TAG" --arg name "$NAME" --arg body "$BODY" '{tag_name:$tag, name:$name, body:$body}')" \
      "https://api.github.com/repos/$GH_OWNOREPO/releases" | jq -r .id)"
    echo "   GitHub Release 已创建"
  fi
  curl -sf -H "$auth" -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@$APK" \
    "https://uploads.github.com/repos/$GH_OWNOREPO/releases/$id/assets?name=$ASSET_NAME" | jq -r '.name' >/dev/null
  asset_id="$(curl -sf -H "$auth" "https://api.github.com/repos/$GH_OWNOREPO/releases/$id" | jq -r ".assets[] | select(.name==\"$ASSET_NAME\") | .id")"
  curl -sf -X PATCH -H "$auth" -H "Content-Type: application/json" \
    -d "$(jq -n --arg label "$LABEL" '{label:$label}')" \
    "https://api.github.com/repos/$GH_OWNOREPO/releases/assets/$asset_id" >/dev/null
  echo "   GitHub: https://github.com/$GH_OWNOREPO/releases/tag/$TAG （资产显示名 $LABEL）"
}

# ---------- Codeberg ----------
publish_codeberg() {
  local auth="Authorization: token $CB_TOKEN"
  local rel id existing_asset
  rel="$(curl -sf -H "$auth" "https://codeberg.org/api/v1/repos/$CB_OWNOREPO/releases/tags/$TAG" || true)"
  if [ -n "$rel" ]; then
    id="$(echo "$rel" | jq -r .id)"
    echo "   Codeberg Release 已存在，刷新资产与说明"
    for existing_asset in $(echo "$rel" | jq -r '.assets[].id'); do
      curl -sf -X DELETE -H "$auth" "https://codeberg.org/api/v1/repos/$CB_OWNOREPO/releases/$id/assets/$existing_asset" >/dev/null
    done
    curl -sf -X PATCH -H "$auth" -H "Content-Type: application/json" \
      -d "$(jq -n --arg name "$NAME" --arg body "$BODY" '{name:$name, body:$body}')" \
      "https://codeberg.org/api/v1/repos/$CB_OWNOREPO/releases/$id" >/dev/null
  else
    id="$(curl -sf -H "$auth" -H "Content-Type: application/json" \
      -d "$(jq -n --arg tag "$TAG" --arg name "$NAME" --arg body "$BODY" '{tag_name:$tag, name:$name, body:$body}')" \
      "https://codeberg.org/api/v1/repos/$CB_OWNOREPO/releases" | jq -r .id)"
    echo "   Codeberg Release 已创建"
  fi
  curl -sf -H "$auth" -F "attachment=@$APK" \
    "https://codeberg.org/api/v1/repos/$CB_OWNOREPO/releases/$id/assets?name=$ASSET_NAME" | jq -r '.name' >/dev/null
  echo "   Codeberg: https://codeberg.org/$CB_OWNOREPO/releases/tag/$TAG"
}

publish_github
publish_codeberg
echo "== 完成"
