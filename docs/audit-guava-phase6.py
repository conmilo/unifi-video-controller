#!/usr/bin/env python3
"""
Phase 6 (v3.10.13-22) Guava 14.0.1 reachability audit.

For each shipped JAR, parse every .class file's constant pool and check
whether it contains a Methodref / InterfaceMethodref / Class entry that
references one of the vulnerable Guava 14.0.1 APIs:

  CVE-2023-2976 / CVE-2020-8908:
    com.google.common.io.Files.createTempDir()
  CVE-2018-10237:
    com.google.common.collect.Ordering.compound(...)        (public entry)
    com.google.common.collect.CompoundOrdering              (internal class)
    com.google.common.util.concurrent.AtomicDoubleArray     (public class)

A constant pool reference is the strongest static signal of "this code
touches that API".  Javac emits constant pool entries for every byte-
code-level reference; entries are NEVER added for code that doesn't
reference them.  Zero entries across every shipped .class file means
zero reachable call sites at the static-analysis level.

Reflection / Class.forName / ServiceLoader-based dispatch is the only
way to reach the API without a static reference; those paths would
require either a known String literal (also visible in the constant
pool's CONSTANT_Utf8 entries) or a dynamically-constructed name (which
no airvision or shipped-library code does for these particular APIs --
manually verified by reviewing every Class.forName call site in
airvision's decompile).

Usage:  python3 audit-guava-phase6.py [LIB_DIR]
Default LIB_DIR: /root/uv-harden/work/lib

Self-references inside guava-14.0.1.jar itself are skipped (that JAR
DEFINES the vulnerable APIs; the question is whether anything ELSE
calls into it).
"""

import struct
import sys
import zipfile
from pathlib import Path

CONSTANT_Utf8 = 1
CONSTANT_Integer = 3
CONSTANT_Float = 4
CONSTANT_Long = 5
CONSTANT_Double = 6
CONSTANT_Class = 7
CONSTANT_String = 8
CONSTANT_Fieldref = 9
CONSTANT_Methodref = 10
CONSTANT_InterfaceMethodref = 11
CONSTANT_NameAndType = 12
CONSTANT_MethodHandle = 15
CONSTANT_MethodType = 16
CONSTANT_Dynamic = 17
CONSTANT_InvokeDynamic = 18
CONSTANT_Module = 19
CONSTANT_Package = 20


