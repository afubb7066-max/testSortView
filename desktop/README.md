# desktop/ — مرجع نسخهٔ دسکتاپ SortView

این پوشه، جای سورس کامل نسخهٔ دسکتاپ (Python/PyQt6 — نسخهٔ v1.10.3) را در ساختار مخزن نشان می‌دهد (بند ۵ از `ROADMAP.md`).

## سورس دسکتاپ کجاست؟

مخزن اصلی و مرجع (authoritative):

**https://github.com/mohammadjafari-dev/SortView** — تگ `v1.10.3`
همهٔ فایل‌ها همان‌جاست: `main.py`، `app_i18n.py`، `requirements.txt`، `tests/`، `assets/`، `docs/`، `BUILD.bat`، `CHANGELOG.md`، `README.fa.md` و ورک‌فلوی CI قدیمی.

## چرا سورس دسکتاپ داخل همین مخزن کپی نشد؟

فاز ۰ در این اجرا با GitHub API انجام شد (بدون دسترسی git محلی)؛ انتقال تاریخچهٔ git و فایل‌های حجیم مثل `main.py` (حدود ۱۸۶ کیلوبایت) از طریق API ممکن نبود و ساخت فورک در این اکانت هم با دسترسی فعلی اپلیکیشن مجاز نشد. پس مرجعِ رفتاریِ پورت، خودِ مخزن اصلی روی تگ `v1.10.3` است؛ تاریخچهٔ کامل و attribution نویسنده همان‌جا حفظ شده است (بند ۸ قرارداد سازگاری).

اگر بخواهید تاریخچهٔ git هم داخل این اکانت کپی شود، از صفحهٔ مخزن اصلی دکمهٔ **Fork** را بزنید (یک کلیک) و سپس این یادداشت را به‌روز کنید.

نقشهٔ خط‌به‌خط کد دسکتاپ برای پورت: بخش ۸ [`ROADMAP.md`](../ROADMAP.md).

> The desktop reference implementation (Python/PyQt6) lives in the upstream repo, tag v1.10.3. This folder is a pointer that keeps the planned repository layout of ROADMAP.md §5.
