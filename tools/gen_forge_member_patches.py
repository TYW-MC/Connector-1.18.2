#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 forge_member_patches.json —— "Forge binpatch 改名成员" 对照表。

背景
----
Forge 用 binpatch 修改过的类里，被改动过签名/可见性的成员会**丢掉 SRG 名、改用 Mojang 名**
（因为 SRG 名只对"未被改动的成员"成立）。例如 1.18.2：

    原版 : private static void  m_174570_(Item, ResourceLocation, ClampedItemPropertyFunction)
    Forge: public  static void  register  (Item, ResourceLocation, ItemPropertyFunction)

Fabric 模组是按 Fabric(intermediary) 编译的，remap 到 SRG 后会去调
`ItemProperties.m_174570_(...)` —— Forge 运行时根本没有这个成员，于是
`NoSuchMethodError`。Connector 必须把这批调用改写回 Forge 实际使用的名字/描述符。

算法
----
逐个比对「原版 SRG jar」与「Forge 补丁 jar」中同名类的方法/字段：
  - 原版有 (name, desc)，Forge 没有          → 该成员被 Forge 改过
  - 用 mappings.tsrg 查出该 SRG 成员的可读名(named)，若 Forge 类里正好有同名成员
    → 判定为改名关系，记录 name/desc 的目标值
描述符直接取 Forge 侧的真实值，不做猜测。

输入
----
  --vanilla   client-1.18.2-...-srg.jar        （全部原版类）
  --forge     forge-1.18.2-40.3.12-client.jar  （Forge 补丁后的类）
  --mappings  SinytraLoader/tools/mappings.tsrg（含 srg 列 + named 列）
  --out       forge_member_patches.json
