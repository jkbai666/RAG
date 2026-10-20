from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont
out = Path(__file__).parent / 'fixtures/两页文本.pdf'
pdfmetrics.registerFont(UnicodeCIDFont('STSong-Light'))
c = canvas.Canvas(str(out))
c.setFont('STSong-Light', 14)
c.drawString(50, 780, '第一页：青禾项目由林溪负责。')
c.showPage()
c.setFont('STSong-Light', 14)
c.drawString(50, 780, '第二页：计划上线日期为2026年11月5日。')
c.save()
print(out.name)

# An image-only PDF, deliberately without a text layer.
from PIL import Image, ImageDraw
from reportlab.lib.utils import ImageReader
image = Image.new('RGB', (600, 120), 'white')
ImageDraw.Draw(image).text((20,40), 'Scanned page: image only; no extractable text.', fill='black')
scan = canvas.Canvas(str(out.with_name('扫描型.pdf')))
scan.drawImage(ImageReader(image), 50, 600, width=500, height=100)
scan.save()
