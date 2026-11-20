#!/usr/bin/env bash
#
# drain-to.sh — copy (merge or mirror) the git-tracked files of this checkout
#               into another folder, never touching .git on either side.
#
# Needs only bash (3.2+, the one shipped with macOS is fine), git and the
# standard Unix tools (find, sort, comm, cmp, cp, awk). No rsync.
# Run '--help' for details.
#
set -euo pipefail

PROG="$(basename "$0")"

# ------------------------------------------------------------------ arguments
DEST="" DRY=0 MIRROR=0 UPDATE=0 PULL=1 HELP=0 BADARG=""
COLOR=auto
[ $# -eq 0 ] && HELP=1
while [ $# -gt 0 ]; do
  case "$1" in
    -n|--dry-run)  DRY=1 ;;
    -m|--mirror)   MIRROR=1 ;;
    -u|--update)   UPDATE=1 ;;
    --no-pull)     PULL=0 ;;
    --no-color)    COLOR=never ;;
    -h|--help)     HELP=1 ;;
    --)            shift; [ $# -gt 0 ] && { if [ -z "$DEST" ]; then DEST="$1"; else BADARG="only one destination is allowed"; fi; } ;;
    -*)            [ -z "$BADARG" ] && BADARG="unknown option: $1" ;;
    *)             if [ -z "$DEST" ]; then DEST="$1"; else BADARG="only one destination is allowed (got '$DEST' and '$1')"; fi ;;
  esac
  [ $# -gt 0 ] && shift
done

# --------------------------------------------------------------------- colors
if [ "$COLOR" = auto ]; then
  if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-dumb}" != dumb ]; then COLOR=always; else COLOR=never; fi
fi
if [ "$COLOR" = always ]; then
  R=$'\033[0m' B=$'\033[1m' D=$'\033[2m'
  RED=$'\033[31m' GRN=$'\033[32m' YEL=$'\033[33m' BLU=$'\033[34m' MAG=$'\033[35m' CYN=$'\033[36m'
else
  R="" B="" D="" RED="" GRN="" YEL="" BLU="" MAG="" CYN=""
fi
# Colors on stderr only if stderr is a terminal too.
if [ -n "$R" ] && [ -t 2 ]; then ER="$R" ERED="$RED" EYEL="$YEL" EB="$B"; else ER="" ERED="" EYEL="" EB=""; fi

die()  { printf '%s%serror:%s %s\n' "$EB" "$ERED" "$ER" "$*" >&2; exit 1; }
warn() { printf '%s%swarning:%s %s\n' "$EB" "$EYEL" "$ER" "$*" >&2; }
udie() { printf '%s%s:%s %s\n' "$EB" "$PROG" "$ER" "$*" >&2; printf "Run '%s --help' for usage.\n" "$PROG" >&2; exit 2; }
step() { printf '%s%s==>%s %s%s%s\n' "$B" "$BLU" "$R" "$B" "$*" "$R"; }

usage() {
  cat <<EOF
${B}${CYN}$PROG${R} — copy the git-tracked files of this checkout into another folder.

${B}WHAT IT DOES${R}
  1. Checks that this checkout is ${B}clean${R} (no uncommitted changes, no
     untracked files; files ignored by .gitignore are fine).
  2. Runs ${B}git pull${R}. On a conflict (or any pull failure) the merge/rebase is
     aborted, the checkout is restored, nothing is copied, and it exits 1.
  3. Copies every file tracked by git (including submodules) into DEST.
     ${B}.git is never copied, and DEST/.git is never touched${R}, even with --mirror.
     Ignored/build files are not copied.
  4. Prints what was added, changed and deleted, with totals.

  The source is always the root of the checkout that contains this script,
  wherever you call it from.

${B}USAGE${R}
  $PROG ${GRN}DEST${R} [options]
  $PROG                  (no arguments) show this help
  $PROG -h | --help      show this help

${B}ARGUMENTS${R}
  ${GRN}DEST${R}             destination folder; created if missing. Must not be this
                   checkout, a folder inside it, or a folder that contains it.

${B}OPTIONS${R}
  ${YEL}-n, --dry-run${R}    show what would change; change nothing
                   (also skips git pull and does not create DEST)
  ${YEL}-m, --mirror${R}     make DEST an exact copy: also ${RED}DELETE${R} files and folders
                   in DEST that are not tracked here (DEST/.git is protected)
  ${YEL}-u, --update${R}     do not overwrite files that are newer in DEST
  ${YEL}    --no-pull${R}    skip git pull
  ${YEL}    --no-color${R}   plain output (also: NO_COLOR=1, or output not a terminal)
  ${YEL}-h, --help${R}       show this help

  Default mode is ${B}merge${R}: add new files, update changed ones, delete nothing.

${B}EXAMPLES${R}
  $PROG ~/work/plan-instrs -n     ${D}# preview${R}
  $PROG ~/work/plan-instrs        ${D}# merge${R}
  $PROG ~/work/plan-instrs -m     ${D}# mirror (deletes extras in DEST)${R}

${B}EXIT CODES${R}
  0 success   1 error or conflicts   2 bad command-line arguments

${B}MORE${R}
  See drain-to.md next to this script.
EOF
}

