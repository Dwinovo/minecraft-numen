#!/usr/bin/env bash
#
# tag 必须与 gradle.properties 对得上,否则拒绝发布。
#
# 发布流水线的版本号来自 gradle.properties(见 publish.yml 的 Prepare publish metadata),
# tag 只提供 channel。两者一旦对不上,就会出现"打着 v0.1.3 的 tag、发出去的是 0.1.2"
# ——GitHub release 的标题、CurseForge 的文件名、maven 的坐标各说各话,而且发出去了
# 才看得出来。这一步把它挡在发布之前。
#
# 用法:scripts/check-tag.sh "$GITHUB_REF_NAME"
# tag 形如 v<version>-<mc>[-alpha|-beta]
set -euo pipefail
cd "$(dirname "$0")/.."

tag="${1:-}"
[ -n "$tag" ] || { echo "::error::没有传 tag"; exit 1; }

prop() { grep -E "^$1=" gradle.properties | head -n1 | cut -d= -f2-; }
want_version="$(prop version)"
want_mc="$(prop minecraft_version)"

# 去掉前缀 v 与末尾的 channel,余下应当是 <version>-<mc>
body="${tag#v}"
body="${body%-alpha}"; body="${body%-beta}"
got_version="${body%%-*}"
got_mc="${body#*-}"

fail=0
if [ "$got_version" != "$want_version" ]; then
  echo "::error::tag 里的版本是 $got_version,gradle.properties 里是 $want_version"
  fail=1
fi
if [ "$got_mc" != "$want_mc" ]; then
  echo "::error::tag 里的 MC 版本是 $got_mc,本分支是 $want_mc(tag 打到别的分支上了?)"
  fail=1
fi
[ "$fail" = 0 ] || exit 1
echo "tag $tag 与 gradle.properties 一致($want_version / MC $want_mc)"
