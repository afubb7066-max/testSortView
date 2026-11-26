# SortView — پورت اندروید (مخزن تست)

این مخزن نسخه‌ی اندرویدِ [سورت‌ویو](https://github.com/mohammadjafari-dev/SortView) را می‌سازد:
گالری تامبنیل هر پوشه + دسته‌بندی فایل‌ها با یک ضربه (یا کلید ۰ تا ۹)، با همان منطق انتقال امن و Undo نسخه دسکتاپ.

- نقشه راه کامل فازها: [`ROADMAP.md`](ROADMAP.md)
- سورس نسخه دسکتاپ (مرجع رفتاری، دست‌نخورده): [`desktop/`](desktop/)
- لایسنس: GPL-3.0-or-later — Copyright (c) 2026 Mohammad Jafari

## وضعیت

| نسخه | فاز | وضعیت |
|---|---|---|
| 0.1.0 | اسکلت + بیلد خودکار CI | در حال راه‌اندازی |
| 0.2.0 | هسته دسته‌بندی (انتقال امن + Undo) | بعدی |
| 1.0.0 | پاریت کامل با دسکتاپ | هدف نهایی |

## بیلد APK

هر push روی `main` به‌طور خودکار با GitHub Actions بیلد می‌شود:
تب **Actions → Build APK → آخرین اجرای سبز → Artifacts → `SortView-debug-apk`**

بیلد دستی (در صورت نصب Android Studio / SDK):

```bash
cd android
gradle assembleDebug     # خروجی: app/build/outputs/apk/debug/
```

> SortView و ردمی‌هایش با کمک هوش مصنوعی نوشته شده‌اند؛ سورس کامل همین‌جاست.
