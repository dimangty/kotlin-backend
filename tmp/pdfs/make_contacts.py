"""Собирает миниатюры страниц PDF в контактные листы для визуальной проверки."""

from pathlib import Path

from PIL import Image, ImageDraw, ImageOps


pages = sorted(Path("tmp/pdfs/rendered").glob("page-*.png"))

for sheet_index, start in enumerate(range(0, len(pages), 9), start=1):
    chunk = pages[start : start + 9]
    rows = (len(chunk) + 2) // 3
    sheet = Image.new("RGB", (2700, rows * 1000), "white")
    draw = ImageDraw.Draw(sheet)

    for page_index, page_path in enumerate(chunk):
        thumbnail = ImageOps.contain(Image.open(page_path).convert("RGB"), (880, 940))
        x = (page_index % 3) * 900 + 10
        y = (page_index // 3) * 1000 + 40
        draw.text((x, y - 30), page_path.stem, fill="black")
        sheet.paste(thumbnail, (x, y))

    sheet.save(f"tmp/pdfs/contact-{sheet_index}.jpg", quality=88)