[ "$HELP" = 1 ] && { usage; exit 0; }
[ -n "$BADARG" ] && udie "$BADARG"
[ -z "$DEST" ]   && udie "destination folder is missing"

command -v git >/dev/null 2>&1 || die "git is not installed"

# --------------------------------------------------------------------- source
SRC="$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null)" \
  || die "$PROG must live inside a git checkout (could not find the repo root)"
SRC="$(cd "$SRC" && pwd -P)"

# ---------------------------------------------------------------- destination
# Resolve DEST to an absolute physical path even if it does not exist yet.
ANC="$DEST" REST=""
while [ ! -e "$ANC" ] && [ ! -L "$ANC" ]; do
  REST="/$(basename "$ANC")$REST"
  ANC="$(dirname "$ANC")"
done
if [ ! -d "$ANC" ]; then
  if [ -z "$REST" ]; then die "destination '$DEST' exists but is not a folder"
  else die "cannot create '$DEST': '$ANC' is not a folder"; fi
fi
ANC_ABS="$(cd "$ANC" 2>/dev/null && pwd -P)" || die "cannot access '$ANC' (permission denied?)"
DEST="${ANC_ABS%/}$REST"
[ -z "$DEST" ] && DEST="/"

[ "$DEST" = "$SRC" ] && die "destination is this checkout itself ($SRC) — refusing."
case "$DEST/" in "$SRC/"*) die "destination '$DEST' is inside this checkout ($SRC) — that would dirty it, refusing." ;; esac
case "$SRC/"  in "${DEST%/}/"*) die "this checkout ($SRC) is inside the destination '$DEST' — refusing." ;; esac

DEST_EXISTS=1
if [ -n "$REST" ]; then
  DEST_EXISTS=0
  [ -w "$ANC_ABS" ] && [ -x "$ANC_ABS" ] \
    || die "destination '$DEST' does not exist and cannot be created: no write permission in '$ANC_ABS'"
else
  [ -w "$DEST" ] && [ -x "$DEST" ] || die "destination folder '$DEST' is not writable"
fi

# -------------------------------------------------------- 1. must be clean
step "Checking that $SRC is clean"
if [ -n "$(git -C "$SRC" status --porcelain --untracked-files=normal)" ]; then
  git -C "$SRC" status --short >&2
  die "source checkout is dirty — commit, stash or clean it first."
fi

# ------------------------------------------------- 2. git pull, or bail out
if [ "$PULL" = 1 ] && [ "$DRY" = 1 ]; then
  step "Dry run: skipping git pull"
elif [ "$PULL" = 1 ]; then
  git -C "$SRC" rev-parse --abbrev-ref --symbolic-full-name '@{u}' >/dev/null 2>&1 \
    || die "current branch has no upstream — set one (git branch -u origin/<branch>) or use --no-pull."
  step "git pull"
  if ! git -C "$SRC" pull; then
    if git -C "$SRC" rev-parse -q --verify MERGE_HEAD >/dev/null; then
      git -C "$SRC" merge --abort
    elif [ -d "$(git -C "$SRC" rev-parse --git-path rebase-merge)" ] \
      || [ -d "$(git -C "$SRC" rev-parse --git-path rebase-apply)" ]; then
      git -C "$SRC" rebase --abort
    fi
    die "git pull failed (conflict, diverged branches or network). Nothing was copied; checkout restored."
  fi
fi

# -------------------------------------------------------------- 3. file lists
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
export LC_ALL=C   # byte-wise sort, so 'sort' and 'comm' agree

git -C "$SRC" ls-files -z --recurse-submodules > "$TMP/tracked0"
N_NUL=$(tr -cd '\000' < "$TMP/tracked0" | wc -c | tr -d ' ')
tr '\000' '\n' < "$TMP/tracked0" | sort -u > "$TMP/tracked"
N_TRACKED=$(wc -l < "$TMP/tracked" | tr -d ' ')
[ "$N_NUL" = "$N_TRACKED" ] || die "some tracked file names contain a newline — not supported."

# every folder that has to exist in DEST
ancestors() { awk '{ n = split($0, a, "/"); p = ""; for (i = 1; i < n; i++) { p = (i == 1) ? a[1] : p "/" a[i]; print p } }'; }
ancestors < "$TMP/tracked" | sort -u > "$TMP/srcdirs"

