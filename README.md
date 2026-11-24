# TV Launcher — لانشر النوافذ المتعددة لـ Android TV

لانشر لأجهزة Android TV يعرض عدة تطبيقات حقيقية في وقت واحد (1، 2، 3، أو 4 نوافذ)، ويدعم فتح أكثر من نسخة من نفس التطبيق. يتضمن أيضاً إعدادات محمية برمز مسؤول، وأوقات الصلاة دون اتصال بالإنترنت، وتحديثاً تلقائياً من داخل التطبيق.

الجهاز الذي اختُبر عليه: **Skyworth SWTV-20AE — Android TV 11** (بدون روت).

---

## 1. التثبيت لأول مرة

1. حمّل ملف `TV-Launcher-N.apk` من صفحة **Releases**، وثبّته عبر ATV Tools (Install).
2. نفّذ أوامر ADB التالية مرة واحدة من طرفية ATV Tools، كل أمر على حدة:

```
settings put global enable_freeform_support 1
settings put global force_resizable_activities 1
cmd package set-home-activity com.arabrock.tvlauncher/.MainActivity
appops set com.arabrock.tvlauncher SYSTEM_ALERT_WINDOW allow
appops set com.arabrock.tvlauncher REQUEST_INSTALL_PACKAGES allow
reboot
```

- **الأمران الأولان** يفعّلان النوافذ الحرة (freeform). هذا ضروري، لأن Android TV لا يفعّلها افتراضياً.
- **`set-home-activity`** يجعل زر Home يفتح هذا اللانشر.
- **`REQUEST_INSTALL_PACKAGES`** يسمح للانشر بتثبيت التحديثات بنفسه.
- إذا ظهرت في شاشة «التشخيص» رسالة أن `setLaunchWindowingMode` محجوبة، نفّذ: `settings put global hidden_api_policy 1`

للتراجع عن كل ذلك: نفّذ نفس الأوامر بالقيمة `0`، أو أزل التطبيق.

## 2. التحديث من داخل التطبيق

- كل تعديل يُرفع إلى المستودع يبني تلقائياً إصداراً جديداً باسم `build-N` على GitHub Releases.
- عند تشغيل اللانشر يبحث عن تحديث. إذا وجد واحداً، يظهر زر **«تحديث متوفر»** في الشريط السفلي.
- اضغط: **الإعدادات ← التحديثات ← تنزيل وتثبيت**. سيظهر مربع تأكيد من أندرويد مرة واحدة، اضغط فيه «تثبيت».
- تبقى كل الإعدادات محفوظة بعد التحديث، لأن جميع الإصدارات موقّعة بنفس المفتاح (`keystore/`).

## 3. الاستخدام بالريموت

| الزر | الوظيفة |
|---|---|
| الأسهم | التنقل بين النوافذ والأزرار |
| OK على نافذة | نقل التحكم إلى تلك النافذة (أو فتحها) |
| OK مطوّل / زر Menu | قائمة النافذة: تحكم، إعادة فتح، تكبير/استعادة، إغلاق، تغيير التطبيق |
| Home | العودة للانشر، مع بقاء النوافذ ظاهرة خلفه |
| يمين/يسار على قيمة رقمية | تغيير القيمة (الفواصل، تعديل الأوقات…) |

## 4. البنية التقنية

تطبيق Kotlin وJetpack Compose، بنمط MVVM مبسّط: الحالة في `LauncherController`، والبيانات في مخازن مستقلة.

| الوحدة | الملفات |
|---|---|
| LauncherCore | `MainActivity.kt` (LauncherController و Screen) |
| AppDiscovery | `data/AppRepository.kt` |
| WindowManager | `window/Layouts.kt`, `window/WindowController.kt` |
| MultiTaskController | `window/HostActivity.kt` |
| WorkspaceManager | `workspace/WorkspaceStore.kt` |
| TVRemoteController | `ui/Components.kt` (`tvFocusable`) |
| CompatibilityChecker | `ui/OtherScreens.kt` (`DiagnosticsScreen`) |
| Settings / Security | `data/SettingsStore.kt`, `security/PinManager.kt`, `ui/SettingsScreen.kt` |
| Prayer times | `prayer/PrayerEngine.kt`, `prayer/Cities.kt`, `prayer/PrayerAlarmReceiver.kt` |
| Updates | `update/Updater.kt` |

### كيف تعمل النوافذ

- **الطريقة المستضافة (الافتراضية):** لكل نافذة مهمة (task) خاصة يملكها اللانشر، وتبدأ بـ `HostActivity` مع:
  - `FLAG_ACTIVITY_NEW_DOCUMENT`
  - `ActivityOptions.setLaunchBounds()`
  - `setLaunchWindowingMode(FREEFORM)`

  بعدها يُشغَّل التطبيق المطلوب **داخل نفس المهمة**. بهذا يستطيع اللانشر عبر `ActivityManager.AppTask` أن ينقل التركيز إلى أي نافذة أو يغلقها، وتعمل عدة نسخ من نفس التطبيق، كل نسخة في مهمة مستقلة.
