#!/usr/bin/env bash
# Static structure checks that need no network. Run from anywhere.
set -u
cd "$(dirname "$0")/.."
fail=0

echo "== 1/4: illegal '--' inside XML comments"
if python3 - <<'PY'
import re,glob,sys
bad=0
for f in glob.glob("**/pom.xml",recursive=True):
    for c in re.findall(r"<!--(.*?)-->",open(f,encoding="utf-8").read(),re.S):
        if "--" in c:
            print("  bad comment in",f); bad=1
sys.exit(bad)
PY
then echo "  ok"; else fail=1; fi

echo "== 2/4: suspicious bare imports"
if grep -rnE '^import [A-Z][A-Za-z0-9_]*;' --include=*.java . ; then fail=1; else echo "  ok"; fi

echo "== 3/4: every <module> has a pom.xml"
for m in $(grep -oE '<module>[^<]+</module>' pom.xml | sed -E 's#</?module>##g'); do
  [ -f "$m/pom.xml" ] || { echo "  missing: $m/pom.xml"; fail=1; }
done
[ $fail -eq 0 ] && echo "  ok"

echo "== 4/4: offline reactor validation (Spring Boot BOM import stripped)"
if command -v mvn >/dev/null; then
  tmp=$(mktemp -d); cp -r . "$tmp/r"
  python3 - "$tmp/r/pom.xml" <<'PY'
import re,sys
p=open(sys.argv[1]).read()
p=re.sub(r"<dependency>\s*<groupId>org.springframework.boot</groupId>\s*<artifactId>spring-boot-dependencies</artifactId>.*?</dependency>","",p,flags=re.S)
open(sys.argv[1],"w").write(p)
PY
  out=$(cd "$tmp/r" && mvn -o -q validate 2>&1)
  unexpected=$(echo "$out" | grep -E '\[ERROR\]' | grep -vE "dependencies.dependency.version.*is missing|BUILD FAILURE|Some problems were encountered|For more information|Re-run Maven|could not read [0-9]+ projects|ProjectBuildingException|\[ERROR\] *$|To see the full stack|After correcting|The project .* has")
  if [ -n "$unexpected" ]; then echo "$unexpected"; fail=1; else echo "  ok"; fi
  rm -rf "$tmp"
else echo "  mvn not found, skipped"; fi

[ $fail -eq 0 ] && echo "ALL CHECKS PASSED" || echo "CHECKS FAILED"
exit $fail
