# ProCraft (Android)

ProCraft is a digital painting and illustration studio for Android, built in **Kotlin** and **Jetpack Compose** with a pure Kotlin/Android Graphics bitmap rendering engine.

## Features

- **Gallery & Stacks**: Artwork grid with custom canvas presets, Photo Picker image import (`PickVisualMedia`), rename, duplicate, stack grouping, swipe actions, and PNG/JPEG sharing via `FileProvider`.
- **Brush Engine & Brush Studio**: Categorized brush library (`Sketching`, `Inking`, `Drawing`, `Painting`, `Watercolour`, `Calligraphy`, `Airbrushing`, `Spray Paint`, `Charcoals`, `Textures`, `Abstract`), Paint / Smudge / Erase modes, live brush size & opacity sliders, stroke stabilization, taper, scatter, dynamics, and per-brush studio tuning with a live drawing pad preview.
- **Layers & Blend Modes**: Multi-layer stack with thumbnails, visibility toggles, opacity, alpha lock, clipping masks, blend modes (`Normal`, `Multiply`, `Screen`, `Overlay`, `Darken`, `Lighten`, `Color Dodge`, `Color Burn`, `Add`, `Subtract`, `Difference`), merge down, duplicate, clear, and background layer color/visibility controls.
- **Color Picker**: Disc, Classic square, Harmony, Value/Hex sliders, and editable custom Palettes, plus quick Eyedropper and ColorDrop flood fill.
- **Adjustments**: Live non-destructive preview and apply/cancel controls for HSB, Color Balance, Curves, Gradient Map, Gaussian Blur, Motion Blur, Perspective Blur, Noise, Sharpen, Bloom, Glitch, Halftone, Chromatic Aberration, Liquify, and Clone.
- **Selection, Transform & Crop**: Automatic flood selection, Freehand, Rectangle, and Ellipse selections with invert, feather, fill, clear, save/load; Freeform, Uniform, Distort, and Warp transforms with snapping, flip, 45° rotation, and fit-to-canvas; Crop & Resize.
- **Canvas Assists & Extras**: Drawing Guides (2D Grid, Isometric, Symmetry), Text Tool, Animation Assist frame timeline with onion skinning & playback, floating Reference Image window, Time-lapse Replay, Radial QuickMenu, and Gesture Controls.

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material Design 3
- **Minimum SDK**: 26 (Android 8.0)
- **Target / Compile SDK**: 36

## License

Dual-licensed under MIT or Apache-2.0 (`LICENSE-MIT`, `LICENSE-APACHE`). Interface icons include Lucide vectors (ISC license, `app/src/main/assets/icons/LICENSE-lucide.txt`) and Inter fonts (`app/src/main/assets/fonts/Inter-OFL.txt`).