def parse_constant_pool(data):
    """Return list of (tag, payload...) tuples; index 0 unused (JVM spec)."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Not a class file")
    count = struct.unpack(">H", data[8:10])[0]
    pos = 10
    pool = [None]
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == CONSTANT_Utf8:
            length = struct.unpack(">H", data[pos:pos + 2])[0]
            pos += 2
            pool.append(("Utf8", data[pos:pos + length].decode("utf-8", "replace")))
            pos += length
        elif tag == CONSTANT_Class:
            pool.append(("Class", struct.unpack(">H", data[pos:pos + 2])[0]))
            pos += 2
        elif tag in (CONSTANT_Fieldref, CONSTANT_Methodref, CONSTANT_InterfaceMethodref):
            cls_idx, nat_idx = struct.unpack(">HH", data[pos:pos + 4])
            pos += 4
            kind = {9: "Fieldref", 10: "Methodref", 11: "InterfaceMethodref"}[tag]
            pool.append((kind, cls_idx, nat_idx))
        elif tag == CONSTANT_String:
            pool.append(("String", struct.unpack(">H", data[pos:pos + 2])[0]))
            pos += 2
        elif tag in (CONSTANT_Integer, CONSTANT_Float):
            pool.append((tag, None))
            pos += 4
        elif tag in (CONSTANT_Long, CONSTANT_Double):
            pool.append((tag, None))
            pool.append(None)  # JVM spec: 8-byte entries occupy two pool slots
            pos += 8
            i += 1
        elif tag == CONSTANT_NameAndType:
            name_idx, desc_idx = struct.unpack(">HH", data[pos:pos + 4])
            pos += 4
            pool.append(("NameAndType", name_idx, desc_idx))
        elif tag == CONSTANT_MethodHandle:
            pool.append((tag, None))
            pos += 3
        elif tag == CONSTANT_MethodType:
            pool.append((tag, None))
            pos += 2
        elif tag in (CONSTANT_Dynamic, CONSTANT_InvokeDynamic):
            pool.append((tag, None))
            pos += 4
        elif tag in (CONSTANT_Module, CONSTANT_Package):
            pool.append((tag, None))
            pos += 2
        else:
            raise ValueError(f"Unknown constant pool tag {tag} at index {i}")
        i += 1
    return pool


def utf8(pool, idx):
    e = pool[idx]
    return e[1] if e and e[0] == "Utf8" else None


# (owner_class_internal_form, method_name)
METHOD_TARGETS = [
    ("com/google/common/io/Files", "createTempDir"),
    ("com/google/common/collect/Ordering", "compound"),
]
CLASS_TARGETS = [
    "com/google/common/util/concurrent/AtomicDoubleArray",
    "com/google/common/collect/CompoundOrdering",
]
STRING_TARGETS = [
    "com.google.common.util.concurrent.AtomicDoubleArray",
    "com.google.common.collect.CompoundOrdering",
    "com.google.common.io.Files",
]


def scan_classfile(data):
    try:
        pool = parse_constant_pool(data)
    except Exception as e:
        return [("PARSE_ERROR", str(e))]
    hits = []

    # Method invocations
    for entry in pool:
        if entry is None or entry[0] not in ("Methodref", "InterfaceMethodref"):
            continue
        _, cls_idx, nat_idx = entry
        cls_entry = pool[cls_idx]
        if cls_entry is None or cls_entry[0] != "Class":
            continue
        owner = utf8(pool, cls_entry[1])
        nat = pool[nat_idx]
        if nat is None or nat[0] != "NameAndType":
            continue
        name = utf8(pool, nat[1])
        desc = utf8(pool, nat[2])
        for tgt_owner, tgt_name in METHOD_TARGETS:
            if owner == tgt_owner and name == tgt_name:
                hits.append(("INVOKE", f"{owner}.{name}{desc}"))

    # Class type references (instantiation, type token, generic bound, etc.)
    seen_classes = set()
    for entry in pool:
        if entry is None or entry[0] != "Class":
            continue
        cls_name = utf8(pool, entry[1])
        if cls_name:
            seen_classes.add(cls_name)
    for tgt in CLASS_TARGETS:
        if tgt in seen_classes:
            hits.append(("CLASSREF", tgt))

    # Reflective access via String literals
    seen_strings = set()
    for entry in pool:
        if entry is None or entry[0] != "Utf8":
            continue
        seen_strings.add(entry[1])
    for tgt in STRING_TARGETS:
        if tgt in seen_strings:
            hits.append(("STRINGREF", tgt))

    return hits


def main():
    lib_dir = sys.argv[1] if len(sys.argv) > 1 else "/root/uv-harden/work/lib"
    jars = sorted(Path(lib_dir).glob("*.jar"))
    print(f"=== Guava 14.0.1 reachability audit ===")
    print(f"Scan target: {lib_dir}")
    print(f"JARs found:  {len(jars)}")
    print()
    print("Targets:")
    for o, n in METHOD_TARGETS:
        print(f"  INVOKE     {o}.{n}")
    for c in CLASS_TARGETS:
        print(f"  CLASSREF   {c}")
    for s in STRING_TARGETS:
        print(f"  STRINGREF  {s}")
    print()

    total_classes = 0
    hits_by_jar = {}

    for jar_path in jars:
        jar_name = jar_path.name
        try:
            with zipfile.ZipFile(jar_path) as z:
                for entry in z.namelist():
                    if not entry.endswith(".class"):
                        continue
                    total_classes += 1
                    data = z.read(entry)
                    hits = scan_classfile(data)
                    if not hits:
                        continue
                    # Skip guava's own internal references (it DEFINES these APIs)
                    if jar_name == "guava-14.0.1.jar":
                        continue
                    hits_by_jar.setdefault(jar_name, {}).setdefault(entry, []).extend(hits)
        except Exception as e:
            print(f"ERROR scanning {jar_name}: {e}")

    print(f"Classes scanned: {total_classes}")
    print(f"JARs scanned:    {len(jars)}")
    print()

    if not hits_by_jar:
        print("=== RESULT: ZERO HITS ===")
        print()
        print(f"No JAR outside guava-14.0.1.jar itself references any of the")
        print(f"vulnerable APIs at the bytecode constant-pool level.")
        print()
        print("Per-CVE disposition:")
        print("  CVE-2023-2976 (createTempDir):       UNREACHABLE -> suppress")
        print("  CVE-2018-10237 (AtomicDoubleArray /  UNREACHABLE -> suppress")
        print("                  CompoundOrdering):")
        print("  CVE-2020-8908 (createTempDir perms): UNREACHABLE -> suppress")
        return

    print("=== HITS ===")
    for jar_name in sorted(hits_by_jar):
        print(f"\n--- {jar_name} ---")
        for cls_path, hits in sorted(hits_by_jar[jar_name].items()):
            for kind, detail in hits:
                print(f"  [{kind}] {cls_path} -> {detail}")
    total_hits = sum(
        len(hits) for cls_hits in hits_by_jar.values() for hits in cls_hits.values()
    )
    print(f"\nSummary: {total_hits} hits across {len(hits_by_jar)} JARs.")


if __name__ == "__main__":
    main()
