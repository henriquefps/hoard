#!/usr/bin/env python3
"""Convert the hfps theme (OKLCH tokens) into a Compose tokens object for one palette, and check WCAG contrast.

    python3 convert_theme.py --palette blue --package dev.hfps.app.designsystem.theme --object HfpsTokens \\
        --out core/designsystem/src/main/kotlin/dev/hfps/app/designsystem/theme/HfpsTokens.kt
    python3 convert_theme.py ... --check          # CI: fail if the file is stale or a WCAG pair fails
    python3 convert_theme.py --wcag-only          # print the contrast of every palette, write nothing

Palettes: blue (default) is the `:root` (light) and `.dark` blocks of theme.css. olive, violet, rose and graphite
are the `:root[data-palette]` block of palettes.css (next to theme.css unless --palettes is given) evaluated with
that palette's --nh/--nc/--bh/--bc (and its own overrides), exactly what the web apps render.

Emits sRGB `Color`s for every token, plus Display P3 variants (`<name>P3`) for `primary`, `ring` and any token whose
OKLCH colour is outside sRGB. The WCAG pairs are checked for EVERY palette on every run (a theme change must not
break a palette some app uses); the exit code is non-zero if any pair fails. Python 3 standard library only.
"""
import argparse
import ast
import math
import operator
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_CSS = HERE.parent.parent / "brand" / "theme.css"
PALETTES = ["blue", "olive", "violet", "rose", "graphite"]

TOKENS = [
    "background", "foreground", "card", "card-foreground", "popover", "popover-foreground", "primary",
    "primary-foreground", "secondary", "secondary-foreground", "muted", "muted-foreground", "accent",
    "accent-foreground", "destructive", "warning", "border", "input", "ring",
]
P3_ALWAYS = {"primary", "ring"}

# (foreground token, background token, minimum ratio): the hfps "Contrast" pairs (WCAG AA 4.5:1 for text,
# 3:1 for the warning status colour, which is used for icons and large labels).
PAIRS = [
    ("foreground", "background", 4.5), ("card-foreground", "card", 4.5),
    ("muted-foreground", "background", 4.5), ("muted-foreground", "card", 4.5), ("muted-foreground", "muted", 4.5),
    ("destructive", "background", 4.5), ("destructive", "card", 4.5), ("destructive", "muted", 4.5),
    ("primary", "background", 4.5), ("primary", "card", 4.5), ("primary-foreground", "primary", 4.5),
    ("warning", "background", 3.0), ("warning", "card", 3.0),
]

# Resource colours written by --res (the window/splash background before Compose draws, and the status/accent).
RES_COLORS = ["background", "foreground", "primary"]


# ---------------------------------------------------------------- CSS reading

def block(css, selector, label):
    m = re.search(re.escape(selector) + r"\s*\{(.*?)\n\}", css, re.S)
    if not m:
        sys.exit(f"{label}: block {selector.strip()} not found")
    return m.group(1)


def declarations(body):
    """`--name: value;` pairs of a block body (values may span lines; comments dropped)."""
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    return {name: " ".join(val.split()) for name, val in re.findall(r"--([a-z0-9-]+)\s*:\s*([^;]+);", body)}


def split_args(s):
    """Top-level comma split (parentheses balanced)."""
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur.strip())
            cur = ""
        else:
            cur += ch
    out.append(cur.strip())
    return out


