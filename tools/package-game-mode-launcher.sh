#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
bundle=${1:-$root/launcher/MTLRenderGameMode.app}
plist=$bundle/Contents/Info.plist
executable=$bundle/Contents/MacOS/mtlrender-game-mode
source_executable=$root/launcher/MTLRenderGameMode.app/Contents/MacOS/mtlrender-game-mode

[ -d "$bundle" ] || { echo "bundle does not exist: $bundle" >&2; exit 1; }
[ -f "$plist" ] || { echo "missing Info.plist: $plist" >&2; exit 1; }
[ -f "$source_executable" ] || { echo "missing wrapper: $source_executable" >&2; exit 1; }
/usr/bin/plutil -lint "$plist" >/dev/null
/usr/bin/plutil -extract CFBundlePackageType raw -expect string "$plist" >/dev/null
[ "$(/usr/bin/plutil -extract CFBundlePackageType raw -expect string "$plist")" = APPL ]
[ "$(/usr/bin/plutil -extract CFBundleExecutable raw -expect string "$plist")" = mtlrender-game-mode ]
[ "$(/usr/bin/plutil -extract CFBundleIdentifier raw -expect string "$plist")" = dev.mikuteto.mtlrender.gamemode ]
[ "$(/usr/bin/plutil -extract LSSupportsGameMode raw -expect bool "$plist")" = true ]
mkdir -p "$(dirname -- "$executable")"
temporary=$executable.tmp
trap 'rm -f "$temporary"' EXIT HUP INT TERM
install -m 755 "$source_executable" "$temporary"
mv -f "$temporary" "$executable"
trap - EXIT HUP INT TERM
[ -x "$executable" ]
/usr/bin/plutil -lint "$plist" >/dev/null
printf '%s\n' "$bundle"
