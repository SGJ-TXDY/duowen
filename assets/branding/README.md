# 应用图标素材

作者 / 署名：Codex（OpenAI），2026-10-25。

本图由内置 imagegen 生成，为原创日式动漫女性头像。正式原图为 `launcher-anime-source.png`，已接入 Android Manifest 的 `@mipmap/ic_launcher`，支持自适应启动图标的圆形与圆角蒙版。全尺寸原图不打进 APK，运行时使用 `app/src/main/res/drawable-nodpi/ic_launcher_anime.png`。未改 Compose 页面。

## 原始提示词（内置 image_gen 模式）

```text
Use case: stylized-concept. Asset type: square Android launcher app icon illustration for an embedded-systems study quiz app. Primary request: a beautiful original Japanese anime young adult woman, wholesome and friendly, polished high-quality anime illustration. Subject: head-and-shoulders portrait, luminous teal eyes, silky dark blue hair with soft highlights, modest cream blouse and a small teal hair clip, warm confident smile. Composition: one large centered face, simple strong silhouette, face and important details entirely within the central 60 percent safe area so circular and rounded Android masks never crop the face, clean full-bleed pale mint background, no frame, no pre-rounded corners. Lighting/mood: soft warm light, calm optimistic study companion. Palette: elegant jade teal, midnight blue, warm ivory, subtle peach accents matching a teal study app. Crisp expressive line art, soft cel shading, readable at tiny app-icon size. Constraints: original character, exactly one person, no text, no letters, no watermark, no logo, no extra objects, no sexualized pose, opaque square image.
```

## 重新生成 Android 资源

在 Windows PowerShell 执行：

```powershell
./tools/generate-launcher-icons.ps1
```

脚本仅用 System.Drawing 对现有原图做标准尺寸缩放，不重新创作图像；输出 432 px 自适应前景。项目最低支持 Android 26，所有支持版本均使用自适应图标，无需旧版密度回退。自适应 XML 位于 `res/mipmap-anydpi`；桌面圆形与圆角蒙版仍需真机确认。

如日后更换图片，替换原图后运行脚本，再编译检查资源。不要覆盖正式签名或已有稳定 APK。