def closing(s, start):
    depth = 0
    for i in range(start, len(s)):
        if s[i] == "(":
            depth += 1
        elif s[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    sys.exit(f"unbalanced parentheses in: {s}")


_OPS = {ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul, ast.Div: operator.truediv}


def arith(expr):
    def ev(node):
        if isinstance(node, ast.Expression):
            return ev(node.body)
        if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
            return node.value
        if isinstance(node, ast.BinOp) and type(node.op) in _OPS:
            return _OPS[type(node.op)](ev(node.left), ev(node.right))
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
            return -ev(node.operand)
        sys.exit(f"unsupported calc(): {expr}")
    return ev(ast.parse(expr, mode="eval"))


def evaluate(value, scope, dark, depth=0):
    """Resolves light-dark(), var(--x) and calc() in [value] to a plain `oklch(...)` string."""
    if depth > 20:
        sys.exit(f"var() cycle near: {value}")
    while (i := value.find("light-dark(")) >= 0:
        j = closing(value, i + len("light-dark"))
        args = split_args(value[i + len("light-dark("):j])
        if len(args) != 2:
            sys.exit(f"light-dark() needs two values: {value}")
        value = value[:i] + args[1 if dark else 0] + value[j + 1:]

    def var(m):
        name = m.group(1)
        if name not in scope:
            sys.exit(f"undefined var(--{name})")
        return evaluate(scope[name], scope, dark, depth + 1)

    value = re.sub(r"var\(--([a-z0-9-]+)\)", var, value)
    while (i := value.find("calc(")) >= 0:
        j = closing(value, i + len("calc"))
        value = value[:i] + f"{arith(value[i + 5:j]):.6g}" + value[j + 1:]
    return value


def palette_tokens(theme_css, palettes_css, palette):
    """{"Light": {token: oklch}, "Dark": {...}} for [palette]."""
    if palette == "blue":
        light = declarations(block(theme_css, ":root", "theme.css"))
        dark = declarations(block(theme_css, "\n.dark", "theme.css"))
        modes = {"Light": light, "Dark": dark}
        for mode, toks in modes.items():
            missing = [t for t in TOKENS if t not in toks]
            if missing:
                sys.exit(f"theme.css: {mode} block lacks {missing}")
        return {m: {t: toks[t] for t in TOKENS} for m, toks in modes.items()}
    generic = declarations(block(palettes_css, ":root[data-palette]", "palettes.css"))
    params = declarations(block(palettes_css, f":root[data-palette='{palette}']", "palettes.css"))
    scope = {**generic, **params}
    missing = [t for t in TOKENS if t not in scope]
    if missing:
        sys.exit(f"palettes.css: the palette block lacks {missing}")
    return {mode: {t: evaluate(scope[t], scope, mode == "Dark") for t in TOKENS} for mode in ("Light", "Dark")}


# ---------------------------------------------------------------- colour maths

def parse_oklch(s):
    m = re.fullmatch(r"oklch\(\s*([\d.]+)\s+([\d.]+)\s+([\d.]+)\s*(?:/\s*([\d.]+)(%?))?\s*\)", s.strip())
    if not m:
        sys.exit(f"unsupported colour: {s}")
    alpha = 1.0
    if m.group(4):
        alpha = float(m.group(4)) / (100 if m.group(5) else 1)
    return float(m.group(1)), float(m.group(2)), float(m.group(3)), alpha


def oklch_to_linear_srgb(L, C, h):
    a, b = C * math.cos(math.radians(h)), C * math.sin(math.radians(h))
    l_ = L + 0.3963377774 * a + 0.2158037573 * b
    m_ = L - 0.1055613458 * a - 0.0638541728 * b
    s_ = L - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_ ** 3, m_ ** 3, s_ ** 3
    return (4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)


def linear_srgb_to_linear_p3(rgb):
    r, g, b = rgb
    x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b
    y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b
    return (2.4934969 * x - 0.9313836 * y - 0.4027108 * z,
            -0.8294890 * x + 1.7626641 * y + 0.0236247 * z,
            0.0358458 * x - 0.0761724 * y + 0.9568845 * z)


def encode(c):
    c = min(1.0, max(0.0, c))
    return 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055


def decode(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


EPS = 1e-4


def convert(value):
    L, C, h, alpha = parse_oklch(value)
    lin = oklch_to_linear_srgb(L, C, h)
    return {
        "srgb": tuple(encode(c) for c in lin),
        "p3": tuple(encode(c) for c in linear_srgb_to_linear_p3(lin)),
        "alpha": alpha,
        "out_of_srgb": not all(-EPS <= c <= 1 + EPS for c in lin),
    }


def hex_argb(c):
    r, g, b = (round(x * 255) for x in c["srgb"])
    return f"0x{round(c['alpha'] * 255):02X}{r:02X}{g:02X}{b:02X}"


def luminance(rgb):
    r, g, b = (decode(x) for x in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def quantised(c):
    return tuple(round(x * 255) / 255 for x in c["srgb"])


def contrast(fg, bg):
    bg_rgb = quantised(bg)
    a = round(fg["alpha"] * 255) / 255
    fg_rgb = tuple(a * f + (1 - a) * b for f, b in zip(quantised(fg), bg_rgb))
    l1, l2 = luminance(fg_rgb), luminance(bg_rgb)
    return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)


# ---------------------------------------------------------------- output

def camel(name):
    parts = name.split("-")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def kotlin(converted, palette, package, obj, command):
    out = [
        f"// GENERATED by hfps-kit android/assets/convert_theme.py from brand/theme.css, palette {palette}. Do not edit.",
        f"// Regenerate: {command}",
        '@file:Suppress("MagicNumber")',
        "",
        f"package {package}",
        "",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.graphics.colorspace.ColorSpaces",
        "",
        "/** The hfps OKLCH tokens as sRGB colours, with Display P3 (`...P3`) variants where they matter. */",
        f"object {obj} {{",
        f"    /** The hfps palette these tokens were generated for. */",
        f'    const val PALETTE = "{palette}"',
        "",
    ]
    for mode in ("Light", "Dark"):
        out.append(f"    /** {mode} tokens. */")
        out.append(f"    object {mode} {{")
        for name in TOKENS:
            c = converted[mode][name]
            out.append(f"        val {camel(name)} = Color({hex_argb(c)})")
            if name in P3_ALWAYS or c["out_of_srgb"]:
                r, g, b = (f"{x:.4f}f" for x in c["p3"])
                out.append(f"        val {camel(name)}P3 = Color({r}, {g}, {b}, {c['alpha']:.2f}f, ColorSpaces.DisplayP3)")
        out.append("    }")
        if mode == "Light":
            out.append("")
    out.append("}")
    return "\n".join(out) + "\n"


def resource_xml(converted, mode, palette):
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- GENERATED by hfps-kit convert_theme.py, palette {palette}, {mode.lower()}. Do not edit. -->",
        "<resources>",
    ]
    for name in RES_COLORS:
        c = converted[mode][name]
        r, g, b = (round(x * 255) for x in c["srgb"])
        lines.append(f'    <color name="hfps_{name.replace("-", "_")}">#{r:02X}{g:02X}{b:02X}</color>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def wcag(converted):
    rows = []
    for mode in ("Light", "Dark"):
        toks = converted[mode]
        for fg, bg, minimum in PAIRS:
            ratio = contrast(toks[fg], toks[bg])
            rows.append((mode, fg, bg, ratio, minimum, ratio >= minimum))
    return rows


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("css", nargs="?", default=str(DEFAULT_CSS), help="hfps theme.css (default: the kit's brand/theme.css)")
    p.add_argument("--palettes", help="palettes.css (default: next to theme.css)")
    p.add_argument("--palette", choices=PALETTES, default="blue")
    p.add_argument("--package", default="dev.hfps.app.designsystem.theme")
    p.add_argument("--object", default="HfpsTokens", help="Kotlin object name (also the default file name)")
    p.add_argument("--out", help="output .kt file (default: ./<object>.kt)")
    p.add_argument("--res", help="also write <res>/values/hfps_colors.xml and values-night/ (splash, window)")
    p.add_argument("--check", action="store_true", help="write nothing; fail if an output is stale")
    p.add_argument("--wcag-only", action="store_true", help="only print the contrast table of every palette")
    args = p.parse_args()

    css_path = Path(args.css).expanduser()
    pal_path = Path(args.palettes).expanduser() if args.palettes else css_path.parent / "palettes.css"
    theme_css = css_path.read_text()
    palettes_css = pal_path.read_text() if pal_path.exists() else None

    failed = False
    chosen = None
    print("WCAG contrast (sRGB, after 8-bit quantisation):")
    for palette in PALETTES:
        if palette != "blue" and palettes_css is None:
            print(f"  {palette}: skipped ({pal_path} not found)")
            if palette == args.palette:
                sys.exit(f"palette {palette} needs {pal_path}")
            continue
        toks = palette_tokens(theme_css, palettes_css, palette)
        converted = {m: {k: convert(v) for k, v in t.items()} for m, t in toks.items()}
        rows = wcag(converted)
        bad = [r for r in rows if not r[5]]
        failed |= bool(bad)
        if palette == args.palette:
            chosen = converted
        if palette == args.palette or args.wcag_only:
            print(f"  {palette}:")
            for mode, fg, bg, ratio, minimum, ok in rows:
                print(f"    {mode:5} {fg:18} on {bg:11} {ratio:5.2f}:1 (min {minimum}) {'ok' if ok else 'FAIL'}")
        else:
            worst = min(rows, key=lambda r: r[3] / r[4])
            print(f"  {palette}: {'ok' if not bad else f'{len(bad)} FAIL'} "
                  f"(tightest: {worst[0]} {worst[1]} on {worst[2]} {worst[3]:.2f}:1)")
            for mode, fg, bg, ratio, minimum, _ in bad:
                print(f"    FAIL {mode} {fg} on {bg} {ratio:.2f}:1 (min {minimum})")

    if args.wcag_only:
        sys.exit(1 if failed else 0)

    out = Path(args.out).expanduser() if args.out else Path.cwd() / f"{args.object}.kt"
    command = (f"python3 convert_theme.py --palette {args.palette} --package {args.package} "
               f"--object {args.object} --out <{out.name}>")
    outputs = {out: kotlin(chosen, args.palette, args.package, args.object, command)}
    if args.res:
        res = Path(args.res).expanduser()
        outputs[res / "values" / "hfps_colors.xml"] = resource_xml(chosen, "Light", args.palette)
        outputs[res / "values-night" / "hfps_colors.xml"] = resource_xml(chosen, "Dark", args.palette)

    for path, text in outputs.items():
        if args.check:
            if not path.exists() or path.read_text() != text:
                print(f"{path} is out of date (palette {args.palette})")
                failed = True
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            print(f"wrote {path}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
