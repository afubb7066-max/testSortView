# desktop/ — مرجع نسخهٔ دسکتاپ SortView

این پوشه، جای سورس کامل نسخهٔ دسکتاپ (Python/PyQt6 — نسخهٔ v1.10.3) را در ساختار مخزن نشان می‌دهد (بند ۵ از `ROADMAP.md`).

## سورس دسکتاپ کجاست؟

| چه چیزی | کجا |
|---|---|
| مخزن اصلی (authoritative) | https://github.com/mohammadjafari-dev/SortView |
| فورک حفظ‌شده در این اکانت — **کل تاریخچهٔ git + همهٔ فایل‌ها** (`main.py`، `app_i18n.py`، `tests/`، `assets/`، `docs/`، `BUILD.bat`، ورک‌فلوی قدیمی و…) | https://github.com/afubb7066-max/SortView |

## چرا سورس دسکتاپ مستقیماً داخل همین مخزن کپی نشد؟

فاز ۰ در این اجرا با GitHub API انجام شد (بدون دسترسی git محلی)؛ انتقال تاریخچهٔ git و فایل‌های حجیم مثل `main.py` (حدود ۱۸۶ کیلوبایت) از طریق API ممکن نبود. در عوض، **فورک کامل** مخزن اصلی در `afubb7066-max/SortView` ساخته شد که همان محتوا را با کل تاریخچه و attribution نویسنده (بند ۸ قرارداد سازگاری) یک‌جا نگه می‌دارد؛ به‌جای کپیِ ناقص، فورکِ کامل مرجع شد.

نقشهٔ خط‌به‌خط کد دسکتاپ برای پورت: بخش ۸ [`ROADMAP.md`](../ROADMAP.md).

> The desktop reference implementation (Python/PyQt6) lives in the upstream repo and its full-history fork above. This folder is a pointer that keeps the planned repository layout of ROADMAP.md §5.
