# تاریخچه تغییرات — پورت اندروید سورت‌ویو

نسخه‌گذاری این پورت از دسکتاپ مستقل است (قرارداد بند ۹ ROADMAP).

## 0.1.0 — 2026-11-27

فاز ۰ — اسکلت و CI:

- اسکلت پروژه اندروید: Kotlin 2.0.21، AGP 8.7.3، Compose BOM 2024.12.01، minSdk 26، target/compileSdk 35
- ورک‌فلو `build-apk.yml` (JDK 17 + Gradle 8.10.2، `assembleDebug`، artifact به نام `SortView-debug-apk`)
- اولین اجرای سبز Actions روی main (کامیت 90e6da9)
- لایسنس GPL-3.0-or-later (دست‌نخورده) + ROADMAP با قرارداد سازگاری با دسکتاپ
- `desktop/` به مخزن مرجع mohammadjafari-dev/SortView (v1.10.3) اشاره می‌کند

## آینده

- 0.2.0 — فاز ۱: هسته دسته‌بندی (اسکن، گرید تامبنیل عکس، انتقال امن با نام یکتا، Undo، config.json)