- **إذا كان التطبيق `singleTask` أو `singleInstance`:** يخرج إلى مهمته الخاصة. في هذه الحالة يضعه النظام في نفس المستطيل، وتظهر حالته «ESCAPED»، ويُسمح له عادة بنسخة واحدة فقط.
- **الطريقة المباشرة:** تُشغّل التطبيق في مهمته الخاصة مع `FLAG_ACTIVITY_NEW_TASK`، وتضيف `MULTIPLE_TASK` عند تكرار نفس التطبيق.
- **شاشة اللانشر شفافة**، فتبقى النوافذ ظاهرة خلفها عند الضغط على Home.

### اختيارات تقنية

- **أوقات الصلاة:** تُحسب محلياً بالخوارزمية الفلكية المعتمدة في PrayTimes.org، دون مكتبة خارجية ودون إنترنت. طريقة أم القرى: العشاء بعد المغرب بـ 90 دقيقة، و120 دقيقة في رمضان.
- **التاريخ الهجري:** من `android.icu IslamicCalendar` بنظام أم القرى.
- **حفظ الإعدادات ومساحات العمل:** ملفات JSON في SharedPreferences بدلاً من DataStore وRoom. السبب أن البيانات صغيرة جداً، وهذا أخف على أجهزة التلفاز الضعيفة.
- **رمز PIN:** لا يُحفظ إلا ناتج PBKDF2-HMAC-SHA256 مع salt عشوائي (60,000 تكرار)، وهذا الناتج مشفّر أيضاً بمفتاح AES-GCM داخل Android Keystore. الرمز لا يُكتب في أي سجل (log).

## 5. القيود المعروفة (بصراحة)

1. **تعديل ADB ضروري.** بدون أوامر الوضع الحر يفتح أندرويد كل تطبيق بملء الشاشة. لا يوجد API عادي يتجاوز ذلك.
2. **تكرار نفس التطبيق قرار يعود للتطبيق نفسه.** التطبيقات ذات `singleTask` أو `singleInstance` لا تسمح إلا بنسخة واحدة. اختبار B/C/D في شاشة التشخيص يبيّن النتيجة الفعلية لكل تطبيق.
3. **الصوت:** عدة نوافذ فيديو قد تتنافس على الصوت (audio focus). بعض التطبيقات توقف التشغيل عندما تفقد التركيز، وهذا سلوك التطبيق وليس النظام.
4. **التكبير والاستعادة** يعيدان فتح النافذة بالمقاس الجديد، وقد يُعاد تحميل محتواها. السبب أن أندرويد لا يسمح للانشر عادي بتغيير مقاس مهام التطبيقات الأخرى وهي تعمل.
5. **إغلاق النوافذ المفتوحة بالطريقة المباشرة** لا يكون إلا من داخل التطبيق (زر الرجوع).
6. **رمز PIN يحمي إعدادات هذا اللانشر فقط.** لا يستطيع لانشر عادي قفل تطبيق «إعدادات أندرويد» نفسه. قفل الجهاز بالكامل يتطلب وضع **Device Owner**، ويُفعَّل على جهاز بعد إعادة ضبط المصنع بالأمر:
   `dpm set-device-owner com.arabrock.tvlauncher/.AdminReceiver`
   هذا الوضع غير مفعّل في هذا الإصدار، ويمكن إضافته لاحقاً عند الطلب.
7. **الاسترداد عند نسيان الرمز:** استخدم رمز الاسترداد المكوّن من 12 حرفاً، الذي يظهر مرة واحدة عند إنشاء الرمز. وإذا فُقد هو أيضاً، فالحل الأخير عبر ADB: `pm clear com.arabrock.tvlauncher`. هذا يمسح كل إعدادات اللانشر.

## 6. الاختبار

شاشة **الإعدادات ← التشخيص والتوافق** تعرض:
- حالة النظام: الوضع الحر، وإتاحة الواجهات، واللانشر الافتراضي.
- اختبارات A وB/C وD وE وF وH حسب المواصفات. كل اختبار يفتح النوافذ فعلياً، ويفحص المهام، ثم يسألك هل ظهرت النوافذ معاً. النتيجة: ناجح، أو دعم جزئي، أو غير مدعوم.
- توافق كل تطبيق مثبت مع فتح أكثر من نسخة.

أرقام الأداء (زمن التشغيل، والذاكرة، والمعالج) تُقاس على الجهاز نفسه. لا توجد أرقام مقدّرة في هذا المستند.

```
adb shell dumpsys meminfo com.arabrock.tvlauncher
adb shell am start -W -n com.arabrock.tvlauncher/.MainActivity
adb shell top -n 1 | grep tvlauncher
```

## 7. البناء

يُبنى تلقائياً عبر GitHub Actions (`.github/workflows/build.yml`). وللبناء المحلي: `gradle :app:assembleRelease` (يتطلب Gradle 8.9 أو أحدث، وJDK 17، وAndroid SDK 34).
