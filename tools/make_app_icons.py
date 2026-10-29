"""Generates launcher icons and the Android TV banner.

Run from the repository root:  python tools/make_app_icons.py
Requires Pillow.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]

BG = (16, 22, 28, 255)
BG_EDGE = (34, 46, 58, 255)
WHITE = (230, 237, 243, 255)
ACCENT = (76, 158, 235, 255)
MUTED = (163, 179, 194, 255)

S = 4  # supersampling factor


def rounded(draw, box, radius, fill=None, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def tv_glyph(size, supersample=S):
    """A TV screen with a stand and two sound arcs (sender icon)."""
    n = size * supersample
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    radius = int(n * 0.22)
    rounded(d, (0, 0, n - 1, n - 1), radius, fill=BG)
    rounded(d, (1, 1, n - 2, n - 2), radius, outline=BG_EDGE, width=max(1, n // 64))

    # screen
    left, top, right, bottom = n * 0.20, n * 0.30, n * 0.80, n * 0.62
    rounded(d, (left, top, right, bottom), n * 0.05, fill=WHITE)

    # stand
    d.rectangle((n * 0.45, bottom, n * 0.55, bottom + n * 0.06), fill=WHITE)
    rounded(
        d,
        (n * 0.34, bottom + n * 0.06, n * 0.66, bottom + n * 0.12),
        n * 0.03,
        fill=WHITE,
    )

    # sound arcs inside the screen, accent coloured
    cx, cy = n * 0.50, n * 0.46
    for i, factor in enumerate((0.10, 0.17)):
        r = n * factor
        d.arc(
            (cx - r, cy - r, cx + r, cy + r),
            start=-60,
            end=60,
            fill=ACCENT,
            width=max(1, int(n * 0.025)),
        )
    return img.resize((size, size), Image.LANCZOS)


def headphones_glyph(size, supersample=S):
    """A headphone glyph (receiver icon)."""
    n = size * supersample
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    radius = int(n * 0.22)
    rounded(d, (0, 0, n - 1, n - 1), radius, fill=BG)
    rounded(d, (1, 1, n - 2, n - 2), radius, outline=BG_EDGE, width=max(1, n // 64))

    # head band
    d.arc(
        (n * 0.22, n * 0.20, n * 0.78, n * 0.74),
        start=180,
        end=360,
        fill=WHITE,
        width=max(2, int(n * 0.075)),
    )
    # ear cups
    rounded(
        d,
        (n * 0.17, n * 0.46, n * 0.34, n * 0.76),
        n * 0.07,
        fill=ACCENT,
    )
    rounded(
        d,
        (n * 0.66, n * 0.46, n * 0.83, n * 0.76),
        n * 0.07,
        fill=ACCENT,
    )
    return img.resize((size, size), Image.LANCZOS)


ICON_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def write_icons(module: str, maker) -> None:
    for folder, size in ICON_SIZES.items():
        target = ROOT / module / "src/main/res" / folder
        target.mkdir(parents=True, exist_ok=True)
        maker(size).save(target / "ic_launcher.png")


def load_font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    candidates = [
        r"C:\Windows\Fonts\segoeuib.ttf" if bold else r"C:\Windows\Fonts\segoeui.ttf",
        r"C:\Windows\Fonts\arialbd.ttf" if bold else r"C:\Windows\Fonts\arial.ttf",
    ]
    for candidate in candidates:
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()


def write_banner() -> None:
    width, height = 320, 180
    img = Image.new("RGBA", (width, height), BG)
    d = ImageDraw.Draw(img)

    # soft accent glow on the right
    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse((width - 150, -40, width + 40, height + 60), fill=(76, 158, 235, 40))
    img = Image.alpha_composite(img, glow)
    d = ImageDraw.Draw(img)

    # tv glyph on the left, inside the 5% overscan safe area
    glyph = tv_glyph(64, supersample=4)
    img.paste(glyph, (26, 58), glyph)

    title_font = load_font(30, bold=True)
    sub_font = load_font(15)

    d.text((104, 60), "AudioBridge", font=title_font, fill=WHITE)
    d.text((104, 96), "TV audio to your phone", font=sub_font, fill=MUTED)
    d.text((104, 118), "and hearing aids", font=sub_font, fill=MUTED)

    target = ROOT / "sender/src/main/res/drawable-xhdpi"
    target.mkdir(parents=True, exist_ok=True)
    img.convert("RGB").save(target / "tv_banner.png")


def main() -> None:
    write_icons("sender", tv_glyph)
    write_icons("receiver", headphones_glyph)
    write_banner()
    print("icons and banner written")


if __name__ == "__main__":
    main()