"""

import argparse
import json
import os
import re
import struct
import sys
import zipfile

SRG_MEMBER_RE = re.compile(r"^(m|f)_\d+_$")


def desc_arity(desc):
    """方法描述符的参数个数。"""
    body = desc[1:desc.rindex(")")]
    n = 0
    i = 0
    while i < len(body):
        if body[i] == "[":
            i += 1
            continue
        if body[i] == "L":
            i = body.index(";", i) + 1
        else:
            i += 1
        n += 1
    return n


def param_simple_names(desc):
    """参数类型的"简单名"列表，例如 [Item, D, D, ResourceLocation]。"""
    body = desc[1:desc.rindex(")")]
    out = []
    i = 0
    while i < len(body):
        dims = ""
        while body[i] == "[":
            dims += "["
            i += 1
        if body[i] == "L":
            j = body.index(";", i)
            full = body[i + 1:j]
            out.append(dims + full.rsplit("/", 1)[-1])
            i = j + 1
        else:
            out.append(dims + body[i])
            i += 1
    return out


def prefix_score(desc_old, desc_new):
    """两个描述符按位置从头比较，返回连续相同的参数个数。"""
    a = param_simple_names(desc_old)
    b = param_simple_names(desc_new)
    n = 0
    for x, y in zip(a, b):
        if x != y:
            break
        n += 1
    return n


def pick_candidate(cands, desc):
    """
    在同名候选中挑最匹配的 Forge 成员。

    规则（Forge 补丁常见形态：末尾加参数 / 中间改正一个类型）：
      - 参数个数不能减少
      - 参数类型从头连续相同的个数 >= min(2, 原参数个数)
    找不到满足条件的候选则返回 None（宁缺勿滥，避免误改坏字节码）。
    """
    arity = desc_arity(desc)
    need = min(2, arity) if arity > 0 else 0
    best, best_score = None, -1
    for c in cands:
        if desc_arity(c) < arity:
            continue
        score = prefix_score(desc, c)
        if score < need:
            continue
        if score > best_score:
            best, best_score = c, score
    return best


# --------------------------------------------------------------------------- #
# 极简 class 文件解析：只看类名 + 字段/方法名与描述符
# --------------------------------------------------------------------------- #
def _skip_attrs(data, off):
    count = struct.unpack_from(">H", data, off)[0]
    off += 2
    for _ in range(count):
        length = struct.unpack_from(">I", data, off + 2)[0]
        off += 6 + length
    return off


def parse_class(data):
    """返回 (internal_name, field_list, method_list)；元素为 (name, desc)。"""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None
    cp_count = struct.unpack_from(">H", data, 8)[0]
    cp = [None] * cp_count
    off = 10
    i = 1
    while i < cp_count:
        tag = data[off]
        off += 1
        if tag == 1:  # Utf8
            length = struct.unpack_from(">H", data, off)[0]
            off += 2
            cp[i] = data[off:off + length].decode("utf-8", "replace")
            off += length
        elif tag in (7, 8, 16, 19, 20):
            # 这些条目引用的是 Utf8 下标，先记下下标，用到时再解析
            cp[i] = ("ref", struct.unpack_from(">H", data, off)[0])
            off += 2
        elif tag in (15,):
            off += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            off += 4
        elif tag in (5, 6):
            off += 8
            i += 1
        else:
            raise ValueError(f"unsupported constant pool tag {tag}")
        i += 1

    def utf(idx):
        v = cp[idx]
        while isinstance(v, tuple):
            v = cp[v[1]]
        return v

    off += 2  # access_flags
    this_idx = struct.unpack_from(">H", data, off)[0]
    off += 2
    off += 2  # super
    this_name = utf(this_idx)

    iface_count = struct.unpack_from(">H", data, off)[0]
    off += 2 + 2 * iface_count

    fields = []
    fcount = struct.unpack_from(">H", data, off)[0]
    off += 2
    for _ in range(fcount):
        off += 2  # access
        n_idx = struct.unpack_from(">H", data, off)[0]
        d_idx = struct.unpack_from(">H", data, off + 2)[0]
        off += 4
        fields.append((cp[n_idx], cp[d_idx]))
        off = _skip_attrs(data, off)

    methods = []
    mcount = struct.unpack_from(">H", data, off)[0]
    off += 2
    for _ in range(mcount):
        off += 2  # access
        n_idx = struct.unpack_from(">H", data, off)[0]
        d_idx = struct.unpack_from(">H", data, off + 2)[0]
        off += 4
        methods.append((cp[n_idx], cp[d_idx]))
        off = _skip_attrs(data, off)

    return this_name, fields, methods


def read_jar_classes(path, only_prefix=("net/minecraft/",)):
    out = {}
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if not info.filename.endswith(".class"):
                continue
            if only_prefix and not info.filename.startswith(only_prefix):
                continue
            try:
                parsed = parse_class(z.read(info))
            except Exception as e:  # noqa: BLE001
                print(f"  ! 解析失败 {info.filename}: {e}", file=sys.stderr)
                continue
            if parsed:
                out[parsed[0]] = (parsed[1], parsed[2])
    return out


# --------------------------------------------------------------------------- #
# mappings.tsrg: srg 成员 -> named(可读) 名
# --------------------------------------------------------------------------- #
def read_mappings(path):
    """返回 {(srg_class, srg_member): readable_name}"""
    table = {}
    cur = None
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\r\n")
            if not line or line.startswith("tsrg2"):
                continue
            if line.startswith("\t"):
                if line.startswith("\t\t"):
                    continue
                parts = line[1:].split()
                if not cur or not parts:
                    continue
                if len(parts) == 5:                 # name desc srg inter named
                    table[(cur, parts[2])] = parts[4]
                elif len(parts) == 3:               # name srg named(?) -- 兜底
                    table[(cur, parts[1])] = parts[2]
            else:
                cur = line.split()[1]               # ns1 = srg 类名
    return table


# --------------------------------------------------------------------------- #
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vanilla", required=True)
    ap.add_argument("--forge", required=True)
    ap.add_argument("--mappings", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    print("[1] 读取 mappings.tsrg ...")
    readable = read_mappings(args.mappings)
    print(f"    srg 成员条目: {len(readable)}")

    print("[2] 读取 Forge 补丁 jar ...")
    forge = read_jar_classes(args.forge)
    print(f"    类: {len(forge)}")

    print("[3] 读取原版 SRG jar ...")
    vanilla = read_jar_classes(args.vanilla)
    print(f"    类: {len(vanilla)}")

    methods, fields = [], []
    for cls, (f_fields, f_methods) in forge.items():
        van = vanilla.get(cls)
        if not van:
            continue
        v_fields, v_methods = van

        f_field_names = {n for n, _ in f_fields}
        f_method_names = {n for n, _ in f_methods}
        f_method_index = {}
        for n, d in f_methods:
            f_method_index.setdefault(n, []).append(d)
        f_field_index = {}
        for n, d in f_fields:
            f_field_index.setdefault(n, []).append(d)

        for name, desc in v_methods:
            if (name, desc) in f_methods:
                continue
            if not SRG_MEMBER_RE.match(name):
                continue
            target = readable.get((cls, name))
            if not target or target == name or SRG_MEMBER_RE.match(target):
                continue
            cands = f_method_index.get(target)
            if not cands:
                continue
            best = pick_candidate(cands, desc)
            if best is None:
                continue
            methods.append({
                "owner": cls,
                "name": name,
                "desc": desc,
                "newName": target,
                "newDesc": best,
            })

        for name, desc in v_fields:
            if (name, desc) in f_fields:
                continue
            if not SRG_MEMBER_RE.match(name):
                continue
            target = readable.get((cls, name))
            if not target or target == name or SRG_MEMBER_RE.match(target):
                continue
            cands = f_field_index.get(target)
            if not cands:
                continue
            fields.append({
                "owner": cls,
                "name": name,
                "desc": desc,
                "newName": target,
                "newDesc": cands[0],
            })

    data = {"methods": methods, "fields": fields}
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1, ensure_ascii=False)

    print(f"[4] 输出 {args.out}")
    print(f"    方法补丁: {len(methods)}")
    print(f"    字段补丁: {len(fields)}")
    for m in methods[:8]:
        print(f"      {m['owner']}.{m['name']}{m['desc']}")
        print(f"        -> {m['newName']}{m['newDesc']}")


if __name__ == "__main__":
    main()
