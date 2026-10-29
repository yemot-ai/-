# סטודיו — עיצוב בלי גבולות

אפליקציית עיצוב בעברית ליצירת מודעות, הזמנות, פוסטרים ופוסטים. בנויה לעבודה מקומית: אין ספריות JavaScript חיצוניות, אין פונטים שמורדים מהרשת ואין קריאות שרת. אפשר להשתמש בתכונות העריכה גם ללא אינטרנט.

## תצוגה מקדימה
פתחו בדפדפן: https://htmlpreview.github.io/?https://github.com/yemot-ai/-/blob/main/poster-studio/index.html

אפשר גם להוריד את \`index.html\` ולפתוח אותו ב-Chrome או Edge. אין צורך בהתקנה או בהרשמה.

## Mobile preview fix
The editor preview is placed first on small screens; tool panels follow it, so the current design remains visible without needing to scroll past the controls first. The canvas size adapts to the mobile viewport.

## Android APK
קובץ APK נבנה אוטומטית באמצעות GitHub Actions. פתחו את לשונית Actions במאגר, בחרו את הריצה האחרונה של **Build Poster Studio Offline Android APK**, והורידו את artifact בשם \`POSTER-STUDIO-Android-APK\`. לאחר חילוץ ה-ZIP, התקינו את \`app-debug.apk\`.

הגרסה מוגדרת עם minSdk 23 (Android 6.0 ומעלה). אין אפשרות להבטיח תמיכה בכל גרסה היסטורית של Android או בכל מכשיר; מומלץ לבדוק על המכשיר בפועל.

## יכולות
- ממשק עברי מלא מימין לשמאל, מותאם למסכים קטנים ולמחשב.
- תבניות מוכנות: מודעה, ברכה, הזמנה, מבצע, פוסט ומראה מינימליסטי.
- פורמטים: פוסטר, סטורי, ריבוע, רוחבי והדפסה.
- רקע אחיד או מעבר צבע, עם צבעים מותאמים אישית.
- שכבות טקסט, צורות, תמונות ואימוג׳י; שינוי טקסט, צבע, גופן, גודל, יישור והדגשה.
- גרירת פריטים על משטח העיצוב, בחירה, שכפול, סדר שכבות, מחיקה ומירכוז.
- ייצוא PNG/JPG ושמירת פרויקט לקובץ JSON שניתן לפתוח שוב.
- שמירת פרויקטים מקומית בדפדפן/במכשיר, לפי מגבלות האחסון של המכשיר.
- פרויקט Android עצמאי בתוך תיקיית \`android\`, ללא הרשאת אינטרנט. חלונות מערכת מאפשרים לבחור תמונות ולשמור קבצים.

## פרטיות ואופליין
התוכן נשאר מקומי. אין סנכרון ענן או העלאת תמונות. שמירה מקומית בדפדפן עלולה להימחק אם מנקים את נתוני הדפדפן; לכן שמרו גם קובץ פרויקט כגיבוי.

## מבנה
- \`index.html\` — אפליקציית דפדפן עצמאית, כולל CSS ו-JavaScript.
- \`android/\` — פרויקט Android WebView עצמאי.
- \`.github/workflows/build-poster-studio.yml\` — בנייה ובדיקת APK.
