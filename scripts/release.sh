#!/usr/bin/env bash
#
# 全版本发版。一条命令取代"逐条分支手工改版本号、手工推、手工 dispatch"。
#
#   scripts/release.sh 0.1.3              # 发 beta(默认)
#   scripts/release.sh 0.1.3 --release    # 发正式版
#   scripts/release.sh 0.1.3 --dry-run    # 只看要做什么,不动任何东西
#
# 两个阶段,中间隔着 CI:
#   一  在每条版本分支上改版本号、提交、推送 → 触发各分支的 Build
#   二  <b>等 13 条全绿之后</b>再打 tag 并推送 → tag 触发发布
#
# 为什么要隔着 CI:tag 是"这个版本发出去了"的意思,而发布流水线由 tag 触发。
# 先 tag 再看 CI 的话,红的那条已经把 jar 传上 CurseForge 了——那东西撤起来
# 要人工去后台删。让不可逆的那步排在判据之后。
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION=""; CHANNEL="beta"; DRY=0
for a in "$@"; do
  case "$a" in
    --release) CHANNEL="release" ;;
    --alpha)   CHANNEL="alpha" ;;
    --dry-run) DRY=1 ;;
    -*) echo "未知选项: $a" >&2; exit 2 ;;
    *) VERSION="$a" ;;
  esac
done
[ -n "$VERSION" ] || { sed -n '3,20p' "$0" | sed 's/^# \?//'; exit 2; }
echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "版本号形如 0.1.3" >&2; exit 2; }

# 分支清单从远端实际存在的版本分支得来,不写死——写死就会有一天新开了分支没人记得加。
mapfile -t BRANCHES < <(git ls-remote --heads origin \
  | grep -oE 'refs/heads/[0-9]+\.[0-9]+(\.[0-9]+)?$' | sed 's|refs/heads/||' | sort -V)
[ "${#BRANCHES[@]}" -gt 0 ] || { echo "没找到版本分支" >&2; exit 1; }

say() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }

say "将发布 $VERSION ($CHANNEL) 到 ${#BRANCHES[@]} 条分支"
printf '   %s\n' "${BRANCHES[@]}"
[ "$DRY" = 1 ] && echo "(dry-run,不会改动任何东西)"

WT="$(mktemp -d)/numen-release"
cleanup() { git worktree remove --force "$WT" >/dev/null 2>&1 || true; }
trap cleanup EXIT
git fetch -q origin
git worktree add -q --detach "$WT" HEAD

say "阶段一:改版本号并推送"
for b in "${BRANCHES[@]}"; do
  # 分离头指针:分支可能正被别的工作树占着(比如你自己开着的那个),checkout 会被拒。
  git -C "$WT" checkout -q --detach "origin/$b"
  mc="$(grep -E '^minecraft_version=' "$WT/gradle.properties" | head -1 | cut -d= -f2)"
  if [ "$DRY" = 1 ]; then
    cur="$(grep -E '^version=' "$WT/gradle.properties" | head -1 | cut -d= -f2)"
    echo "  $b (MC $mc): $cur → $VERSION,并刷新 4 份 README 里的坐标"
    continue
  fi
  # 版本号的唯一出处是 gradle.properties。文档里的坐标是派生物,由下面这段机械刷新
  # ——不是第二个出处。分隔符用 @:正则里有 (common|fabric|...) 的竖线。
  sed -i "s@^version=.*@version=$VERSION@" "$WT/gradle.properties"
  for doc in README.md README_EN.md api/README.md api/README_EN.md; do
    [ -f "$WT/$doc" ] || continue
    sed -i -E "s@(numen-(api-)?(common|fabric|forge|neoforge)-[0-9.]+):[0-9]+\.[0-9]+\.[0-9]+@\1:$VERSION@g; s@badge/version-[0-9.]+-@badge/version-$VERSION-@g" "$WT/$doc"
  done
  if git -C "$WT" diff --quiet; then echo "  $b 已经是 $VERSION,跳过"; continue; fi
  git -C "$WT" add -A
  git -C "$WT" commit -q -m "release: $VERSION"
  git -C "$WT" push -q origin "HEAD:refs/heads/$b"
  echo "  $b ($mc) → $VERSION 已推送"
done

[ "$DRY" = 1 ] && { say "dry-run 结束"; exit 0; }

say "阶段二:等 13 条 CI"
deadline=$(( $(date +%s) + 3600 ))
while :; do
  bad=0; pending=0
  for b in "${BRANCHES[@]}"; do
    read -r st cc < <(gh run list --branch "$b" --workflow=build.yml --limit 1 \
      --json status,conclusion --jq '"\(.[0].status // "none") \(.[0].conclusion // "-")"')
    [ "$st" != "completed" ] && pending=$((pending+1))
    [ "$st" = "completed" ] && [ "$cc" != "success" ] && { echo "  ✗ $b: $cc"; bad=$((bad+1)); }
  done
  [ "$bad" -gt 0 ] && { echo "有 $bad 条没绿,不打 tag。修好后重跑本脚本。"; exit 1; }
  [ "$pending" -eq 0 ] && break
  [ "$(date +%s)" -gt "$deadline" ] && { echo "等 CI 超时" >&2; exit 1; }
  echo "  还有 $pending 条在跑…"; sleep 30
done
echo "  13 条全绿"

say "阶段三:打 tag 并推送(每批 ≤3)"
# 每批最多 3 个:一次推超过 3 个 tag,GitHub 不会为多出来的那些触发工作流。
batch=()
flush() {
  [ "${#batch[@]}" -eq 0 ] && return
  git push -q origin "${batch[@]}"
  printf '  已推 %s\n' "${batch[*]}"
  batch=()
  sleep 10
}
for b in "${BRANCHES[@]}"; do
  # 只要读一个属性,不必 checkout
  mc="$(git show "origin/$b:gradle.properties" | grep -E '^minecraft_version=' | head -1 | cut -d= -f2)"
  tag="v${VERSION}-${mc}"; [ "$CHANNEL" != "release" ] && tag="${tag}-${CHANNEL}"
  git tag -f "$tag" "origin/$b" >/dev/null
  batch+=("$tag")
  [ "${#batch[@]}" -ge 3 ] && flush
done
flush

say "完成:tag 已推,发布流水线由它触发"
echo "看进度: gh run list --workflow=publish.yml --limit 15"
