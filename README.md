# SortView — Android Port | پورت اندروید سورت‌ویو

<div dir="rtl">

## فارسی

این مخزن نسخه‌ی اندرویدِ [سورت‌ویو](https://github.com/mohammadjafari-dev/SortView) (دسکتاپ، Python/PyQt6، v1.10.3) را می‌سازد:
گالری تامبنیل هر پوشه از حافظهٔ گوشی + دسته‌بندی فایل‌ها با یک ضربه (یا کلید عددی ۰ تا ۹)، با همان منطق انتقال امن (هرگز بازنویسی نمی‌کند)، نام یکتا، Undo تا ۵۰ دور، ۶ تم، فیلتر پسوند و مجموعه‌های دستهٔ سازگار با دسکتاپ.

- نقشه راه فازها و قرارداد سازگاری با دسکتاپ: [`ROADMAP.md`](ROADMAP.md)
- تاریخچه نسخه‌ها: [`CHANGELOG.md`](CHANGELOG.md)
- سورس نسخه دسکتاپ (مرجع رفتاری، دست‌نخورده): [`desktop/`](desktop/)
- لایسنس: GPL-3.0-or-later — Copyright (c) 2026 Mohammad Jafari

### وضعیت — v1.0.0

| نسخه | فاز | وضعیت |
|---|---|---|
| 0.1.0 | اسکلت + بیلد خودکار CI | ✅ |
| 0.2.0 | هسته دسته‌بندی (انتقال امن + Undo) | ✅ |
| 0.3.0 | موتور تامبنیل (ویدیو/PDF/آرشیو + کش) | ✅ |
| 0.4.0 | پیش‌نمایش و پخش (Media3 + PDF چندصفحه‌ای + متن) | ✅ |
| 0.5.0 | پاریت کامل با دسکتاپ (تم‌ها، فیلتر، مجموعه دسته‌ها) | ✅ |
| 0.6.0 | پالایش (splash، آیکون، pull-to-refresh، هپتیک، progress اعلان) | ✅ |
| 1.0.0 | زیرساخت انتشار (امضا + ورک‌فلو Release) | ✅ کد کامل — انتشار با push تگ `v1.0.0` |

> ⚠️ CI سبز یعنی «بیلد می‌شود و ۲۵ تست JVM پاس است»، نه «روی گوشی تست شده». معیارهای قبولی روی دستگاه واقعی (مثل جابه‌جایی ۵۰ عکس + Undo کامل) هنوز باید توسط کاربر تأیید شوند.

### دانلود و نصب

- **APK دیباگ (بدون امضا، برای تست سریع):** تب Actions ← Build APK ← آخرین اجرای سبز ← Artifacts ← `SortView-debug-apk`
- **APK امضاشده (انتشار):** تب Releases ← آخرین تگ `v*` ← فایل `SortView-<tag>-release.apk`

### انتشار یک نسخه (keystore + تگ)

1. ساخت keystore (یک‌بار):

```bash
keytool -genkeypair -v -keystore sortview-release.keystore \
  -alias sortview -keyalg RSA -keysize 2048 -validity 10000
```

2. افزودن ۴ Secret به مخزن (Settings ← Secrets and variables ← Actions):

| Secret | مقدار |
|---|---|
| `SORTVIEW_KEYSTORE_B64` | خروجی `base64 -w0 sortview-release.keystore` |
| `SORTVIEW_KEYSTORE_PASSWORD` | گذرواژه keystore |
| `SORTVIEW_KEY_ALIAS` | مثلاً `sortview` |
| `SORTVIEW_KEY_PASSWORD` | گذرواژه کلید |

3. push تگ:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

ورک‌فلو `Release APK` اجرا می‌شود: تست‌های JVM + `assembleRelease` امضاشده + انتشار خودکار در Releases.

> محتوای کامل ورک‌فلو در [`docs/release-apk.yml`](docs/release-apk.yml) آمده است؛ قبل از قدم ۳ آن را یک‌بار به `.github/workflows/release-apk.yml` کپی کنید (ویرایش فایل در رابط وب گیت‌هاب — افزوده‌شدن فایل‌های ورک‌فلو از طریق API مجاز نیست).

### بیلد محلی

```bash
cd android
gradle assembleDebug        # خروجی: app/build/outputs/apk/debug/
gradle assembleRelease      # بدون Secrets: APK بدون امضا
gradle testDebugUnitTest    # ۲۵ تست JVM
```

> SortView و ردمی‌هایش با کمک هوش مصنوعی نوشته شده‌اند؛ سورس کامل همین‌جاست.

---

## English

This repository builds the Android port of [SortView](https://github.com/mohammadjafari-dev/SortView) (desktop, Python/PyQt6, v1.10.3): turn any phone folder into a thumbnail gallery and file every item into its category with one tap (or hardware keys 0-9) — same safe-transfer semantics as the desktop (never overwrites, unique `name (1).ext` names, delete-source-after-verified-copy), in-memory undo (last 50 rounds), six themes, extension filter, and desktop-compatible category sets (`{"name","categories"}` JSON).

**Status — v1.0.0:** all five phases implemented (25 green JVM tests in CI); the release is published by pushing tag `v1.0.0` after the four `SORTVIEW_*` secrets are set (see the Persian section above for the exact steps).

**Downloads:** debug APK from Actions artifacts (`SortView-debug-apk`); signed release APK from GitHub Releases (`SortView-<tag>-release.apk`).

**Desktop-compatibility contract** (details in `ROADMAP.md` §4): identical `config.json` schema, identical category/sets JSON, identical transfer rules, canonical English strings + Persian texts from `desktop/app_i18n.py`, GPL-3.0-or-later preserved with attribution to Mohammad Jafari.

</div>