: > "$TMP/del_files"; : > "$TMP/del_dirs"; : > "$TMP/gone"
if [ "$MIRROR" = 1 ] && [ "$DEST_EXISTS" = 1 ]; then
  ( cd "$DEST"
    find . -name .git -prune -o \( -type f -o -type l \) -print | sed 's|^\./||' | sort > "$TMP/destfiles"
    find . -name .git -prune -o -type d ! -path . -print     | sed 's|^\./||' | sort > "$TMP/destdirs"
    find . -name .git -prune -print                          | sed 's|^\./||' | sort > "$TMP/gitpaths" )
  comm -23 "$TMP/destfiles" "$TMP/tracked" > "$TMP/del_files"
  # folders that still hold something we keep (a tracked file or a .git)
  { comm -12 "$TMP/destfiles" "$TMP/tracked"; cat "$TMP/gitpaths"; } | sed 's|$|/x|' | ancestors > "$TMP/keep0"
  cat "$TMP/srcdirs" "$TMP/keep0" | sort -u > "$TMP/keepdirs"
  comm -23 "$TMP/destdirs" "$TMP/keepdirs" | sort -r > "$TMP/del_dirs"   # deepest first
  cat "$TMP/del_files" "$TMP/del_dirs" > "$TMP/gone"
fi

# ------------------------------------------------------------------ 4. header
MODE="merge"; [ "$MIRROR" = 1 ] && MODE="mirror"
[ "$UPDATE" = 1 ] && MODE="$MODE, update-only"
[ "$DRY" = 1 ]    && MODE="$MODE, ${YEL}${B}DRY RUN${R}"
echo
printf '  %sFrom:%s %s/\n' "$D" "$R" "$SRC"
printf '  %sTo:%s   %s/\n' "$D" "$R" "$DEST"
printf '  %sMode:%s %s\n'  "$D" "$R" "$MODE"
printf '  %sFiles:%s %s tracked\n' "$D" "$R" "$N_TRACKED"
echo

if [ "$DEST_EXISTS" = 0 ]; then
  if [ "$DRY" = 1 ]; then step "Would create $DEST"
  else mkdir -p "$DEST" 2>/dev/null || die "could not create destination folder '$DEST'"; step "Created $DEST"; fi
fi

N_ADD_F=0 N_ADD_D=0 N_CHG=0 N_MODE=0 N_DEL_F=0 N_DEL_D=0 N_SKIP=0 N_CONF=0 N_SAME=0
CREATED=$'\n'
add()  { printf '  %s+%s %s%s\n'  "$GRN" "$R" "$1" "${2:-}"; }
chg()  { printf '  %s~%s %s%s\n'  "$YEL" "$R" "$1" "${2:-}"; }
del()  { printf '  %s-%s %s%s\n'  "$RED" "$R" "$1" "${2:-}"; }
skp()  { printf '  %s= %s%s\n'   "$D" "$1" "${2:-}$R"; }
conf() { printf '  %s%s!%s %s %s(%s)%s\n' "$B" "$MAG" "$R" "$1" "$MAG" "$2" "$R"; N_CONF=$((N_CONF + 1)); }
gone() { [ "$DRY" = 1 ] && [ "$MIRROR" = 1 ] && grep -Fxq -- "$1" "$TMP/gone"; }

# ------------------------------------------------------- 5. mirror: deletions
if [ -s "$TMP/del_files" ] || [ -s "$TMP/del_dirs" ]; then
  while IFS= read -r p; do
    del "$p"; N_DEL_F=$((N_DEL_F + 1))
    [ "$DRY" = 1 ] || rm -f "$DEST/$p"
  done < "$TMP/del_files"
  while IFS= read -r p; do
    if [ "$DRY" = 1 ] || rmdir "$DEST/$p" 2>/dev/null; then
      del "$p/"; N_DEL_D=$((N_DEL_D + 1))
    else
      warn "could not remove folder '$p' (not empty?)"
    fi
  done < "$TMP/del_dirs"
fi

# Make sure all parent folders of $1 exist in DEST. Returns 1 on a conflict.
ensure_parent() {
  local dir="${1%/*}" cur="" part path
  [ "$dir" = "$1" ] && return 0                  # top-level file
  [ -d "$DEST/$dir" ] && [ ! -L "$DEST/$dir" ] && return 0
  local IFS=/
  for part in $dir; do
    cur="${cur:+$cur/}$part"; path="$DEST/$cur"
    if [ -d "$path" ] && [ ! -L "$path" ]; then continue; fi
    if { [ -e "$path" ] || [ -L "$path" ]; } && ! gone "$cur"; then
      conf "$1" "'$cur' is a file in the destination"; return 1
    fi
    case "$CREATED" in *$'\n'"$cur"$'\n'*) continue ;; esac
    CREATED="$CREATED$cur"$'\n'
    [ "$DRY" = 1 ] || mkdir "$path" || { conf "$1" "cannot create folder '$cur'"; return 1; }
    add "$cur/"; N_ADD_D=$((N_ADD_D + 1))
  done
  return 0
}

