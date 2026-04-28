# MobileFFmpeg Library

Place the MobileFFmpeg AAR file in this directory.

## Download

Download `mobile-ffmpeg-full-gpl-4.4.aar` from:
- **GitHub**: https://github.com/tanersener/mobile-ffmpeg/releases/tag/v4.4

Rename the downloaded file to `mobile-ffmpeg.aar` and place it here.

## File Structure

```
libs/
├── README.md          ← This file
└── mobile-ffmpeg.aar  ← Place AAR here
```

## Verification

After placing the AAR, run:
```bash
ls -la android/app/libs/mobile-ffmpeg.aar
```

Then build:
```bash
flutter clean
flutter build apk --debug
```
