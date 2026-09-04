#!/usr/bin/env python3
"""
Phase 7 (v3.10.13-24) jackson-databind PolymorphicTypeValidator (PTV) bypass
reachability audit -- CVE-2026-54512 / CVE-2026-54513.

Both CVEs are bypasses of jackson-databind's PolymorphicTypeValidator, the
allowlist mechanism that gates which concrete classes Jackson may instantiate
when polymorphic typing is enabled with `@JsonTypeInfo(use = Id.CLASS)` (or
`Id.MINIMAL_CLASS`) and an explicit `BasicPolymorphicTypeValidator`. The two
vulnerable code paths are:

  CVE-2026-54512:
    DatabindContext._resolveAndValidateGeneric() validates only the raw
    container class name for a generic type ID (the substring before '<'),
    never the nested type argument(s).  Reachable only when polymorphic
    typing is enabled AND the attacker controls a type-ID string containing
    '<' (i.e. Id.CLASS/Id.MINIMAL_CLASS + a PTV are configured).

  CVE-2026-54513:
    BasicPolymorphicTypeValidator.Builder.allowIfSubTypeIsArray() allowlists
    any array type based only on clazz.isArray(), never the array's element
    type.  Reachable under the same precondition: Id.CLASS/Id.MINIMAL_CLASS
    polymorphic typing with a PTV configured.

Neither vulnerable code path is reachable unless the application:
  (a) enables Jackson's "default typing" (ObjectMapper.enableDefaultTyping()
      / activateDefaultTyping()), which internally uses Id.CLASS +  a PTV, OR
  (b) explicitly annotates a type with `@JsonTypeInfo(use = Id.CLASS, ...)`
      or `Id.MINIMAL_CLASS` AND configures a PolymorphicTypeValidator /
      BasicPolymorphicTypeValidator for that mapper.

This script checks airvision's and Mongojack's decompiled sources for both
preconditions. If NEITHER is present anywhere in the shipped code, the two
CVEs have no reachable trigger: there is no code path that ever constructs a
type ID containing '<' for PTV-gated resolution, nor any PTV-gated array
type resolution, because Id.CLASS/Id.MINIMAL_CLASS + PTV is never used at
all in this application's configuration.

What this script checks, per decompiled source tree:
  1. `enableDefaultTyping` / `activateDefaultTyping` calls (would silently
     turn on Id.CLASS-style polymorphic typing globally on an ObjectMapper).
  2. `PolymorphicTypeValidator` / `BasicPolymorphicTypeValidator` references
     (the only way to configure a PTV instance in the first place).
  3. `@JsonTypeInfo(use = Id.CLASS, ...)` or `Id.MINIMAL_CLASS` annotations
     (the only per-type way to opt into PTV-gated polymorphic resolution;
     `Id.NAME` -- what airvision actually uses -- resolves via an explicit
     `@JsonSubTypes` allowlist of logical names, and never calls
     DatabindContext._resolveAndValidateGeneric() or
     BasicPolymorphicTypeValidator.Builder.allowIfSubTypeIsArray() at all).

Usage:  python3 audit-jackson-ptv-phase7.py [DECOMPILE_DIR]
Default DECOMPILE_DIR: /root/uv-harden/decompile
"""

import re
import sys
from pathlib import Path

DEFAULT_TYPING_RE = re.compile(r"enableDefaultTyping|activateDefaultTyping")
PTV_RE = re.compile(r"PolymorphicTypeValidator")
UNSAFE_TYPEINFO_RE = re.compile(r"@JsonTypeInfo\s*\([^)]*Id\.(CLASS|MINIMAL_CLASS)")
SAFE_TYPEINFO_RE = re.compile(r"@JsonTypeInfo\s*\([^)]*Id\.NAME")


def scan_tree(root: Path):
    hits = {"default_typing": [], "ptv_config": [], "unsafe_typeinfo": []}
    safe_typeinfo = []
    for path in root.rglob("*.java"):
        try:
            text = path.read_text(errors="replace")
        except OSError:
            continue
        if DEFAULT_TYPING_RE.search(text):
            hits["default_typing"].append(str(path))
        if PTV_RE.search(text):
            hits["ptv_config"].append(str(path))
        if UNSAFE_TYPEINFO_RE.search(text):
            hits["unsafe_typeinfo"].append(str(path))
        for m in SAFE_TYPEINFO_RE.finditer(text):
            safe_typeinfo.append(str(path))
    return hits, safe_typeinfo


def main():
    decompile_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "/root/uv-harden/decompile")
    if not decompile_dir.is_dir():
        print(f"ERROR: {decompile_dir} is not a directory", file=sys.stderr)
        sys.exit(2)

    trees = sorted(p for p in decompile_dir.iterdir() if p.is_dir())
    overall_clean = True

    for tree in trees:
        print(f"=== {tree.name} ===")
        hits, safe_typeinfo = scan_tree(tree)

        for label, key in (
            ("enableDefaultTyping / activateDefaultTyping calls", "default_typing"),
            ("PolymorphicTypeValidator references", "ptv_config"),
            ("@JsonTypeInfo(Id.CLASS / Id.MINIMAL_CLASS) usages", "unsafe_typeinfo"),
        ):
            files = hits[key]
            if files:
                overall_clean = False
                print(f"  [HIT] {label}: {len(files)} file(s)")
                for f in files:
                    print(f"      {f}")
            else:
                print(f"  [clean] {label}: 0 files")

        if safe_typeinfo:
            print(f"  [info] @JsonTypeInfo(Id.NAME) (safe pattern) usages: {len(safe_typeinfo)} file(s)")
            for f in safe_typeinfo:
                print(f"      {f}")
        print()

    print("=" * 70)
    if overall_clean:
        print("RESULT: ZERO HITS. No enableDefaultTyping/activateDefaultTyping")
        print("call, no PolymorphicTypeValidator configuration, and no")
        print("@JsonTypeInfo(Id.CLASS/Id.MINIMAL_CLASS) usage found anywhere")
        print("in the scanned trees. CVE-2026-54512 and CVE-2026-54513 have")
        print("no reachable trigger in this application's Jackson usage.")
        sys.exit(0)
    else:
        print("RESULT: HITS FOUND. Manual review required before suppressing")
        print("CVE-2026-54512 / CVE-2026-54513 -- see the file list above.")
        sys.exit(1)


if __name__ == "__main__":
    main()
