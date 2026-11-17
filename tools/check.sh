#!/bin/sh
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
fail=0
report() {
	echo "check: $1" >&2
	fail=1
}

rust_src="$root/native/crates"
unsafe_allowed="native/crates/mtl4/src/lib.rs native/crates/abi/src/lib.rs"
clone_budget=$(cat "$root/tools/clone-budget")

cd "$root"
for f in $(grep -rlE '\bunsafe\b|allow\(unsafe_code\)' "$rust_src" --include='*.rs'); do
	rel=${f#"$root/"}
	case " $unsafe_allowed " in
		*" $rel "*) ;;
		*) report "unsafe outside the FFI/Metal wrapper modules: $rel" ;;
	esac
done

if grep -rnE '#!?\[(allow|expect)\((clippy::|unused|dead_code|warnings)' "$rust_src" --include='*.rs'; then
	report "lint suppression attributes are not allowed"
fi

clones=$(grep -rhoE '\.clone\(\)' "$rust_src" --include='*.rs' | wc -l | tr -d ' ')
if [ "$clones" -gt "$clone_budget" ]; then
	report ".clone() count $clones exceeds budget $clone_budget (tools/clone-budget)"
fi

if grep -rnE '//|/\*|TODO|FIXME' "$rust_src" --include='*.rs' "$root/mod/src/main/java/dev/mikuteto/mtlrender" --include='*.java' | grep -v '/ffi/' | grep -vE '"[^"]*//[^"]*"'; then
	report "comments/TODO are not allowed in source (report constraints in the final summary instead)"
fi

mixin_dir="$root/mod/src/main/java/dev/mikuteto/mtlrender/mixin"
mixin_configs=$(find "$root/mod/src/main/resources" -maxdepth 1 -name '*mixins.json' -print)
for f in "$mixin_dir"/*.java; do
	name=$(basename "$f" .java)
	if ! grep -q '^@Mixin' "$f"; then
		report "non-mixin type in the mixin package (cannot be referenced at runtime): $name"
	elif grep -qE '^(public |abstract |final )*class ' "$f" && grep -rqE "\(\(?$name\)" "$root/mod/src/main/java"; then
		report "mixin class $name is cast to as a type; use an @Mixin interface (accessor) instead"
	fi
	grep -q "\"$name\"" $mixin_configs || report "mixin not registered in a mixin config: $name"
done

(cd native && cargo fmt --all --check) || report "cargo fmt"
(cd native && cargo clippy --all-targets --quiet -- -D warnings) || report "cargo clippy"

if [ "${CHECK_JAVA:-1}" = 1 ]; then
	(cd mod && ./gradlew --quiet compileJava) || report "java compile"
fi

[ "$fail" = 0 ] && echo "check: ok"
exit "$fail"
