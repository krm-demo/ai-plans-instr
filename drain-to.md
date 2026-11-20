# drain-to: copy this checkout into another folder

> Back to the [main README](README.md).

Two interchangeable scripts that copy the **git-tracked files** of this
checkout into another folder, for example a copy of the repo on a work
laptop. **`.git` is skipped on both sides:** the source `.git` is never
copied, and the destination's own `.git` (with its own history) is never
touched.

| Script | Needs | Notes |
|---|---|---|
| `drain-to.sh` | bash (macOS's bash 3.2 is fine), git | Nothing to install on a Mac. No `rsync`. |
| `drain-to-java` | JDK 17+, git | A Java source file with a shebang. No build, no Maven, no dependencies. |

They take the same options and print the same output. Use whichever is
convenient.

## What a run does

1. **Checks that this checkout is clean.** It must have no uncommitted
   changes and no untracked files. Files ignored by `.gitignore` are fine.
   If the checkout is dirty, the script stops.
2. **Runs `git pull`.** If the pull fails (merge conflict, diverged
   branches, no network), the script aborts the merge or rebase so the
   checkout is back to how it was. Nothing is copied, and the exit code is 1.
3. **Copies every file tracked by git** into the destination, including
   files in submodules. Build output and other ignored files are **not**
   copied. Symlinks are copied as symlinks, and the executable bit is
   preserved.
4. **Prints every change and a summary:**

   ```
     + docs/new.md                 added
     ~ README.md                   changed
     - old/notes.txt               deleted (only with --mirror)
     ! x/y.md (reason)             conflict, left as is
     = a.md (newer in destination) skipped (only with --update)

   Summary
     added      3 file(s), 1 folder(s)
     changed    1 file(s)
     deleted    0 file(s), 0 folder(s)
     unchanged  42 file(s)
   ```

The source is always the root of the checkout that contains the script, so
you can call it from any subfolder.

## Usage

```bash
./drain-to.sh   DEST [options]
./drain-to-java DEST [options]
```

Running a script with no arguments, or with `-h` / `--help`, prints the help.

| Option | Meaning |
|---|---|
| `-n`, `--dry-run` | Show what would change and change nothing. Also skips `git pull` and doesn't create `DEST`. |
| `-m`, `--mirror` | Make `DEST` an exact copy: also **delete** files and folders in `DEST` that are not tracked here. `DEST/.git`, and any folder that contains a `.git`, are kept. |
| `-u`, `--update` | Don't overwrite files that are newer in `DEST`. |
| `--no-pull` | Skip `git pull`. |
| `--no-color` | Plain output. Colors are also turned off by `NO_COLOR=1` or when the output is not a terminal. |
| `-h`, `--help` | Show the help. |

**Default mode is merge:** new files are added, changed files are updated,
and nothing is deleted.

### Typical use

```bash
cd ~/src/plan-instrs
./drain-to.sh ~/work/plan-instrs -n      # 1. preview: always a good first step
./drain-to.sh ~/work/plan-instrs         # 2. merge
./drain-to.sh ~/work/plan-instrs -m      # or mirror: also removes files deleted here

cd ~/work/plan-instrs && git status      # 3. review and commit in the copy
```

### Exit codes

| Code | Meaning |
|---|---|
| `0` | Success. |
| `1` | Error: dirty checkout, failed pull, bad destination, or conflicts. |
| `2` | Bad command-line arguments. |

## Safety checks

- **`DEST` must not be this checkout, a folder inside it, or a folder that
  contains it.** Copying into itself would dirty the checkout, and
  mirroring into a parent would delete the checkout. Paths are compared
  after symlinks, `..` and relative paths are resolved.
- **`DEST` is created if it doesn't exist.** If it can't be created (no
  permission, or part of the path is a file), or if it isn't writable, the
  script prints a clear error. All of these checks run **before** `git pull`.
- **Conflicts are skipped, not forced.** A conflict is a file where the
  copy needs a folder, or a folder where it needs a file. These paths are
  reported with `!`, left as they are, and the exit code is 1. In
  `--mirror` mode most conflicts resolve themselves, because the blocking
  file or folder is untracked and gets deleted first.

## Running the Java version

`drain-to-java` starts with the line `#!/usr/bin/env -S java --source 17`.
Because of that line, it runs like any script: the JDK compiles it in memory
on each run. That takes about a second.

```bash
./drain-to-java ~/work/plan-instrs -n
java --source 17 drain-to-java ~/work/plan-instrs -n   # same thing, explicitly
```

- **Don't rename it to `*.java`.** With a `.java` extension, `java` rejects
  the `#!` line.
- **`java` must be on `PATH` and be JDK 17 or newer.** Check with
  `java -version`. On a Mac with several JDKs:
  `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- **To edit it with highlighting in IntelliJ:** right-click the file →
  *Override File Type* → *Java*.

## Setup notes

- **Keep the executable bits in git.** This matters when the repo is
  cloned on Windows, or on a file system that drops them:

  ```bash
  git update-index --chmod=+x drain-to.sh drain-to-java
  ```

- **If you get `permission denied`,** run `chmod +x drain-to.sh drain-to-java`.
- **Limitation:** file names that contain a newline character are not
  supported. The scripts detect such names and refuse to run.