# state of a path in DEST: none | link | dir | file
dstate() {
  local p="$DEST/$1"
  if [ -L "$p" ]; then echo link
  elif [ -d "$p" ]; then if gone "$1"; then echo none; else echo dir; fi
  elif [ -e "$p" ]; then echo file
  else echo none; fi
}

# ---------------------------------------------------------------- 6. copying
while IFS= read -r p; do
  s="$SRC/$p" t="$DEST/$p"
  if [ -d "$s" ] && [ ! -L "$s" ]; then
    warn "skipping '$p': submodule is not checked out"; continue
  fi
  ensure_parent "$p" || continue
  st="$(dstate "$p")"

  if [ -L "$s" ]; then                                        # --- symlink
    target="$(readlink "$s")"
    case "$st" in
      none) [ "$DRY" = 1 ] || ln -s "$target" "$t"; add "$p" " -> $target"; N_ADD_F=$((N_ADD_F + 1)) ;;
      link) if [ "$(readlink "$t")" = "$target" ]; then N_SAME=$((N_SAME + 1))
            else [ "$DRY" = 1 ] || { rm -f "$t"; ln -s "$target" "$t"; }; chg "$p" " -> $target"; N_CHG=$((N_CHG + 1)); fi ;;
      file) [ "$DRY" = 1 ] || { rm -f "$t"; ln -s "$target" "$t"; }; chg "$p" " -> $target"; N_CHG=$((N_CHG + 1)) ;;
      dir)  conf "$p" "a folder in the destination" ;;
    esac
    continue
  fi
                                                              # --- regular file
  case "$st" in
    none) [ "$DRY" = 1 ] || cp -p "$s" "$t"; add "$p"; N_ADD_F=$((N_ADD_F + 1)) ;;
    link) [ "$DRY" = 1 ] || { rm -f "$t"; cp -p "$s" "$t"; }; chg "$p" " (was a symlink)"; N_CHG=$((N_CHG + 1)) ;;
    dir)  conf "$p" "a folder in the destination" ;;
    file)
      if cmp -s "$s" "$t"; then
        sx=0 tx=0; [ -x "$s" ] && sx=1; [ -x "$t" ] && tx=1
        if [ "$sx" != "$tx" ]; then
          if [ "$DRY" != 1 ]; then if [ "$sx" = 1 ]; then chmod +x "$t"; else chmod a-x "$t"; fi; fi
          chg "$p" " ${D}(permissions)${R}"; N_MODE=$((N_MODE + 1))
        else
          N_SAME=$((N_SAME + 1))
        fi
      elif [ "$UPDATE" = 1 ] && [ "$t" -nt "$s" ]; then
        skp "$p" " (newer in destination, kept)"; N_SKIP=$((N_SKIP + 1))
      else
        [ "$DRY" = 1 ] || { rm -f "$t"; cp -p "$s" "$t"; }; chg "$p"; N_CHG=$((N_CHG + 1))
      fi ;;
  esac
done < "$TMP/tracked"

# ---------------------------------------------------------------- 7. summary
echo
if [ "$DRY" = 1 ]; then printf '%sSummary%s %s(dry run — nothing was changed)%s\n' "$B" "$R" "$YEL" "$R"
else printf '%sSummary%s\n' "$B" "$R"; fi
line() { printf '  %s%-10s%s %s\n' "$1" "$2" "$R" "$3"; }
line "$GRN" "added"     "$N_ADD_F file(s), $N_ADD_D folder(s)"
line "$YEL" "changed"   "$N_CHG file(s)"
[ "$N_MODE" -gt 0 ] && line "$YEL" "perms"   "$N_MODE file(s) (executable bit only)"
line "$RED" "deleted"   "$N_DEL_F file(s), $N_DEL_D folder(s)"
[ "$N_SKIP" -gt 0 ] && line "$D"   "skipped" "$N_SKIP file(s) newer in destination"
line "$D"   "unchanged" "$N_SAME file(s)"
if [ "$N_CONF" -gt 0 ]; then
  line "$MAG$B" "conflicts" "$N_CONF — see '!' lines above; those paths were left as is"
  exit 1
fi
if [ $((N_ADD_F + N_ADD_D + N_CHG + N_MODE + N_DEL_F + N_DEL_D)) -eq 0 ]; then
  printf '\n  %s✔ Destination is already up to date.%s\n' "$GRN" "$R"
else
  if [ "$DRY" = 1 ]; then printf '\n  %sRe-run without -n to apply.%s\n' "$YEL" "$R"
  else printf '\n  %s✔ Done.%s\n' "$GRN" "$R"; fi
fi
