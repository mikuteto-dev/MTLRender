# MTLRender

Apple Silicon 向け Minecraft 26.2 Fabric mod。Blaze3D の描画を Metal 4 バックエンドへ置き換えます。

## 要件

- Apple M シリーズ
- macOS 27 以上
- Minecraft 26.2
- Fabric Loader

## ビルド

JDK 25、Rust、Xcode Command Line Tools が必要です。

```sh
cd mod
./gradlew build
```

生成物は `mod/build/libs/` に出力されます。

## ライセンス

GPL-3.0-only
